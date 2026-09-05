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

// The title-bar mark: same "f" (flow) motif as icons/appicon.svg — a curve between two node
// dots with a small crossbar — but drawn rather than loaded from that PNG, so it keeps taking
// the current theme's accent gradient (a static bitmap can't recolor itself for light/dark).
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
        val stroke = Stroke(width = s * 0.10f, cap = StrokeCap.Round)
        // the curve: bottom-left node up to top-right node, bowing through the middle like an "f"
        val ax = s * 0.28f; val ay = s * 0.72f
        val bx = s * 0.72f; val by = s * 0.28f
        val path = Path().apply {
            moveTo(ax, ay)
            cubicTo(s * 0.62f, ay, s * 0.38f, by, bx, by)
        }
        drawPath(path, white, style = stroke)
        // crossbar where the curve is steepest — the one detail that reads as an "f" rather than
        // a plain S-curve
        drawLine(white, Offset(s * 0.34f, s * 0.52f), Offset(s * 0.56f, s * 0.46f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        drawCircle(white, s * 0.10f, Offset(ax, ay))
        drawCircle(white, s * 0.10f, Offset(bx, by))
    }
}
