package flow.platform

import flow.engine.FlowEngine
import flow.model.FlowFile
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.OptType
import kotlinx.serialization.json.Json
import java.io.File
import flow.extension.host.Wire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

// Install store: everything installed lives under extensions/<id>/, holding either the module's
// jar(s) or a component.json plus the module jars that component depends on. Each loads and runs
// through its own isolated URLClassLoader (sandbox) so one install's dependencies never clash with
// another's.
//
// A component folder carries jars too, so "is this a module or a component" is decided by the
// presence of component.json — without that check a component's bundled dependency would register
// itself as an installed module.
//
// extensionsDir under the user's home is the only root. Nothing ships with the app: every module
// arrives by being installed, from the registry or from a file, and can be removed the same way.
internal object ExtensionLoader {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    private val extensionsDir = File(baseDir, "extensions")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private const val COMPONENT_FILE = "component.json"

    // Extensions are jars underneath — the class loader reads them by content, not by name — but
    // they carry their own suffix so one is recognisable as a Flow extension rather than as some
    // library that happens to be lying around. ".jar" is still accepted when scanning, so anything
    // installed before this keeps loading.
    const val EXTENSION_SUFFIX = ".flowext"
    private val LOADABLE_SUFFIXES = listOf(EXTENSION_SUFFIX, ".jar")

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
        dir.listFiles { f -> f.isFile && LOADABLE_SUFFIXES.any { s -> f.name.endsWith(s) } } ?: emptyArray()

    /* ───────── installed modules (a process per extension) ───────── */

    // what a worker told us about one module it serves
    private class Loaded(val dir: File, val info: ModuleInfo)

    private val processes = LinkedHashMap<File, ExtensionProcess>()

    private fun processFor(dir: File): ExtensionProcess = synchronized(processes) {
        processes.getOrPut(dir) { ExtensionProcess(dir, jarsIn(dir).toList()) }
    }

    // Asks each extension folder what it provides. A folder that cannot answer — a broken jar, a
    // worker that will not start — is left out rather than taking the scan down with it.
    private fun scanModuleRoot(root: File?): Map<String, Loaded> {
        val map = LinkedHashMap<String, Loaded>()
        root?.listFiles { f -> f.isDirectory }?.forEach { dir ->
            // a component's bundled dependency jars are not installed modules
            if (isComponentDir(dir)) return@forEach
            if (jarsIn(dir).isEmpty()) return@forEach
            runCatching { describe(processFor(dir)) }.getOrDefault(emptyList()).forEach { info ->
                map[info.id] = Loaded(dir, info)
            }
        }
        return map
    }

    /** DESCRIBE: everything one worker serves. */
    private fun describe(proc: ExtensionProcess): List<ModuleInfo> {
        val reply = proc.request(Wire.DESCRIBE) {}
        if (!reply.ok) return emptyList()
        val input = DataInputStream(ByteArrayInputStream(reply.payload))
        // read in the order the worker writes them, not the order ModuleInfo declares them
        return (0 until input.readInt()).map {
            val id = Wire.readString(input)
            val name = Wire.readString(input)
            val version = Wire.readString(input)
            val inputs = Wire.readStringList(input)
            val outputs = Wire.readStringList(input)
            ModuleInfo(id, name, inputs, outputs, readOptions(input), version)
        }
    }

    private fun readOptions(input: DataInputStream): List<OptDef> =
        (0 until input.readInt()).map {
            val name = Wire.readString(input)
            val type = when (Wire.readString(input)) {
                "NUMBER" -> OptType.NUMBER
                "SELECT" -> OptType.SELECT
                else -> OptType.TEXT
            }
            OptDef(name, type, Wire.readString(input), Wire.readStringList(input))
        }

    // a worker's failure is this call's failure, carrying whatever the extension said
    private fun ExtensionProcess.Reply.orThrow(): DataInputStream {
        if (!ok) error(payload.decodeToString())
        return DataInputStream(ByteArrayInputStream(payload))
    }

    // id -> loaded module, from the one install root.
    private var moduleCache: Map<String, Loaded>? = null
    private fun loadedModules(): Map<String, Loaded> {
        moduleCache?.let { return it }
        ensureDirs()
        val map = LinkedHashMap<String, Loaded>()
        map.putAll(scanModuleRoot(extensionsDir))
        moduleCache = map
        return map
    }

    fun moduleInfos(): List<ModuleInfo> = loadedModules().values.map { it.info }

    fun process(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val loaded = loadedModules()[id] ?: return emptyMap()
        val reply = processFor(loaded.dir).request(Wire.PROCESS) { o ->
            Wire.writeString(o, id)
            Wire.writeByteMap(o, inputs)
            Wire.writeStringMap(o, options)
        }
        return Wire.readByteMap(reply.orThrow())
    }

    // ports for the given option values (null = id isn't a known module — the host treats a null
    // result differently from an empty port list, which is legitimate for a no-input generator)
    private fun ports(id: String, options: Map<String, String>): Pair<List<String>, List<String>>? {
        val loaded = loadedModules()[id] ?: return null
        val reply = processFor(loaded.dir).request(Wire.PORTS) { o ->
            Wire.writeString(o, id)
            Wire.writeStringMap(o, options)
        }
        if (!reply.ok) return loaded.info.inputs to loaded.info.outputs
        val input = DataInputStream(ByteArrayInputStream(reply.payload))
        return Wire.readStringList(input) to Wire.readStringList(input)
    }

    fun inputsFor(id: String, options: Map<String, String>): List<String>? = ports(id, options)?.first
    fun outputsFor(id: String, options: Map<String, String>): List<String>? = ports(id, options)?.second

    fun optionsFor(id: String, values: Map<String, String>): List<OptDef>? {
        val loaded = loadedModules()[id] ?: return null
        val reply = processFor(loaded.dir).request(Wire.OPTIONS) { o ->
            Wire.writeString(o, id)
            Wire.writeStringMap(o, values)
        }
        if (!reply.ok) return loaded.info.options
        return readOptions(DataInputStream(ByteArrayInputStream(reply.payload)))
    }

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

        // the component's bundled jars get their own worker, same as an installed extension
        val proc = processFor(dir)
        val sandboxIds = runCatching { describe(proc).map { it.id }.toSet() }.getOrDefault(emptySet())
        val engine = FlowEngine(
            loadFlow = { ref -> readComponent(ref)?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } },
            moduleIds = sandboxIds,
            moduleProcess = { mid, ins, params ->
                runInterruptible(Dispatchers.Default) {
                    val reply = proc.request(Wire.PROCESS) { o ->
                        Wire.writeString(o, mid)
                        Wire.writeByteMap(o, ins)
                        Wire.writeStringMap(o, params)
                    }
                    Wire.readByteMap(reply.orThrow())
                }
            },
        )
        // a sandboxed component is run from ordinary (non-suspending) calls, so block here
        return runBlocking { engine.run(flow, inputs.mapValues { it.value ?: ByteArray(0) }) }
    }

    /* ───────── uninstall ───────── */

    // ids are package-format names; refuse anything that could escape the store dirs
    private fun safeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    // the worker holds the jars open, so it has to go before the folder can be removed
    private fun release(dir: File) {
        synchronized(processes) { processes.remove(dir) }?.kill()
    }

    fun uninstallModule(id: String) {
        if (!safeId(id)) return
        release(File(extensionsDir, id))
        runCatching { File(extensionsDir, id).deleteRecursively() }
        moduleCache = null
    }

    fun uninstallComponent(id: String) {
        if (!safeId(id)) return
        release(File(extensionsDir, id))
        runCatching { File(extensionsDir, id).deleteRecursively() }
    }

    /* ───────── install ───────── */

    // An extension JAR provides modules only (components are built in the Flow tool).
    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        // asked in a throwaway worker: finding out what a jar provides means running its code, and
        // an installer should not be the one place that still does that in the app's own JVM
        val probe = ExtensionProcess(jar.parentFile ?: extensionsDir, listOf(jar))
        val mods = try {
            runCatching { describe(probe) }.getOrDefault(emptyList())
        } finally {
            probe.kill()
        }
        if (mods.isEmpty()) return InstallResult()

        val conflicts = mods.filter { File(extensionsDir, it.id).exists() }.map { it.id }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        mods.forEach { m ->
            release(File(extensionsDir, m.id))
            val d = File(extensionsDir, m.id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching { jar.copyTo(File(d, "${m.id}$EXTENSION_SUFFIX"), overwrite = true) }
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
