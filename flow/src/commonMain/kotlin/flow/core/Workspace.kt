package flow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.ModuleInfo
import flow.model.INPUT_PARAM
import flow.model.OUTPUT_PARAM
import flow.model.InputInfo
import flow.model.OutputInfo
import flow.model.OptDef
import flow.model.Node
import flow.model.Session
import flow.model.Settings
import flow.model.DEFAULT_REGISTRY_URL
import flow.model.RegistryEntry
import flow.model.RegistryIndex
import flow.model.RegistryState
import flow.model.compareVersions
import flow.model.asComponent
import flow.platform.Platform
import flow.ui.theme.Theme
import flow.util.flowLabel
import flow.util.isValidSegment
import flow.util.pathAncestors
import flow.util.pathJoin
import flow.util.pathName
import flow.util.pathParent
import flow.util.pathUnder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// pending install-overwrite confirmation (label = conflicting id, commit = perform overwrite)
class InstallPending(val label: String, val commit: () -> Unit)

// An open data-editor tab: edits the sample data of a component boundary (cin/cout) node.
class DataTab(val doc: EditorState, val nodeId: String) {
    val node: Node? get() = doc.nodeById(nodeId)
    val title: String get() = node?.label ?: "?"

    /**
     * What an output has made of being clicked on: which branch is open, which row is picked.
     *
     * It lives here rather than in the node because it is not part of the flow. Putting it there
     * made every click on a tree a document edit — marking the file changed, redrawing the canvas,
     * and re-serialising the whole flow to see whether it still matched what was saved — which is
     * a great deal of work for opening a row. It lasts as long as the tab is open, which is as long
     * as it means anything.
     */
    var outputState by mutableStateOf<Map<String, String>>(emptyMap())
}

// Workspace: global UI state + open documents (tabs) + project file list + component registry
class Workspace(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // global UI (shared across documents)
    var lang by mutableStateOf("en") // default language: English
    var theme by mutableStateOf(Theme.SYSTEM) // system | dark | light
    var registryUrl by mutableStateOf(DEFAULT_REGISTRY_URL)
    var showLeft by mutableStateOf(true)
    var leftTab by mutableStateOf("project") // project | modules
    // palette sections left open, by key
    var expandedSections by mutableStateOf(setOf<String>())

    fun isSectionOpen(key: String) = key in expandedSections
    fun toggleSection(key: String) {
        expandedSections = if (key in expandedSections) expandedSections - key else expandedSections + key
    }

    // VS Code-style activity bar click: open that panel, or collapse when the
    // same icon is clicked while its panel is already open.
    fun clickActivity(tab: String) {
        if (showLeft && leftTab == tab) {
            showLeft = false
        } else {
            leftTab = tab
            showLeft = true
        }
    }
    var showProps by mutableStateOf(true)
    var showMinimap by mutableStateOf(true)
    // resizable panel widths (dp), persisted in the session
    var leftWidth by mutableStateOf(240f)
    var propsWidth by mutableStateOf(268f)
    // run animation duration per step, in seconds (larger = slower), persisted in the config
    var animSeconds by mutableStateOf(1f)
    // main window bounds (dp), persisted in the session; null = no saved bounds yet (Main.kt falls
    // back to its own default centered/clamped-to-screen size)
    var windowX by mutableStateOf<Float?>(null)
    var windowY by mutableStateOf<Float?>(null)
    var windowWidth by mutableStateOf<Float?>(null)
    var windowHeight by mutableStateOf<Float?>(null)
    var windowMaximized by mutableStateOf(false)
    var menu by mutableStateOf<String?>(null)
    var dragModule by mutableStateOf<DragModule?>(null)
    var saveTime by mutableStateOf<String?>(null)

    // close-confirm target (tab index being closed). null = no dialog.
    var closeConfirm by mutableStateOf<Int?>(null)

    // whether the settings screen is shown (logo menu > Settings), and which category it opens on
    var showSettings by mutableStateOf(false)
    var settingsCategory by mutableStateOf("appearance")

    fun openSettings(category: String = "appearance") {
        settingsCategory = category
        showSettings = true
    }

    // internal clipboard for module copy/paste between documents
    var clipboard by mutableStateOf<FlowFile?>(null)

    // message dialog (null = none)
    var saveError by mutableStateOf<String?>(null)
    // true when the message is a non-blocking warning
    var saveWarn by mutableStateOf(false)
    var errorTitleKey by mutableStateOf("saveErrorTitle")

    fun showError(message: String, titleKey: String = "saveErrorTitle") {
        saveWarn = false
        errorTitleKey = titleKey
        saveError = message
    }

    // true while the project tree is the panel the user last clicked in — the scope its
    // shortcuts (rename/delete) act on
    var projectFocused by mutableStateOf(false)

    // action id -> shortcut, editable in Settings > Keymap
    var keymap by mutableStateOf(DEFAULT_KEYMAP)

    fun shortcut(action: String): Shortcut? = keymap[action]
    fun shortcutLabel(action: String): String = keymap[action]?.label(Platform.metaKeyLabel()) ?: ""
    fun setShortcut(action: String, shortcut: Shortcut) {
        // a shortcut belongs to one action: drop it from whichever action held it
        keymap = keymap.filterValues { it != shortcut } + (action to shortcut)
    }
    fun resetKeymap() { keymap = DEFAULT_KEYMAP }

    // a modal is up: shortcuts belong to it, not to the tool windows
    val dialogOpen: Boolean
        get() = renameTarget != null || newFolderParent != null || fileDeleteConfirm != null ||
            closeConfirm != null || installConfirm != null || saveError != null
    fun actionFor(ev: androidx.compose.ui.input.key.KeyEvent): String? {
        val pressed = Shortcut.of(ev) ?: return null
        return keymap.entries.find { it.value == pressed }?.key
    }

    // project tree multi-selection, by path
    var projectSelected by mutableStateOf(setOf<String>())
    // item whose context menu is open, and where it was opened (window px)
    var projectMenuFor by mutableStateOf<String?>(null)
    var projectMenuPos by mutableStateOf(Offset.Zero)

    fun openProjectMenu(path: String, pos: Offset) {
        projectMenuPos = pos
        projectMenuFor = path
    }

    fun closeProjectMenu() { projectMenuFor = null }
    // items pending delete-confirmation
    var fileDeleteConfirm by mutableStateOf<Set<String>?>(null)
    // item pending rename
    var renameTarget by mutableStateOf<String?>(null)
    // parent of a pending new folder
    var newFolderParent by mutableStateOf<String?>(null)
    // install-overwrite confirmation (null = none)
    var installConfirm by mutableStateOf<InstallPending?>(null)

    // open tabs
    val docs = mutableStateListOf<EditorState>()
    var activeIndex by mutableStateOf(0)
    val active: EditorState? get() = docs.getOrNull(activeIndex)

    // open data-editor tabs (in/out sample data). When activeData != null it is
    // shown in the content area instead of the active document's canvas.
    val dataTabs = mutableStateListOf<DataTab>()
    var activeData by mutableStateOf<DataTab?>(null)

    fun openDataEditor(doc: EditorState, nodeId: String) {
        val tab = dataTabs.find { it.doc === doc && it.nodeId == nodeId }
            ?: DataTab(doc, nodeId).also { dataTabs.add(it) }
        activeData = tab
    }

    fun selectDataTab(tab: DataTab) { activeData = tab }

    /**
     * Whether a canvas is on screen to receive a dropped module. A data-editor tab covers the
     * canvas, so a drop then would add a node to something the user cannot see.
     */
    val canvasOpen: Boolean get() = activeData == null && active != null

    fun closeDataTab(tab: DataTab) {
        val i = dataTabs.indexOf(tab)
        if (i < 0) return
        dataTabs.removeAt(i)
        if (activeData === tab) {
            // same index now holds the next tab; if this was the last one, fall back to the previous;
            // if there are no data tabs left, null reveals the active document's canvas
            activeData = dataTabs.getOrNull(i) ?: dataTabs.getOrNull(i - 1)
        }
    }

    // project tree; paths are relative to the flows root
    var files by mutableStateOf<List<String>>(emptyList())
    var folders by mutableStateOf<List<String>>(emptyList())
    // folders drawn open; "" is the root row
    var expandedDirs by mutableStateOf(setOf(""))
    var components by mutableStateOf<List<CompDef>>(emptyList())
    var installedModules by mutableStateOf<List<ModuleInfo>>(emptyList())
    var installedOutputs by mutableStateOf<List<OutputInfo>>(emptyList())
    var installedInputs by mutableStateOf<List<InputInfo>>(emptyList())
    // switched off in Settings; still installed, just not offered
    var disabledOutputs by mutableStateOf<Set<String>>(emptySet())
    var disabledInputs by mutableStateOf<Set<String>>(emptySet())
    // the open folder, or null. Kept as state so the tree and the menus follow it.
    var projectRoot by mutableStateOf<String?>(null)
    val rootLabel: String get() = Platform.projectName() ?: ""
    val dirLabel: String get() = projectRoot ?: ""
    val hasProject: Boolean get() = projectRoot != null

    /** Opens [path] as the project folder, or closes the current one when null. */
    fun openProject(path: String?) {
        Platform.openProject(path)
        projectRoot = Platform.projectRoot()
        projectSelected = emptySet()
        expandedDirs = setOf("")
        refreshFiles()
    }

    fun t(key: String) = flow.i18n.tr(lang, key)

    init {
        // no folder is open at startup, the way an editor starts with none; the session restores
        // preferences and window state but not a project
        refreshFiles()
        loadSession()
    }

    /* ───────── project files + installed ───────── */

    fun refreshFiles() {
        files = Platform.listProjectFiles()
        folders = Platform.listFlowDirs()
        // sorted here rather than in each place that lists them: the install store hands them back
        // in whatever order the filesystem walked, which shuffles as extensions come and go
        installedModules = Platform.installedModuleInfos().sortedBy { it.name.lowercase() }
        installedOutputs = Platform.installedOutputInfos().sortedBy { it.name.lowercase() }
        installedInputs = Platform.installedInputInfos().sortedBy { it.name.lowercase() }
        // project components (flows/) + installed components (components/, read-only)
        val local = files.filter { it.endsWith(".flow") }.mapNotNull { name ->
            val raw = Platform.readFlow(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)
        }
        val installed = Platform.listInstalledComponents().mapNotNull { name ->
            val raw = Platform.readInstalledComponent(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)?.copy(installed = true)
        }
        components = (local + installed).sortedBy { it.name.lowercase() }
    }

    fun moduleInfo(type: String): ModuleInfo? = installedModules.find { it.id == type }

    /** The views on offer: everything installed that has not been switched off. */
    val enabledOutputs: List<OutputInfo> get() = installedOutputs.filterNot { it.id in disabledOutputs }

    /** The inputs on offer, on the same terms. */
    val enabledInputs: List<InputInfo> get() = installedInputs.filterNot { it.id in disabledInputs }

    fun setOutputEnabled(id: String, enabled: Boolean) {
        disabledOutputs = if (enabled) disabledOutputs - id else disabledOutputs + id
    }

    fun setInputEnabled(id: String, enabled: Boolean) {
        disabledInputs = if (enabled) disabledInputs - id else disabledInputs + id
    }

    fun inputInfo(id: String): InputInfo? = installedInputs.find { it.id == id }

    /**
     * The input a node's value is typed with.
     *
     * Unlike an output, an unnamed one falls back to whichever is installed first: a value has to
     * be written as something, and leaving the user with no way to enter one at all would be worse
     * than picking for them. Null only when nothing is installed, and then the editor's own plain
     * hex stands in.
     */
    fun inputFor(node: Node): InputInfo? =
        node.params[INPUT_PARAM]?.takeIf { it.isNotBlank() }?.let { id -> enabledInputs.find { it.id == id } }
            ?: enabledInputs.firstOrNull()

    fun outputInfo(id: String): OutputInfo? = installedOutputs.find { it.id == id }

    /**
     * The view a node is shown with, or null for the raw bytes.
     *
     * Null is also the answer when the named view is not installed here or has been switched off:
     * a flow saved on a machine with some view still opens on one without it, showing the data
     * plainly rather than an error where the data should be.
     */
    fun outputFor(node: Node): OutputInfo? =
        node.params[OUTPUT_PARAM]?.takeIf { it.isNotBlank() }?.let { id -> enabledOutputs.find { it.id == id } }

    // options a module shows for the values a node currently holds
    fun moduleOptions(type: String, values: Map<String, String>): List<OptDef> =
        Platform.moduleOptionsFor(type, values) ?: moduleInfo(type)?.options ?: emptyList()

    /* ───────── install ───────── */

    fun installJarFlow(path: String) {
        val r = Platform.installJar(path, overwrite = false)
        if (r.conflicts.isNotEmpty()) {
            installConfirm = InstallPending(r.conflicts.joinToString(", ")) {
                Platform.installJar(path, overwrite = true); refreshFiles()
            }
        } else refreshFiles()
    }

    /* ───────── extension registry ───────── */

    var registry by mutableStateOf<List<RegistryEntry>>(emptyList())
    var registryLoading by mutableStateOf(false)
    var registryError by mutableStateOf<String?>(null)
    // ids currently downloading, so each row can show its own progress
    var registryBusy by mutableStateOf(setOf<String>())

    // A jar path in the manifest is relative to the manifest itself, so a registry can be moved or
    // mirrored without rewriting every entry.
    private fun entryUrl(file: String): String =
        if (file.startsWith("https://")) file else registryUrl.substringBeforeLast('/') + "/" + file.removePrefix("./")

    fun loadRegistry() {
        if (registryLoading) return
        registryLoading = true
        registryError = null
        scope.launch {
            val raw = withContext(Dispatchers.Default) { Platform.fetchText(registryUrl) }
            val index = raw?.let { runCatching { json.decodeFromString<RegistryIndex>(it) }.getOrNull() }
            registry = index?.extensions.orEmpty()
            registryError = when {
                raw == null -> t("registryUnreachable")
                index == null -> t("registryBroken")
                else -> null
            }
            registryLoading = false
        }
    }

    /**
     * Whether [entry] is installable, already installed, or has a newer version on offer.
     *
     * Views and modules share one install store and one registry, so both lists are searched: an
     * entry's own `kind` says where it is shown, not where it might be found.
     */
    fun registryState(entry: RegistryEntry): RegistryState {
        val version = installedModules.find { it.id == entry.id }?.version
            ?: installedOutputs.find { it.id == entry.id }?.version
            ?: installedInputs.find { it.id == entry.id }?.version
            ?: return RegistryState.AVAILABLE
        return if (compareVersions(entry.version, version) > 0) RegistryState.UPDATABLE
        else RegistryState.INSTALLED
    }

    /** Download and install (or update) one registry entry. Overwrites, since that is the point. */
    fun installFromRegistry(entry: RegistryEntry) {
        if (entry.id in registryBusy) return
        registryBusy = registryBusy + entry.id
        scope.launch {
            val r = withContext(Dispatchers.Default) { Platform.installFromUrl(entryUrl(entry.file), overwrite = true) }
            registryBusy = registryBusy - entry.id
            if (r.installed.isEmpty()) {
                showError(t("registryInstallFailed").replace("{name}", entry.name.ifBlank { entry.id }))
            }
            refreshFiles()
        }
    }

    /** Install a .flow chosen from disk: it goes where flows live, alongside the rest of them. */
    fun installLocalFlow(path: String) {
        copyFlowIntoFolder(path, "")
    }

    fun confirmInstall() { installConfirm?.commit?.invoke(); installConfirm = null }
    fun cancelInstall() { installConfirm = null }

    fun uninstallModule(id: String) {
        Platform.uninstallModule(id)
        refreshFiles()
    }

    // Open a component file: installed ones (components/) as a read-only copy, project files as-is
    fun openComponentFile(file: String) {
        if (components.find { it.file == file }?.installed == true) openInstalledComponent(file)
        else openFile(file)
        warnUnconnected()
    }

    // Show a warning dialog if the active doc still has unconnected modules
    // (used on user-initiated open/save; not during silent session restore).
    private fun warnUnconnected() {
        active?.connectionWarning()?.let { saveWarn = true; saveError = it }
    }

    // Open an installed component for editing: as a new read-only copy (saved into flows/)
    fun openInstalledComponent(file: String) {
        val raw = Platform.readInstalledComponent(file) ?: return
        val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return
        val name = nextName("", file.removeSuffix(".flow").substringAfterLast('.').ifBlank { "flow" })
        val doc = EditorState(scope, this, name).also { it.load(flow); it.persisted = false; it.showValidation = true }
        docs.add(doc)
        activeData = null
        activeIndex = docs.lastIndex
    }

    fun findComp(file: String): CompDef? = components.find { it.file == file }

    fun isComponentFile(file: String): Boolean = components.any { it.file == file }

    /* ───────── project tree ───────── */

    class Row(val path: String, val name: String, val isDir: Boolean, val depth: Int)

    fun isDir(path: String): Boolean = path in folders

    fun isExpanded(dir: String): Boolean = dir in expandedDirs

    fun toggleExpand(dir: String) {
        expandedDirs = if (dir in expandedDirs) expandedDirs - dir else expandedDirs + dir
    }

    // open every folder down to [dir]
    fun revealDir(dir: String) { expandedDirs = expandedDirs + pathAncestors(dir) }

    // Tree order: folders first, then files, alphabetical; expanded folders include their children.
    fun projectRows(): List<Row> {
        val dirsByParent = folders.groupBy { pathParent(it) }
        val filesByParent = files.groupBy { pathParent(it) }
        val out = mutableListOf<Row>()
        fun walk(dir: String, depth: Int) {
            dirsByParent[dir].orEmpty().sortedBy { pathName(it).lowercase() }.forEach { d ->
                out += Row(d, pathName(d), isDir = true, depth = depth)
                if (isExpanded(d)) walk(d, depth + 1)
            }
            filesByParent[dir].orEmpty().sortedBy { pathName(it).lowercase() }.forEach { f ->
                out += Row(f, pathName(f), isDir = false, depth = depth)
            }
        }
        walk("", 0)
        return out
    }

    // where a new file/folder lands
    fun targetDir(): String {
        val sel = projectSelected.firstOrNull() ?: return ""
        return if (isDir(sel)) sel else pathParent(sel)
    }

    /* ───────── project select / open / delete (multi) ───────── */

    fun selectFile(name: String) { projectSelected = setOf(name) }
    fun toggleFileSelect(name: String) {
        projectSelected = if (name in projectSelected) projectSelected - name else projectSelected + name
    }

    fun openFiles(names: Collection<String>) {
        names.filterNot { isDir(it) }.forEach { openFile(it) }
        warnUnconnected()
    }

    // request delete-confirmation -> show the dialog
    fun requestDeleteFiles(names: Set<String>) {
        if (names.isNotEmpty()) fileDeleteConfirm = names
    }

    fun confirmDeleteFiles() {
        fileDeleteConfirm?.let { deleteFiles(it) }
        fileDeleteConfirm = null
    }

    fun cancelDeleteFiles() { fileDeleteConfirm = null }

    private fun deleteFiles(names: Set<String>) {
        names.forEach { path ->
            // close its tab, or every tab under the folder
            val folder = isDir(path)
            while (true) {
                val i = docs.indexOfFirst { if (folder) pathUnder(it.fileName, path) else it.fileName == path }
                if (i < 0) break
                removeDoc(i)
            }
            Platform.deleteFlowPath(path)
        }
        projectSelected = projectSelected - names
        refreshFiles()
    }

    /* ───────── new folder ───────── */

    fun requestNewFolder(parent: String = targetDir()) { newFolderParent = parent }
    fun cancelNewFolder() { newFolderParent = null }

    fun createFolder(rawName: String) {
        val parent = newFolderParent ?: return
        newFolderParent = null
        val name = rawName.trim()
        if (!isValidSegment(name)) return
        val path = pathJoin(parent, name)
        if (Platform.createFlowDir(path)) {
            revealDir(path)
            refreshFiles()
            selectFile(path)
        }
    }

    /* ───────── rename (file or folder) ───────── */

    fun requestRename(name: String) { renameTarget = name }
    fun cancelRename() { renameTarget = null }

    // folders keep their name, files drop the .flow suffix
    fun renameInitial(): String = renameTarget?.let { if (isDir(it)) pathName(it) else flowLabel(it) } ?: ""

    fun doRename(newBase: String) {
        val old = renameTarget ?: return
        renameTarget = null
        val dir = isDir(old)
        val typed = newBase.trim()
        // a flow file keeps its suffix; folders and other files are renamed as typed
        val name = if (!dir && old.endsWith(".flow") && !typed.endsWith(".flow")) "$typed.flow" else typed
        if (!isValidSegment(name)) return
        val new = pathJoin(pathParent(old), name)
        if (new == old) return
        if (!Platform.renameFlowPath(old, new)) return
        // re-point open tabs, expansion and selection
        fun moved(path: String) = if (path == old) new else new + path.substring(old.length)
        if (dir) {
            docs.forEach { if (pathUnder(it.fileName, old)) it.fileName = moved(it.fileName) }
            expandedDirs = expandedDirs.map { if (it.isNotEmpty() && pathUnder(it, old)) moved(it) else it }.toSet()
        } else {
            docs.find { it.fileName == old }?.fileName = new
        }
        projectSelected = projectSelected.map { if (pathUnder(it, old)) moved(it) else it }.toSet()
        refreshFiles()
    }

    /* ───────── open/close tabs ───────── */

    // Only a readable .flow file opens; anything else reports an error instead.
    fun openFile(name: String, reportError: Boolean = true) {
        val i = docs.indexOfFirst { it.fileName == name }
        if (i >= 0) { activeData = null; activeIndex = i; return }
        val label = pathName(name)
        if (!name.endsWith(".flow")) {
            if (reportError) showError(t("openErrorNotFlow").replace("{name}", label), "openErrorTitle")
            return
        }
        val raw = Platform.readFlow(name)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() }
        if (flow == null) {
            if (reportError) showError(t("openErrorBroken").replace("{name}", label), "openErrorTitle")
            return
        }
        val doc = EditorState(scope, this, name).also { it.load(flow); it.showValidation = true }
        docs.add(doc)
        activeData = null
        activeIndex = docs.lastIndex
    }

    // Copy an external .flow file (from a File > Open dialog or an OS drag-and-drop) into the
    // project under [dir], keeping its original name when free, else suffixed like a new flow's
    // ("name-1.flow", "name-2.flow", ...). Returns the new project-relative path, or null if
    // [path] isn't a readable, valid flow file (an error dialog is shown in that case).
    private fun copyFlowIn(path: String, dir: String): String? {
        val label = Platform.fileName(path)
        if (!path.endsWith(".flow")) {
            showError(t("openErrorNotFlow").replace("{name}", label), "openErrorTitle")
            return null
        }
        val raw = Platform.readExternalFlow(path)
        if (raw == null || runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() == null) {
            showError(t("openErrorBroken").replace("{name}", label), "openErrorTitle")
            return null
        }
        val base = label.removeSuffix(".flow")
        val preferred = pathJoin(dir, "$base.flow")
        val existing = (files + docs.map { it.fileName }).toSet()
        val dest = if (preferred !in existing) preferred else nextName(dir, base)
        Platform.writeFlow(dest, raw)
        refreshFiles()
        revealDir(dir)
        return dest
    }

    /**
     * A .flow chosen from disk, or dropped on the window.
     *
     * With a folder open it is copied in and opened from there, so it becomes part of the project.
     * With none, it is opened where it lies and saves back to the same file — an editor lets you
     * work on a single file without adopting its folder.
     */
    fun importFlow(path: String, dir: String = targetDir()) {
        if (hasProject) copyFlowIn(path, dir)?.let { openFile(it) } else openStandaloneFile(path)
    }

    /** Opens a .flow by absolute path, with no project around it. */
    fun openStandaloneFile(path: String) {
        docs.indexOfFirst { it.standalonePath == path }.takeIf { it >= 0 }?.let {
            activeData = null; activeIndex = it; return
        }
        val label = Platform.fileName(path)
        val raw = Platform.readExternalFlow(path)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() }
        if (flow == null) {
            showError(
                t(if (raw == null) "openErrorNotFlow" else "openErrorBroken").replace("{name}", label),
                "openErrorTitle",
            )
            return
        }
        val doc = EditorState(scope, this, path).also {
            it.standalonePath = path
            it.load(flow)
            it.showValidation = true
        }
        docs.add(doc)
        activeData = null
        activeIndex = docs.lastIndex
    }

    // A .flow file dropped onto a project folder (or the root row): copy it in, but leave the
    // editor's open tabs alone.
    fun copyFlowIntoFolder(path: String, dir: String) {
        copyFlowIn(path, dir)
    }

    // New component in [dir] (default: the selected folder). Written to a file only on
    // save, which validates the in/out contract.
    fun newComponent(dir: String = targetDir()) {
        val name = nextName(dir, "flow")
        val doc = EditorState(scope, this, name).also { it.load(FlowFile()); it.persisted = false }
        docs.add(doc)
        revealDir(dir)
        activeData = null
        activeIndex = docs.lastIndex
    }

    fun select(i: Int) {
        if (docs.isEmpty()) return
        activeData = null // switch back to the canvas view
        activeIndex = i.coerceIn(0, docs.lastIndex)
    }

    fun save(i: Int) {
        docs.getOrNull(i)?.save()
    }

    fun saveActive() {
        active?.save()
    }

    fun saveAll() {
        docs.forEach { if (it.dirty) it.save() }
    }

    // Close-tab request: if there are unsaved changes, ask whether to save first.
    fun requestClose(i: Int) {
        if (i !in docs.indices) return
        if (docs[i].dirty) closeConfirm = i else removeDoc(i)
    }

    fun confirmSaveAndClose() {
        closeConfirm?.let { i ->
            // keep the tab open when validation fails (the error dialog explains why)
            if (docs.getOrNull(i)?.save() == false) {
                closeConfirm = null
                return
            }
            removeDoc(i)
        }
        closeConfirm = null
    }

    fun confirmDiscardAndClose() {
        closeConfirm?.let { removeDoc(it) }
        closeConfirm = null
    }

    fun cancelClose() {
        closeConfirm = null
    }

    private fun removeDoc(i: Int) {
        if (i !in docs.indices) return
        val doc = docs[i]
        // close any data-editor tabs that belonged to this document
        dataTabs.removeAll { it.doc === doc }
        if (activeData?.doc === doc) activeData = null
        docs.removeAt(i)
        // a tab before the active one shifts everything left by one, so follow it to stay on the same doc
        if (i < activeIndex) activeIndex--
        // closing the active tab itself lands on the next tab at the same index; clamp to the previous one if it was last
        if (activeIndex >= docs.size) activeIndex = (docs.size - 1).coerceAtLeast(0)
    }

    private fun nextName(dir: String, base: String): String {
        val existing = (files + docs.map { it.fileName }).toSet()
        var n = 1
        while (pathJoin(dir, "$base-$n.flow") in existing) n++
        return pathJoin(dir, "$base-$n.flow")
    }

    /* ───────── session ───────── */

    fun sessionJson(): String = json.encodeToString(
        Session(
            openFiles = docs.map { it.fileName },
            activeIndex = activeIndex,
            showLeft = showLeft,
            leftTab = leftTab,
            expandedDirs = expandedDirs.toList().sorted(),
            expandedSections = expandedSections.toList().sorted(),
            showProps = showProps,
            showMinimap = showMinimap,
            leftWidth = leftWidth,
            propsWidth = propsWidth,
            windowX = windowX,
            windowY = windowY,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            windowMaximized = windowMaximized,
        )
    )

    fun settingsJson(): String = json.encodeToString(
        Settings(
            lang, theme, keymap.mapValues { it.value.id() }, animSeconds, registryUrl,
            disabledOutputs.sorted(),
            disabledInputs.sorted(),
        )
    )

    private fun loadSession() {
        val s = Platform.loadSession()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        // settings.json is authoritative; a session written before the split still carries them, so
        // it stands in once and is then superseded the next time settings are saved
        val saved = Platform.loadSettings()?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() }
            ?: s?.let { Settings(it.lang ?: "ko", it.theme ?: Theme.SYSTEM, it.keymap, it.animSeconds ?: 1f) }
        if (saved != null) {
            lang = saved.lang
            theme = saved.theme.takeIf { it in Theme.ALL } ?: Theme.SYSTEM
            animSeconds = saved.animSeconds.coerceIn(0.05f, 10f)
            registryUrl = saved.registryUrl.ifBlank { DEFAULT_REGISTRY_URL }
            disabledOutputs = saved.disabledOutputs.toSet()
            disabledInputs = saved.disabledInputs.toSet()
            // unknown/unparseable bindings fall back to the default for that action
            keymap = DEFAULT_KEYMAP + saved.keymap.mapNotNull { (action, id) ->
                Shortcut.parse(id)?.let { action to it }
            }.toMap()
        }
        if (s != null) {
            showLeft = s.showLeft
            leftTab = s.leftTab
            // an older session file has no such field; keep the root open
            expandedDirs = s.expandedDirs.toSet() + ""
            expandedSections = s.expandedSections.toSet()
            showProps = s.showProps
            showMinimap = s.showMinimap
            leftWidth = s.leftWidth.coerceIn(160f, 500f)
            propsWidth = s.propsWidth.coerceIn(200f, 560f)
            windowX = s.windowX
            windowY = s.windowY
            windowWidth = s.windowWidth
            windowHeight = s.windowHeight
            windowMaximized = s.windowMaximized
            // openFiles named paths inside whichever folder was open, and none is at startup —
            // they are left for the user to reopen rather than guessed at against another folder
            activeIndex = 0
        }
        // and nothing is created either: an editor with no folder open shows an empty editor,
        // it does not invent a file
    }
}
