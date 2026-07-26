package flow.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import flow.core.Workspace
import flow.ui.common.ResizeDivider
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette

// VS Code-style left side: an activity bar (icon rail) + an optional panel.
// Clicking an icon opens its panel; clicking the same icon again collapses the panel.
@Composable
fun LeftToolWindow(ws: Workspace) {
    Row {
        // activity bar
        Column(
            Modifier.width(44.dp).fillMaxHeight().background(Palette.tabBarBg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActivityIcon(ws, "project") { tint -> FolderGlyph(tint) }
            ActivityIcon(ws, "modules") { tint -> BlocksGlyph(tint) }
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
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Txt(ws.t("tabModules").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
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

@Composable
private fun ActivityIcon(ws: Workspace, tab: String, icon: @Composable (Color) -> Unit) {
    val (hoverSrc, hovered) = rememberHover()
    val active = ws.showLeft && ws.leftTab == tab
    val tint = when {
        active -> Palette.text
        hovered -> Palette.menuText
        else -> Palette.dimText
    }
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .hoverable(hoverSrc)
            .plainClick { ws.clickActivity(tab) },
    ) {
        if (active) {
            Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(22.dp).background(Palette.accent))
        }
        Box(Modifier.align(Alignment.Center)) { icon(tint) }
    }
}

// folder outline (project files)
@Composable
private fun FolderGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = w * 0.09f)
        drawRoundRect(
            tint, topLeft = Offset(w * 0.10f, h * 0.16f), size = Size(w * 0.36f, h * 0.18f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f), style = st,
        )
        drawRoundRect(
            tint, topLeft = Offset(w * 0.10f, h * 0.28f), size = Size(w * 0.80f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f), style = st,
        )
    }
}

// blocks (palette), one square detached like the VS Code extensions icon
@Composable
private fun BlocksGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val s = Size(w * 0.34f, w * 0.34f)
        val r = CornerRadius(w * 0.06f, w * 0.06f)
        val st = Stroke(width = w * 0.09f)
        drawRoundRect(tint, topLeft = Offset(w * 0.08f, w * 0.08f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.08f, w * 0.56f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.56f, w * 0.56f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.62f, w * 0.04f), size = s, cornerRadius = r, style = st)
    }
}

@Composable
private fun ProjectPanel(ws: Workspace) {
    Column(Modifier.fillMaxWidth()) {
        // Header: new flow (+) / delete selection
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(ws.t("tabProject").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (ws.projectSelected.size == 1) {
                Txt(
                    "✎", 14.sp, Palette.subText,
                    modifier = Modifier.plainClick { ws.requestRename(ws.projectSelected.first()) }.padding(horizontal = 6.dp),
                )
            }
            if (ws.projectSelected.isNotEmpty()) {
                Txt(
                    "🗑", 13.sp, Palette.errorSoft,
                    modifier = Modifier.plainClick { ws.requestDeleteFiles(ws.projectSelected) }.padding(horizontal = 6.dp),
                )
            }
            Txt(
                "+", 15.sp, Palette.subText,
                modifier = Modifier.plainClick { ws.newComponent() }.padding(horizontal = 6.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        ) {
            ws.files.forEach { name -> FileRow(ws, name) }
            Txt(
                ws.dirLabel, 10.sp, Palette.faintText, mono = true,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FileRow(ws: Workspace, name: String) {
    val (hoverSrc, hovered) = rememberHover()
    val isOpen = ws.docs.any { it.fileName == name }
    val isActive = ws.active?.fileName == name
    val selected = name in ws.projectSelected
    val isComp = ws.isComponentFile(name)
    val bg = when {
        selected -> Palette.langActiveBg
        hovered -> Palette.hoverBg
        else -> Color.Transparent
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverable(hoverSrc)
                .background(bg, RoundedCornerShape(5.dp))
                // click = select, Cmd/Win+click = toggle, double-click = open, right-click = context menu
                .pointerInput(name) {
                    var lastDown = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val mods = currentEvent.keyboardModifiers
                        if (currentEvent.buttons.isSecondaryPressed) {
                            if (name !in ws.projectSelected) ws.selectFile(name)
                            ws.projectMenuFor = name
                        } else if (mods.isCtrlPressed || mods.isMetaPressed) {
                            ws.toggleFileSelect(name)
                        } else {
                            val now = down.uptimeMillis
                            if (now - lastDown <= viewConfiguration.doubleTapTimeoutMillis) {
                                ws.openFiles(if (name in ws.projectSelected) ws.projectSelected else setOf(name))
                                lastDown = 0L
                            } else {
                                ws.selectFile(name)
                                lastDown = now
                            }
                        }
                    }
                }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Txt(if (isComp) "◆" else "▪", 11.sp, if (isComp) Palette.catComponent else Palette.dimText, weight = FontWeight.Bold)
            Txt(
                name.removeSuffix(".flow"), 12.sp,
                if (isActive || isOpen) Palette.text else Palette.menuText,
                weight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (isComp) Txt("C", 9.sp, Palette.catComponent, mono = true, weight = FontWeight.Bold)
        }
        if (ws.projectMenuFor == name) FileContextMenu(ws)
    }
}

@Composable
private fun FileContextMenu(ws: Workspace) {
    Popup(
        offset = IntOffset(12, 24),
        onDismissRequest = { ws.projectMenuFor = null },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(min = 140.dp)
                .background(Palette.dropdownBg, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(8.dp))
                .padding(5.dp),
        ) {
            ContextItem(ws.t("open"), Palette.text) {
                ws.openFiles(ws.projectSelected)
                ws.projectMenuFor = null
            }
            if (ws.projectSelected.size == 1) {
                ContextItem(ws.t("rename"), Palette.text) {
                    val target = ws.projectSelected.first()
                    ws.projectMenuFor = null
                    ws.requestRename(target)
                }
            }
            ContextItem(ws.t("delete"), Palette.errorSoft) {
                val target = ws.projectSelected
                ws.projectMenuFor = null
                ws.requestDeleteFiles(target)
            }
        }
    }
}

@Composable
private fun ContextItem(label: String, color: Color, onClick: () -> Unit) {
    val (src, hovered) = rememberHover()
    Box(
        Modifier
            .fillMaxWidth()
            .hoverable(src)
            .background(if (hovered) Palette.dropdownHover else Color.Transparent, RoundedCornerShape(5.dp))
            .plainClick(onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Txt(label, 12.5.sp, color)
    }
}
