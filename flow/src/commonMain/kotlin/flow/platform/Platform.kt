package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo

expect object Platform {
    // ── installed modules/components (each folder isolated by a classloader = sandbox) ──
    fun installedModuleInfos(): List<ModuleInfo>
    fun moduleProcess(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
    // ports for the given option values (null = id isn't a known module). Most modules' ports
    // don't depend on options, in which case this just returns the module's fixed inputs/outputs.
    fun moduleInputsFor(id: String, options: Map<String, String>): List<String>?
    fun moduleOutputsFor(id: String, options: Map<String, String>): List<String>?
    fun listInstalledComponents(): List<String>          // id.json under components/<id>/
    fun readInstalledComponent(name: String): String?
    // run a component in its own folder sandbox (bundled dependency modules)
    fun runComponent(id: String, inputs: Map<String, ByteArray?>): Map<String, ByteArray?>

    // install: an already-existing id is returned in conflicts when overwrite=false (not installed)
    fun installJar(path: String, overwrite: Boolean): InstallResult
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult
    fun uninstallModule(id: String)
    fun uninstallComponent(id: String)
    fun pickJar(): String? // JAR file picker dialog

    // flow files in the project folder (*.flow)
    fun listFlows(): List<String>
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)
    fun deleteFlow(name: String)
    fun renameFlow(oldName: String, newName: String): Boolean
    fun flowsDirLabel(): String

    // arbitrary file access for the data editor
    fun pickFileRead(): String?               // open dialog -> path
    fun pickFileSave(defaultName: String): String? // save dialog -> path
    fun fileSize(path: String): Long          // -1 if missing
    fun readFileRange(path: String, offset: Long, length: Int): ByteArray // partial read (window)
    fun writeBytes(path: String, bytes: ByteArray): Boolean
    fun appendBytes(path: String, bytes: ByteArray): Boolean
    fun fileName(path: String): String
    fun createTempFile(prefix: String): String // an app-owned scratch file (deleteOnExit); caller writes to it

    // Streams the system clipboard's text in bounded chunks (never materializes it all as one
    // string) via onChunk; returns false if the clipboard has no text content at all.
    fun pasteClipboardChunks(maxChunkChars: Int, onChunk: (String) -> Unit): Boolean

    // session (open tabs + UI state) restore
    fun loadSession(): String?
    fun saveSession(json: String)

    fun currentTimeHms(): String
}
