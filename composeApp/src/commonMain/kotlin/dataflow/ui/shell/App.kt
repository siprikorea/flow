package dataflow.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dataflow.core.DragModule
import dataflow.core.Workspace
import dataflow.model.findDef
import dataflow.model.isComp
import dataflow.platform.Platform
import dataflow.ui.canvas.CanvasView
import dataflow.ui.common.Txt
import dataflow.ui.common.plainClick
import dataflow.ui.props.PropsPanel
import dataflow.ui.theme.Palette
import dataflow.ui.tools.LeftToolWindow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
fun App(ws: Workspace, leadingInset: Dp = 0.dp) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(ws, leadingInset)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (ws.showLeft) LeftToolWindow(ws)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorTabs(ws)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val active = ws.active
                        if (active != null) CanvasView(active, Modifier.fillMaxSize())
                        else EmptyEditor(ws)
                    }
                }
                val active = ws.active
                if (ws.showProps && active != null) PropsPanel(active)
            }
            StatusBar(ws)
        }
        ws.dragModule?.let { DragGhost(it) }
        ws.closeConfirm?.let { i ->
            val name = ws.docs.getOrNull(i)?.fileName?.removeSuffix(".json") ?: ""
            SaveCloseDialog(ws, name)
        }
        ws.fileDeleteConfirm?.let { FileDeleteDialog(ws, it.size) }
        ws.installConfirm?.let { OverwriteDialog(ws, it.label) }
        if (ws.showSettings) SettingsScreen(ws)
    }

    // 세션(열린 탭 + UI) 저장 — 파일 내용은 명시적 저장(Ctrl+S / 닫기 확인)으로 관리
    LaunchedEffect(Unit) {
        snapshotFlow { ws.sessionJson() }
            .debounce(350)
            .collect { Platform.saveSession(it) }
    }
}

@Composable
private fun SaveCloseDialog(ws: Workspace, name: String) {
    // 스크림: 뒤 클릭 차단
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { } // 카드 클릭이 스크림으로 전파되지 않도록
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
                    .plainClick { ws.newDoc() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Txt(ws.t("newFlow"), 12.sp, Palette.accentHover) }
        }
    }
}

@Composable
private fun DragGhost(d: DragModule) {
    val comp = isComp(d.type)
    val def = findDef(d.type)
    val label = when {
        comp -> d.type.removePrefix("comp:").removeSuffix(".json")
        def != null -> def.name["en"] ?: def.type
        else -> d.type
    }
    val cat = if (comp) "component" else def?.cat ?: "transform"
    Box(
        Modifier
            .offset { IntOffset(d.pos.x.roundToInt() + 8, d.pos.y.roundToInt() + 8) }
            .background(Palette.dropdownBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.accent, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(Palette.catColor(cat), RoundedCornerShape(3.dp)))
            Txt(label, 12.sp, Palette.text)
        }
    }
}

// 설정 전용 화면 (로고 메뉴 > 설정)
@Composable
private fun SettingsScreen(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(Palette.panelBg).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(ws.t("settingsTitle"), 15.sp, Palette.text, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.border(1.dp, Palette.buttonBorder, RoundedCornerShape(6.dp))
                        .plainClick { ws.showSettings = false }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Txt(ws.t("done"), 12.sp, Palette.menuText) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(ws.t("language").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold)
                SettingChoice("한국어", ws.lang == "ko") { ws.lang = "ko" }
                SettingChoice("English", ws.lang == "en") { ws.lang = "en" }
            }
        }
    }
}

@Composable
private fun SettingChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .width(280.dp)
            .background(if (selected) Palette.langActiveBg else Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(7.dp))
            .plainClick(onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp)) {
            if (selected) Txt("●", 11.sp, Palette.accent) else Txt("○", 11.sp, Palette.dimText)
        }
        Txt(label, 12.5.sp, if (selected) Palette.text else Palette.menuText)
    }
}

fun handleKey(ws: Workspace, ev: KeyEvent): Boolean {
    val active = ws.active
    if (ev.type == KeyEventType.KeyUp) {
        if (ev.key == Key.Spacebar) { active?.spaceDown = false; return true }
        return false
    }
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.key == Key.Escape) { active?.wire = null; ws.menu = null; return true }
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    if (ctrl && ev.key == Key.N) { ws.newDoc(); return true }
    if (ctrl && ev.key == Key.S) { ws.saveActive(); return true }
    if (ctrl && ev.key == Key.W) { ws.requestClose(ws.activeIndex); return true }
    if (active == null) return false
    if (active.textEditing) return false // 입력 필드 포커스 중에는 단축키 무시
    return when {
        ev.key == Key.Spacebar -> { active.spaceDown = true; true }
        ev.key == Key.Delete || ev.key == Key.Backspace -> { active.deleteSelection(); true }
        ctrl && ev.key == Key.Z -> { if (ev.isShiftPressed) active.redo() else active.undo(); true }
        ctrl && ev.key == Key.Y -> { active.redo(); true }
        else -> false
    }
}
