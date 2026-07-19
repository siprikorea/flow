package dataflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun App(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(ws)
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
    }

    // 자동 저장: 활성 문서 → 파일 (350ms 디바운스)
    LaunchedEffect(Unit) {
        snapshotFlow { ws.active?.let { it.fileName to it.flowJson() } }
            .debounce(350)
            .collect { pair ->
                if (pair != null) {
                    Platform.writeFlow(pair.first, pair.second)
                    ws.saveTime = Platform.currentTimeHms()
                    ws.refreshFiles()
                }
            }
    }
    // 세션(열린 탭 + UI) 저장
    LaunchedEffect(Unit) {
        snapshotFlow { ws.sessionJson() }
            .debounce(350)
            .collect { Platform.saveSession(it) }
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
    if (ctrl && ev.key == Key.W) { ws.closeDoc(ws.activeIndex); return true }
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
