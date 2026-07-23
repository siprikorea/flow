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
import androidx.compose.ui.unit.dp

// A 1px visible divider with a wider invisible hit area. Hovering it shows a
// horizontal-resize cursor; dragging reports the horizontal delta in dp.
@Composable
fun ResizeDivider(color: Color, onDrag: (Float) -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .width(6.dp)
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
