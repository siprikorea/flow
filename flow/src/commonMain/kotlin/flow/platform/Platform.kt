package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef

expect object Platform {
    // ── installed modules/components (each folder isolated by a classloader = sandbox) ──
    fun installedModuleInfos(): List<ModuleInfo>
    fun moduleProcess(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
    // ports for the given option values (null = id isn't a known module). Most modules' ports
    // don't depend on options, in which case this just returns the module's fixed inputs/outputs.
    fun moduleInputsFor(id: String, options: Map<String, String>): List<String>?
    fun moduleOutputsFor(id: String, options: Map<String, String>): List<String>?
    // options to show for the given values (a module may hide options another option makes moot)
    fun moduleOptionsFor(id: String, values: Map<String, String>): List<OptDef>?
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
    fun pickFlowFile(): String? // Open dialog restricted to *.flow -> absolute path (File > Open)
    // Read a .flow file anywhere on disk by its absolute path (File > Open / drag-and-drop of a
    // file the project sandbox wouldn't otherwise resolve). Null if missing or not a .flow file.
    fun readExternalFlow(path: String): String?

    // Project folder tree; paths are relative to the flows root ("sub/a.flow", "sub").
    fun listFlows(): List<String>            // *.flow anywhere under the root, recursive
    fun listProjectFiles(): List<String>     // every file under the root (the tree shows them all)
    fun listFlowDirs(): List<String>         // folders anywhere under the root, recursive
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)  // creates missing parent folders
    fun createFlowDir(path: String): Boolean
    fun deleteFlowPath(path: String)         // file, or folder with everything under it
    fun renameFlowPath(oldPath: String, newPath: String): Boolean // file or folder
    fun flowsDirLabel(): String              // absolute path
    fun flowsDirName(): String               // root folder name

    // "Open In" on a project item. [rel] is a project-relative path ("" = the root folder itself);
    // anything that escapes the project is refused, as everywhere else.
    fun revealInFileManager(rel: String)     // select the item in Finder/Explorer/the file manager
    fun openInTerminal(rel: String)          // a shell already sitting in that folder
    fun fileManagerName(): String            // "Finder" / "Explorer" / "Files", for the menu label

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

    // how the meta modifier is written in shortcuts: "⌘" on macOS, "Win+" elsewhere
    fun metaKeyLabel(): String
}
