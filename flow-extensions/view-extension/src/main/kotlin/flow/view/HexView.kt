package flow.view

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ViewCanvas
import flow.extension.ViewExtension

/**
 * The data as a hex dump: offset, bytes, and the printable characters beside them.
 *
 * The number of bytes per row is chosen to fit the width rather than fixed at 16, so a narrow panel
 * still lines up and a wide one is not mostly empty. Powers of two only — a row of 13 makes the
 * offsets useless for finding anything.
 */
class HexView : ViewExtension {
    override val id = "flow.view.hex"
    override val displayName = "Hex View"
    override val version = "1.0.0"
    override val options = listOf(
        ExtensionOption("bytesPerRow", OptionType.SELECT, "auto", listOf("auto", "8", "16", "32")),
    )

    override fun draw(canvas: ViewCanvas, data: ByteArray, options: Map<String, String>) {
        val grid = Grid(canvas)
        val shown = data.size.coerceAtMost(MAX_BYTES)
        val perRow = bytesPerRow(options["bytesPerRow"], grid.columns)

        val rows = (shown + perRow - 1) / perRow
        val hexStart = OFFSET_COLUMNS
        val asciiStart = hexStart + perRow * 3 + 1

        for (row in 0 until rows) {
            val y = grid.y(row)
            val start = row * perRow
            val end = (start + perRow).coerceAtMost(shown)

            canvas.text(grid.x(0), y, offsetLabel(start), grid.fontSize, ViewCanvas.MUTED_TEXT, mono = true)

            val hex = StringBuilder(perRow * 3)
            val ascii = StringBuilder(perRow)
            for (i in start until end) {
                val b = data[i].toInt() and 0xFF
                hex.append(HEX[b shr 4]).append(HEX[b and 0x0F]).append(' ')
                // the printable ASCII range; everything else is a dot, as every hex dump does it
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            canvas.text(grid.x(hexStart), y, hex.toString().trimEnd(), grid.fontSize, ViewCanvas.DEFAULT_TEXT, mono = true)
            canvas.text(grid.x(asciiStart), y, ascii.toString(), grid.fontSize, ViewCanvas.ACCENT, mono = true)
        }

        var total = rows
        truncationNote(shown, data.size)?.let {
            total += 2
            canvas.text(grid.x(0), grid.y(rows + 1), it, grid.fontSize, ViewCanvas.MUTED_TEXT, mono = true)
        }
        if (shown == 0) {
            canvas.text(grid.x(0), grid.y(0), "(no data)", grid.fontSize, ViewCanvas.MUTED_TEXT, mono = true)
            total = 1
        }
        canvas.contentHeight(grid.height(total))
    }

    /**
     * A row is "OOOOOOOO  hh hh … |aaaa": eight offset characters and two spaces, then three
     * characters per byte for the hex, then one for its own character. Auto picks the largest
     * power of two whose row fits.
     */
    private fun bytesPerRow(option: String?, columns: Int): Int {
        option?.toIntOrNull()?.let { return it.coerceIn(1, 64) }
        val room = columns - OFFSET_COLUMNS - 1
        var n = 8
        while (n < 64 && (n * 2) * 4 <= room) n *= 2
        return if (room < 8 * 4) 8 else n
    }

    private fun offsetLabel(offset: Int): String =
        offset.toString(16).padStart(8, '0').uppercase()

    private companion object {
        const val OFFSET_COLUMNS = 10 // eight hex digits and the gap after them
        const val HEX = "0123456789ABCDEF"
    }
}
