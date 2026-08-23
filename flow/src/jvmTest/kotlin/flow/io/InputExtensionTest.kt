package flow.io

import flow.input.HexInput
import flow.input.StringInput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shipped inputs: how a value going into a flow is written.
 *
 * The one property everything else rests on is that formatting bytes and parsing the result gives
 * the same bytes back. Without it, opening a value and closing it again would change it — and the
 * editor formats and parses constantly, so a break there would corrupt data rather than merely look
 * wrong.
 */
class InputExtensionTest {

    /* ───────── String Input ───────── */

    @Test
    fun `text is the bytes of that text`() {
        assertContentEquals("hello".encodeToByteArray(), StringInput().parse("hello", emptyMap()))
    }

    @Test
    fun `bytes read back as the text they came from`() {
        val input = StringInput()
        listOf("hello", "안녕하세요", "line\nbreak", "", "  spaced  ").forEach { text ->
            assertEquals(text, input.format(input.parse(text, emptyMap()), emptyMap()), "round trip of '$text'")
        }
    }

    @Test
    fun `the encoding is the user's choice, and it changes the bytes`() {
        val input = StringInput()
        val utf8 = input.parse("é", mapOf("encoding" to "UTF-8"))
        val latin1 = input.parse("é", mapOf("encoding" to "ISO-8859-1"))
        assertEquals(2, utf8.size)
        assertEquals(1, latin1.size)
    }

    @Test
    fun `a character the encoding cannot write is reported`() {
        val problem = StringInput().problem("안녕", mapOf("encoding" to "ISO-8859-1"))
        assertNotNull(problem, "writing Korean as ISO-8859-1 was not flagged")
        assertTrue(problem.contains("ISO-8859-1"), problem)
    }

    @Test
    fun `text the encoding can write is not flagged`() {
        assertNull(StringInput().problem("plain ascii", mapOf("encoding" to "ISO-8859-1")))
        assertNull(StringInput().problem("안녕하세요", mapOf("encoding" to "UTF-8")))
    }

    @Test
    fun `an encoding this build has never heard of falls back rather than failing`() {
        val input = StringInput()
        assertContentEquals("hi".encodeToByteArray(), input.parse("hi", mapOf("encoding" to "NOT-A-CHARSET")))
    }

    /* ───────── Hex Input ───────── */

    @Test
    fun `hex digits are the bytes they spell`() {
        assertContentEquals(byteArrayOf(0x00, 0x41, 0xFF.toByte()), HexInput().parse("00 41 FF", emptyMap()))
    }

    @Test
    fun `bytes read back as the hex they came from`() {
        val input = HexInput()
        val data = ByteArray(64) { it.toByte() }
        listOf("bytes", "none", "words").forEach { grouping ->
            val options = mapOf("grouping" to grouping)
            assertContentEquals(data, input.parse(input.format(data, options), options), "round trip with $grouping")
        }
    }

    @Test
    fun `reading is forgiving about how it was written`() {
        val expected = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        listOf("DEADBEEF", "de ad be ef", "DE AD BE EF", "0xDEADBEEF", "  DEAD\n BEEF ", "dEaDbEeF")
            .forEach { assertContentEquals(expected, HexInput().parse(it, emptyMap()), "reading '$it'") }
    }

    @Test
    fun `a half-typed byte is taken as what is there`() {
        // typing "41 4" on the way to "41 42": the last digit is a byte being written, not an error
        assertContentEquals(byteArrayOf(0x41, 0x04), HexInput().parse("41 4", emptyMap()))
        assertNull(HexInput().problem("41 4", emptyMap()), "a half-typed byte was reported as wrong")
    }

    @Test
    fun `a character that is not a hex digit is reported`() {
        val problem = HexInput().problem("41 4Q", emptyMap())
        assertNotNull(problem)
        assertTrue(problem.contains("Q"), problem)
    }

    @Test
    fun `case and grouping are how it is written, not how it is read`() {
        val data = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
        assertEquals("0A 0B 0C 0D 0E", HexInput().format(data, mapOf("grouping" to "bytes", "case" to "upper")))
        assertEquals("0a0b0c0d0e", HexInput().format(data, mapOf("grouping" to "none", "case" to "lower")))
        assertEquals("0A0B0C0D 0E", HexInput().format(data, mapOf("grouping" to "words", "case" to "upper")))
    }

    @Test
    fun `nothing typed is no bytes, not one empty one`() {
        assertContentEquals(ByteArray(0), HexInput().parse("", emptyMap()))
        assertContentEquals(ByteArray(0), HexInput().parse("   ", emptyMap()))
        assertEquals("", HexInput().format(ByteArray(0), emptyMap()))
    }

    @Test
    fun `the width it declares is the width it writes`() {
        val input = HexInput()
        // the editor divides a caret position by this to find the byte, so it has to match the
        // spacing that format() actually produces
        val text = input.format(ByteArray(8) { it.toByte() }, mapOf("grouping" to "bytes"))
        assertEquals(input.charsPerByte, (text.length + 1) / 8)
    }

    @Test
    fun `text is not fixed width and says so`() {
        assertEquals(0, StringInput().charsPerByte)
    }
}
