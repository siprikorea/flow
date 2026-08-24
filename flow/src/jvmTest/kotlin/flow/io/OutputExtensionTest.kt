package flow.io

import flow.extension.OutputCanvas
import flow.qr.QrExtension
import flow.output.Asn1Output
import flow.output.HexOutput
import flow.output.ImageOutput
import flow.output.StringOutput
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped views, against the data they exist to make sense of.
 *
 * A view is judged by what it draws, so these read the recorded calls rather than a return value —
 * the same calls the app replays, which is what the user ends up looking at.
 */
class OutputExtensionTest {

    /* ───────── String View ───────── */

    @Test
    fun `text is drawn line by line`() {
        val canvas = FakeCanvas()
        StringOutput().draw(canvas, "one\ntwo\nthree".encodeToByteArray(), emptyMap())
        assertEquals(listOf("one", "two", "three"), canvas.lines)
    }

    @Test
    fun `a carriage return is not drawn as a character`() {
        val canvas = FakeCanvas()
        StringOutput().draw(canvas, "a\r\nb".encodeToByteArray(), emptyMap())
        assertEquals(listOf("a", "b"), canvas.lines)
    }

    @Test
    fun `a long line is wrapped to fit the width it was given`() {
        // 120 points of text width with 0.6-em characters at size 12: room for 16 columns per row
        val canvas = FakeCanvas(width = 120f + 20f)
        StringOutput().draw(canvas, "x".repeat(50).encodeToByteArray(), emptyMap())
        val columns = 16
        assertTrue(canvas.lines.size > 1, "a 50-character line was not wrapped")
        canvas.lines.forEach { assertTrue(it.length <= columns, "'$it' is wider than $columns columns") }
        assertEquals("x".repeat(50), canvas.lines.joinToString(""))
    }

    @Test
    fun `wrapping can be turned off`() {
        val canvas = FakeCanvas(width = 140f)
        StringOutput().draw(canvas, "x".repeat(50).encodeToByteArray(), mapOf("wrap" to "false"))
        assertEquals(listOf("x".repeat(50)), canvas.lines)
    }

    @Test
    fun `the drawing is as tall as the lines it drew`() {
        val canvas = FakeCanvas()
        StringOutput().draw(canvas, "a\nb\nc".encodeToByteArray(), emptyMap())
        val bottom = canvas.texts.maxOf { it.y }
        assertNotNull(canvas.height)
        assertTrue(canvas.height!! > bottom, "content height ${canvas.height} does not cover the last line at $bottom")
    }

    /* ───────── Hex View ───────── */

    @Test
    fun `hex rows carry the offset the bytes are at`() {
        val canvas = FakeCanvas()
        HexOutput().draw(canvas, ByteArray(48) { it.toByte() }, mapOf("bytesPerRow" to "16"))
        val leftmost = canvas.texts.minOf { it.x }
        val offsets = canvas.texts.filter { it.x == leftmost }.map { it.text }
        assertEquals(listOf("00000000", "00000010", "00000020"), offsets)
    }

    @Test
    fun `hex and characters describe the same bytes`() {
        val canvas = FakeCanvas()
        HexOutput().draw(canvas, "AB!".encodeToByteArray(), mapOf("bytesPerRow" to "16"))
        val drawn = canvas.lines
        assertTrue(drawn.any { it == "41 42 21" }, "hex column was $drawn")
        assertTrue(drawn.any { it == "AB!" }, "character column was $drawn")
    }

    @Test
    fun `a byte outside printable ascii is shown as a dot`() {
        val canvas = FakeCanvas()
        HexOutput().draw(canvas, byteArrayOf(0x41, 0x00, 0x7F), mapOf("bytesPerRow" to "16"))
        // the character column has to stay one character per byte, or it stops lining up with the hex
        assertTrue(canvas.lines.any { it == "A.." }, canvas.lines.toString())
    }

    @Test
    fun `columns line up across every row`() {
        val canvas = FakeCanvas()
        HexOutput().draw(canvas, ByteArray(64) { it.toByte() }, mapOf("bytesPerRow" to "16"))
        // three columns per row: offset, hex, characters — each always at the same x
        val xs = canvas.texts.map { it.x }.distinct()
        assertEquals(3, xs.size, "columns drifted: found ${xs.size} distinct x positions")
    }

    @Test
    fun `a narrow panel still gets whole rows`() {
        val canvas = FakeCanvas(width = 200f)
        HexOutput().draw(canvas, ByteArray(64) { it.toByte() }, emptyMap())
        val hexRows = canvas.texts.filter { it.color == OutputCanvas.DEFAULT_TEXT }
        // auto never picks something that is not a power of two, so offsets stay readable
        val bytesPerRow = hexRows.first().text.split(" ").size
        assertTrue(bytesPerRow in listOf(8, 16, 32), "auto chose $bytesPerRow bytes per row")
    }

    @Test
    fun `no data says so rather than drawing nothing`() {
        val canvas = FakeCanvas()
        HexOutput().draw(canvas, ByteArray(0), emptyMap())
        assertEquals(listOf("(no data)"), canvas.lines)
    }

    /* ───────── ASN.1 View ───────── */

    @Test
    fun `a real public key is read as the structure it is`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        // wide enough that nothing is cut to fit: this is about what is read, not about layout
        val canvas = FakeCanvas(width = 1600f)
        Asn1Output().draw(canvas, key.encoded, emptyMap())
        val text = canvas.lines.joinToString("\n")
        // SubjectPublicKeyInfo ::= SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
        assertTrue(text.contains("SEQUENCE"), text)
        assertTrue(text.contains("rsaEncryption"), "the algorithm OID was not named:\n$text")
        assertTrue(text.contains("BIT STRING"), text)
        assertTrue(text.contains("NULL"), text)
    }

    @Test
    fun `nesting is shown by indentation`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        // wide enough that nothing is cut to fit: this is about what is read, not about layout
        val canvas = FakeCanvas(width = 1600f)
        Asn1Output().draw(canvas, key.encoded, emptyMap())
        val outer = canvas.texts.first { it.text.contains("SEQUENCE") }
        val inner = canvas.texts.first { it.text.contains("rsaEncryption") }
        assertTrue(inner.x > outer.x, "the algorithm identifier was not drawn inside the sequence")
    }

    @Test
    fun `an integer small enough to read is shown as a number`() {
        // INTEGER 42
        val canvas = FakeCanvas()
        Asn1Output().draw(canvas, byteArrayOf(0x02, 0x01, 42), emptyMap())
        assertTrue(canvas.lines.any { it == "INTEGER  42" }, canvas.lines.toString())
    }

    @Test
    fun `a truncated value reports where it went wrong instead of failing`() {
        // a SEQUENCE claiming ten bytes with only two present
        val canvas = FakeCanvas()
        Asn1Output().draw(canvas, byteArrayOf(0x30, 0x0A, 0x02, 0x01), emptyMap())
        assertTrue(canvas.lines.any { it.contains("past the end") }, canvas.lines.toString())
    }

    @Test
    fun `random bytes do not take the view down`() {
        val canvas = FakeCanvas()
        Asn1Output().draw(canvas, ByteArray(64) { (it * 37).toByte() }, emptyMap())
        assertTrue(canvas.lines.isNotEmpty(), "nothing at all was drawn")
    }

    /* ───────── Image View ───────── */

    @Test
    fun `an image is drawn at the size its own header gives`() {
        val png = qr("flow", moduleSize = "4")
        val canvas = FakeCanvas(width = 400f)
        ImageOutput().draw(canvas, png, emptyMap())

        val image = canvas.ops.filterIsInstance<FakeCanvas.Image>().single()
        assertTrue(image.bytes.contentEquals(png), "the bytes were re-encoded rather than passed through")
        // a QR code is square, so whatever the scale the drawn box has to be too
        assertEquals(image.w, image.h, 0.01f, "the aspect ratio was not kept")
        assertTrue(image.w <= 400f, "the image was drawn wider than the panel")
    }

    @Test
    fun `actual size never overflows a panel narrower than the image`() {
        val canvas = FakeCanvas(width = 60f)
        ImageOutput().draw(canvas, qr("flow", moduleSize = "8"), mapOf("fit" to "actual"))
        val image = canvas.ops.filterIsInstance<FakeCanvas.Image>().single()
        assertTrue(image.w <= 60f, "at ${image.w} the image ran off a 60-point panel")
    }

    @Test
    fun `data that is not an image says so`() {
        val canvas = FakeCanvas()
        val failure = assertFailsWith<IllegalStateException> {
            ImageOutput().draw(canvas, "not an image at all".encodeToByteArray(), emptyMap())
        }
        assertTrue(failure.message!!.contains("not an image"), failure.message!!)
    }

    private fun qr(text: String, moduleSize: String): ByteArray =
        QrExtension().process(
            mapOf("in" to text.encodeToByteArray()),
            mapOf("correction" to "L", "moduleSize" to moduleSize, "quietZone" to "4"),
        )["out"]!!
}
