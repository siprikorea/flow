package flow.ui.tools

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
import androidx.compose.ui.graphics.Color
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
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette

// 좌측 툴윈도우: Project / Modules 탭 전환
@Composable
fun LeftToolWindow(ws: Workspace) {
    Row {
        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(Palette.panelBg),
        ) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToolTab(ws.t("tabProject"), ws.leftTab == "project") { ws.leftTab = "project" }
                ToolTab(ws.t("tabModules"), ws.leftTab == "modules") { ws.leftTab = "modules" }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
            when (ws.leftTab) {
                "modules" -> ModulePalette(ws)
                else -> ProjectPanel(ws)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
    }
}

@Composable
private fun ToolTab(text: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.plainClick(onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Txt(
                text.uppercase(), 11.sp,
                if (active) Palette.text else Palette.dimText,
                weight = FontWeight.Bold, letterSpacing = 0.5.sp,
            )
        }
        Box(
            Modifier.width(44.dp).height(2.dp)
                .background(if (active) Palette.accent else Color.Transparent)
        )
    }
}

@Composable
private fun ProjectPanel(ws: Workspace) {
    Column(Modifier.fillMaxWidth()) {
        // 헤더: 새 플로우(+) / 선택 삭제
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(ws.t("tabProject").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (ws.projectSelected.isNotEmpty()) {
                Txt(
                    "🗑", 13.sp, Palette.errorSoft,
                    modifier = Modifier.plainClick { ws.requestDeleteFiles(ws.projectSelected) }.padding(horizontal = 6.dp),
                )
            }
            Txt(
                "+", 15.sp, Palette.subText,
                modifier = Modifier.plainClick { ws.newDoc() }.padding(horizontal = 6.dp),
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
                // 클릭=선택, Cmd/Win+클릭=토글, 더블클릭=열기, 우클릭=컨텍스트 메뉴
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
                name.removeSuffix(".json"), 12.sp,
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
