package dataflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dataflow.core.Workspace
import dataflow.ui.common.Txt
import dataflow.ui.common.plainClick
import dataflow.ui.common.rememberHover
import dataflow.ui.theme.Palette

private class MenuItemDef(
    val label: String,
    val shortcut: String? = null,
    val checked: Boolean? = null,
    val action: () -> Unit,
)

@Composable
fun MenuBar(ws: Workspace) {
    val active = ws.active
    Column {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Palette.panelBg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 로고 = 앱 메뉴 (클릭 시 설정 등)
            Menu(ws, "app", listOf(
                MenuItemDef(ws.t("menuSettings")) { ws.showSettings = true },
            )) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(16.dp).background(Brush.linearGradient(listOf(Palette.accent, Palette.catSource)), RoundedCornerShape(4.dp)))
                    Txt("DataFlow", 14.sp, Palette.text, weight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))

            Menu(ws, "file", ws.t("menuFile"), listOf(
                MenuItemDef(ws.t("newFlow"), "Ctrl+N") { ws.newDoc() },
                MenuItemDef(ws.t("newComponent")) { ws.newComponent() },
                MenuItemDef(ws.t("closeTab"), "Ctrl+W") { ws.requestClose(ws.activeIndex) },
            ))
            Menu(ws, "edit", ws.t("menuEdit"), listOf(
                MenuItemDef(ws.t("undo"), "Ctrl+Z") { active?.undo() },
                MenuItemDef(ws.t("redo"), "Ctrl+Shift+Z") { active?.redo() },
                MenuItemDef(ws.t("deleteSel"), "Del") { active?.deleteSelection() },
                MenuItemDef(ws.t("autoLayout")) { active?.autoLayout() },
            ))
            Menu(ws, "view", ws.t("menuView"), listOf(
                MenuItemDef(ws.t("toggleLeft"), checked = ws.showLeft) { ws.showLeft = !ws.showLeft },
                MenuItemDef(ws.t("toggleProps"), checked = ws.showProps) { ws.showProps = !ws.showProps },
                MenuItemDef(ws.t("toggleMinimap"), checked = ws.showMinimap) { ws.showMinimap = !ws.showMinimap },
            ))

            Spacer(Modifier.weight(1f))
            // 실행/중지 토글 버튼 (하나로 합침)
            val running = active?.running == true
            if (running) {
                RunButton(ws.t("stop"), enabled = true, borderColor = Palette.dangerBorder, textColor = Palette.errorSoft) { active?.stopRun() }
            } else {
                RunButton(ws.t("start"), enabled = active != null, bg = Palette.accent, textColor = Palette.holeBg) { active?.startRun() }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

// 텍스트 라벨 메뉴 (File/Edit/View)
@Composable
private fun Menu(ws: Workspace, id: String, label: String, items: List<MenuItemDef>) {
    Menu(ws, id, items) {
        Txt(label, 13.sp, Palette.menuText)
    }
}

// 앵커 콘텐츠를 직접 지정하는 메뉴 (로고 앱 메뉴 등)
@Composable
private fun Menu(ws: Workspace, id: String, items: List<MenuItemDef>, anchor: @Composable () -> Unit) {
    val (hoverSrc, hovered) = rememberHover()
    var anchorH by remember { mutableStateOf(0) }
    // 다른 메뉴가 열려 있을 때 이 버튼 위로 커서가 오면 자연스럽게 전환
    LaunchedEffect(hovered) {
        if (hovered && ws.menu != null && ws.menu != id) ws.menu = id
    }
    Box {
        Box(
            Modifier
                .onGloballyPositioned { anchorH = it.size.height }
                .hoverable(hoverSrc)
                .background(if (hovered || ws.menu == id) Palette.hoverBg else Palette.panelBg, RoundedCornerShape(6.dp))
                .plainClick { ws.menu = if (ws.menu == id) null else id }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            anchor()
        }
        if (ws.menu == id) {
            Popup(
                offset = IntOffset(0, anchorH + 4),
                onDismissRequest = { ws.menu = null },
                // non-focusable: 팝업이 창 활성화를 가져가지 않아야 메뉴바가 계속 hover 이벤트를 받아
                // 다른 메뉴로 커서를 옮길 때 자연스럽게 전환된다.
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier
                        .widthIn(min = 150.dp, max = 300.dp)
                        .background(Palette.dropdownBg, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(8.dp))
                        .padding(5.dp)
                ) {
                    items.forEach { item ->
                        if (item.label == "—") {
                            Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 2.dp).background(Palette.dropdownBorder))
                        } else {
                            DropdownItem(item) { ws.menu = null; item.action() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownItem(item: MenuItemDef, onClick: () -> Unit) {
    val (src, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(src)
            .background(if (hovered) Palette.dropdownHover else Color.Transparent, RoundedCornerShape(5.dp))
            .plainClick(onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(16.dp)) {
            if (item.checked == true) Txt("✓", 11.sp, Palette.accent)
        }
        Txt(item.label, 12.5.sp, Palette.text)
        Spacer(Modifier.weight(1f).widthIn(min = 18.dp))
        item.shortcut?.let { Txt(it, 11.sp, Palette.dimText, mono = true) }
    }
}

@Composable
private fun RunButton(
    label: String,
    enabled: Boolean,
    textColor: Color,
    bg: Color? = null,
    borderColor: Color? = null,
    onClick: () -> Unit,
) {
    var m = Modifier.background(bg ?: Color.Transparent, RoundedCornerShape(6.dp))
    if (borderColor != null) m = m.border(1.dp, borderColor, RoundedCornerShape(6.dp))
    Box(m.plainClick { if (enabled) onClick() }.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Txt(
            label, 12.5.sp,
            if (enabled) textColor else textColor.copy(alpha = 0.4f),
            weight = if (bg != null) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
