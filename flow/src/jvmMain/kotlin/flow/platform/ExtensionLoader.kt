package flow.platform

import flow.engine.FlowEngine
import flow.extension.ModuleExtension
import flow.model.FlowFile
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.OptType
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

// Install store: everything installed lives under extensions/<id>/, holding either the module's
// jar(s) or a component.json plus the module jars that component depends on. Each loads and runs
// through its own isolated URLClassLoader (sandbox) so one install's dependencies never clash with
// another's.
//
// A component folder carries jars too, so "is this a module or a component" is decided by the
// presence of component.json — without that check a component's bundled dependency would register
// itself as an installed module.
//
// Two module roots: `builtinModulesDir` ships inside the app install (Compose's bundled app-resources
// dir, exposed at runtime via the compose.application.resources.dir system property) and is never
// written to or deleted by this loader; `extensionsDir` under the user's home is where user-installed
// extensions live and is the only root install/uninstall ever touch.
internal object ExtensionLoader {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    private val extensionsDir = File(baseDir, "extensions")
    private val builtinModulesDir = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "modules") }?.takeIf { it.isDirectory }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val apiClassLoader = ModuleExtension::class.java.classLoader

    private const val COMPONENT_FILE = "component.json"

    init {
        // one-time move of the previous split store; a name already taken in the merged dir is
        // left alone rather than overwritten, so nothing installed is silently replaced
        runCatching {
            if (!extensionsDir.exists()) {
                extensionsDir.mkdirs()
                listOf(File(baseDir, "modules"), File(baseDir, "components")).forEach { old ->
                    old.listFiles { f: File -> f.isDirectory }?.forEach { dir ->
                        val target = File(extensionsDir, dir.name)
                        if (!target.exists()) dir.renameTo(target)
                    }
                    // delete() only succeeds on an empty directory, so anything left behind by a
                    // name clash keeps its old home rather than being thrown away
                    old.delete()
                }
            }
        }
    }

    private fun ensureDirs() { extensionsDir.mkdirs() }

    private fun isComponentDir(dir: File) = File(dir, COMPONENT_FILE).exists()

    private fun jarsIn(dir: File): Array<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: emptyArray()

    private fun isolatedLoader(jars: Array<File>): URLClassLoader =
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), apiClassLoader)

    // extension option specs -> the model's typed option specs (for palette/props)
    private fun optDefs(ext: ModuleExtension): List<OptDef> = optDefs(ext.options)

    private fun optDefs(options: List<flow.extension.ExtensionOption>): List<OptDef> = options.map {
        OptDef(it.name, when (it.type) {
            flow.extension.OptionType.NUMBER -> OptType.NUMBER
            flow.extension.OptionType.SELECT -> OptType.SELECT
            else -> OptType.TEXT
        }, it.default, it.choices)
    }

    // Extension ports and the engine both carry raw bytes now, so no bridging is needed here.
    // Exceptions (e.g. an invalid key/IV size) propagate to the caller, which attributes them to
    // the specific node and surfaces them as that node's error status (see FlowEngine.evaluate).
    private fun runExtension(ext: ModuleExtension, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        ext.process(inputs, options)

    /* ───────── installed modules (isolated loader per folder) ───────── */

    private class LoadedModule(val dir: File, val loader: URLClassLoader, val ext: ModuleExtension, val builtin: Boolean)

    // scans one root (each subfolder = one module's jar(s), loaded via its own isolated classloader)
    private fun scanModuleRoot(root: File?, builtin: Boolean): Map<String, LoadedModule> {
        val map = LinkedHashMap<String, LoadedModule>()
        root?.listFiles { f -> f.isDirectory }?.forEach { dir ->
            // a component's bundled dependency jars are not installed modules
            if (isComponentDir(dir)) return@forEach
            val jars = jarsIn(dir)
            if (jars.isEmpty()) return@forEach
            val cl = isolatedLoader(jars)
            val exts = runCatching { ServiceLoader.load(ModuleExtension::class.java, cl).toList() }.getOrDefault(emptyList())
            val primary = exts.find { it.id == dir.name } ?: exts.firstOrNull()
            if (primary != null) map[primary.id] = LoadedModule(dir, cl, primary, builtin)
        }
        return map
    }

    // id -> loaded module. Builtin (app-bundled) entries first; user installs can shadow by id (rare, not enforced).
    private var moduleCache: Map<String, LoadedModule>? = null
    private fun loadedModules(): Map<String, LoadedModule> {
        moduleCache?.let { return it }
        ensureDirs()
        val map = LinkedHashMap<String, LoadedModule>()
        map.putAll(scanModuleRoot(builtinModulesDir, builtin = true))
        map.putAll(scanModuleRoot(extensionsDir, builtin = false))
        moduleCache = map
        return map
    }

    fun moduleInfos(): List<ModuleInfo> =
        loadedModules().values.map { m -> ModuleInfo(m.ext.id, m.ext.displayName, m.ext.inputs, m.ext.outputs, optDefs(m.ext), m.builtin) }

    fun process(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        loadedModules()[id]?.ext?.let { runExtension(it, inputs, options) } ?: emptyMap()

    // ports for the given option values (null = id isn't a known module — the host treats a null
    // result differently from an empty port list, which is legitimate for a no-input generator)
    fun inputsFor(id: String, options: Map<String, String>): List<String>? = loadedModules()[id]?.ext?.inputsFor(options)
    fun outputsFor(id: String, options: Map<String, String>): List<String>? = loadedModules()[id]?.ext?.outputsFor(options)
    fun optionsFor(id: String, values: Map<String, String>): List<OptDef>? =
        loadedModules()[id]?.ext?.let { optDefs(it.optionsFor(values)) }

    /* ───────── installed components (per folder) ───────── */

    fun listComponents(): List<String> {
        ensureDirs()
        return extensionsDir.listFiles { f -> f.isDirectory && isComponentDir(f) }
            ?.map { "${it.name}.json" }?.sorted() ?: emptyList()
    }

    fun readComponent(name: String): String? {
        val id = name.removeSuffix(".json")
        return runCatching { File(extensionsDir, "$id/$COMPONENT_FILE").takeIf { it.exists() }?.readText() }.getOrNull()
    }

    // run a component in its own folder sandbox (only bundled dependency modules; independent of global modules)
    fun runComponent(id: String, inputs: Map<String, ByteArray?>): Map<String, ByteArray?> {
        val dir = File(extensionsDir, id)
        val compFile = File(dir, COMPONENT_FILE)
        if (!compFile.exists()) return emptyMap()
        val flow = runCatching { json.decodeFromString<FlowFile>(compFile.readText()) }.getOrNull() ?: return emptyMap()

        val sandbox = HashMap<String, ModuleExtension>()
        val jars = jarsIn(dir)
        if (jars.isNotEmpty()) {
            val cl = isolatedLoader(jars) // component-specific isolated loader
            runCatching { ServiceLoader.load(ModuleExtension::class.java, cl).forEach { sandbox[it.id] = it } }
        }
        val engine = FlowEngine(
            loadFlow = { ref -> readComponent(ref)?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } },
            moduleIds = sandbox.keys,
            moduleProcess = { mid, ins, params -> sandbox[mid]?.let { runExtension(it, ins, params) } ?: emptyMap() },
        )
        return engine.run(flow, inputs.mapValues { it.value ?: ByteArray(0) })
    }

    /* ───────── uninstall ───────── */

    // ids are package-format names; refuse anything that could escape the store dirs
    private fun safeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    fun uninstallModule(id: String) {
        if (!safeId(id)) return
        runCatching { File(extensionsDir, id).deleteRecursively() }
        moduleCache = null
    }

    fun uninstallComponent(id: String) {
        if (!safeId(id)) return
        runCatching { File(extensionsDir, id).deleteRecursively() }
    }

    /* ───────── install ───────── */

    // An extension JAR provides modules only (components are built in the Flow tool).
    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        val tmp = isolatedLoader(arrayOf(jar))
        val mods = runCatching { ServiceLoader.load(ModuleExtension::class.java, tmp).toList() }.getOrDefault(emptyList())
        if (mods.isEmpty()) return InstallResult()

        // built-in ids can never be shadowed by a user install, overwrite or not
        val builtinConflicts = mods.filter { loadedModules()[it.id]?.builtin == true }.map { it.id }
        if (builtinConflicts.isNotEmpty()) return InstallResult(conflicts = builtinConflicts)

        val conflicts = mods.filter { File(extensionsDir, it.id).exists() }.map { it.id }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        mods.forEach { m ->
            val d = File(extensionsDir, m.id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching { jar.copyTo(File(d, "${m.id}.jar"), overwrite = true) }
        }
        moduleCache = null
        return InstallResult(installed = mods.map { it.id })
    }

    // Install an editor component: create the folder + component.json, bundle referenced installed-module jars (sandbox)
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult {
        ensureDirs()
        val d = File(extensionsDir, id)
        if (d.exists() && !overwrite) return InstallResult(conflicts = listOf(id))
        return runCatching {
            d.deleteRecursively(); d.mkdirs()
            File(d, COMPONENT_FILE).writeText(flowJson)
            val flow = json.decodeFromString<FlowFile>(flowJson)
            val mods = loadedModules()
            flow.nodes.map { it.type }.distinct().forEach { t ->
                mods[t]?.let { m -> jarsIn(m.dir).forEach { jar -> runCatching { jar.copyTo(File(d, jar.name), overwrite = true) } } }
            }
            InstallResult(installed = listOf(id))
        }.getOrDefault(InstallResult())
    }
}
