package flow.platform

import flow.engine.FlowEngine
import flow.model.FlowFile
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.OptType
import flow.model.ViewInfo
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

    /**
     * What Settings ▸ Extensions has been set to, by extension id.
     *
     * Held here rather than read off disk per run: it is the app's own state, the Settings screen
     * is the only thing that writes it, and a run must not pay for a file read per node. Pushed in
     * by the workspace whenever it changes (Platform.setExtensionSettings).
     */
    @Volatile
    private var extensionSettings: Map<String, Map<String, String>> = emptyMap()

    fun setExtensionSettings(values: Map<String, Map<String, String>>) {
        extensionSettings = values
    }

    private fun settingsFor(id: String): Map<String, String> = extensionSettings[id] ?: emptyMap()

    private fun processFor(dir: File): ExtensionProcess = synchronized(processes) {
        processes.getOrPut(dir) { ExtensionProcess(dir, jarsIn(dir).toList()) }
    }

    // what a worker told us about one output it serves
    private class LoadedView(val dir: File, val info: ViewInfo)

    // Asks each extension folder what it provides. A folder that cannot answer — a broken jar, a
    // worker that will not start — is left out rather than taking the scan down with it.
    //
    // Modules and views are collected together in one walk so the two can never disagree about
    // which folders exist, and so each worker is asked both questions while it is already up.
    private fun scan() {
        ensureDirs()
        val processors = LinkedHashMap<String, Loaded>()
        val views = LinkedHashMap<String, LoadedView>()
        extensionsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            // a component's bundled dependency jars are not installed processors
            if (isComponentDir(dir)) return@forEach
            if (jarsIn(dir).isEmpty()) return@forEach
            val proc = processFor(dir)
            runCatching { describe(proc) }.getOrDefault(emptyList()).forEach { processors[it.id] = Loaded(dir, it) }
            runCatching { describeViews(proc) }.getOrDefault(emptyList()).forEach { views[it.id] = LoadedView(dir, it) }
        }
        processorCache = processors
        viewCache = views
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
            val options = readOptions(input)
            val settings = readOptions(input)
            ModuleInfo(
                id, name, inputs, outputs, options, version, settings,
                Wire.readStringList(input), Wire.readStringMap(input), Wire.readStringMap(input),
                Wire.readStringList(input),
            )
        }
    }

    /** VIEW_DESCRIBE: the views one worker serves, which for most extensions is none. */
    private fun describeViews(proc: ExtensionProcess): List<ViewInfo> {
        val reply = proc.request(Wire.VIEW_DESCRIBE) {}
        if (!reply.ok) return emptyList()
        val input = DataInputStream(ByteArrayInputStream(reply.payload))
        return (0 until input.readInt()).map {
            val id = Wire.readString(input)
            val name = Wire.readString(input)
            val version = Wire.readString(input)
            ViewInfo(id, name, Wire.readString(input), version)
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
            OptDef(name, type, Wire.readString(input), Wire.readStringList(input), input.readBoolean())
        }

    // a worker's failure is this call's failure, carrying whatever the extension said
    private fun ExtensionProcess.Reply.orThrow(): DataInputStream {
        if (!ok) throw failure(payload)
        return DataInputStream(ByteArrayInputStream(payload))
    }

    /**
     * What the extension said, without the framing it was written with.
     *
     * The worker writes its message with Wire.writeString — a four-byte length, then the text —
     * and reading the payload as raw bytes glued that length onto the front of every error a
     * module produced: three NULs and a stray character before "'in' is not a number". It reached
     * the canvas, the CLI and the MCP client that way. A payload whose length does not describe
     * itself is read as it stands, so an unframed message from anywhere else still arrives whole.
     */
    private fun failure(payload: ByteArray): ExtensionFailure {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val message = runCatching { Wire.readString(input) }.getOrNull()
            // an unframed payload is read as it stands, so a message from anywhere else arrives whole
            ?: return ExtensionFailure(payload.decodeToString(), "")
        val type = runCatching { Wire.readString(input) }.getOrDefault("")
        return ExtensionFailure(message, type)
    }

    // id -> what was loaded, from the one install root.
    private var processorCache: Map<String, Loaded>? = null
    private var viewCache: Map<String, LoadedView>? = null

    private fun loadedModules(): Map<String, Loaded> =
        processorCache ?: run { scan(); processorCache.orEmpty() }

    private fun loadedViews(): Map<String, LoadedView> =
        viewCache ?: run { scan(); viewCache.orEmpty() }

    fun moduleInfos(): List<ModuleInfo> = loadedModules().values.map { it.info }

    fun process(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val loaded = loadedModules()[id] ?: return emptyMap()
        val reply = processFor(loaded.dir).request(Wire.PROCESS) { o ->
            Wire.writeString(o, id)
            Wire.writeByteMap(o, inputs)
            Wire.writeStringMap(o, options)
            Wire.writeStringMap(o, settingsFor(id))
        }
        return Wire.readByteMap(reply.orThrow())
    }

    /**
     * The settings an extension offers for the values they currently hold.
     *
     * Asked rather than taken from what DESCRIBE said, because an extension may answer it with
     * something it had to go and find out — the models a server has, for the key just entered. That
     * makes this slow enough to matter, so the caller does it off the UI thread and remembers.
     */
    fun settingsFor(id: String, values: Map<String, String>): List<OptDef>? {
        val loaded = loadedModules()[id] ?: return null
        val reply = processFor(loaded.dir).request(Wire.SETTINGS) { o ->
            Wire.writeString(o, id)
            Wire.writeStringMap(o, values)
        }
        if (!reply.ok) return null
        return readOptions(DataInputStream(ByteArrayInputStream(reply.payload)))
    }

    // ports for the given option values (null = id isn't a known processor — the host treats a null
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

    fun viewInfos(): List<ViewInfo> = loadedViews().values.map { it.info }

    /**
     * Asks a view to open a window on [data].
     *
     * Returns as soon as the window has been asked for. A window belongs to the process that opened
     * it and lives as long as it likes; the app is not waiting for it and cannot close it.
     */
    fun openView(id: String, data: ByteArray, options: Map<String, String>) {
        val loaded = loadedViews()[id] ?: error("view '$id' is not installed")
        val reply = processFor(loaded.dir).request(Wire.VIEW_OPEN) { o ->
            Wire.writeString(o, id)
            Wire.writeBytes(o, data)
            Wire.writeStringMap(o, options)
        }
        if (!reply.ok) error(reply.payload.decodeToString())
    }

    /**
     * Brings a view's window to the front.
     *
     * Nothing happens if it has no window open — asking is cheaper than keeping track of which
     * windows are up, and the worker is the only one that knows anyway.
     */
    fun focusView(id: String) {
        val loaded = loadedViews()[id] ?: return
        runCatching { processFor(loaded.dir).request(Wire.VIEW_FOCUS) {} }
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
        val sandboxed = runCatching { describe(proc) }.getOrDefault(emptyList())
        val sandboxIds = sandboxed.map { it.id }.toSet()
        val engine = FlowEngine(
            loadFlow = { ref -> readComponent(ref)?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } },
            moduleIds = sandboxIds,
            optionalInputsOf = { id -> sandboxed.find { it.id == id }?.optionalInputs?.toSet() },
            moduleProcess = { mid, ins, params ->
                runInterruptible(Dispatchers.Default) {
                    val reply = proc.request(Wire.PROCESS) { o ->
                        Wire.writeString(o, mid)
                        Wire.writeByteMap(o, ins)
                        Wire.writeStringMap(o, params)
                        Wire.writeStringMap(o, settingsFor(mid))
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
        invalidate()
    }

    fun uninstallComponent(id: String) {
        if (!safeId(id)) return
        release(File(extensionsDir, id))
        runCatching { File(extensionsDir, id).deleteRecursively() }
    }

    private fun invalidate() {
        processorCache = null
        viewCache = null
    }

    /* ───────── install ───────── */

    // An extension JAR provides modules only (components are built in the Flow tool).
    fun installJar(path: String, overwrite: Boolean): InstallResult {
        val jar = File(path).takeIf { it.isFile } ?: return InstallResult()
        ensureDirs()
        // asked in a throwaway worker: finding out what a jar provides means running its code, and
        // an installer should not be the one place that still does that in the app's own JVM
        val probe = ExtensionProcess(jar.parentFile ?: extensionsDir, listOf(jar))
        // a jar may provide processors, views, or both — either is worth installing
        val ids = try {
            (runCatching { describe(probe) }.getOrDefault(emptyList()).map { it.id } +
                runCatching { describeViews(probe) }.getOrDefault(emptyList()).map { it.id }).distinct()
        } finally {
            probe.kill()
        }
        if (ids.isEmpty()) return InstallResult()

        val conflicts = ids.filter { File(extensionsDir, it).exists() }
        if (conflicts.isNotEmpty() && !overwrite) return InstallResult(conflicts = conflicts)

        ids.forEach { id ->
            release(File(extensionsDir, id))
            val d = File(extensionsDir, id).also { it.deleteRecursively(); it.mkdirs() }
            runCatching { jar.copyTo(File(d, "$id$EXTENSION_SUFFIX"), overwrite = true) }
        }
        invalidate()
        return InstallResult(installed = ids)
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
