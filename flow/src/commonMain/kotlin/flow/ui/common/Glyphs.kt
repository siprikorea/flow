package flow.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Icons drawn in code: each fills a square of [size] in the given tint.
//
// Most UI chrome icons now come from the Lucide set (see LucideIcon.kt) — the design guide's one
// icon language. The few left here (play/stop/gear) still back the title-bar run controls until
// those are restyled to the guide's button hierarchy; they'll fold into Lucide.Play/Square/Settings
// at that point.

// play triangle
@Composable
fun PlayGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.30f, h * 0.80f)
            lineTo(w * 0.82f, h * 0.50f)
            close()
        }
        drawPath(path, tint)
    }
}

// stop square
@Composable
fun StopGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = w * 0.5f
        drawRoundRect(
            tint,
            topLeft = Offset((w - s) / 2f, (w - s) / 2f),
            size = Size(s, s),
            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f),
        )
    }
}

// gear
@Composable
fun GearGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val st = w * 0.09f
        // teeth
        repeat(8) { i ->
            val a = i * (2.0 * kotlin.math.PI / 8.0)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(
                tint,
                Offset(c.x + dx * w * 0.30f, c.y + dy * w * 0.30f),
                Offset(c.x + dx * w * 0.44f, c.y + dy * w * 0.44f),
                strokeWidth = st,
            )
        }
        drawCircle(tint, radius = w * 0.27f, center = c, style = Stroke(width = st))
        drawCircle(tint, radius = w * 0.09f, center = c)
    }
}
