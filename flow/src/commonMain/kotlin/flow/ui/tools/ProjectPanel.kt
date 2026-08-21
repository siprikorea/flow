package flow.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Action
import flow.core.Workspace
import flow.platform.droppedFilePath
import flow.util.pathParent
import flow.ui.common.ActivityButton
import flow.ui.common.ActivityRail
import flow.ui.common.BlocksGlyph
import flow.ui.common.ChevronGlyph
import flow.ui.common.FolderGlyph
import flow.ui.common.FolderPlusGlyph
import flow.ui.common.KindBadge
import flow.ui.common.RailSide
import flow.ui.common.ResizeDivider
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette
import kotlin.math.roundToInt

private val ROW_INDENT = 13.dp // per tree level

// Left side: the activity rail + the panel it opens. Pressing an open one collapses it.
@Composable
fun LeftToolWindow(ws: Workspace) {
    Row {
        ActivityRail {
            ActivityButton(RailSide.LEFT, selected = ws.showLeft && ws.leftTab == "project", onClick = { ws.clickActivity("project") }) { tint ->
                FolderGlyph(tint)
            }
            ActivityButton(RailSide.LEFT, selected = ws.showLeft && ws.leftTab == "modules", onClick = { ws.clickActivity("modules") }) { tint ->
                BlocksGlyph(tint)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
        if (ws.showLeft) {
            Column(
                Modifier
                    .width(ws.leftWidth.dp)
                    .fillMaxHeight()
                    .background(Palette.panelBg),
            ) {
                when (ws.leftTab) {
                    "modules" -> {
                        PanelHeader(ws.t("tabModules"))
                        ModulePalette(ws)
                    }
                    else -> ProjectPanel(ws)
                }
            }
            ResizeDivider(Palette.panelBorder) {
                ws.leftWidth = (ws.leftWidth + it).coerceIn(160f, 500f)
            }
        }
    }
}

// The flows folder as a tree: root row + expandable folders, add/rename/delete from the
// header buttons or the right-click menu.
@Composable
private fun ProjectPanel(ws: Workspace) {
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(ws.t("tabProject")) {
            HeaderIcon(onClick = { ws.requestNewFolder() }) { tint -> FolderPlusGlyph(tint, 15.dp) }
            HeaderIcon(onClick = { ws.newComponent() }) { tint -> Txt("+", 15.sp, tint, weight = FontWeight.Bold) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
        ) {
            RootRow(ws)
            if (ws.isExpanded("")) {
                ws.projectRows().forEach { row -> key(row.path) { ItemRow(ws, row) } }
            }
        }
    }
}

// Same title row for every left panel, so switching tabs doesn't shift the title.
@Composable
private fun PanelHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(title.uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HeaderIcon(onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val (hoverSrc, hovered) = rememberHover()
    Box(
        Modifier
            .size(24.dp)
            .hoverable(hoverSrc)
            .background(if (hovered) Palette.hoverBg else Color.Transparent, RoundedCornerShape(5.dp))
            .plainClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (hovered) Palette.text else Palette.subText)
    }
}

// an OS file dropped on a project folder row (or the root) copies a .flow file into it
@Composable
private fun rememberFolderDropTarget(ws: Workspace, dir: String): DragAndDropTarget =
    remember(ws, dir) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val path = droppedFilePath(event) ?: return false
                ws.copyFlowIntoFolder(path, dir)
                return true
            }
        }
    }

// the project folder itself, with its full path greyed out beside the name
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RootRow(ws: Workspace) {
    val (hoverSrc, hovered) = rememberHover()
    val expanded = ws.isExpanded("")
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dropTarget = rememberFolderDropTarget(ws, "")
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .hoverable(hoverSrc)
            .background(if (hovered) Palette.hoverBg else Color.Transparent)
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.first()
                        change.consume()
                        ws.projectFocused = true
                        if (event.buttons.isSecondaryPressed) ws.openProjectMenu("", origin + change.position)
                        else ws.toggleExpand("")
                    }
                }
            }
            .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ChevronGlyph(Palette.subText, expanded)
        FolderGlyph(Palette.accentSoft, 14.dp, filled = true)
        Txt(ws.rootLabel, 12.5.sp, Palette.text, weight = FontWeight.Medium, maxLines = 1)
        // full path of the folder being shown — greyed out, like IntelliJ's project root
        Txt(ws.dirLabel, 10.sp, Palette.faintText, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ItemRow(ws: Workspace, row: Workspace.Row) {
    val (hoverSrc, hovered) = rememberHover()
    val path = row.path
    val selected = path in ws.projectSelected
    val isOpen = ws.docs.any { it.fileName == path }
    val isActive = ws.active?.fileName == path
    val isFlow = row.name.endsWith(".flow")
    val isComp = !row.isDir && ws.isComponentFile(path)
    val expanded = row.isDir && ws.isExpanded(path)
    val bg = when {
        selected -> Palette.langActiveBg
        hovered -> Palette.hoverBg
        else -> Color.Transparent
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // a dropped .flow file copies into this folder; file rows aren't a drop target
    val dropTarget = if (row.isDir) rememberFolderDropTarget(ws, path) else null
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .hoverable(hoverSrc)
            .background(bg)
            .let { m -> if (dropTarget != null) m.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget) else m }
            // click = select, Cmd/Ctrl+click = toggle, double-click = open, right-click = menu
            .pointerInput(path, row.isDir) {
                var lastPress = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.first()
                        change.consume()
                        ws.projectFocused = true
                        val mods = event.keyboardModifiers
                        if (event.buttons.isSecondaryPressed) {
                            if (path !in ws.projectSelected) ws.selectFile(path)
                            ws.openProjectMenu(path, origin + change.position)
                        } else if (mods.isCtrlPressed || mods.isMetaPressed) {
                            ws.toggleFileSelect(path)
                        } else {
                            val now = change.uptimeMillis
                            if (now - lastPress <= viewConfiguration.doubleTapTimeoutMillis) {
                                if (row.isDir) ws.toggleExpand(path)
                                else ws.openFiles(if (path in ws.projectSelected) ws.projectSelected else setOf(path))
                                lastPress = 0L
                            } else {
                                ws.selectFile(path)
                                lastPress = now
                            }
                        }
                    }
                }
            }
            .padding(start = 6.dp + ROW_INDENT * (row.depth + 1), end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // folders toggle from their own chevron; files keep the indent
        if (row.isDir) {
            Box(
                Modifier.size(12.dp).plainClick { ws.toggleExpand(path) },
                contentAlignment = Alignment.Center,
            ) { ChevronGlyph(Palette.subText, expanded) }
            FolderGlyph(if (expanded) Palette.accentSoft else Palette.subText, 14.dp, filled = expanded)
            Txt(row.name, 12.sp, if (selected) Palette.text else Palette.menuText, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.size(12.dp))
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (isFlow) KindBadge("f", if (isComp) Palette.catComponent else Palette.dimText, 13.dp)
                else Txt("\u25aa", 11.sp, Palette.faintestText, weight = FontWeight.Bold)
            }
            Txt(
                if (isFlow) row.name.removeSuffix(".flow") else row.name, 12.sp,
                when {
                    !isFlow -> Palette.faintText // not a flow file: can't be opened
                    isActive || isOpen -> Palette.text
                    else -> Palette.menuText
                },
                weight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
        }
    }
}

// Right-click menu for the project tree. Drawn as a window-level overlay (see App) so it is
// never clipped by the panel and closes on the next click anywhere.
@Composable
fun ProjectContextMenu(ws: Workspace) {
    val path = ws.projectMenuFor ?: return
    val isRoot = path.isEmpty()
    val isDir = isRoot || ws.isDir(path)
    var showNew by remember(path) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().plainClick { ws.closeProjectMenu() }) {
        Row(Modifier.offset { IntOffset(ws.projectMenuPos.x.roundToInt(), ws.projectMenuPos.y.roundToInt()) }) {
            MenuCard {
                MenuItem(ws.t("menuNew"), submenu = true, highlighted = showNew) { showNew = !showNew }
                if (isRoot) {
                    MenuItem(ws.t("refresh")) {
                        ws.closeProjectMenu()
                        ws.refreshFiles()
                    }
                } else {
                    MenuItem(ws.t("open")) {
                        ws.closeProjectMenu()
                        if (isDir) ws.revealDir(path) else ws.openFiles(ws.projectSelected)
                    }
                    MenuItem(ws.t("rename"), shortcut = ws.shortcutLabel(Action.RENAME)) {
                        ws.closeProjectMenu()
                        ws.requestRename(path)
                    }
                    MenuItem(ws.t("delete"), shortcut = ws.shortcutLabel(Action.DELETE)) {
                        val target = if (path in ws.projectSelected) ws.projectSelected else setOf(path)
                        ws.closeProjectMenu()
                        ws.requestDeleteFiles(target)
                    }
                }
            }
            if (showNew) {
                // new items land in the clicked folder, or the clicked file's folder
                val dir = if (isDir) path else pathParent(path)
                MenuCard {
                    MenuItem(ws.t("addFlow"), shortcut = ws.shortcutLabel(Action.NEW_FLOW)) {
                        ws.closeProjectMenu()
                        ws.newComponent(dir)
                    }
                    MenuItem(ws.t("addFolder"), shortcut = ws.shortcutLabel(Action.NEW_FOLDER)) {
                        ws.closeProjectMenu()
                        ws.requestNewFolder(dir)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            // as wide as its longest item, but never cramped
            .width(IntrinsicSize.Max)
            .widthIn(min = 168.dp)
            .background(Palette.dropdownBg, RoundedCornerShape(6.dp))
            .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(6.dp))
            .padding(3.dp),
        content = { content() },
    )
}

@Composable
private fun MenuItem(
    label: String,
    shortcut: String = "",
    submenu: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val (src, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(src)
            .background(if (hovered || highlighted) Palette.dropdownHover else Color.Transparent, RoundedCornerShape(4.dp))
            .plainClick(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 12.sp, Palette.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        if (shortcut.isNotEmpty()) Txt(shortcut, 11.sp, Palette.dimText)
        if (submenu) Txt("\u25b6", 9.sp, Palette.subText)
    }
}
