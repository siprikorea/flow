package flow.ui.io

import flow.platform.Platform

/**
 * The two ways of reading and writing bytes that Flow always has.
 *
 * Everything else about the app is installed, and that is the right default — but a flow with no
 * modules at all still has to let a value be typed and a result be read, or there is nothing to
 * install modules *for*. Text and hex are the two that need nothing: hex can carry any byte, and
 * text is what most values are.
 *
 * They are the app's own code rather than modules, so there is no process, no jar and no round
 * trip: typing goes straight to bytes. Anything richer than these is a view module, which is a
 * different thing — it opens its own window rather than living in this panel.
 */
object Builtin {

    const val STRING = "string"
    const val HEX = "hex"

    /** Both, in the order they are offered. */
    val ALL = listOf(STRING, HEX)

    fun name(id: String) = if (id == HEX) "Hex" else "String"

    /* ───────── reading and writing a value ───────── */

    /** The bytes [text] stands for, and what is wrong with it — the note, not a failure. */
    fun parse(id: String, text: String, charset: String): Pair<ByteArray, String?> =
        if (id == HEX) parseHex(text) else Platform.encodeText(text, charset) to textProblem(text, charset)

    /** How [data] reads back in the editor. */
    fun format(id: String, data: ByteArray, charset: String): String =
        if (id == HEX) formatHex(data) else Platform.decodeText(data, charset)

    /**
     * How many characters one byte is written as, or 0 where it is not fixed.
     *
     * The editor uses it to work out which byte the caret is on, which is what lets it show a
     * window onto a value too large to put in a text field all at once.
     */
    fun charsPerByte(id: String) = if (id == HEX) 3 else 0

    /* ───────── hex ───────── */

    /**
     * Deliberately forgiving: whitespace anywhere, an optional 0x, upper or lower case, and a
     * trailing lone digit taken as a half-written byte. All of those happen on the way to a value
     * that is fine, and stopping on them would mean the editor fought back on every other keystroke.
     */
    private fun parseHex(text: String): Pair<ByteArray, String?> {
        val stripped = text.filterNot { it.isWhitespace() }.replace("0x", "", ignoreCase = true)
        val stray = stripped.firstOrNull { !it.isHexDigit() }
        val digits = stripped.filter { it.isHexDigit() }
        val out = ByteArray(digits.length / 2 + digits.length % 2)
        var i = 0
        var at = 0
        while (i < digits.length) {
            val high = hexValue(digits[i])
            val low = if (i + 1 < digits.length) hexValue(digits[i + 1]) else 0
            out[at++] = if (i + 1 < digits.length) ((high shl 4) or low).toByte() else high.toByte()
            i += 2
        }
        return out to stray?.let { "'$it' is not a hex digit" }
    }

    private fun formatHex(data: ByteArray): String {
        val out = StringBuilder(data.size * 3)
        data.forEachIndexed { i, b ->
            if (i > 0) out.append(' ')
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /* ───────── text ───────── */

    /**
     * Text that does not survive the round trip.
     *
     * Typing a character the chosen encoding has no room for — a Korean syllable in ISO-8859-1 —
     * turns it into a question mark on the way to bytes, and the value is quietly not what is on
     * screen. Better to say so than to let it pass.
     */
    private fun textProblem(text: String, charset: String): String? {
        val roundTrip = Platform.decodeText(Platform.encodeText(text, charset), charset)
        if (roundTrip == text) return null
        val lost = text.indices.firstOrNull { it >= roundTrip.length || roundTrip[it] != text[it] }
        val character = lost?.let { text[it] } ?: return "some characters cannot be written as $charset"
        return "'$character' cannot be written as $charset"
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun hexValue(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
