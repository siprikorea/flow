package dataflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasView(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    state.density = density

    // 애니메이션 시계: 실행 중일 때만 프레임 갱신
    var timeMs by remember { mutableStateOf(0L) }
    val animating = state.running || state.edges.any { it.active } || state.nodes.any { it.status == "running" }
    LaunchedEffect(animating) {
        while (animating) {
            withFrameNanos { timeMs = it / 1_000_000 }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .background(Palette.canvasBg)
            .onGloballyPositioned {
                state.canvasOrigin = it.positionInWindow()
                state.canvasSize = it.size
            }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val ch = ev.changes.first()
                state.zoomAt(ch.position, ch.scrollDelta.y)
                ch.consume()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (state.spaceDown) {
                        down.consume()
                        drag(down.id) { ch ->
                            state.pan += ch.position - ch.previousPosition
                            ch.consume()
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            val world = state.screenToWorld(up.position)
                            val hit = state.edgeAt(world)
                            state.menu = null
                            state.sel = hit?.let { Sel("edge", it) }
                        }
                    }
                }
            }
    ) {
        // 격자 + 연결선 + 프리뷰 + 패킷
        Canvas(Modifier.fillMaxSize()) {
            val s = GRID * density * state.zoom
            val ox = ((state.pan.x % s) + s) % s
            val oy = ((state.pan.y % s) + s) % s
            val dotR = 1f * density * state.zoom
            var gx = ox
            while (gx < size.width) {
                var gy = oy
                while (gy < size.height) {
                    drawCircle(Palette.gridDot, dotR, Offset(gx, gy))
                    gy += s
                }
                gx += s
            }

            withTransform({
                translate(state.pan.x, state.pan.y)
                scale(state.zoom * density, state.zoom * density, Offset.Zero)
            }) {
                val byId = state.nodes.associateBy { it.id }
                state.edges.forEach { e ->
                    val from = byId[e.from.node] ?: return@forEach
                    val to = byId[e.to.node] ?: return@forEach
                    val a = portPos(from, "out", from.outputs.indexOf(e.from.port).coerceAtLeast(0))
                    val b = portPos(to, "in", to.inputs.indexOf(e.to.port).coerceAtLeast(0))
                    val c = bezierCtrl(a, b)
                    val path = Path().apply {
                        moveTo(a.x, a.y)
                        cubicTo(a.x + c, a.y, b.x - c, b.y, b.x, b.y)
                    }
                    val selected = state.sel?.kind == "edge" && state.sel?.id == e.id
                    val color = when {
                        e.active -> Palette.accent
                        selected -> Palette.edgeSelected
                        else -> Palette.edge
                    }
                    val effect = if (e.active)
                        PathEffect.dashPathEffect(floatArrayOf(7f, 6f), -((timeMs % 500) / 500f) * 24f)
                    else null
                    drawPath(path, color, style = Stroke(if (selected) 3.5f else 2.5f, pathEffect = effect))

                    if (e.active) {
                        // 패킷: 9px 흰 원 + glow, 곡선을 따라 0→100% (0.85s 반복)
                        val t = (timeMs % 850) / 850f
                        val p = bezierPoint(a, b, t)
                        drawCircle(Palette.accentSoft.copy(alpha = 0.45f), 8f, p)
                        drawCircle(Palette.edgeSelected, 4.5f, p)
                    }
                }
                state.wire?.let { w ->
                    val from = byId[w.node]
                    if (from != null) {
                        val a = portPos(from, "out", from.outputs.indexOf(w.port).coerceAtLeast(0))
                        val c = bezierCtrl(a, w.pos)
                        val path = Path().apply {
                            moveTo(a.x, a.y)
                            cubicTo(a.x + c, a.y, w.pos.x - c, w.pos.y, w.pos.x, w.pos.y)
                        }
                        drawPath(
                            path, Palette.accentHover,
                            style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))),
                        )
                    }
                }
            }
        }

        // 노드 레이어
        Box(
            Modifier.fillMaxSize().graphicsLayer(
                translationX = state.pan.x,
                translationY = state.pan.y,
                scaleX = state.zoom,
                scaleY = state.zoom,
                transformOrigin = TransformOrigin(0f, 0f),
            )
        ) {
            state.nodes.forEach { node ->
                key(node.id) { NodeView(state, node, timeMs) }
            }
        }

        if (state.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(state.t("emptyCanvas"), 13.sp, Palette.faintestText)
            }
        }

        if (state.showMinimap) {
            Minimap(state, Modifier.align(Alignment.BottomEnd).padding(14.dp))
        }
    }
}

/* ───────── 노드 ───────── */

@Composable
private fun NodeView(state: EditorState, node: Node, timeMs: Long) {
    val density = state.density
    val selected = state.sel?.kind == "node" && state.sel?.id == node.id
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
            .pointerInput(node.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    state.sel = Sel("node", node.id)
                    state.menu = null
                    drag(down.id) { it.consume() }
                }
            }
    ) {
        // 헤더 28px: 드래그 이동
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Palette.nodeHeaderBg, RoundedCornerShape(topStart = 7.5.dp, topEnd = 7.5.dp))
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
            Box(Modifier.size(9.dp).background(Palette.catColor(findDef(node.type)?.cat ?: "transform"), RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Txt(node.label, 12.sp, Palette.text, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
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
private fun PortView(state: EditorState, node: Node, kind: String, idx: Int, name: String) {
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
            .pointerInput(node.id, kind, name) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (kind == "out") {
                        val fresh = state.nodeById(node.id) ?: return@awaitEachGesture
                        var cur = portPos(fresh, "out", fresh.outputs.indexOf(name).coerceAtLeast(0))
                        state.wire = Wire(node.id, name, cur)
                        drag(down.id) { ch ->
                            cur += (ch.position - ch.previousPosition) / density
                            state.wire = Wire(node.id, name, cur)
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

/* ───────── 미니맵 ───────── */

@Composable
fun Minimap(state: EditorState, modifier: Modifier = Modifier) {
    val mmW = 184f
    val mmH = 124f
    val density = state.density

    // world(dp) 기준 뷰포트와 노드 경계
    val zd = state.zoom * density
    val view = listOf(
        -state.pan.x / zd,
        -state.pan.y / zd,
        (state.canvasSize.width - state.pan.x) / zd,
        (state.canvasSize.height - state.pan.y) / zd,
    )
    var minX = view[0]; var minY = view[1]; var maxX = view[2]; var maxY = view[3]
    state.nodes.forEach { n ->
        minX = min(minX, n.x); minY = min(minY, n.y)
        maxX = max(maxX, n.x + n.w); maxY = max(maxY, n.y + n.h)
    }
    minX -= 40f; minY -= 40f; maxX += 40f; maxY += 40f
    val scale = min(mmW / (maxX - minX), mmH / (maxY - minY))
    val ox = (mmW - (maxX - minX) * scale) / 2 - minX * scale
    val oy = (mmH - (maxY - minY) * scale) / 2 - minY * scale

    Canvas(
        modifier
            .size(mmW.dp, mmH.dp)
            .background(Palette.holeBg.copy(alpha = 0.88f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(7.dp))
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val world = Offset(
                        (pos.x / density - ox) / scale,
                        (pos.y / density - oy) / scale,
                    )
                    state.minimapJump(world)
                }
            }
    ) {
        state.nodes.forEach { n ->
            val color = when (n.status) {
                "running" -> Palette.accent
                "error" -> Palette.error
                else -> Palette.minimapNode
            }
            drawRect(
                color,
                topLeft = Offset((n.x * scale + ox) * density, (n.y * scale + oy) * density),
                size = Size(max(2f, n.w * scale * density), max(2f, n.h * scale * density)),
            )
        }
        drawRect(
            Palette.accent.copy(alpha = 0.07f),
            topLeft = Offset((view[0] * scale + ox) * density, (view[1] * scale + oy) * density),
            size = Size((view[2] - view[0]) * scale * density, (view[3] - view[1]) * scale * density),
        )
        drawRect(
            Palette.accent,
            topLeft = Offset((view[0] * scale + ox) * density, (view[1] * scale + oy) * density),
            size = Size((view[2] - view[0]) * scale * density, (view[3] - view[1]) * scale * density),
            style = Stroke(1.5f * density),
        )
    }
}
