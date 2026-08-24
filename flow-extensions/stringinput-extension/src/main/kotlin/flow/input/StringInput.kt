package flow.input

import flow.extension.ExtensionOption
import flow.extension.InputExtension
import flow.extension.OptionType
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Bytes written as text.
 *
 * The plainest way in: what is typed is what the flow gets, encoded. The encoding is an option
 * because "the text ABC" is not one sequence of bytes — it is a different one in UTF-8, UTF-16 and
 * ISO-8859-1, and which is meant is the user's to say.
 */
class StringInput : InputExtension {
    override val id = "flow.input.string"
    override val displayName = "String Input"
    override val version = "1.0.0"
    override val options = listOf(
        ExtensionOption("encoding", OptionType.SELECT, "UTF-8", ENCODINGS),
    )

    override fun parse(text: String, options: Map<String, String>): ByteArray =
        text.toByteArray(charsetOf(options))

    override fun format(data: ByteArray, options: Map<String, String>): String =
        String(data, charsetOf(options))

    /**
     * Text that does not survive the round trip.
     *
     * Typing a character the chosen encoding has no room for — a Korean syllable in ISO-8859-1 —
     * turns it into a question mark on the way to bytes, and the value is quietly not what is on
     * screen. Better to say so than to let it pass.
     */
    override fun problem(text: String, options: Map<String, String>): String? {
        val charset = charsetOf(options)
        val roundTrip = String(text.toByteArray(charset), charset)
        if (roundTrip == text) return null
        val lost = text.indices.firstOrNull { it >= roundTrip.length || roundTrip[it] != text[it] }
        val character = lost?.let { text[it] } ?: return "some characters cannot be written as ${charset.name()}"
        return "'$character' cannot be written as ${charset.name()}"
    }

    private fun charsetOf(options: Map<String, String>): Charset =
        runCatching { Charset.forName(options["encoding"] ?: "UTF-8") }.getOrDefault(StandardCharsets.UTF_8)

    private companion object {
        val ENCODINGS = listOf("UTF-8", "US-ASCII", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE")
    }
}
