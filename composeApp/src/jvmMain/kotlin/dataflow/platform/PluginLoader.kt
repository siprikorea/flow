package dataflow.platform

import dataflow.core.sizeForPorts
import dataflow.core.snapF
import dataflow.model.Edge
import dataflow.model.FlowFile
import dataflow.model.InstallResult
import dataflow.model.ModuleInfo
import dataflow.model.Node
import dataflow.model.PortRef
import dataflow.plugin.ComponentPlugin
import dataflow.plugin.ModulePlugin
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

// 설치 저장소: 모듈은 JAR(코드), 컴포넌트는 구체화된 플로우 JSON.
internal object PluginLoader {
    private val baseDir = File(System.getProperty("user.home"), ".dataflow-editor")
    private val modulesDir = File(baseDir, "modules")
    private val componentsDir = File(baseDir, "components")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun ensureDirs() { modulesDir.mkdirs(); componentsDir.mkdirs() }

    // 설치된 모듈 플러그인 인스턴스 (id → plugin), 캐시
    private var moduleCache: Map<String, ModulePlugin>? = null
    private fun modules(): Map<String, ModulePlugin> {
        moduleCache?.let { return it }
        ensureDirs()
        val jars = modulesDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: emptyArray()
        val map = LinkedHashMap<String, ModulePlugin>()
        if (jars.isNotEmpty()) {
            val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ModulePlugin::class.java.classLoader)
            runCatching {
                ServiceLoader.load(ModulePlugin::class.java, loader).forEach { map[it.id] = it }
            }
        }
        moduleCache = map
        return map
    }

    fun moduleInfos(): List<ModuleInfo> =
        modules().values.map { ModuleInfo(it.id, it.displayName, it.inputs, it.outputs) }

    fun process(id: String, inputs: Map<String, String?>): Map<String, String?> =
        modules()[id]?.let { runCatching { it.process(inputs) }.getOrNull() } ?: emptyMap()

    fun listComponents(): List<String> {
        ensureDirs()
        return componentsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun readComponent(name: String): String? {
        val safe = if (name.endsWith(".json")) name else "$name.json"
        return runCatching { File(componentsDir, safe).takeIf { it.exists() }?.readText() }.getOrNull()
    }

    /* ───────── 설치 ───────── */

    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), ModulePlugin::class.java.classLoader)
        val mods = runCatching { ServiceLoader.load(ModulePlugin::class.java, loader).toList() }.getOrDefault(emptyList())
        val comps = runCatching { ServiceLoader.load(ComponentPlugin::class.java, loader).toList() }.getOrDefault(emptyList())
        if (mods.isEmpty() && comps.isEmpty()) return InstallResult()

        val conflicts = mods.filter { File(modulesDir, "${it.id}.jar").exists() }.map { it.id } +
            comps.filter { File(componentsDir, "${it.id}.json").exists() }.map { it.id }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        mods.forEach { runCatching { jar.copyTo(File(modulesDir, "${it.id}.jar"), overwrite = true) } }
        comps.forEach { runCatching { File(componentsDir, "${it.id}.json").writeText(json.encodeToString(materialize(it))) } }
        moduleCache = null
        return InstallResult(installed = mods.map { it.id } + comps.map { it.id })
    }

    // 에디터에서 만든 컴포넌트(이미 cin/cout 포함한 flow)를 설치
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult {
        ensureDirs()
        val target = File(componentsDir, "$id.json")
        if (target.exists() && !overwrite) return InstallResult(conflicts = listOf(id))
        return runCatching {
            target.writeText(flowJson)
            InstallResult(installed = listOf(id))
        }.getOrDefault(InstallResult())
    }

    /* ───────── 컴포넌트 그래프 → 좌표 포함 플로우 (자동 배치) ───────── */

    private fun materialize(c: ComponentPlugin): FlowFile {
        val nodes = ArrayList<Node>()
        val edges = ArrayList<Edge>()
        var seq = 1
        var e = 1

        val cinId = HashMap<String, String>()
        c.inputs.forEach { inp ->
            val id = "cin_${seq++}"
            cinId[inp] = id
            val (w, h) = sizeForPorts(0, 1)
            nodes.add(Node(id, "cin", inp, 0f, 0f, w, h, emptyList(), listOf("out")))
        }
        c.nodes().forEach { pn ->
            val (w, h) = sizeForPorts(pn.inputs.size, pn.outputs.size)
            nodes.add(Node(pn.id, pn.type, pn.type, 0f, 0f, w, h, pn.inputs, pn.outputs, pn.params))
        }
        val coutId = HashMap<String, String>()
        c.outputs.forEach { out ->
            val id = "cout_${seq++}"
            coutId[out] = id
            val (w, h) = sizeForPorts(1, 0)
            nodes.add(Node(id, "cout", out, 0f, 0f, w, h, listOf("in"), emptyList()))
        }

        c.connections().forEach {
            edges.add(Edge("e_${e++}", PortRef(it.fromNode, it.fromPort), PortRef(it.toNode, it.toPort)))
        }
        c.inputBindings().forEach { (inp, target) ->
            edges.add(Edge("e_${e++}", PortRef(cinId[inp] ?: return@forEach, "out"), PortRef(target.substringBefore('.'), target.substringAfter('.'))))
        }
        c.outputBindings().forEach { (out, source) ->
            edges.add(Edge("e_${e++}", PortRef(source.substringBefore('.'), source.substringAfter('.')), PortRef(coutId[out] ?: return@forEach, "in")))
        }

        return FlowFile(1, autoLayout(nodes, edges), edges, seq)
    }

    // BFS 깊이별 컬럼 자동 배치 (좌표 없는 그래프용)
    private fun autoLayout(nodes: List<Node>, edges: List<Edge>): List<Node> {
        if (nodes.isEmpty()) return nodes
        val depth = nodes.associate { it.id to 0 }.toMutableMap()
        repeat(nodes.size) {
            var changed = false
            edges.forEach { ed ->
                val df = depth[ed.from.node] ?: return@forEach
                if (ed.to.node !in depth) return@forEach
                val d = df + 1
                if (d > depth[ed.to.node]!! && d <= nodes.size) { depth[ed.to.node] = d; changed = true }
            }
            if (!changed) return@repeat
        }
        val cursorY = HashMap<Int, Float>()
        return nodes.map { n ->
            val d = depth[n.id] ?: 0
            val y = cursorY.getOrElse(d) { 60f }
            cursorY[d] = y + n.h + 50f
            n.copy(x = snapF(60f + d * 280f), y = snapF(y))
        }
    }
}
