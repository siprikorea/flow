package flow.platform

import flow.model.AiReply
import flow.model.AiSetup
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.ViewInfo

// See Platform.requestFlowRun / takePendingFlowRun.
data class FlowRunRequest(val path: String, val start: Boolean, val inputs: Map<String, String> = emptyMap())

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
    // ── installed views (the same extension store; a jar may provide processors, views or both) ──
    fun installedViewInfos(): List<ViewInfo>
    // Asks a view to open a window on [data]. Returns once the window has been asked for — a window
    // belongs to the process that opened it, and the app neither waits for it nor closes it.
    suspend fun openView(id: String, data: ByteArray, options: Map<String, String>)
    // Brings a view's window forward. Cmd-` cycles the windows of one application and a view is
    // another process, so this is how a keyboard reaches one.
    suspend fun focusView(id: String)

    // ── the assistant: Claude Code, or an Ollama server on this machine ──
    // Where a provider's CLI is, or null when it is not installed — worth saying rather than
    // failing. Only asked of a provider set to be reached that way.
    fun cliPath(provider: String): String?
    // One turn. [onText] is called as the answer arrives; the reply's session carries the
    // conversation to the next turn.
    suspend fun askAi(prompt: String, sessionId: String?, ai: AiSetup, onText: (String) -> Unit): AiReply
    // Ends the run in progress. Whatever it had said by then stands.
    fun stopAi()
    // Drops a conversation the provider is holding. Only the HTTP providers hold one — their
    // transcripts live in this process, since none of those servers remembers anything between
    // requests.
    fun forgetAi(sessionId: String?)
    // What [ai]'s server offers, or empty if it cannot be reached or has no key — which is the same
    // answer the panel gives either way: there is nothing to pick.
    suspend fun aiModels(ai: AiSetup): List<String>

    // One environment variable, or null if it is not set. The API providers each name one that
    // their own tools already use, so a key exported for those need not be typed in again.
    fun env(name: String): String?

    // An API key, kept out of settings.json: that file is rewritten on every preference change and
    // is meant to be readable. Null when nothing has been stored under [name].
    fun loadSecret(name: String): String?
    fun saveSecret(name: String, value: String)

    // text in a named encoding, for the built-in String input — commonMain has UTF-8 and nothing
    // else, and which encoding a value is written in is the user's choice
    fun encodeText(text: String, charset: String): ByteArray
    fun decodeText(bytes: ByteArray, charset: String): String
    fun charsetNames(): List<String>

    // What Settings ▸ Modules was set to, by extension id — handed to a processor underneath the
    // node's own options. Pushed in when it changes rather than read per run.
    fun setExtensionSettings(values: Map<String, Map<String, String>>)

    // The settings an extension offers for the values it currently holds — asked rather than taken
    // from what it declared, because an extension may answer with something it had to go and find
    // out (the models a server has, for the key just entered). Suspending for that reason.
    suspend fun extensionSettingsFor(id: String, values: Map<String, String>): List<OptDef>

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

    // A flow to bring into view once the AI's current turn finishes — see the open_flow MCP tool
    // and Workspace.askAi, the only reader. save_flow never opens anything on its own; open_flow is
    // the one thing that does, for a flow it just saved or one that already existed.
    fun requestOpenFlow(name: String)
    fun takePendingOpenFlow(): String?  // consumes it — a second call the same turn sees nothing

    // Sets a saved flow's cin inputs on its open tab (opening one first if needed) — the same
    // field its inline data editor writes to, so a later run sees exactly what typing the value in
    // by hand would have. See the set_flow_input MCP tool; Workspace.askAi is the only reader.
    fun requestFlowInput(path: String, inputs: Map<String, String>)
    fun takePendingFlowInput(): Pair<String, Map<String, String>>?

    // Starts or stops the actual run on a flow's open tab (opening it first if needed) — real
    // execution on screen, as pressing the title bar's Start/Stop would, unlike run_flow's headless
    // one. [inputs] (start only; always empty for a stop) is applied the same instant, before the
    // run itself starts — one request, so there's no gap between setting a value and running with
    // it for something else to land in between. See start_flow / stop_flow (MCP); Workspace.askAi
    // is the only reader.
    fun requestFlowRun(path: String, start: Boolean, inputs: Map<String, String> = emptyMap())
    fun takePendingFlowRun(): FlowRunRequest?

    // Whether the Flow app itself is currently running. open_flow, requestFlowInput and
    // requestFlowRun all need a live app to ever pick up what they leave behind, and the MCP
    // server — a separate process — has no other way to tell before doing so.
    fun isAppRunning(): Boolean
    // Records that this process is the running app, for isAppRunning() to find. Called once, by
    // the GUI entry point only — never by the CLI/MCP one, which loads this same Platform object
    // but is not the app isAppRunning() means to detect.
    fun markAppRunning()

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
