package dataflow.ui.canvas

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
import dataflow.core.EditorState
import dataflow.ui.theme.Palette
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun Minimap(state: EditorState, modifier: Modifier = Modifier) {
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

    // 제스처 도중 pan 변화로 scale/ox/oy 가 흔들리지 않도록 최신값을 스냅샷으로 참조
    val scaleS = rememberUpdatedState(scale)
    val oxS = rememberUpdatedState(ox)
    val oyS = rememberUpdatedState(oy)

    Canvas(
        modifier
            .size(mmW.dp, mmH.dp)
            .background(Palette.holeBg.copy(alpha = 0.88f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(7.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume() // 부모 캔버스의 클릭(선택 해제)과 충돌 방지
                    val s = scaleS.value
                    val ex = oxS.value
                    val ey = oyS.value
                    fun jump(p: Offset) {
                        state.minimapJump(Offset((p.x / density - ex) / s, (p.y / density - ey) / s))
                    }
                    jump(down.position)          // 클릭 즉시 이동
                    drag(down.id) { ch ->        // 눌러서 드래그하면 계속 이동
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
