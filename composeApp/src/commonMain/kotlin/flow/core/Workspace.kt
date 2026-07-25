package flow.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import flow.model.CompDef
import flow.model.FlowFile
import flow.model.ModuleInfo
import flow.model.Node
import flow.model.Session
import flow.model.asComponent
import flow.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

// pending install-overwrite confirmation (label = conflicting id, commit = perform overwrite)
class InstallPending(val label: String, val commit: () -> Unit)

// An open data-editor tab: edits the sample data of a component boundary (cin/cout) node.
class DataTab(val doc: EditorState, val nodeId: String) {
    val node: Node? get() = doc.nodeById(nodeId)
    val title: String get() = node?.label ?: "?"
}

// Workspace: global UI state + open documents (tabs) + project file list + component registry
class Workspace(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // global UI (shared across documents)
    var lang by mutableStateOf("en") // default language: English
    var showLeft by mutableStateOf(true)
    var leftTab by mutableStateOf("project") // project | modules

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
    // run animation speed multiplier (higher = faster), persisted in the config
    var animSpeed by mutableStateOf(1f)
    var menu by mutableStateOf<String?>(null)
    var dragModule by mutableStateOf<DragModule?>(null)
    var saveTime by mutableStateOf<String?>(null)

    // close-confirm target (tab index being closed). null = no dialog.
    var closeConfirm by mutableStateOf<Int?>(null)

    // whether the settings screen is shown (logo menu > Settings)
    var showSettings by mutableStateOf(false)

    // whether the extensions (module/component manage) window is shown
    var showManage by mutableStateOf(false)

    // internal clipboard for module copy/paste between documents
    var clipboard by mutableStateOf<FlowFile?>(null)

    // component validation message to display (null = none)
    var saveError by mutableStateOf<String?>(null)
    // true when saveError is a non-blocking warning (the save still succeeded)
    var saveWarn by mutableStateOf(false)

    // project panel multi-selection (highlight). Open via double-click / right-click menu.
    var projectSelected by mutableStateOf(setOf<String>())
    // file whose right-click context menu is open (null = none)
    var projectMenuFor by mutableStateOf<String?>(null)
    // files pending delete-confirmation (null = no dialog)
    var fileDeleteConfirm by mutableStateOf<Set<String>?>(null)
    // file pending rename (null = no dialog)
    var renameTarget by mutableStateOf<String?>(null)
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

    fun closeDataTab(tab: DataTab) {
        dataTabs.remove(tab)
        if (activeData === tab) activeData = null
    }

    // project files + components + installed modules
    var files by mutableStateOf<List<String>>(emptyList())
    var components by mutableStateOf<List<CompDef>>(emptyList())
    var installedModules by mutableStateOf<List<ModuleInfo>>(emptyList())
    val dirLabel: String = Platform.flowsDirLabel()

    fun t(key: String) = flow.i18n.tr(lang, key)

    init {
        refreshFiles()
        loadSession()
    }

    /* ───────── project files + installed ───────── */

    fun refreshFiles() {
        files = Platform.listFlows()
        installedModules = Platform.installedModuleInfos()
        // project components (flows/) + installed components (components/, read-only)
        val local = files.mapNotNull { name ->
            val raw = Platform.readFlow(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)
        }
        val installed = Platform.listInstalledComponents().mapNotNull { name ->
            val raw = Platform.readInstalledComponent(name) ?: return@mapNotNull null
            val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return@mapNotNull null
            flow.asComponent(name)?.copy(installed = true)
        }
        components = local + installed
    }

    fun moduleInfo(type: String): ModuleInfo? = installedModules.find { it.id == type }

    /* ───────── install ───────── */

    fun installJarFlow(path: String) {
        val r = Platform.installJar(path, overwrite = false)
        if (r.conflicts.isNotEmpty()) {
            installConfirm = InstallPending(r.conflicts.joinToString(", ")) {
                Platform.installJar(path, overwrite = true); refreshFiles()
            }
        } else refreshFiles()
    }

    // Install the currently-edited component (with cin/cout). The id is generated in package-name format.
    fun installActiveComponent() {
        val doc = active ?: return
        if (doc.nodes.none { it.type == "cin" || it.type == "cout" }) return // components only
        val id = "local." + doc.fileName.removeSuffix(".json")
        val payload = doc.flowJson()
        val r = Platform.installComponent(id, payload, overwrite = false)
        if (r.conflicts.isNotEmpty()) {
            installConfirm = InstallPending(id) {
                Platform.installComponent(id, payload, overwrite = true); refreshFiles()
            }
        } else refreshFiles()
    }

    fun confirmInstall() { installConfirm?.commit?.invoke(); installConfirm = null }
    fun cancelInstall() { installConfirm = null }

    fun uninstallModule(id: String) {
        Platform.uninstallModule(id)
        refreshFiles()
    }

    fun uninstallComponent(id: String) {
        Platform.uninstallComponent(id)
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
        val name = nextName(file.removeSuffix(".json").substringAfterLast('.').ifBlank { "component" })
        val doc = EditorState(scope, this, name).also { it.load(flow); it.persisted = false; it.showValidation = true }
        docs.add(doc)
        activeData = null
        activeIndex = docs.lastIndex
    }

    fun findComp(file: String): CompDef? = components.find { it.file == file }

    fun isComponentFile(file: String): Boolean = components.any { it.file == file }

    /* ───────── project file select/open/delete (multi) ───────── */

    fun selectFile(name: String) { projectSelected = setOf(name) }
    fun toggleFileSelect(name: String) {
        projectSelected = if (name in projectSelected) projectSelected - name else projectSelected + name
    }

    fun openFiles(names: Collection<String>) {
        names.forEach { openFile(it) }
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
        names.forEach { name ->
            val i = docs.indexOfFirst { it.fileName == name }
            if (i >= 0) removeDoc(i) // also close the tab if open
            Platform.deleteFlow(name)
        }
        projectSelected = projectSelected - names
        refreshFiles()
    }

    /* ───────── rename ───────── */

    fun requestRename(name: String) { renameTarget = name }
    fun cancelRename() { renameTarget = null }

    fun doRename(newBase: String) {
        val old = renameTarget ?: return
        renameTarget = null
        val trimmed = newBase.trim()
        if (trimmed.isEmpty()) return
        val new = if (trimmed.endsWith(".json")) trimmed else "$trimmed.json"
        if (new == old) return
        if (Platform.renameFlow(old, new)) {
            docs.find { it.fileName == old }?.fileName = new // sync the open tab's file name
            if (old in projectSelected) projectSelected = projectSelected - old + new
            refreshFiles()
        }
    }

    /* ───────── open/close tabs ───────── */

    fun openFile(name: String) {
        val i = docs.indexOfFirst { it.fileName == name }
        if (i >= 0) { activeData = null; activeIndex = i; return }
        val raw = Platform.readFlow(name)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } ?: FlowFile()
        val doc = EditorState(scope, this, name).also { it.load(flow); it.showValidation = true }
        docs.add(doc)
        activeData = null
        activeIndex = docs.lastIndex
    }

    // New component: starts empty (drag in/out boundaries from the palette).
    // Not written to a file until saved; saving validates the in/out contract.
    fun newComponent() {
        val name = nextName("comp")
        val doc = EditorState(scope, this, name).also { it.load(FlowFile()); it.persisted = false }
        docs.add(doc)
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
        if (activeIndex >= docs.size) activeIndex = (docs.size - 1).coerceAtLeast(0)
    }

    private fun nextName(base: String): String {
        val existing = (files + docs.map { it.fileName }).toSet()
        var n = 1
        while ("$base-$n.json" in existing) n++
        return "$base-$n.json"
    }

    /* ───────── session ───────── */

    fun sessionJson(): String = json.encodeToString(
        Session(docs.map { it.fileName }, activeIndex, lang, showLeft, leftTab, showProps, showMinimap, leftWidth, propsWidth, animSpeed)
    )

    private fun loadSession() {
        val s = Platform.loadSession()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        if (s != null) {
            lang = s.lang
            showLeft = s.showLeft
            leftTab = s.leftTab
            showProps = s.showProps
            showMinimap = s.showMinimap
            leftWidth = s.leftWidth.coerceIn(160f, 500f)
            propsWidth = s.propsWidth.coerceIn(200f, 560f)
            animSpeed = s.animSpeed.coerceIn(0.25f, 8f)
            s.openFiles.filter { Platform.readFlow(it) != null }.forEach { openFile(it) }
            activeIndex = s.activeIndex.coerceIn(0, (docs.size - 1).coerceAtLeast(0))
        }
        if (docs.isEmpty()) {
            if (files.isNotEmpty()) openFile(files.first()) else newComponent()
        }
    }
}
