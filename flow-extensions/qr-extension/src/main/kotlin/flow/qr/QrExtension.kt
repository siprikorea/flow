package flow.qr

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Turns the data on "in" into a QR code image on "out".
 *
 * The output is a PNG, which the Image View shows on the canvas — so a flow can end in something
 * that can be pointed a phone at rather than in bytes that have to be read somewhere else.
 */
class QrExtension : ProcessorExtension {
    override val id = "flow.qr"
    override val displayName = "QR Code"
    override val version = "1.0.2"
    override val category = "other"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        // correction level: how much of the symbol can be damaged and still read. Higher costs
        // capacity, so L is the default and H is there for a code that will be printed and handled.
        ExtensionOption("correction", OptionType.SELECT, "L", listOf("L", "M", "Q", "H")),
        ExtensionOption("moduleSize", OptionType.NUMBER, "8"),
        // the light border a scanner needs to find the symbol's edges; four modules is the minimum
        // the spec allows and anything less makes a code that will not read against a dark page
        ExtensionOption("quietZone", OptionType.NUMBER, "4"),
    )

    override val portDescriptions = mapOf(
        "_module" to "Encode the input as a QR code image. Use it to hand something to a phone — a TOTP secret, a URL. The output is a PNG, meant for the Image view or a file.",
        "in" to "The text to encode. Longer text needs a denser code.",
        "out" to "A PNG image.",
    )

    override val optionDescriptions = mapOf(
        "correction" to "How much of the code can be damaged and still read: L about 7 percent, up to H about 30. Higher correction makes the code denser for the same text.",
        "moduleSize" to "Pixels per QR module — how large the image comes out.",
        "quietZone" to "Modules of blank margin. The standard says 4; less and some scanners fail.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: ByteArray(0)
        require(data.isNotEmpty()) { "no data on 'in'" }

        val qr = QrCode.encode(data, QrCode.Ecc.of(options["correction"]))
        val scale = options["moduleSize"]?.trim()?.toIntOrNull()?.coerceIn(1, 40) ?: 8
        val quiet = options["quietZone"]?.trim()?.toIntOrNull()?.coerceIn(0, 16) ?: 4

        val pixels = (qr.size + quiet * 2) * scale
        val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until pixels) {
            for (x in 0 until pixels) {
                val mx = x / scale - quiet
                val my = y / scale - quiet
                val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.isDark(mx, my)
                image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
            }
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return mapOf("out" to out.toByteArray())
    }
}
