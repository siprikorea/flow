package flow.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import flow.ui.theme.Palette

// The app icon drawn in Compose: gradient rounded square + flow (node-connection) motif.
@Composable
fun AppLogo(size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val r = s * 0.28f
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Palette.accent, Palette.catSource)),
            cornerRadius = CornerRadius(r, r),
        )
        val white = Color.White.copy(alpha = 0.92f)
        val path = Path().apply {
            moveTo(s * 0.30f, s * 0.34f)
            cubicTo(s * 0.55f, s * 0.34f, s * 0.45f, s * 0.66f, s * 0.70f, s * 0.66f)
        }
        drawPath(path, white, style = Stroke(width = s * 0.09f, cap = StrokeCap.Round))
        drawCircle(white, s * 0.075f, Offset(s * 0.30f, s * 0.34f))
        drawCircle(white, s * 0.075f, Offset(s * 0.70f, s * 0.66f))
    }
}
