package flow.extension

/**
 * Somewhere to draw. A view is handed one of these along with the data and draws whatever it wants.
 *
 * A view runs in its own process, so it cannot reach the app's screen: what it draws here is
 * recorded and replayed by the app. That is invisible to the view — it just draws — but it is why
 * the calls take plain numbers and ARGB ints rather than any toolkit's types, and why there is no
 * way to read pixels back.
 *
 * The origin is the top-left of the area. [width] is what the view has to work within; height is
 * not fixed — draw as tall as the data needs and say how tall with [contentHeight], and the app
 * scrolls what does not fit.
 */
interface ViewCanvas {
    /** The width the view has to lay out within, in points. */
    val width: Float

    /**
     * How wide one monospace character is at text size 1 — so a view that lays out in columns can
     * work out how many fit: `floor(width / (monoCharWidth * size))`.
     *
     * The app measures this in its own font and passes it down, because the view has no way to know
     * what that font is. Guessing it is what makes a hex dump's columns drift out of line.
     */
    val monoCharWidth: Float

    /** How tall the drawing turned out. Call it once the drawing is done; the app scrolls to fit. */
    fun contentHeight(height: Float)

    /**
     * Text with its top-left corner at [x], [y] — the top, not the baseline, so a view laying out
     * rows can step by a line height without knowing anything about font metrics. [size] is that
     * height in the same units as every other coordinate here. [color] is ARGB, so
     * 0xFF000000.toInt() is opaque black. Monospace is worth asking for whenever columns line up.
     */
    fun text(x: Float, y: Float, text: String, size: Float = 12f, color: Int = DEFAULT_TEXT, mono: Boolean = false)

    /** A rectangle, filled or outlined. */
    fun rect(x: Float, y: Float, width: Float, height: Float, color: Int, filled: Boolean = true)

    /** A straight line. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, strokeWidth: Float = 1f)

    /**
     * An image, given as PNG bytes, scaled into the box. This is how a view shows something it
     * generated itself — a QR code, a chart — without needing drawing calls for every pixel.
     */
    fun image(x: Float, y: Float, width: Float, height: Float, png: ByteArray)

    /**
     * Marks a rectangle as something the user can act on, named [id].
     *
     * Nothing is drawn. When a click lands inside it the view is told, with this [id] — so a view
     * decides for itself what its clickable parts are, while the app does the hit-testing without
     * having to ask across the process boundary on every mouse move.
     *
     * Regions may overlap; the last one declared wins, which is the one drawn on top.
     */
    fun region(x: Float, y: Float, width: Float, height: Float, id: String)

    companion object {
        /**
         * Ask the app for its own colour rather than naming one, so a view is not stuck light on
         * light when the theme changes. Any of these may be passed as a [color].
         */
        const val DEFAULT_TEXT = 0x0100_0001
        const val MUTED_TEXT = 0x0100_0002
        const val ACCENT = 0x0100_0003
        const val ERROR = 0x0100_0004
        const val SURFACE = 0x0100_0005
        const val BORDER = 0x0100_0006
    }
}

/**
 * A way of showing output data.
 *
 * Where a [ModuleExtension] turns bytes into bytes, a view turns bytes into something to look at:
 * the same output can be read as text, as hex, as a parsed structure or as an image, and which one
 * is useful is the user's choice at the time. Implementations must have a no-arg constructor and be
 * registered under `META-INF/services/flow.extension.ViewExtension`.
 */
interface ViewExtension {
    /** Identifier in package-name format (e.g. "com.example.hexview"). */
    val id: String

    /** Name shown where the view is picked. */
    val displayName: String

    /** Version, compared against a registry's to decide whether an update is on offer. */
    val version: String get() = "1.0.0"

    /** Options shown alongside the view, edited the same way a module's are. */
    val options: List<ExtensionOption> get() = emptyList()

    /**
     * Whether this view wants to hear about the pointer moving over it.
     *
     * Off unless asked for, because every move is a round trip to this process: a view that only
     * responds to clicks should leave it alone, and one that highlights what is under the cursor
     * should turn it on.
     */
    val wantsHover: Boolean get() = false

    /**
     * Draw [data] into [canvas].
     *
     * Called again whenever the data, the options or the width change, so it should be cheap enough
     * to run on a resize and must not keep anything between calls. Throwing reports the problem in
     * place of the drawing — which is the right thing for data this view cannot make sense of.
     *
     * A view keeps nothing between calls, so anything it needs to remember — what is expanded, what
     * is selected — belongs in [options]: whatever [onEvent] returns is passed back here, and is
     * saved with the flow, so a tree left half-open is still half-open tomorrow.
     */
    fun draw(canvas: ViewCanvas, data: ByteArray, options: Map<String, String>)

    /**
     * Something happened on the view. Return the options it should be drawn with next.
     *
     * Returning [options] unchanged means nothing happened as far as this view is concerned, and
     * nothing is redrawn. The default does exactly that, so a view that does not care about being
     * clicked need not mention it.
     */
    fun onEvent(event: ViewEvent, options: Map<String, String>): Map<String, String> = options
}

/**
 * Something the user did on a view.
 *
 * [region] is the name the view gave that part of its drawing, which is usually all it needs to
 * know; [x] and [y] are there for the cases where the position within the view matters more than
 * which part was hit.
 */
class ViewEvent(
    /** One of [CLICK], [DOUBLE_CLICK] or [HOVER]. */
    val kind: String,
    /** The innermost region under the pointer, or null where the view declared none. */
    val region: String?,
    val x: Float,
    val y: Float,
) {
    companion object {
        const val CLICK = "click"
        const val DOUBLE_CLICK = "doubleClick"

        /** Only delivered to a view whose [ViewExtension.wantsHover] is true. */
        const val HOVER = "hover"
    }
}
