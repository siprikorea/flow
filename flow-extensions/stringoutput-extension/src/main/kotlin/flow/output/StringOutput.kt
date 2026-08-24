package flow.output

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.OutputCanvas
import flow.extension.OutputExtension
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The data as text.
 *
 * Bytes that are not valid in the chosen encoding are shown as the replacement character rather
 * than failing the whole view: seeing where text stops being text is usually the thing being
 * looked for.
 */
class StringOutput : OutputExtension {
    override val id = "flow.output.string"
    override val displayName = "String Output"
    override val version = "1.1.0"
    override val options = listOf(
        ExtensionOption("encoding", OptionType.SELECT, "UTF-8", listOf("UTF-8", "US-ASCII", "ISO-8859-1", "UTF-16")),
        ExtensionOption("wrap", OptionType.SELECT, "true", listOf("true", "false")),
    )

    override fun draw(canvas: OutputCanvas, data: ByteArray, options: Map<String, String>) {
        val grid = Grid(canvas)
        val shown = data.size.coerceAtMost(MAX_BYTES)
        val charset = charsetOf(options["encoding"])
        val text = String(data, 0, shown, charset)
        val wrap = options["wrap"] != "false"

        val lines = ArrayList<String>()
        text.split("\n").forEach { line ->
            val clean = line.trimEnd('\r')
            if (!wrap || clean.length <= grid.columns) {
                lines.add(clean)
            } else {
                // hard wrap at the column count: this is a data view, so a break in the middle of a
                // word is better than text running off where it cannot be read
                var i = 0
                while (i < clean.length) {
                    val end = (i + grid.columns).coerceAtMost(clean.length)
                    lines.add(clean.substring(i, end))
                    i = end
                }
            }
        }
        truncationNote(shown, data.size)?.let { lines.add(""); lines.add(it) }

        lines.forEachIndexed { row, line ->
            val muted = line.startsWith("… showing")
            canvas.text(
                grid.x(0), grid.y(row), line, grid.fontSize,
                if (muted) OutputCanvas.MUTED_TEXT else OutputCanvas.DEFAULT_TEXT,
                mono = true,
            )
        }
        canvas.contentHeight(grid.height(lines.size))
    }

    private fun charsetOf(name: String?): Charset =
        runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(StandardCharsets.UTF_8)
}
