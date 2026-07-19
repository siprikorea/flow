package dataflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
fun App(state: EditorState) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(state)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (state.showSidebar) Sidebar(state)
                CanvasView(state, Modifier.weight(1f).fillMaxHeight())
                if (state.showProps) PropsPanel(state)
            }
            StatusBar(state)
        }
        state.dragModule?.let { DragGhost(it) }
    }
    // 영속화: 350ms 디바운스 자동 저장
    LaunchedEffect(Unit) {
        snapshotFlow { state.persistJson() }
            .debounce(350)
            .collect { state.persistNow(it) }
    }
}

fun handleKey(state: EditorState, ev: KeyEvent): Boolean {
    if (ev.type == KeyEventType.KeyUp) {
        if (ev.key == Key.Spacebar) {
            state.spaceDown = false
            return true
        }
        return false
    }
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.key == Key.Escape) {
        state.wire = null
        state.menu = null
        return true
    }
    if (state.textEditing) return false // 입력 필드 포커스 중에는 단축키 무시
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    return when {
        ev.key == Key.Spacebar -> { state.spaceDown = true; true }
        ev.key == Key.Delete || ev.key == Key.Backspace -> { state.deleteSelection(); true }
        ctrl && ev.key == Key.Z -> { if (ev.isShiftPressed) state.redo() else state.undo(); true }
        ctrl && ev.key == Key.Y -> { state.redo(); true }
        else -> false
    }
}

/* ───────── 메뉴바 ───────── */

private data class MenuItemDef(val label: String, val shortcut: String? = null, val action: () -> Unit)

@Composable
fun MenuBar(state: EditorState) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(41.dp).background(Palette.panelBg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(16.dp).background(
                        Brush.linearGradient(listOf(Palette.accent, Palette.catSource)),
                        RoundedCornerShape(4.dp),
                    )
                )
                Txt("DataFlow", 14.sp, Palette.text, weight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            MenuButton(state, "file", state.t("menuFile"), listOf(
                MenuItemDef(state.t("newFlow")) { state.newFlow() },
                MenuItemDef(state.t("exportJson")) { state.exportJson() },
                MenuItemDef(state.t("importJson")) { state.importJson() },
            ))
            MenuButton(state, "edit", state.t("menuEdit"), listOf(
                MenuItemDef(state.t("undo"), "Ctrl+Z") { state.undo() },
                MenuItemDef(state.t("redo"), "Ctrl+Shift+Z") { state.redo() },
                MenuItemDef(state.t("deleteSel"), "Del") { state.deleteSelection() },
                MenuItemDef(state.t("autoLayout")) { state.autoLayout() },
            ))
            MenuButton(state, "window", state.t("menuWindow"), listOf(
                MenuItemDef((if (state.showSidebar) "✓ " else "") + state.t("toggleSidebar")) { state.showSidebar = !state.showSidebar },
                MenuItemDef((if (state.showProps) "✓ " else "") + state.t("toggleProps")) { state.showProps = !state.showProps },
                MenuItemDef((if (state.showMinimap) "✓ " else "") + state.t("toggleMinimap")) { state.showMinimap = !state.showMinimap },
            ))
            Spacer(Modifier.weight(1f))
            RunButton(state.t("start"), enabled = true, bg = Palette.accent, textColor = Palette.holeBg) { state.startRun() }
            val hasNodeSel = state.sel?.kind == "node"
            RunButton(state.t("runSel"), enabled = hasNodeSel, borderColor = Palette.buttonBorder, textColor = Palette.menuText) {
                state.runFromSelection()
            }
            RunButton(state.t("stop"), enabled = state.running, textColor = Palette.errorSoft) { state.stopRun() }
            Spacer(Modifier.width(4.dp))
            LangToggle(state)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

@Composable
private fun MenuButton(state: EditorState, id: String, label: String, items: List<MenuItemDef>) {
    val (hoverSrc, hovered) = rememberHover()
    Box {
        Box(
            Modifier
                .hoverable(hoverSrc)
                .background(
                    if (hovered || state.menu == id) Palette.hoverBg else Palette.panelBg,
                    RoundedCornerShape(6.dp),
                )
                .plainClick { state.menu = if (state.menu == id) null else id }
                .padding(horizontal = 11.dp, vertical = 5.dp)
        ) {
            Txt(label, 13.sp, Palette.menuText)
        }
        if (state.menu == id) {
            Popup(
                offset = IntOffset(0, 90),
                onDismissRequest = { state.menu = null },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .widthIn(min = 196.dp)
                        .background(Palette.dropdownBg, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(8.dp))
                        .padding(5.dp)
                ) {
                    items.forEach { item ->
                        val (itemSrc, itemHovered) = rememberHover()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hoverable(itemSrc)
                                .background(
                                    if (itemHovered) Palette.dropdownHover else Palette.dropdownBg,
                                    RoundedCornerShape(5.dp),
                                )
                                .plainClick { state.menu = null; item.action() }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(item.label, 12.5.sp, Palette.text)
                            Spacer(Modifier.weight(1f).widthIn(min = 18.dp))
                            item.shortcut?.let { Txt(it, 11.sp, Palette.dimText, mono = true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunButton(
    label: String,
    enabled: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color? = null,
    borderColor: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    var m = Modifier
        .background(bg ?: androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
    if (borderColor != null) m = m.border(1.dp, borderColor, RoundedCornerShape(6.dp))
    Box(
        m.plainClick { if (enabled) onClick() }.padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Txt(
            label, 12.5.sp,
            if (enabled) textColor else textColor.copy(alpha = 0.4f),
            weight = if (bg != null) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LangToggle(state: EditorState) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("ko" to "KO", "en" to "EN").forEach { (code, label) ->
            val active = state.lang == code
            Box(
                Modifier
                    .background(
                        if (active) Palette.langActiveBg else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(5.dp),
                    )
                    .plainClick { state.lang = code }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Txt(label, 11.5.sp, if (active) Palette.langActiveText else Palette.dimText)
            }
        }
    }
}

/* ───────── 사이드바 ───────── */

@Composable
fun Sidebar(state: EditorState) {
    Row {
        Column(
            Modifier
                .width(225.dp)
                .fillMaxHeight()
                .background(Palette.panelBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(
                state.t("moduleList").uppercase(), 11.sp, Palette.subText,
                weight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
            REGISTRY.filter { !it.plugin }.forEach { ModCard(state, it) }
            Row(
                Modifier.padding(start = 2.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(Palette.catSource, RoundedCornerShape(3.dp)))
                Txt(
                    state.t("installedPlugins").uppercase(), 11.sp, Palette.subText,
                    weight = FontWeight.Bold, letterSpacing = 1.sp,
                )
            }
            REGISTRY.filter { it.plugin }.forEach { ModCard(state, it) }
            Txt(
                state.t("dragHint"), 11.sp, Palette.dimText,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
    }
}

@Composable
private fun ModCard(state: EditorState, def: ModuleDef) {
    val (hoverSrc, hovered) = rememberHover()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .hoverable(hoverSrc)
            .background(Palette.dropdownBg, RoundedCornerShape(7.dp))
            .dashedOrSolidBorder(def.plugin, if (hovered) Palette.buttonBorder.copy(alpha = 1f) else null)
            .pointerInput(def.type) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    drag(down.id) { ch ->
                        state.dragModule = DragModule(def.type, origin + ch.position)
                        ch.consume()
                    }
                    state.dropModule()
                }
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).background(Palette.catColor(def.cat), RoundedCornerShape(3.dp)))
        Txt(
            def.name[state.lang] ?: def.type, 12.5.sp, Palette.text,
            weight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (def.plugin) {
            Box(
                Modifier
                    .border(1.dp, Palette.pluginBadgeBorder, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Txt("PLUGIN", 9.sp, Palette.catSource, weight = FontWeight.Bold)
            }
        }
        Txt("${def.ins.size}→${def.outs.size}", 10.5.sp, Palette.dimText, mono = true)
    }
}

// 플러그인 카드: dashed border #33405a / 일반 카드: solid #2c3140 (hover #3d4557)
private fun Modifier.dashedOrSolidBorder(
    plugin: Boolean,
    hoverColor: androidx.compose.ui.graphics.Color?,
): Modifier {
    val color = when {
        hoverColor != null -> androidx.compose.ui.graphics.Color(0xFF3D4557)
        plugin -> Palette.runFromBorder
        else -> Palette.border
    }
    return border(
        1.dp, color, RoundedCornerShape(7.dp),
    )
}

/* ───────── 드래그 고스트 ───────── */

@Composable
private fun DragGhost(d: DragModule) {
    val def = findDef(d.type) ?: return
    Box(
        Modifier
            .offset { IntOffset(d.pos.x.roundToInt() + 8, d.pos.y.roundToInt() + 8) }
            .background(Palette.dropdownBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.accent, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(Palette.catColor(def.cat), RoundedCornerShape(3.dp)))
            Txt(def.name["ko"] ?: def.type, 12.sp, Palette.text)
        }
    }
}

/* ───────── 상태바 ───────── */

@Composable
fun StatusBar(state: EditorState) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(Palette.panelBg).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(state.t("statusHint"), 11.sp, Palette.dimText)
            Spacer(Modifier.weight(1f))
            Txt(
                "${state.nodes.size} ${state.t("modules")} · ${state.edges.size} ${state.t("connections")}",
                11.sp, Palette.dimText, mono = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("−", 12.sp, Palette.subText, modifier = Modifier.plainClick {
                    state.zoom = (state.zoom - 0.1f).coerceAtLeast(0.3f)
                }.padding(horizontal = 6.dp))
                Txt(
                    "${(state.zoom * 100).roundToInt()}%", 11.sp, Palette.dimText, mono = true,
                    modifier = Modifier.plainClick { state.zoom = 1f },
                )
                Txt("+", 12.sp, Palette.subText, modifier = Modifier.plainClick {
                    state.zoom = (state.zoom + 0.1f).coerceAtMost(2.5f)
                }.padding(horizontal = 6.dp))
            }
            state.saveTime?.let { Txt("${state.t("autoSaved")} $it", 11.sp, Palette.autosave) }
        }
    }
}
