package flow.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import flow.core.EditorState
import flow.ui.theme.Palette
import flow.ui.theme.Radius
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun Minimap(state: EditorState, modifier: Modifier = Modifier) {
    val mmW = 184f
    val mmH = 124f
    val density = state.density

    // viewport and node bounds in world (dp)
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

    // snapshot the latest mapping so scale/ox/oy don't shift as pan changes mid-gesture
    val scaleS = rememberUpdatedState(scale)
    val oxS = rememberUpdatedState(ox)
    val oyS = rememberUpdatedState(oy)

    Canvas(
        modifier
            .size(mmW.dp, mmH.dp)
            .background(Palette.holeBg.copy(alpha = 0.88f), RoundedCornerShape(Radius.surface))
            .border(1.dp, Palette.border, RoundedCornerShape(Radius.surface))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume() // avoid conflicting with the parent canvas click (deselect)
                    val s = scaleS.value
                    val ex = oxS.value
                    val ey = oyS.value
                    fun jump(p: Offset) {
                        state.minimapJump(Offset((p.x / density - ex) / s, (p.y / density - ey) / s))
                    }
                    jump(down.position)          // jump immediately on click
                    drag(down.id) { ch ->        // keep moving while dragging
                        jump(ch.position)
                        ch.consume()
                    }
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
            Palette.accent.copy(alpha = 0.20f),
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
