package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Action
import flow.core.DEFAULT_KEYMAP
import flow.core.DragModule
import flow.core.Shortcut
import flow.core.Workspace
import flow.model.KIND_VIEW
import flow.model.KIND_PROCESSOR
import flow.platform.Platform
import flow.platform.droppedFilePath
import flow.util.flowLabel
import flow.ui.canvas.CanvasView
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import flow.ui.common.DtxField
import flow.ui.common.LucideIcon
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Size
import flow.ui.theme.Theme
import flow.ui.tools.LeftToolWindow
import flow.ui.tools.ProjectContextMenu
import flow.ui.tools.RightToolWindow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun App(
    ws: Workspace,
    leadingInset: Dp = 0.dp,
    onTitleDoubleClick: (() -> Unit)? = null,
    onTitleBarPress: (() -> Unit)? = null,
) {
    ApplyTheme(ws.theme)
    // external .flow file dropped onto the editor window: copy it into the project and open it
    val editorDropTarget = remember(ws) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val path = droppedFilePath(event) ?: return false
                ws.importFlow(path)
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(ws, leadingInset, onTitleDoubleClick, onTitleBarPress)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LeftToolWindow(ws) // activity bar always visible; panel folds via ws.showLeft
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorTabs(ws)
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = editorDropTarget),
                    ) {
                        val active = ws.active
                        if (active != null) CanvasView(active, Modifier.fillMaxSize()) else EmptyEditor(ws)
                    }
                }
                RightToolWindow(ws) // props panel + the settings/properties rail
            }
            StatusBar(ws)
        }
        ws.dragModule?.let { DragGhost(it) }
        if (ws.projectMenuFor != null) ProjectContextMenu(ws)
        ws.closeConfirm?.let { i ->
            val name = ws.docs.getOrNull(i)?.let { flowLabel(it.fileName) } ?: ""
            SaveCloseDialog(ws, name)
        }
        ws.fileDeleteConfirm?.let { FileDeleteDialog(ws, it.size) }
        ws.installConfirm?.let { OverwriteDialog(ws, it.label) }
        ws.renameTarget?.let { RenameDialog(ws, it) }
        ws.newFolderParent?.let { NewFolderDialog(ws) }
        ws.saveError?.let { SaveErrorDialog(ws, it) }
    }

    // Save the session (open tabs + UI); file contents are saved explicitly (Ctrl+S / close-confirm)
    LaunchedEffect(Unit) {
        snapshotFlow { ws.sessionJson() }
            .debounce(350)
            .collect { Platform.saveSession(it) }
    }

    // Preferences live in their own file, so they are watched separately — a settings change is
    // not a reason to rewrite the workspace, or the other way round.
    LaunchedEffect(Unit) {
        snapshotFlow { ws.settingsJson() }
            .debounce(350)
            .collect { Platform.saveSettings(it) }
    }
}

@Composable
private fun SaveCloseDialog(ws: Workspace, name: String) {
    // scrim: block clicks behind
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { } // keep card clicks from propagating to the scrim
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("unsavedTitle"), 14.sp, Palette.text)
            Txt(ws.t("unsavedBody").replace("{name}", name), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelClose() }
                DialogButton(ws.t("dontSave"), Palette.dangerBorder, Palette.errorSoft) { ws.confirmDiscardAndClose() }
                DialogButton(ws.t("save"), Palette.accent, Palette.holeBg, filled = true) { ws.confirmSaveAndClose() }
            }
        }
    }
}

@Composable
private fun FileDeleteDialog(ws: Workspace, count: Int) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelDeleteFiles() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("deleteFilesTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            Txt(ws.t("deleteFilesBody").replace("{n}", count.toString()), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelDeleteFiles() }
                DialogButton(ws.t("delete"), Palette.error, Palette.holeBg, filled = true) { ws.confirmDeleteFiles() }
            }
        }
    }
}

@Composable
private fun RenameDialog(ws: Workspace, current: String) {
    var text by remember(current) { mutableStateOf(ws.renameInitial()) }
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelRename() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("renameTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            DtxField(text, { text = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelRename() }
                DialogButton(ws.t("rename"), Palette.accent, Palette.holeBg, filled = true) { ws.doRename(text) }
            }
        }
    }
}

@Composable
private fun NewFolderDialog(ws: Workspace) {
    var text by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelNewFolder() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("newFolderTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            // where it lands: the project root shows as its folder name
            Txt(listOf(ws.rootLabel, ws.newFolderParent ?: "").filter { it.isNotEmpty() }.joinToString("/"),
                11.sp, Palette.faintText, mono = true, maxLines = 1)
            DtxField(text, { text = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelNewFolder() }
                DialogButton(ws.t("create"), Palette.accent, Palette.holeBg, filled = true) { ws.createFolder(text) }
            }
        }
    }
}

@Composable
private fun OverwriteDialog(ws: Workspace, id: String) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelInstall() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("overwriteTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            Txt(ws.t("overwriteBody").replace("{id}", id), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelInstall() }
                DialogButton(ws.t("overwrite"), Palette.accent, Palette.holeBg, filled = true) { ws.confirmInstall() }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    var m = Modifier.then(
        if (filled) Modifier.background(color, RoundedCornerShape(6.dp))
        else Modifier.border(1.dp, color, RoundedCornerShape(6.dp))
    )
    Box(m.plainClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Txt(label, 12.sp, textColor)
    }
}

@Composable
private fun EmptyEditor(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.canvasBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(ws.t("noOpenFile"), 13.sp, Palette.faintText)
            Box(
                Modifier
                    .border(1.dp, Palette.runFromBorder, RoundedCornerShape(6.dp))
                    .plainClick { ws.newComponent() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Txt(ws.t("newFlow"), 12.sp, Palette.accentHover) }
        }
    }
}

@Composable
private fun DragGhost(d: DragModule) {
    val icon = when (d.type) {
        "cin" -> Lucide.LogIn
        "cout" -> Lucide.LogOut
        else -> Lucide.Cpu
    }
    val color = when (d.type) {
        "cin" -> Palette.catInput
        "cout" -> Palette.catOutput
        else -> Palette.catProcessor
    }
    Box(
        Modifier
            .offset { IntOffset(d.pos.x.roundToInt() + 8, d.pos.y.roundToInt() + 8) }
            .background(Palette.overlay.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.accent, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIcon(icon, color, Size.icon)
            Txt(d.label, 12.sp, Palette.textPrimary)
        }
    }
}

// component validation error (e.g. missing/unconnected in/out)
@Composable
private fun SaveErrorDialog(ws: Workspace, message: String) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.saveError = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = if (ws.saveWarn) ws.t("saveWarnTitle") else ws.t(ws.errorTitleKey)
            val titleColor = if (ws.saveWarn) Palette.warnSoft else Palette.errorSoft
            Txt(title, 14.sp, titleColor, weight = FontWeight.SemiBold)
            Txt(message, 12.5.sp, Palette.subText)
            Row(modifier = Modifier.align(Alignment.End)) {
                DialogButton(ws.t("ok"), Palette.accent, Palette.holeBg, filled = true) { ws.saveError = null }
            }
        }
    }
}

// Processors: the extensions that do the work in the middle of a flow. Inputs and outputs have
// their own pages, since what you can do with one of those is different.
@Composable
private fun ExtensionsSettings(ws: Workspace) {
    LaunchedEffect(Unit) { if (ws.registry.isEmpty()) ws.loadRegistry() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DialogButton(ws.t("installLocalJar"), Palette.accent, Palette.holeBg, filled = true) {
            Platform.pickJar()?.let { ws.installJarFlow(it) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(ws.t("registrySection").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
            Txt(ws.t("registryRefresh"), 11.sp, Palette.accentHover, modifier = Modifier.plainClick { ws.loadRegistry() })
        }

        val offered = ws.registry.filter { it.kind == KIND_PROCESSOR }
        val offeredIds = offered.map { it.id }.toSet()
        // installed from a file rather than the registry: it still has to be removable
        val strays = ws.installedModules.filterNot { it.id in offeredIds }

        when {
            ws.registryLoading && offered.isEmpty() -> Txt(ws.t("registryLoading"), 12.sp, Palette.faintText)
            ws.registryError != null && offered.isEmpty() -> Txt(ws.registryError!!, 12.sp, Palette.errorSoft)
            offered.isEmpty() && strays.isEmpty() -> Txt(ws.t("registryEmpty"), 12.sp, Palette.faintText)
        }
        ws.registryError?.takeIf { offered.isNotEmpty() }?.let { Txt(it, 11.sp, Palette.errorSoft) }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            offered.forEach { entry -> ExtensionRow(ws, entry) }
            strays.forEach { m ->
                ExtensionRow(
                    ws, title = m.name, version = m.version, note = ws.t("fromFile"),
                    onUninstall = { ws.uninstallModule(m.id) },
                )
            }
        }
    }
}

// Views, as a Settings page: the viewers that open a window of their own. Text and hex are the
// app's own and are not listed here — there is nothing to install or remove about them.
@Composable
private fun ViewsSettings(ws: Workspace) {
    LaunchedEffect(Unit) { if (ws.registry.isEmpty()) ws.loadRegistry() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DialogButton(ws.t("installLocalJar"), Palette.accent, Palette.holeBg, filled = true) {
            Platform.pickJar()?.let { ws.installJarFlow(it) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(ws.t("viewsSection").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
            Txt(ws.t("registryRefresh"), 11.sp, Palette.accentHover, modifier = Modifier.plainClick { ws.loadRegistry() })
        }

        val offered = ws.registry.filter { it.kind == KIND_VIEW }
        val offeredIds = offered.map { it.id }.toSet()
        val strays = ws.installedViews.filterNot { it.id in offeredIds }

        when {
            ws.registryLoading && offered.isEmpty() -> Txt(ws.t("registryLoading"), 12.sp, Palette.faintText)
            ws.registryError != null && offered.isEmpty() -> Txt(ws.registryError!!, 12.sp, Palette.errorSoft)
            offered.isEmpty() && strays.isEmpty() -> Txt(ws.t("noViews"), 12.sp, Palette.faintText)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            offered.forEach { entry -> ExtensionRow(ws, entry) }
            strays.forEach { v ->
                ExtensionRow(
                    ws, title = v.name, version = v.version, note = ws.t("fromFile"),
                    onUninstall = { ws.uninstallModule(v.id) },
                )
            }
        }
    }
}

// The project's flows. This is where a flow lives — the project panel and the module palette read
// the same folder — so the page lists what is actually there rather than a separate install store.
@Composable
private fun FlowsSettings(ws: Workspace) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ws.hasProject) {
            DialogButton(ws.t("installLocalFlow"), Palette.accent, Palette.holeBg, filled = true) {
                Platform.pickFlowFile()?.let { ws.installLocalFlow(it) }
            }
        }
        Txt(ws.t("installedComponents").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
        val flows = if (ws.hasProject) ws.files.filter { it.endsWith(".flow") } else emptyList()
        if (!ws.hasProject) Txt(ws.t("noProject"), 12.sp, Palette.faintText)
        else if (flows.isEmpty()) Txt(ws.t("noneInstalled"), 12.sp, Palette.faintText)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            flows.forEach { path ->
                // a flow only reads as a component once it has boundary nodes; say so either way
                val comp = ws.findComp(path)
                ExtensionRow(
                    ws,
                    title = flowLabel(path),
                    version = null,
                    note = comp?.let { "${it.ins.joinToString(",")} → ${it.outs.joinToString(",")}" }
                        ?: ws.t("flowNoPorts"),
                    onUninstall = { ws.requestDeleteFiles(setOf(path)) },
                    uninstallLabel = ws.t("delete"),
                )
            }
        }
    }
}

// A registry entry: install it, or — once installed — uninstall it, with update offered first when
// the registry has moved ahead of the jar on disk.
@Composable
private fun ExtensionRow(ws: Workspace, entry: flow.model.RegistryEntry) {
    val state = ws.registryState(entry)
    val installed = state != flow.model.RegistryState.AVAILABLE
    ExtensionRow(
        ws,
        title = entry.name.ifBlank { entry.id },
        // what is on disk, not what is on offer — the two differ exactly when there is an update,
        // and showing the new number beside a row that has not taken it yet reads as if it had
        version = ws.installedVersion(entry.id) ?: entry.version,
        updateTo = entry.version.takeIf { state == flow.model.RegistryState.UPDATABLE },
        note = entry.description,
        busy = entry.id in ws.registryBusy,
        onUpdate = if (state == flow.model.RegistryState.UPDATABLE) ({ ws.installFromRegistry(entry) }) else null,
        onInstall = if (!installed) ({ ws.installFromRegistry(entry) }) else null,
        onUninstall = if (installed) ({ ws.uninstallModule(entry.id) }) else null,
    )
}

@Composable
private fun ExtensionRow(
    ws: Workspace,
    title: String,
    version: String?,
    // the version an update would move to, when one is on offer
    updateTo: String? = null,
    note: String,
    busy: Boolean = false,
    onUpdate: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
    // switches something on or off without removing it — what a view has and a module does not
    onToggle: (() -> Unit)? = null,
    toggleLabel: String = "",
    onUninstall: (() -> Unit)? = null,
    // what the destructive action is called. On the Extensions page it removes an installed copy;
    // on Flows it deletes the user's own file, and a button reading "Uninstall" there invites
    // someone to unregister something and lose the file instead.
    uninstallLabel: String = ws.t("uninstall"),
) {
    Row(
        Modifier
            .width(560.dp)
            .background(Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(title, 12.5.sp, Palette.text, weight = FontWeight.Medium)
                if (version != null) Txt("v$version", 10.5.sp, Palette.dimText, mono = true)
                if (updateTo != null) {
                    Txt("→ $updateTo", 10.5.sp, Palette.warn, mono = true, weight = FontWeight.Medium)
                }
            }
            if (note.isNotBlank()) Txt(note, 11.sp, Palette.subText, maxLines = 2)
        }
        if (busy) {
            Txt(ws.t("installing"), 11.sp, Palette.faintText)
        } else {
            // update sits ahead of uninstall, so the useful action is the one nearer the text
            onUpdate?.let { RowButton(ws.t("update"), Palette.warn, Palette.holeBg, it) }
            onInstall?.let { RowButton(ws.t("install"), Palette.accent, Palette.holeBg, it) }
            onToggle?.let { RowButton(toggleLabel, null, Palette.menuText, it) }
            onUninstall?.let { RowButton(uninstallLabel, null, Palette.errorSoft, it) }
        }
    }
}

// filled when it is the row's main action, outlined when it is the destructive one
@Composable
private fun RowButton(
    label: String,
    fill: androidx.compose.ui.graphics.Color?,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val base = if (fill != null) Modifier.background(fill, RoundedCornerShape(5.dp))
    else Modifier.border(1.dp, Palette.dangerBorder, RoundedCornerShape(5.dp))
    Box(base.plainClick(onClick).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(label, 11.sp, textColor, weight = FontWeight.Medium)
    }
}

// Settings, laid out like IntelliJ's: searchable category list on the left, the selected
// category's options on the right, Cancel / Apply / OK at the bottom. Values are edited as a
// draft and only written to the workspace on Apply/OK.
@Composable
fun SettingsScreen(ws: Workspace) {
    ApplyTheme(ws.theme)
    var lang by remember { mutableStateOf(ws.lang) }
    var theme by remember { mutableStateOf(ws.theme) }
    var anim by remember { mutableStateOf(ws.animSeconds) }
    var keymap by remember { mutableStateOf(ws.keymap) }
    var recording by remember { mutableStateOf<String?>(null) } // action waiting for a key press
    var category by remember { mutableStateOf(ws.settingsCategory) }
    var query by remember { mutableStateOf("") }
    val recorder = remember { FocusRequester() }

    // Extensions is a heading with the four kinds under it: an extension is any of them, and which
    // one you are looking for is the first thing you know.
    val categories = listOf(
        Category("appearance", ws.t("setAppearance")),
        Category("keymap", ws.t("setKeymap")),
        Category("extensions", ws.t("manageTitle"), heading = true),
        Category("views", ws.t("setViews"), nested = true),
        Category("processors", ws.t("setProcessors"), nested = true),
        Category("flows", ws.t("setFlows"), nested = true),
    )
    val shown = categories.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    // the heading is a place in the list, not a page; landing on it means landing on its first kind
    val selectable = shown.filterNot { it.heading }
    // "extensions" is where the rest of the app asks to be taken; it is a heading now, so it means
    // the first kind under it rather than nothing
    val requested = if (category == "extensions") "processors" else category
    val current = selectable.find { it.key == requested } ?: selectable.firstOrNull()

    fun apply() {
        ws.lang = lang
        ws.theme = theme
        ws.animSeconds = anim
        ws.keymap = keymap
    }

    // while recording, the next key press becomes the binding (Esc cancels)
    LaunchedEffect(recording) { if (recording != null) recorder.requestFocus() }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Palette.appBg)
            .focusRequester(recorder)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val action = recording ?: return@onPreviewKeyEvent false
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val pressed = Shortcut.of(ev) ?: return@onPreviewKeyEvent true // bare modifier: keep waiting
                if (pressed.key == "escape") { recording = null; return@onPreviewKeyEvent true }
                keymap = keymap.filterValues { it != pressed } + (action to pressed)
                recording = null
                true
            },
    ) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(210.dp).fillMaxHeight().background(Palette.panelBg).padding(8.dp)) {
                DtxField(query, { query = it })
                Spacer(Modifier.height(8.dp))
                shown.forEach { item ->
                    CategoryRow(
                        item.label,
                        selected = current?.key == item.key,
                        heading = item.heading,
                        nested = item.nested,
                    ) { if (!item.heading) category = item.key }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                Txt(current?.label ?: "", 14.sp, Palette.text, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
                Spacer(Modifier.height(16.dp))
                when (current?.key) {
                    "appearance" -> Column {
                        SettingRow(ws.t("language")) {
                            Segmented(listOf("en" to "English", "ko" to "한국어"), lang) { lang = it }
                        }
                        SettingRow(ws.t("theme")) {
                            Segmented(
                                listOf(
                                    Theme.SYSTEM to ws.t("themeSystem"),
                                    Theme.LIGHT to ws.t("themeLight"),
                                    Theme.DARK to ws.t("themeDark"),
                                ),
                                theme,
                            ) { theme = it }
                        }
                        SettingRow(ws.t("animTime")) {
                            val unit = ws.t("secUnit")
                            Segmented(
                                listOf(0.25f, 0.5f, 1f).map { v ->
                                    val num = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
                                    v.toString() to "$num$unit"
                                },
                                anim.toString(),
                            ) { anim = it.toFloat() }
                        }
                    }
                    "keymap" -> Column {
                        Action.ALL.forEach { action ->
                            SettingRow(ws.t(action)) {
                                ShortcutField(
                                    label = keymap[action]?.label(Platform.metaKeyLabel()) ?: "",
                                    recording = recording == action,
                                    hint = ws.t("pressKey"),
                                ) { recording = action }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        DialogButton(ws.t("restoreDefaults"), Palette.buttonBorder, Palette.menuText) {
                            keymap = DEFAULT_KEYMAP
                            recording = null
                        }
                    }
                    "views" -> ViewsSettings(ws)
                    "processors" -> ExtensionsSettings(ws)
                    "flows" -> FlowsSettings(ws)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
        Row(
            Modifier.fillMaxWidth().background(Palette.panelBg).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.showSettings = false }
            DialogButton(ws.t("apply"), Palette.buttonBorder, Palette.menuText) { apply() }
            DialogButton(ws.t("ok"), Palette.accent, Palette.holeBg, filled = true) { apply(); ws.showSettings = false }
        }
    }
    // deleting a flow is asked about in the window that asked for it, not behind the main one
    ws.fileDeleteConfirm?.let { FileDeleteDialog(ws, it.size) }
    }
}

// Click to record: shows the current binding, then waits for the next key press.
@Composable
private fun ShortcutField(label: String, recording: Boolean, hint: String, onClick: () -> Unit) {
    val (src, hovered) = rememberHover()
    Box(
        Modifier
            .width(180.dp)
            .hoverable(src)
            .background(if (recording) Palette.langActiveBg else Palette.holeBg, RoundedCornerShape(6.dp))
            .border(1.dp, if (recording || hovered) Palette.accent else Palette.border, RoundedCornerShape(6.dp))
            .plainClick(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Txt(
            if (recording) hint else label.ifEmpty { "—" }, 12.sp,
            if (recording) Palette.accentHover else Palette.text,
        )
    }
}

@Composable
private fun CategoryRow(
    label: String,
    selected: Boolean,
    heading: Boolean = false,
    nested: Boolean = false,
    onSelect: () -> Unit,
) {
    val (src, hovered) = rememberHover()
    val bg = when {
        selected -> Palette.langActiveBg
        hovered && !heading -> Palette.hoverBg
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().hoverable(src).background(bg, RoundedCornerShape(5.dp))
            .then(if (heading) Modifier else Modifier.plainClick(onSelect))
            .padding(start = if (nested) 20.dp else 9.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Txt(
            label, 12.5.sp,
            when {
                heading -> Palette.subText
                selected -> Palette.text
                else -> Palette.menuText
            },
            weight = if (heading) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A row in the settings category list: a page, or the heading a group of them sits under. */
private data class Category(
    val key: String,
    val label: String,
    val heading: Boolean = false,
    val nested: Boolean = false,
)

// label column + control, like IntelliJ's option rows
@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 12.5.sp, Palette.subText, modifier = Modifier.width(140.dp))
        control()
    }
}

// segmented choice: one lit cell inside a bordered strip
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val (src, hovered) = rememberHover()
            Box(
                Modifier
                    .hoverable(src)
                    .background(
                        when {
                            on -> Palette.accent
                            hovered -> Palette.hoverBg
                            else -> androidx.compose.ui.graphics.Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .plainClick { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Txt(label, 12.sp, if (on) Palette.holeBg else Palette.menuText, weight = if (on) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

// Esc closes a secondary window (Settings / Extensions)
fun closeOnEscape(ev: KeyEvent, close: () -> Unit): Boolean {
    if (ev.type != KeyEventType.KeyDown || ev.key != Key.Escape) return false
    close()
    return true
}

fun handleKey(ws: Workspace, ev: KeyEvent): Boolean {
    val active = ws.active
    if (ev.type == KeyEventType.KeyUp) {
        if (ev.key == Key.Spacebar) { active?.spaceDown = false; return true }
        return false
    }
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.key == Key.Escape) {
        // whatever is on top takes Esc: a dialog cancels, then a menu closes, then the editor
        when {
            ws.saveError != null -> ws.saveError = null
            ws.installConfirm != null -> ws.cancelInstall()
            ws.newFolderParent != null -> ws.cancelNewFolder()
            ws.renameTarget != null -> ws.cancelRename()
            ws.fileDeleteConfirm != null -> ws.cancelDeleteFiles()
            ws.closeConfirm != null -> ws.cancelClose()
            ws.projectMenuFor != null -> ws.closeProjectMenu()
            ws.menu != null -> ws.menu = null
            // only closes an Input/Output data-editor tab; the canvas (document) tab never does
            else -> active?.wire = null
        }
        return true
    }
    // project tool window bindings (Settings > Keymap). Rename/delete act on the tree only while
    // it is the panel the user last clicked in, so Delete still clears a canvas selection.
    if (!ws.dialogOpen) {
        when (ws.actionFor(ev)) {
            Action.NEW_FLOW -> { ws.newComponent(); return true }
            Action.NEW_FOLDER -> { ws.requestNewFolder(); return true }
            Action.SETTINGS -> { ws.showSettings = true; return true }
            Action.RENAME -> if (ws.projectFocused) {
                ws.projectSelected.singleOrNull()?.let { ws.requestRename(it) }
                return true
            }
            Action.DELETE -> if (ws.projectFocused && ws.projectSelected.isNotEmpty()) {
                ws.requestDeleteFiles(ws.projectSelected)
                return true
            }
        }
    }
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    if (ctrl && ev.key == Key.S) { ws.saveActive(); return true }
    if (ctrl && ev.key == Key.W) {
        ws.requestClose(ws.activeIndex)
        return true
    }
    if (active == null) return false
    if (active.textEditing) return false // ignore shortcuts while a text field is focused
    return when {
        // space toggles run/stop; with a node selected it runs from there
        // (like the "Start from here" button). Guard against key auto-repeat.
        ev.key == Key.Spacebar -> {
            if (!active.spaceDown) {
                active.spaceDown = true
                when {
                    active.running -> active.stopRun()
                    active.selNodes.isNotEmpty() -> active.runFromSelection()
                    else -> active.startRun()
                }
            }
            true
        }
        ev.key == Key.Delete || ev.key == Key.Backspace -> { active.deleteSelection(); true }
        ctrl && ev.key == Key.C -> { active.copySelection(); true }
        ctrl && ev.key == Key.V -> { active.paste(); true }
        ctrl && ev.key == Key.Z -> { if (ev.isShiftPressed) active.redo() else active.undo(); true }
        ctrl && ev.key == Key.Y -> { active.redo(); true }
        ctrl && ev.isShiftPressed && ev.key == Key.L -> { active.autoLayout(); true }
        else -> false
    }
}
