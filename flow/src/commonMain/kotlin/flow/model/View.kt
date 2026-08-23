package flow.model

/**
 * An installed view, as its worker described it.
 *
 * A view shows output data: the same bytes read as text, as hex, as a parsed structure or as an
 * image. Which one is useful is the user's choice at the time, so a node names the view it is shown
 * with and can be switched to another without changing what the flow computes.
 */
data class ViewInfo(
    val id: String,
    val name: String,
    val options: List<OptDef> = emptyList(),
    val version: String = "1.0.0",
)

/**
 * One thing a view drew.
 *
 * A view runs in its own process, so it cannot reach the screen — it draws into a canvas that
 * records the calls, and these are what arrive. Keeping them as calls rather than as pixels is what
 * lets the app lay the text out itself, crisply and in the current theme.
 */
sealed interface DrawOp {
    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float,
        val color: Int,
        val mono: Boolean,
    ) : DrawOp

    data class Rect(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val color: Int,
        val filled: Boolean,
    ) : DrawOp

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: Int,
        val stroke: Float,
    ) : DrawOp

    /** PNG bytes, scaled into the box — how a view shows something it rendered itself. */
    class Image(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val png: ByteArray,
    ) : DrawOp
}

/**
 * A finished drawing. [contentHeight] is how tall the view says it is, which may exceed the space
 * on screen — the app scrolls the difference rather than asking the view to draw again.
 */
data class Drawing(val contentHeight: Float, val ops: List<DrawOp>)

/**
 * Colours a view can name instead of choosing one, so it is not stuck dark-on-dark when the theme
 * changes. These are the values of `flow.extension.ViewCanvas`'s constants, repeated here because
 * this module cannot depend on the extension contract; ViewPaletteTest holds the two together.
 */
object ViewColor {
    const val DEFAULT_TEXT = 0x0100_0001
    const val MUTED_TEXT = 0x0100_0002
    const val ACCENT = 0x0100_0003
    const val ERROR = 0x0100_0004
    const val SURFACE = 0x0100_0005
    const val BORDER = 0x0100_0006

    /** True for any of the above: the app substitutes its own colour rather than reading ARGB. */
    fun isThemeColor(argb: Int) = argb in DEFAULT_TEXT..BORDER
}

/**
 * The node parameter naming the view an output is shown with. It lives in params like any other
 * option, so it is saved with the flow and travels with it.
 */
const val VIEW_PARAM = "view"
