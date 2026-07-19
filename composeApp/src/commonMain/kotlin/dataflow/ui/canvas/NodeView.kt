package dataflow.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dataflow.core.EditorState
import dataflow.core.portPos
import dataflow.core.portY
import dataflow.core.snapF
import dataflow.model.Sel
import dataflow.model.compFile
import dataflow.model.findDef
import dataflow.model.isComp
import dataflow.ui.common.Txt
import dataflow.ui.common.moveCursorIcon
import dataflow.ui.common.rememberHover
import dataflow.ui.common.resizeCursorIcon
import dataflow.ui.theme.Palette
import kotlin.math.max

@Composable
internal fun NodeView(state: EditorState, node: dataflow.model.Node, timeMs: Long) {
    val density = state.density
    val selected = state.sel?.kind == "node" && state.sel?.id == node.id
    val comp = isComp(node.type)
    val cat = when {
        comp -> "component"
        else -> findDef(node.type)?.cat ?: "transform"
    }
    val borderColor = when {
        node.status == "running" -> Palette.accent
        node.status == "done" -> Palette.doneBorder
        node.status == "error" -> Palette.error
        selected -> Palette.accentHover
        else -> Palette.nodeBorder
    }
    val statusText = when (node.status) {
        "running" -> state.t("stRunning")
        "done" -> state.t("stDone")
        "error" -> state.t("stNoInput")
        else -> null
    }
    val statusColor = when (node.status) {
        "running" -> Palette.accentSoft
        "done" -> Palette.successText
        else -> Palette.errorSoft
    }
    val badge = when {
        node.type == "cin" -> "IN"
        node.type == "cout" -> "OUT"
        comp -> "COMP"
        else -> null
    }

    Box(
        Modifier
            .offset(node.x.dp, node.y.dp)
            .size(node.w.dp, node.h.dp)
            .drawBehind {
                // nodepulse: 퍼지는 테두리 링 (1.2s)
                if (node.status == "running") {
                    val p = (timeMs % 1200) / 1200f
                    val spread = p * 9f * density
                    drawRoundRect(
                        color = Palette.accent.copy(alpha = (1 - p) * 0.45f),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(9f * density + spread),
                        style = Stroke(2.5f * density),
                    )
                }
            }
            .background(Palette.nodeBg, RoundedCornerShape(9.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(9.dp))
            .pointerInput(node.id, node.type) {
                // down 즉시 선택(지연 없음) + uptime 간격으로 더블클릭(컴포넌트 편집) 감지
                var lastDown = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume() // 캔버스가 선택을 해제하지 못하도록 소비
                    state.sel = Sel("node", node.id)
                    state.menu = null
                    val now = down.uptimeMillis
                    if (comp && now - lastDown <= viewConfiguration.doubleTapTimeoutMillis) {
                        state.ws.openFile(compFile(node.type))
                        lastDown = 0L
                    } else {
                        lastDown = now
                    }
                }
            }
    ) {
        // 헤더 28px: 드래그 이동
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Palette.nodeHeaderBg, RoundedCornerShape(topStart = 7.5.dp, topEnd = 7.5.dp))
                .pointerHoverIcon(moveCursorIcon())
                .pointerInput(node.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        state.sel = Sel("node", node.id)
                        state.menu = null
                        val snap0 = state.snapshot()
                        val start = state.nodeById(node.id) ?: return@awaitEachGesture
                        var acc = Offset.Zero
                        var moved = false
                        drag(down.id) { ch ->
                            acc += (ch.position - ch.previousPosition) / density
                            val nx = snapF(start.x + acc.x)
                            val ny = snapF(start.y + acc.y)
                            if (nx != start.x || ny != start.y) moved = true
                            state.moveNode(node.id, nx, ny)
                            ch.consume()
                        }
                        if (moved) state.pushHistory(snap0) // mouseup 시 1회 push
                    }
                }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(Palette.catColor(cat), RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Txt(node.label, 12.sp, Palette.text, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            if (badge != null) {
                NodeBadge(badge, Palette.catColor(cat))
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.size(8.dp).background(Palette.statusDot(node.status), CircleShape))
        }

        // 노드 id (본문 중앙)
        Box(Modifier.matchParentSize().padding(top = 14.dp), contentAlignment = Alignment.Center) {
            Txt(node.id, 9.5.sp, Palette.faintText, mono = true)
        }
        // 상태 라벨 (하단)
        statusText?.let {
            Box(Modifier.matchParentSize().padding(bottom = 6.dp), contentAlignment = Alignment.BottomCenter) {
                Txt(it, 10.sp, statusColor, mono = true)
            }
        }

        node.inputs.forEachIndexed { i, name -> PortView(state, node, "in", i, name) }
        node.outputs.forEachIndexed { i, name -> PortView(state, node, "out", i, name) }

        // 리사이즈 핸들 (우하단 L자, min 120×60)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset((-3).dp, (-3).dp)
                .size(13.dp)
                .pointerHoverIcon(resizeCursorIcon())
                .drawBehind {
                    val w = 2f * density
                    drawLine(Palette.resizeHandle, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    drawLine(Palette.resizeHandle, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                }
                .pointerInput(node.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val snap0 = state.snapshot()
                        val start = state.nodeById(node.id) ?: return@awaitEachGesture
                        var acc = Offset.Zero
                        var moved = false
                        drag(down.id) { ch ->
                            acc += (ch.position - ch.previousPosition) / density
                            val nw = max(120f, snapF(start.w + acc.x))
                            val nh = max(60f, snapF(start.h + acc.y))
                            if (nw != start.w || nh != start.h) moved = true
                            state.resizeNode(node.id, nw, nh)
                            ch.consume()
                        }
                        if (moved) state.pushHistory(snap0)
                    }
                }
        )
    }
}

@Composable
private fun NodeBadge(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Txt(text, 8.5.sp, color, weight = FontWeight.Bold)
    }
}

@Composable
private fun PortView(state: EditorState, node: dataflow.model.Node, kind: String, idx: Int, name: String) {
    val density = state.density
    val (hoverSrc, hovered) = rememberHover()
    val cy = portY(node, (if (kind == "in") node.inputs else node.outputs).size, idx) - node.y
    val connected = if (kind == "in")
        state.edges.any { it.to.node == node.id && it.to.port == name }
    else
        state.edges.any { it.from.node == node.id && it.from.port == name }

    Box(
        Modifier
            .offset(if (kind == "in") (-8).dp else (node.w - 7).dp, (cy - 7.5f).dp)
            .size(15.dp)
            .hoverable(hoverSrc)
            .background(if (hovered) Palette.accent else Palette.holeBg, CircleShape)
            .border(2.5.dp, if (connected) Palette.accent else Palette.portBorder, CircleShape)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(node.id, kind, name) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (kind == "out") {
                        val fresh = state.nodeById(node.id) ?: return@awaitEachGesture
                        var cur = portPos(fresh, "out", fresh.outputs.indexOf(name).coerceAtLeast(0))
                        state.wire = dataflow.core.Wire(node.id, name, cur)
                        drag(down.id) { ch ->
                            cur += (ch.position - ch.previousPosition) / density
                            state.wire = dataflow.core.Wire(node.id, name, cur)
                            ch.consume()
                        }
                        state.completeWire(cur)
                    } else {
                        drag(down.id) { it.consume() }
                    }
                }
            }
    )
    // 포트 id 라벨 (안쪽)
    Box(
        Modifier
            .offset(0.dp, (cy - 6f).dp)
            .width(node.w.dp)
            .padding(horizontal = 12.dp)
    ) {
        Txt(
            name, 10.sp, Palette.subText, mono = true,
            modifier = Modifier.align(if (kind == "in") Alignment.CenterStart else Alignment.CenterEnd),
        )
    }
}
