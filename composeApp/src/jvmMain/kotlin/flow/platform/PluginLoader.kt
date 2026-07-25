package flow.platform

import flow.engine.FlowEngine
import flow.model.FlowFile
import flow.model.InstallResult
import flow.model.ModuleInfo
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

    // A plugin JAR provides modules only (components are built in the Flow tool).
    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        val tmp = isolatedLoader(arrayOf(jar))
        val mods = runCatching { ServiceLoader.load(ModulePlugin::class.java, tmp).toList() }.getOrDefault(emptyList())
        if (mods.isEmpty()) return InstallResult()

        val conflicts = mods.filter { File(modulesDir, it.id).exists() }.map { it.id }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        mods.forEach { m ->
            val d = File(modulesDir, m.id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching { jar.copyTo(File(d, "${m.id}.jar"), overwrite = true) }
        }
        moduleCache = null
        return InstallResult(installed = mods.map { it.id })
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

}
