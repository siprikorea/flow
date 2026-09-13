package flow.extension

/**
 * A viewer for what a flow produced, in a window of its own.
 *
 * The app shows a value as text or as hex without help; a view is for the ways of reading it that
 * are worth a program of their own — a certificate as a tree beside its bytes, an image, a parsed
 * format with panels that scroll separately.
 *
 * It runs in the module's own process and opens its own window, which is what makes it worth
 * having: it can use anything, including Compose, and lay itself out however the data deserves,
 * with no ceiling set by what the app happened to anticipate. Nothing it does can reach the app —
 * a view that hangs or crashes takes only its own window with it, and the Compose it is built
 * against is its own, so an update to Flow does not break it.
 *
 * What it gives up is being inside the Flow window. A view is a viewer, opened beside the editor.
 *
 * Implementations must have a no-arg constructor and be registered under
 * `META-INF/services/flow.extension.ViewExtension`.
 */
interface ViewExtension {
    /** Identifier in package-name format (e.g. "com.example.asn1"). */
    val id: String

    /** Name shown where the view is picked. */
    val displayName: String

    /** Version, compared against a registry's to decide whether an update is on offer. */
    val version: String get() = "1.0.0"

    /** What this view is for, shown beside its name. */
    val description: String get() = ""

    /**
     * Opens a window showing [data].
     *
     * Called on a thread of its own, so it may block for as long as the window is up — returning is
     * not what closes it, and the app is not waiting. Throwing before a window appears is reported
     * to the user; after that the window is the module's own business.
     *
     * [options] carries the node's parameters, and `theme` among them ("dark" or "light"), so a
     * window can open in the same colours as the app that opened it. It also carries where that
     * app's window is — see [ViewWindow], which turns those into a position to open at.
     */
    fun open(data: ByteArray, options: Map<String, String>)
}

/**
 * Where the Flow window was when it asked for a view.
 *
 * A viewer belongs to the window that opened it, so it should appear over that window rather than
 * wherever the platform would otherwise put it — on a second monitor, the difference is the window
 * being on the screen you are looking at. The app passes its own bounds in the view's options
 * (`hostX`, `hostY`, `hostWidth`, `hostHeight`, in dp); this turns them into the top-left corner a
 * window of a given size needs to sit centred on them.
 */
object ViewWindow {
    /** Top-left (x, y) in dp for a [width]×[height] window centred on the host, or null if the app said nothing. */
    fun centeredOn(options: Map<String, String>, width: Float, height: Float): Pair<Float, Float>? {
        val x = options["hostX"]?.toFloatOrNull() ?: return null
        val y = options["hostY"]?.toFloatOrNull() ?: return null
        val hostWidth = options["hostWidth"]?.toFloatOrNull() ?: return null
        val hostHeight = options["hostHeight"]?.toFloatOrNull() ?: return null
        return (x + (hostWidth - width) / 2f) to (y + (hostHeight - height) / 2f)
    }
}
