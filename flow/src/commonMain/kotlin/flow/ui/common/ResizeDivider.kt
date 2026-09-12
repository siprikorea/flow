package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A draggable seam between two regions.
 *
 * [color] is the line it shows — [Color.Transparent] for the margin between the content frame and
 * a tool window beside it, where the gap in the window's own ground is the separation and a line
 * down the middle of it would only be clutter. It stays invisible while hovered and while being
 * dragged too: the resize cursor is the whole affordance, and a blue line appearing in the gap
 * reads as something arriving on the canvas rather than as a handle.
 *
 * [width] is the whole hit area, which is why it can stand in for the gap itself rather than
 * adding to it.
 */
@Composable
fun ResizeDivider(color: Color, width: Dp = 6.dp, onDrag: (Float) -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .pointerHoverIcon(hResizeCursorIcon())
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x / density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(color))
    }
}
