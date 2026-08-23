package flow.view

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ViewCanvas
import flow.extension.ViewEvent
import flow.extension.ViewExtension

/**
 * The data as a hex dump: offset, bytes, and the printable characters beside them.
 *
 * The number of bytes per row is chosen to fit the width rather than fixed at 16, so a narrow panel
 * still lines up and a wide one is not mostly empty. Powers of two only — a row of 13 makes the
 * offsets useless for finding anything.
 *
 * Clicking a byte says exactly which one it is and what it holds. Working an offset out by counting
 * columns is the tedious part of reading a dump, and it is the part the view can just do.
 */
class HexView : ViewExtension {
    override val id = "flow.view.hex"
    override val displayName = "Hex View"
    override val version = "1.1.0"
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
        val selected = options[SELECTED]?.toIntOrNull()?.takeIf { it in 0 until shown }

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

            // the hex column is the target; the offset and character columns are there to be read
            canvas.region(
                grid.x(hexStart), y, grid.x(asciiStart) - grid.x(hexStart), grid.lineHeight,
                regionId(start, grid.x(hexStart), grid.charWidth),
            )

            if (selected != null && selected in start until end) {
                val column = hexStart + (selected - start) * 3
                canvas.rect(
                    grid.x(column) - 1f, y - 1f, grid.charWidth * 2 + 2f, grid.lineHeight,
                    ViewCanvas.ACCENT, filled = false,
                )
            }
        }

        var total = rows
        if (selected != null) {
            total += 2
            val b = data[selected].toInt() and 0xFF
            val note = "offset ${selected} (0x${selected.toString(16).uppercase()})  " +
                "= 0x${"%02X".format(b)}  ${b}  '${if (b in 0x20..0x7E) b.toChar() else '.'}'"
            canvas.text(grid.x(0), grid.y(rows + 1), note, grid.fontSize, ViewCanvas.ACCENT, mono = true)
        }
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

    override fun onEvent(event: ViewEvent, options: Map<String, String>): Map<String, String> {
        if (event.kind != ViewEvent.CLICK) return options
        val parts = event.region?.split('|')?.takeIf { it.size == 4 && it[0] == "r" } ?: return options
        val rowStart = parts[1].toIntOrNull() ?: return options
        val originX = parts[2].toFloatOrNull() ?: return options
        val charWidth = parts[3].toFloatOrNull()?.takeIf { it > 0f } ?: return options

        // three characters to a byte in the hex column, so the click's distance along the row is
        // what says which byte it landed on
        val column = ((event.x - originX) / charWidth).toInt()
        val byte = rowStart + (column / 3).coerceAtLeast(0)
        // clicking the selected byte again clears it, so there is a way back to a plain dump
        return if (options[SELECTED] == byte.toString()) options - SELECTED
        else options + (SELECTED to byte.toString())
    }

    /**
     * A row's name carries the layout that reads a click back into a byte.
     *
     * A view keeps nothing between drawing and being clicked — it may not even be the same call on
     * the same thread — so what [onEvent] needs to do the arithmetic travels in the name rather
     * than in a field that a second drawing would overwrite.
     */
    private fun regionId(start: Int, originX: Float, charWidth: Float) = "r|$start|$originX|$charWidth"

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
        /** The offset of the byte the user picked out, if any. */
        const val SELECTED = "hex.selected"

        const val OFFSET_COLUMNS = 10 // eight hex digits and the gap after them
        const val HEX = "0123456789ABCDEF"
    }
}
