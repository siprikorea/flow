package flow.views

import flow.extension.ViewCanvas

/**
 * A [ViewCanvas] that keeps what was drawn, so a view can be asked what it produced.
 *
 * The real one records into bytes for the pipe; this one records into objects, which is the same
 * thing from the view's side and far easier to make assertions about.
 */
class FakeCanvas(
    override val width: Float = 800f,
    override val monoCharWidth: Float = 0.6f,
) : ViewCanvas {

    sealed interface Op
    data class Text(val x: Float, val y: Float, val text: String, val size: Float, val color: Int, val mono: Boolean) : Op
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val filled: Boolean) : Op
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val stroke: Float) : Op
    class Image(val x: Float, val y: Float, val w: Float, val h: Float, val bytes: ByteArray) : Op
    data class Region(val x: Float, val y: Float, val w: Float, val h: Float, val id: String) : Op

    val ops = mutableListOf<Op>()
    var height: Float? = null
        private set

    val texts: List<Text> get() = ops.filterIsInstance<Text>()
    val lines: List<String> get() = texts.map { it.text }
    val regions: List<Region> get() = ops.filterIsInstance<Region>()

    /** The region a point falls in, the way the app resolves one: last declared wins. */
    fun regionAt(x: Float, y: Float): Region? = regions.lastOrNull {
        x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h
    }

    override fun contentHeight(height: Float) { this.height = height }

    override fun text(x: Float, y: Float, text: String, size: Float, color: Int, mono: Boolean) {
        ops.add(Text(x, y, text, size, color, mono))
    }

    override fun rect(x: Float, y: Float, width: Float, height: Float, color: Int, filled: Boolean) {
        ops.add(Rect(x, y, width, height, color, filled))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, strokeWidth: Float) {
        ops.add(Line(x1, y1, x2, y2, color, strokeWidth))
    }

    override fun image(x: Float, y: Float, width: Float, height: Float, png: ByteArray) {
        ops.add(Image(x, y, width, height, png))
    }

    override fun region(x: Float, y: Float, width: Float, height: Float, id: String) {
        ops.add(Region(x, y, width, height, id))
    }
}
