package flow.io

import flow.ui.io.Builtin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ways of reading and writing bytes that Flow always has.
 *
 * The property everything else rests on is that formatting bytes and parsing the result gives the
 * same bytes back. The editor formats and parses constantly, so a break there would corrupt a value
 * rather than merely look wrong.
 */
class BuiltinTest {

    /* ───────── hex ───────── */

    @Test
    fun `hex digits are the bytes they spell`() {
        val (bytes, problem) = Builtin.parse(Builtin.HEX, "00 41 FF", "UTF-8")
        assertContentEquals(byteArrayOf(0x00, 0x41, 0xFF.toByte()), bytes)
        assertNull(problem)
    }

    @Test
    fun `hex survives the round trip for any byte at all`() {
        val data = ByteArray(256) { it.toByte() }
        val (back, _) = Builtin.parse(Builtin.HEX, Builtin.format(Builtin.HEX, data, "UTF-8"), "UTF-8")
        assertContentEquals(data, back)
    }

    @Test
    fun `reading hex is forgiving about how it was written`() {
        val expected = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        listOf("DEADBEEF", "de ad be ef", "DE AD BE EF", "0xDEADBEEF", "  DEAD\n BEEF ", "dEaDbEeF")
            .forEach { assertContentEquals(expected, Builtin.parse(Builtin.HEX, it, "UTF-8").first, "reading '$it'") }
    }

    @Test
    fun `a half-typed byte is taken as what is there`() {
        // typing "41 4" on the way to "41 42": a byte being written, not an error
        val (bytes, problem) = Builtin.parse(Builtin.HEX, "41 4", "UTF-8")
        assertContentEquals(byteArrayOf(0x41, 0x04), bytes)
        assertNull(problem, "a half-typed byte was reported as wrong")
    }

    @Test
    fun `a character that is not a hex digit is reported, and the rest still reads`() {
        val (bytes, problem) = Builtin.parse(Builtin.HEX, "41 4Q 42", "UTF-8")
        assertNotNull(problem)
        assertTrue(problem.contains("Q"), problem)
        // the note is beside the value, not instead of it
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `nothing typed is no bytes, not one empty one`() {
        assertContentEquals(ByteArray(0), Builtin.parse(Builtin.HEX, "", "UTF-8").first)
        assertContentEquals(ByteArray(0), Builtin.parse(Builtin.HEX, "   ", "UTF-8").first)
        assertEquals("", Builtin.format(Builtin.HEX, ByteArray(0), "UTF-8"))
    }

    @Test
    fun `the width hex declares is the width it writes`() {
        // the editor divides a caret position by this to find the byte, so it has to match
        val text = Builtin.format(Builtin.HEX, ByteArray(8) { it.toByte() }, "UTF-8")
        assertEquals(Builtin.charsPerByte(Builtin.HEX), (text.length + 1) / 8)
    }

    /* ───────── text ───────── */

    @Test
    fun `text is the bytes of that text`() {
        assertContentEquals("hello".encodeToByteArray(), Builtin.parse(Builtin.STRING, "hello", "UTF-8").first)
    }

    @Test
    fun `text survives the round trip for what text can hold`() {
        listOf("hello", "안녕하세요", "line\nbreak", "", "  spaced  ").forEach { text ->
            val bytes = Builtin.parse(Builtin.STRING, text, "UTF-8").first
            assertEquals(text, Builtin.format(Builtin.STRING, bytes, "UTF-8"), "round trip of '$text'")
        }
    }

    @Test
    fun `the encoding is the user's choice, and it changes the bytes`() {
        assertEquals(2, Builtin.parse(Builtin.STRING, "é", "UTF-8").first.size)
        assertEquals(1, Builtin.parse(Builtin.STRING, "é", "ISO-8859-1").first.size)
    }

    @Test
    fun `a character the encoding cannot write is reported`() {
        val problem = Builtin.parse(Builtin.STRING, "안녕", "ISO-8859-1").second
        assertNotNull(problem, "writing Korean as ISO-8859-1 was not flagged")
        assertTrue(problem.contains("ISO-8859-1"), problem)
    }

    @Test
    fun `text the encoding can write is not flagged`() {
        assertNull(Builtin.parse(Builtin.STRING, "plain ascii", "ISO-8859-1").second)
        assertNull(Builtin.parse(Builtin.STRING, "안녕하세요", "UTF-8").second)
    }

    @Test
    fun `an encoding this build has never heard of falls back rather than failing`() {
        assertContentEquals(
            "hi".encodeToByteArray(),
            Builtin.parse(Builtin.STRING, "hi", "NOT-A-CHARSET").first,
        )
    }

    @Test
    fun `text is not fixed width and says so`() {
        assertEquals(0, Builtin.charsPerByte(Builtin.STRING))
    }
}
