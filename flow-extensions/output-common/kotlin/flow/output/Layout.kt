package flow.output

import flow.extension.OutputCanvas

/**
 * The shared shape of these views: a monospace column grid with a bit of padding.
 *
 * All four lay text out in rows of a fixed height, so working out where a row goes and how many
 * characters fit belongs in one place rather than in each of them.
 */
internal class Grid(private val canvas: OutputCanvas, val fontSize: Float = 12f) {
    val pad = 10f
    val lineHeight = fontSize * 1.45f
    /** The advance width of one character at this size — what a column step is. */
    val charWidth = canvas.monoCharWidth * fontSize

    /** The width available for text, once the padding on both sides is taken out. */
    val textWidth: Float get() = (canvas.width - pad * 2).coerceAtLeast(charWidth)

    /** How many monospace characters fit across, never fewer than one. */
    val columns: Int get() = (textWidth / charWidth).toInt().coerceAtLeast(1)

    fun x(column: Int): Float = pad + column * charWidth
    fun y(row: Int): Float = pad + row * lineHeight

    /** The height a drawing of [rows] rows comes to, which is what the app scrolls against. */
    fun height(rows: Int): Float = pad * 2 + rows * lineHeight
}

/**
 * A cap on how much of the data an output will draw at once.
 *
 * The recording is sent whole and drawn whole, so an output that renders a 200MB output as text would
 * cost more than the window it appears in. Views stop here and say so, which is more useful than a
 * spinner that never ends.
 */
internal const val MAX_BYTES = 512 * 1024

internal fun truncationNote(shown: Int, total: Int): String? =
    if (total > shown) "… showing the first $shown of $total bytes" else null
