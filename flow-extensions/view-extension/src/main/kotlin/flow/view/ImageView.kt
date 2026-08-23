package flow.view

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ViewCanvas
import flow.extension.ViewExtension
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The data as a picture — for a module whose output is an encoded image, such as a QR code.
 *
 * The bytes are passed through as they are rather than re-encoded; all this view works out is how
 * big to draw them. The size comes from the image's own header, so the aspect ratio is the image's
 * and not a guess.
 */
class ImageView : ViewExtension {
    override val id = "flow.view.image"
    override val displayName = "Image View"
    override val version = "1.1.0"
    override val options = listOf(
        ExtensionOption("fit", OptionType.SELECT, "contain", listOf("contain", "actual")),
    )

    override fun draw(canvas: ViewCanvas, data: ByteArray, options: Map<String, String>) {
        val grid = Grid(canvas)
        if (data.isEmpty()) {
            canvas.text(grid.x(0), grid.y(0), "(no data)", grid.fontSize, ViewCanvas.MUTED_TEXT, mono = true)
            canvas.contentHeight(grid.height(1))
            return
        }

        val size = readSize(data)
            ?: error("this is not an image format that can be shown (${data.size} bytes)")
        val (width, height) = size

        val available = (canvas.width - grid.pad * 2).coerceAtLeast(1f)
        // "actual" still comes down to fit rather than overflow sideways, since there is nothing to
        // scroll horizontally against — only the height is scrollable
        val scale = when {
            options["fit"] == "actual" -> minOf(1f, available / width)
            else -> available / width
        }
        val drawWidth = width * scale
        val drawHeight = height * scale

        canvas.image(grid.pad, grid.pad, drawWidth, drawHeight, data)
        val caption = "$width × $height"
        canvas.text(
            grid.pad, grid.pad + drawHeight + 6f, caption, 11f, ViewCanvas.MUTED_TEXT, mono = true,
        )
        canvas.contentHeight(grid.pad * 2 + drawHeight + 6f + grid.lineHeight)
    }

    /** Reads the header only — decoding a large image just to find out how wide it is is waste. */
    private fun readSize(data: ByteArray): Pair<Float, Float>? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = stream
                reader.getWidth(reader.minIndex).toFloat() to reader.getHeight(reader.minIndex).toFloat()
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}
