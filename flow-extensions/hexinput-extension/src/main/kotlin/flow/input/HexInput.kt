package flow.input

import flow.extension.ExtensionOption
import flow.extension.InputExtension
import flow.extension.OptionType

/**
 * Bytes written as hex digits.
 *
 * How you type a key, an IV, a digest — anything whose value is the bytes themselves rather than
 * text that stands for them.
 *
 * Reading is deliberately forgiving: whitespace anywhere, an optional 0x, upper or lower case, and
 * a trailing lone digit taken as a half-written byte rather than as a mistake. All of those happen
 * on the way to a value that is fine, and stopping on them would mean the editor fought back on
 * every other keystroke. What is genuinely not a hex digit is reported as a note instead.
 */
class HexInput : InputExtension {
    override val id = "flow.input.hex"
    override val displayName = "Hex Input"
    override val version = "1.0.0"
    override val options = listOf(
        // how the bytes are written back out; what is read in accepts all of these regardless
        ExtensionOption("grouping", OptionType.SELECT, "bytes", listOf("bytes", "none", "words")),
        ExtensionOption("case", OptionType.SELECT, "upper", listOf("upper", "lower")),
    )

    // two digits and the space after them, which is what "bytes" grouping writes
    override val charsPerByte = 3

    override fun parse(text: String, options: Map<String, String>): ByteArray {
        val digits = digitsOf(text)
        // an odd count means the last byte is still being typed: take the digit as its low nibble
        val out = ByteArray(digits.length / 2 + digits.length % 2)
        var i = 0
        var at = 0
        while (i < digits.length) {
            val high = value(digits[i])
            val low = if (i + 1 < digits.length) value(digits[i + 1]) else 0
            out[at++] = if (i + 1 < digits.length) ((high shl 4) or low).toByte() else high.toByte()
            i += 2
        }
        return out
    }

    override fun format(data: ByteArray, options: Map<String, String>): String {
        val alphabet = if (options["case"] == "lower") "0123456789abcdef" else "0123456789ABCDEF"
        val out = StringBuilder(data.size * 3)
        data.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            when (options["grouping"] ?: "bytes") {
                "none" -> Unit
                "words" -> if (i > 0 && i % 4 == 0) out.append(' ')
                else -> if (i > 0) out.append(' ')
            }
            out.append(alphabet[v shr 4]).append(alphabet[v and 0x0F])
        }
        return out.toString()
    }

    override fun problem(text: String, options: Map<String, String>): String? {
        val stray = stripped(text).firstOrNull { !it.isHexDigit() } ?: return null
        return "'$stray' is not a hex digit"
    }

    /** The hex digits in [text], with everything that is only there for readability taken out. */
    private fun digitsOf(text: String) = stripped(text).filter { it.isHexDigit() }

    private fun stripped(text: String) =
        text.filterNot { it.isWhitespace() }.replace("0x", "", ignoreCase = true)

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun value(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }
}
