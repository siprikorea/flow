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

// Workspace: global UI state + open documents (tabs) + project file list + component registry
class Workspace(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // global UI (shared across documents)
    var lang by mutableStateOf("en") // default language: English
    var showLeft by mutableStateOf(true)
    var leftTab by mutableStateOf("project") // project | modules
    var showProps by mutableStateOf(true)
    var showMinimap by mutableStateOf(true)
    var menu by mutableStateOf<String?>(null)
    var dragModule by mutableStateOf<DragModule?>(null)
    var saveTime by mutableStateOf<String?>(null)

    // close-confirm target (tab index being closed). null = no dialog.
    var closeConfirm by mutableStateOf<Int?>(null)

    // whether the settings screen is shown (logo menu > Settings)
    var showSettings by mutableStateOf(false)

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

    // Open a component file: installed ones (components/) as a read-only copy, project files as-is
    fun openComponentFile(file: String) {
        if (components.find { it.file == file }?.installed == true) openInstalledComponent(file)
        else openFile(file)
    }

    // Open an installed component for editing: as a new read-only copy (saved into flows/)
    fun openInstalledComponent(file: String) {
        val raw = Platform.readInstalledComponent(file) ?: return
        val flow = runCatching { json.decodeFromString<FlowFile>(raw) }.getOrNull() ?: return
        val name = nextName(file.removeSuffix(".json").substringAfterLast('.').ifBlank { "component" })
        val doc = EditorState(scope, this, name).also { it.load(flow); it.persisted = false }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    fun findComp(file: String): CompDef? = components.find { it.file == file }

    fun isComponentFile(file: String): Boolean = components.any { it.file == file }

    /* ───────── project file select/open/delete (multi) ───────── */

    fun selectFile(name: String) { projectSelected = setOf(name) }
    fun toggleFileSelect(name: String) {
        projectSelected = if (name in projectSelected) projectSelected - name else projectSelected + name
    }

    fun openFiles(names: Collection<String>) = names.forEach { openFile(it) }

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
        if (i >= 0) { activeIndex = i; return }
        val raw = Platform.readFlow(name)
        val flow = raw?.let { runCatching { json.decodeFromString<FlowFile>(it) }.getOrNull() } ?: FlowFile()
        val doc = EditorState(scope, this, name).also { it.load(flow) }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    // New flow: not written to a file (project list) until saved.
    fun newDoc() {
        val name = nextName("flow")
        val doc = EditorState(scope, this, name).also { it.load(FlowFile()); it.persisted = false }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    // New component: pre-places input/output boundary nodes. Not written to a file until saved.
    fun newComponent() {
        val name = nextName("comp")
        val cin = Node(
            id = "cin_1", type = "cin", label = "in",
            x = 60f, y = 120f, w = 160f, h = 80f, inputs = emptyList(), outputs = listOf("out"),
        )
        val cout = Node(
            id = "cout_2", type = "cout", label = "out",
            x = 420f, y = 120f, w = 160f, h = 80f, inputs = listOf("in"), outputs = emptyList(),
        )
        val doc = EditorState(scope, this, name).also { it.load(FlowFile(1, listOf(cin, cout), emptyList(), 3)); it.persisted = false }
        docs.add(doc)
        activeIndex = docs.lastIndex
    }

    fun select(i: Int) {
        if (docs.isEmpty()) return
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
            docs.getOrNull(i)?.save()
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
        Session(docs.map { it.fileName }, activeIndex, lang, showLeft, leftTab, showProps, showMinimap)
    )

    private fun loadSession() {
        val s = Platform.loadSession()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        if (s != null) {
            lang = s.lang
            showLeft = s.showLeft
            leftTab = s.leftTab
            showProps = s.showProps
            showMinimap = s.showMinimap
            s.openFiles.filter { Platform.readFlow(it) != null }.forEach { openFile(it) }
            activeIndex = s.activeIndex.coerceIn(0, (docs.size - 1).coerceAtLeast(0))
        }
        if (docs.isEmpty()) {
            if (files.isNotEmpty()) openFile(files.first()) else newDoc()
        }
    }
}
