package flow.platform

import flow.model.Drawing
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.InputInfo
import flow.model.OutputInfo

expect object Platform {
    // ── installed modules/components (each folder isolated by a classloader = sandbox) ──
    fun installedModuleInfos(): List<ModuleInfo>
    // Suspending because a module is arbitrary code that may block for as long as it likes, and
    // Stop has to be able to cut it off; the JVM side runs it where cancellation interrupts it.
    suspend fun moduleProcess(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?>
    // ports for the given option values (null = id isn't a known module). Most modules' ports
    // don't depend on options, in which case this just returns the module's fixed inputs/outputs.
    fun moduleInputsFor(id: String, options: Map<String, String>): List<String>?
    fun moduleOutputsFor(id: String, options: Map<String, String>): List<String>?
    // options to show for the given values (a module may hide options another option makes moot)
    fun moduleOptionsFor(id: String, values: Map<String, String>): List<OptDef>?
    // ── installed views (the same extension store; a jar may provide modules, views or both) ──
    fun installedOutputInfos(): List<OutputInfo>
    // Asks a view to draw [data] into a strip [width] wide. Suspending for the same reason
    // moduleProcess is: a view is arbitrary code, and a drawing that never finishes has to be
    // cancellable. It draws as tall as it needs and the app scrolls the rest.
    suspend fun drawOutput(
        id: String,
        data: ByteArray,
        options: Map<String, String>,
        width: Float,
        monoCharWidth: Float,
    ): Drawing

    // Reports what the user did on a view and returns the options to draw it with next — the same
    // ones back when the view makes nothing of it. Suspending like the rest: it runs the extension.
    suspend fun outputEvent(
        id: String,
        kind: String,
        region: String?,
        x: Float,
        y: Float,
        options: Map<String, String>,
    ): Map<String, String>

    // ── installed inputs (how the bytes going into a flow are written) ──
    fun installedInputInfos(): List<InputInfo>
    // The bytes [text] stands for, and what is wrong with it — both from one call, because the
    // editor needs both on the same keystroke.
    suspend fun inputParse(id: String, text: String, options: Map<String, String>): Pair<ByteArray, String?>
    // How [data] reads back in the editor.
    suspend fun inputFormat(id: String, data: ByteArray, options: Map<String, String>): String

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

    // ── extension registry (a manifest served over HTTPS; see flow.model.RegistryIndex) ──
    fun fetchText(url: String): String?  // null on any network/HTTP failure
    // Download the jar at [url] and install it. Same conflict rules as installJar.
    fun installFromUrl(url: String, overwrite: Boolean): InstallResult
    fun pickFlowFile(): String? // Open dialog restricted to *.flow -> absolute path (File > Open)
    // Read a .flow file anywhere on disk by its absolute path (File > Open / drag-and-drop of a
    // file the project sandbox wouldn't otherwise resolve). Null if missing or not a .flow file.
    fun readExternalFlow(path: String): String?

    // ── the project folder ──
    // Any folder the user picks, or none. Every path below is relative to whichever is open, and
    // reads as empty while none is: the app starts without one, as an editor does.
    fun projectRoot(): String?               // absolute path, or null when no folder is open
    fun openProject(path: String?)           // null closes the current one
    fun projectName(): String?               // the folder's own name, for the tree's root row
    fun pickFolder(): String?                // folder picker dialog

    // Reading and writing a .flow by absolute path, for a file opened on its own with no project
    // folder around it.
    fun writeExternalFlow(path: String, json: String): Boolean

    // Project folder tree; paths are relative to the project root ("sub/a.flow", "sub").
    fun listFlows(): List<String>            // *.flow anywhere under the root, recursive
    fun listProjectFiles(): List<String>     // every file under the root (the tree shows them all)
    fun listFlowDirs(): List<String>         // folders anywhere under the root, recursive
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)  // creates missing parent folders
    fun createFlowDir(path: String): Boolean
    fun deleteFlowPath(path: String)         // file, or folder with everything under it
    fun renameFlowPath(oldPath: String, newPath: String): Boolean // file or folder

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

    // user preferences, kept in their own file so they survive independently of a workspace
    fun loadSettings(): String?
    fun saveSettings(json: String)

    fun currentTimeHms(): String

    // how the meta modifier is written in shortcuts: "⌘" on macOS, "Win+" elsewhere
    fun metaKeyLabel(): String
}
