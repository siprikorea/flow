package flow.modules

import flow.view.ImageMeta
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the image viewer says a picture is.
 *
 * Read by hand out of the file — PNG chunks, JPEG segments, the TIFF directory that EXIF is — so
 * the tests are written against files something else produced: ImageIO writes the PNG and the JPEG,
 * and the EXIF block is built byte by byte to the spec rather than by the code being tested.
 *
 * Half of this is about not trusting the bytes. They arrive from a module and may be truncated,
 * padded, or not a picture at all, and a viewer that throws on one is a window that does not open.
 */
class ImageMetaTest {

    private fun meta(data: ByteArray): Map<String, Map<String, String>> =
        ImageMeta.read(data).associate { it.title to it.rows.toMap() }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { ImageIO.write(image, "jpg", it); it.toByteArray() }
    }

    @Test
    fun `a PNG reports its own header`() {
        val sections = meta(png(64, 48))
        val file = assertNotNull(sections["file"])
        assertEquals("PNG", file["format"])
        assertEquals("64 x 48", file["pixels"])
        assertEquals("8 bit", file["depth"])
        assertTrue(file["colour"] in listOf("RGB", "RGBA"), "unexpected colour type ${file["colour"]}")
    }

    @Test
    fun `a JPEG reports the frame header`() {
        val sections = meta(jpeg(120, 80))
        val file = assertNotNull(sections["file"])
        assertEquals("JPEG", file["format"])
        assertEquals("120 x 80", file["pixels"])
        assertEquals("8 bit", file["depth"])
        assertEquals("3", file["components"])
    }

    /** The text chunks are where a PNG keeps what a person wrote about it. */
    @Test
    fun `PNG text chunks are read`() {
        val withText = insertChunk(png(8, 8), "tEXt", "Software".encodeToByteArray() + byteArrayOf(0) + "Flow".encodeToByteArray())
        val text = assertNotNull(meta(withText)["text"])
        assertEquals("Flow", text["Software"])
    }

    /* ───────── EXIF ───────── */

    /**
     * A JPEG with an EXIF block built to the spec: a TIFF header, an image directory naming a
     * camera, and an EXIF sub-directory with the exposure in it.
     */
    @Test
    fun `EXIF says what took the picture and how`() {
        val exif = ExifFixture(
            image = listOf(
                0x010F to ExifFixture.text("Flow Cameras"),
                0x0110 to ExifFixture.text("Model One"),
                0x0112 to ExifFixture.short(6), // 90 degrees clockwise
            ),
            exif = listOf(
                0x9003 to ExifFixture.text("2026:09:19 10:11:12"),
                0x829A to ExifFixture.rational(1, 250),
                0x829D to ExifFixture.rational(28, 10),
                0x8827 to ExifFixture.short(400),
                0x920A to ExifFixture.rational(35, 1),
            ),
        ).build()

        val sections = meta(withExif(jpeg(16, 16), exif))
        val camera = assertNotNull(sections["camera"], "no camera section: ${sections.keys}")
        assertEquals("Flow Cameras", camera["make"])
        assertEquals("Model One", camera["model"])
        assertEquals("90 CW", camera["orientation"], "the orientation is a number, not a direction")

        val exposure = assertNotNull(sections["exposure"], "no exposure section: ${sections.keys}")
        assertEquals("2026:09:19 10:11:12", exposure["taken"])
        assertEquals("1/250 s", exposure["exposure"], "a shutter speed reads as a fraction of a second")
        assertEquals("f/2.8", exposure["aperture"])
        assertEquals("400", exposure["ISO"])
        assertEquals("35 mm", exposure["focal length"])
    }

    /** Degrees, minutes and seconds, as a GPS tag writes them. */
    @Test
    fun `EXIF location is read as coordinates`() {
        val exif = ExifFixture(
            image = listOf(0x010F to ExifFixture.text("Flow Cameras")),
            gps = listOf(
                1 to ExifFixture.text("N"),
                2 to ExifFixture.rationals(37 to 1, 33 to 1, 0 to 1),
                3 to ExifFixture.text("E"),
                4 to ExifFixture.rationals(126 to 1, 58 to 1, 30 to 1),
            ),
        ).build()

        val location = assertNotNull(meta(withExif(jpeg(16, 16), exif))["location"])
        val coordinates = assertNotNull(location["coordinates"])
        assertTrue(coordinates.startsWith("37.550000 N"), coordinates)
        assertTrue(coordinates.contains("126.975000 E"), coordinates)
    }

    /* ───────── bytes that are not what they claim ───────── */

    @Test
    fun `a file cut in half still says what it can`() {
        val whole = png(64, 48)
        val half = whole.copyOfRange(0, whole.size / 2)
        val file = assertNotNull(meta(half)["file"])
        assertEquals("PNG", file["format"], "the header is still there and still readable")
        assertEquals("64 x 48", file["pixels"])
    }

    @Test
    fun `bytes that are not an image at all are described, not refused`() {
        val sections = meta(ByteArray(64) { it.toByte() })
        val file = assertNotNull(sections["file"])
        assertEquals("unknown", file["format"])
        assertEquals("64 B", file["bytes"])
    }

    @Test
    fun `an EXIF block that lies about its offsets is survived`() {
        val exif = ExifFixture(image = listOf(0x010F to ExifFixture.text("Flow Cameras"))).build()
        // point the first directory somewhere past the end of the block
        val broken = exif.copyOf().also {
            it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte(); it[6] = 0xFF.toByte(); it[7] = 0xFF.toByte()
        }
        val sections = meta(withExif(jpeg(16, 16), broken))
        assertEquals("JPEG", assertNotNull(sections["file"])["format"], "a broken EXIF block took the whole read down")
    }

    @Test
    fun `an empty input is not an error`() {
        assertEquals("unknown", assertNotNull(meta(ByteArray(0))["file"])["format"])
    }

    /* ───────── building the fixtures ───────── */

    /** Puts an APP1 EXIF segment into a JPEG, right after the SOI, where a camera writes it. */
    private fun withExif(jpeg: ByteArray, exif: ByteArray): ByteArray {
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + exif
        val length = payload.size + 2
        return jpeg.copyOfRange(0, 2) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
            payload +
            jpeg.copyOfRange(2, jpeg.size)
    }

    /** Puts a chunk into a PNG after the header, with the CRC a reader would check. */
    private fun insertChunk(png: ByteArray, type: String, body: ByteArray): ByteArray {
        val at = 8 + 25 // signature plus IHDR
        val crc = java.util.zip.CRC32().apply { update(type.encodeToByteArray()); update(body) }.value
        val chunk = byteArrayOf(
            (body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(),
        ) + type.encodeToByteArray() + body + byteArrayOf(
            (crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte(),
        )
        return png.copyOfRange(0, at) + chunk + png.copyOfRange(at, png.size)
    }
}
