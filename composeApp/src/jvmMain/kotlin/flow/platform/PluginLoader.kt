package flow.platform

import flow.core.sizeForPorts
import flow.core.snapF
import flow.engine.FlowEngine
import flow.model.Edge
import flow.model.FlowFile
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.Node
import flow.model.PortRef
import flow.plugin.ComponentPlugin
import flow.plugin.ModulePlugin
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

// Install store (per folder): modules/<id>/*.jar, components/<id>/component.json (+ dependency module jars).
// Modules and components load/run via their own isolated URLClassLoader (sandbox) so dependency modules don't clash.
internal object PluginLoader {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    private val modulesDir = File(baseDir, "modules")
    private val componentsDir = File(baseDir, "components")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val apiClassLoader = ModulePlugin::class.java.classLoader

    private fun ensureDirs() { modulesDir.mkdirs(); componentsDir.mkdirs() }

    private fun jarsIn(dir: File): Array<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: emptyArray()

    private fun isolatedLoader(jars: Array<File>): URLClassLoader =
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), apiClassLoader)

    /* ───────── installed modules (isolated loader per folder) ───────── */

    // id -> (isolated classloader, primary module). Each module has its own loader so dependencies don't conflict.
    private var moduleCache: Map<String, Pair<URLClassLoader, ModulePlugin>>? = null
    private fun loadedModules(): Map<String, Pair<URLClassLoader, ModulePlugin>> {
        moduleCache?.let { return it }
        ensureDirs()
        val map = LinkedHashMap<String, Pair<URLClassLoader, ModulePlugin>>()
        modulesDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val jars = jarsIn(dir)
            if (jars.isEmpty()) return@forEach
            val cl = isolatedLoader(jars)
            val plugins = runCatching { ServiceLoader.load(ModulePlugin::class.java, cl).toList() }.getOrDefault(emptyList())
            val primary = plugins.find { it.id == dir.name } ?: plugins.firstOrNull()
            if (primary != null) map[primary.id] = cl to primary
        }
        moduleCache = map
        return map
    }

    fun moduleInfos(): List<ModuleInfo> =
        loadedModules().values.map { (_, p) -> ModuleInfo(p.id, p.displayName, p.inputs, p.outputs) }

    fun process(id: String, inputs: Map<String, String?>): Map<String, String?> =
        loadedModules()[id]?.second?.let { runCatching { it.process(inputs) }.getOrNull() } ?: emptyMap()

    /* ───────── installed components (per folder) ───────── */

    fun listComponents(): List<String> {
        ensureDirs()
        return componentsDir.listFiles { f -> f.isDirectory && File(f, "component.json").exists() }
            ?.map { "${it.name}.json" }?.sorted() ?: emptyList()
    }

    fun readComponent(name: String): String? {
        val id = name.removeSuffix(".json")
        return runCatching { File(componentsDir, "$id/component.json").takeIf { it.exists() }?.readText() }.getOrNull()
    }

    // run a component in its own folder sandbox (only bundled dependency modules; independent of global modules)
    fun runComponent(id: String, inputs: Map<String, String?>): Map<String, String?> {
        val dir = File(componentsDir, id)
        val compFile = File(dir, "component.json")
        if (!compFile.exists()) return emptyMap()
        val flow = runCatching { json.decodeFromString<FlowFile>(compFile.readText()) }.getOrNull() ?: return emptyMap()

        val sandbox = HashMap<String, ModulePlugin>()
        val jars = jarsIn(dir)
        if (jars.isNotEmpty()) {
            val cl = isolatedLoader(jars) // component-specific isolated loader
            runCatching { ServiceLoader.load(ModulePlugin::class.java, cl).forEach { sandbox[it.id] = it } }
        }
        val engine = FlowEngine(
            loadFlow = { ref -> readComponent(ref)?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } },
            moduleIds = sandbox.keys,
            moduleProcess = { mid, ins -> sandbox[mid]?.let { runCatching { it.process(ins) }.getOrNull() } ?: emptyMap() },
        )
        return engine.run(flow, inputs.mapValues { it.value ?: "" })
    }

    /* ───────── uninstall ───────── */

    // ids are package-format names; refuse anything that could escape the store dirs
    private fun safeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    fun uninstallModule(id: String) {
        if (!safeId(id)) return
        runCatching { File(modulesDir, id).deleteRecursively() }
        moduleCache = null
    }

    fun uninstallComponent(id: String) {
        if (!safeId(id)) return
        runCatching { File(componentsDir, id).deleteRecursively() }
    }

    /* ───────── install ───────── */

    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        val tmp = isolatedLoader(arrayOf(jar))
        val mods = runCatching { ServiceLoader.load(ModulePlugin::class.java, tmp).toList() }.getOrDefault(emptyList())
        val comps = runCatching { ServiceLoader.load(ComponentPlugin::class.java, tmp).toList() }.getOrDefault(emptyList())
        if (mods.isEmpty() && comps.isEmpty()) return InstallResult()

        val conflicts = mods.filter { File(modulesDir, it.id).exists() }.map { it.id } +
            comps.filter { File(componentsDir, it.id).exists() }.map { it.id }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        mods.forEach { m ->
            val d = File(modulesDir, m.id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching { jar.copyTo(File(d, "${m.id}.jar"), overwrite = true) }
        }
        comps.forEach { c ->
            val d = File(componentsDir, c.id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching {
                File(d, "component.json").writeText(json.encodeToString(materialize(c)))
                jar.copyTo(File(d, "deps.jar"), overwrite = true) // bundle dependency module code for the sandbox
            }
        }
        moduleCache = null
        return InstallResult(installed = mods.map { it.id } + comps.map { it.id })
    }

    // Install an editor component: create the folder + component.json, bundle referenced installed-module jars (sandbox)
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult {
        ensureDirs()
        val d = File(componentsDir, id)
        if (d.exists() && !overwrite) return InstallResult(conflicts = listOf(id))
        return runCatching {
            d.deleteRecursively(); d.mkdirs()
            File(d, "component.json").writeText(flowJson)
            val flow = json.decodeFromString<FlowFile>(flowJson)
            val mods = loadedModules()
            flow.nodes.map { it.type }.distinct().forEach { t ->
                if (mods.containsKey(t)) {
                    jarsIn(File(modulesDir, t)).forEach { jar -> runCatching { jar.copyTo(File(d, jar.name), overwrite = true) } }
                }
            }
            InstallResult(installed = listOf(id))
        }.getOrDefault(InstallResult())
    }

    /* ───────── component graph -> flow with coordinates (auto layout) ───────── */

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
