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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Icons drawn in code: each fills a square of [size] in the given tint.

// folder
@Composable
fun FolderGlyph(tint: Color, size: Dp = 20.dp, filled: Boolean = false) {
    Canvas(Modifier.size(size)) { drawFolder(tint, filled) }
}

// folder + plus
@Composable
fun FolderPlusGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        drawFolder(tint, filled = false)
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.72f
        val cy = h * 0.58f
        val arm = w * 0.13f
        val st = w * 0.10f
        drawLine(tint, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = st)
        drawLine(tint, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = st)
    }
}

private fun DrawScope.drawFolder(tint: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val st = Stroke(width = w * 0.09f)
    val tabTopLeft = Offset(w * 0.10f, h * 0.16f)
    val tabSize = Size(w * 0.36f, h * 0.18f)
    val bodyTopLeft = Offset(w * 0.10f, h * 0.28f)
    val bodySize = Size(w * 0.80f, h * 0.52f)
    val r = CornerRadius(w * 0.06f, w * 0.06f)
    if (filled) {
        drawRoundRect(tint.copy(alpha = 0.28f), topLeft = tabTopLeft, size = tabSize, cornerRadius = r)
        drawRoundRect(tint.copy(alpha = 0.28f), topLeft = bodyTopLeft, size = bodySize, cornerRadius = CornerRadius(w * 0.08f, w * 0.08f))
    }
    drawRoundRect(tint, topLeft = tabTopLeft, size = tabSize, cornerRadius = r, style = st)
    drawRoundRect(tint, topLeft = bodyTopLeft, size = bodySize, cornerRadius = CornerRadius(w * 0.08f, w * 0.08f), style = st)
}

/**
 * The Claude mark: a burst of twelve tapered rays.
 *
 * Measured off the installed Claude application's own icon rather than drawn by eye — the angles
 * are deliberately uneven and so are the lengths, and a regular star drawn from memory reads as the
 * wrong logo rather than as a simplified one. Anthropic's mark, used to say which assistant this
 * panel runs.
 *
 * Drawn rather than shipped as an image so it takes the tint of the rail it sits in, like every
 * other icon here.
 */
@Composable
fun ClaudeGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val unit = this.size.minDimension / 2f * 0.96f
        CLAUDE_RAYS.forEach { ray -> drawRay(tint, centre, unit, ray) }
        // the rays meet in a solid hub rather than a dozen points touching
        drawCircle(tint, radius = unit * 0.13f, center = centre)
    }
}

/** One ray: which way it points, how far it goes, and how thick it is. */
private class Ray(val angle: Float, val reach: Float, val halfWidth: Float)

// Angle clockwise from the right, reach as a fraction of the longest ray, half-width as a fraction
// of that same length. Measured off the icon: the spacing is uneven, the lengths vary a little, and
// the thicknesses vary a lot — which together are most of why the mark looks hand-made rather than
// like a symmetrical star.
private val CLAUDE_RAYS = listOf(
    Ray(17f, 0.93f, 0.063f),
    Ray(44f, 0.95f, 0.055f),
    Ray(63f, 0.93f, 0.078f),
    Ray(93f, 0.96f, 0.055f),
    Ray(120f, 0.96f, 0.061f),
    Ray(144f, 0.92f, 0.068f),
    Ray(177f, 0.96f, 0.050f),
    Ray(210f, 0.95f, 0.090f),
    Ray(241f, 1.00f, 0.095f),
    Ray(279f, 0.87f, 0.069f),
    Ray(314f, 0.91f, 0.116f),
    Ray(355f, 0.90f, 0.071f),
)

/**
 * A ray, as a wedge: thick where they all meet and tapering outward to a blunt tip.
 *
 * Nearly parallel-sided rather than pointed — the icon's rays hold most of their width to the end
 * and are cut off square, which is what keeps them legible when the whole mark is sixteen pixels.
 */
private fun DrawScope.drawRay(tint: Color, centre: Offset, unit: Float, ray: Ray) {
    val a = Math.toRadians(ray.angle.toDouble())
    val across = a + Math.PI / 2
    val dx = cos(a).toFloat()
    val dy = sin(a).toFloat()
    val nx = cos(across).toFloat()
    val ny = sin(across).toFloat()
    val tip = unit * ray.reach
    val w = unit * ray.halfWidth

    fun point(along: Float, side: Float) =
        Offset(centre.x + dx * along + nx * side, centre.y + dy * along + ny * side)

    val path = Path().apply {
        moveTo(point(0f, w).x, point(0f, w).y)
        lineTo(point(tip * 0.88f, w * 0.85f).x, point(tip * 0.88f, w * 0.85f).y)
        lineTo(point(tip, w * 0.5f).x, point(tip, w * 0.5f).y)
        lineTo(point(tip, -w * 0.5f).x, point(tip, -w * 0.5f).y)
        lineTo(point(tip * 0.88f, -w * 0.85f).x, point(tip * 0.88f, -w * 0.85f).y)
        lineTo(point(0f, -w).x, point(0f, -w).y)
        close()
    }
    drawPath(path, tint)
}

// blocks, one detached
@Composable
fun BlocksGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = Size(w * 0.34f, w * 0.34f)
        val r = CornerRadius(w * 0.06f, w * 0.06f)
        val st = Stroke(width = w * 0.09f)
        drawRoundRect(tint, topLeft = Offset(w * 0.08f, w * 0.08f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.08f, w * 0.56f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.56f, w * 0.56f), size = s, cornerRadius = r, style = st)
        drawRoundRect(tint, topLeft = Offset(w * 0.62f, w * 0.04f), size = s, cornerRadius = r, style = st)
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

// side panel, right column lit when [filled]
@Composable
fun PanelRightGlyph(tint: Color, size: Dp = 20.dp, filled: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = Stroke(width = w * 0.09f)
        drawRoundRect(
            tint, topLeft = Offset(w * 0.08f, h * 0.16f), size = Size(w * 0.84f, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = st,
        )
        val divX = w * 0.62f
        drawLine(tint, Offset(divX, h * 0.16f), Offset(divX, h * 0.84f), strokeWidth = w * 0.09f)
        if (filled) drawRect(tint.copy(alpha = 0.9f), topLeft = Offset(divX, h * 0.16f), size = Size(w * 0.30f, h * 0.68f))
    }
}

// tree chevron: right = collapsed, down = expanded
@Composable
fun ChevronGlyph(tint: Color, expanded: Boolean, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val st = w * 0.14f
        if (expanded) {
            drawLine(tint, Offset(w * 0.20f, w * 0.36f), Offset(w * 0.50f, w * 0.68f), strokeWidth = st)
            drawLine(tint, Offset(w * 0.50f, w * 0.68f), Offset(w * 0.80f, w * 0.36f), strokeWidth = st)
        } else {
            drawLine(tint, Offset(w * 0.36f, w * 0.20f), Offset(w * 0.68f, w * 0.50f), strokeWidth = st)
            drawLine(tint, Offset(w * 0.68f, w * 0.50f), Offset(w * 0.36f, w * 0.80f), strokeWidth = st)
        }
    }
}
