package flow.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.EditorState
import flow.core.GRID
import flow.core.bezierCtrl
import flow.core.bezierPoint
import flow.core.portPos
import androidx.compose.ui.geometry.Rect
import flow.ui.common.Txt
import flow.ui.theme.Palette

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasView(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    state.density = density

    // animation clock: advance frames only while running
    var timeMs by remember { mutableStateOf(0L) }
    val animating = state.running || state.edges.any { it.active } || state.nodes.any { it.status == "running" }
    LaunchedEffect(animating) {
        while (animating) {
            withFrameNanos { timeMs = it / 1_000_000 }
        }
    }
    // when each active edge started, so its packet travels the curve exactly once
    val packetStart = remember { mutableMapOf<String, Long>() }

    Box(
        modifier
            .clipToBounds()
            .background(Palette.canvasBg)
            .onGloballyPositioned {
                state.canvasOrigin = it.positionInWindow()
                state.canvasSize = it.size
            }
            .onPointerEvent(PointerEventType.Move) { ev ->
                // paste target: remember where the cursor is on the canvas (world dp)
                state.cursorWorld = state.screenToWorld(ev.changes.first().position)
            }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val ch = ev.changes.first()
                val mods = ev.keyboardModifiers
                if (mods.isCtrlPressed || mods.isMetaPressed) {
                    // Cmd/Ctrl + scroll = zoom about the cursor
                    state.zoomAt(ch.position, ch.scrollDelta.y)
                } else {
                    // plain scroll (two-finger swipe) = pan the canvas
                    state.pan -= ch.scrollDelta * 40f
                }
                ch.consume()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.menu = null
                    if (state.spaceDown) {
                        down.consume()
                        drag(down.id) { ch ->
                            state.pan += ch.position - ch.previousPosition
                            ch.consume()
                        }
                    } else {
                        // drag on empty canvas = rubber-band select, click = select/deselect an edge
                        val startWorld = state.screenToWorld(down.position)
                        var moved = false
                        drag(down.id) { ch ->
                            moved = true
                            val cur = state.screenToWorld(ch.position)
                            state.selRect = Rect(
                                minOf(startWorld.x, cur.x), minOf(startWorld.y, cur.y),
                                maxOf(startWorld.x, cur.x), maxOf(startWorld.y, cur.y),
                            )
                            ch.consume()
                        }
                        if (moved) {
                            state.selRect?.let { state.selectInRect(it) }
                            state.selRect = null
                        } else {
                            val hit = state.edgeAt(startWorld)
                            if (hit != null) state.selectEdge(hit) else state.clearSel()
                        }
                    }
                }
            }
    ) {
        // grid + edges + preview + packets
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
                    // anchor edges at the port circle's outer edge (radius ~8) so lines
                    // never show behind the solid port circles
                    val a = portPos(from, "out", from.outputs.indexOf(e.from.port).coerceAtLeast(0)).let { it.copy(x = it.x + 8f) }
                    val b = portPos(to, "in", to.inputs.indexOf(e.to.port).coerceAtLeast(0)).let { it.copy(x = it.x - 8f) }
                    val c = bezierCtrl(a, b)
                    val path = Path().apply {
                        moveTo(a.x, a.y)
                        cubicTo(a.x + c, a.y, b.x - c, b.y, b.x, b.y)
                    }
                    val selected = e.id in state.selEdges
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
                        // packet: white dot + glow, travels the curve exactly once (0.85s), synced to
                        // edge activation so it arrives right when the next node starts
                        val start = packetStart.getOrPut(e.id) { timeMs }
                        val t = ((timeMs - start) / 850f).coerceIn(0f, 1f)
                        val p = bezierPoint(a, b, t)
                        drawCircle(Palette.accentSoft.copy(alpha = 0.45f), 8f, p)
                        drawCircle(Palette.edgeSelected, 4.5f, p)
                    } else {
                        packetStart.remove(e.id)
                    }
                }
                state.wire?.let { w ->
                    val from = byId[w.node]
                    if (from != null) {
                        val a = portPos(from, "out", from.outputs.indexOf(w.port).coerceAtLeast(0)).let { it.copy(x = it.x + 8f) }
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
                // rubber-band selection rectangle
                state.selRect?.let { r ->
                    drawRect(
                        Palette.accent.copy(alpha = 0.10f),
                        topLeft = Offset(r.left, r.top),
                        size = androidx.compose.ui.geometry.Size(r.width, r.height),
                    )
                    drawRect(
                        Palette.accent,
                        topLeft = Offset(r.left, r.top),
                        size = androidx.compose.ui.geometry.Size(r.width, r.height),
                        style = Stroke(1f),
                    )
                }
            }
        }

        // node layer
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
