package flow.json

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType

/**
 * Re-formats JSON: parses "in" and writes it back out indented.
 *
 * The parser is written here rather than pulled in, so the module stays a single jar with nothing
 * to collide with. It is only asked to recognise well-formed JSON — anything it cannot read is
 * reported with the offset, which is more use than a generic "invalid JSON".
 */
class JsonFormatExtension : ModuleExtension {
    override val id = "flow.json"
    override val displayName = "JSON Format"
    override val version = "1.0.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("indent", OptionType.SELECT, "2", listOf("2", "4", "tab")),
        // sorting makes two documents comparable; off by default, since order can carry meaning
        ExtensionOption("sortKeys", OptionType.SELECT, "false", listOf("false", "true")),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val text = (inputs["in"] ?: ByteArray(0)).decodeToString()
        if (text.isBlank()) return mapOf("out" to ByteArray(0))
        val indent = when (options["indent"]) {
            "4" -> "    "
            "tab" -> "\t"
            else -> "  "
        }
        val sort = options["sortKeys"] == "true"
        val value = JsonReader(text).readDocument()
        return mapOf("out" to render(value, indent, sort, 0).encodeToByteArray())
    }

    private fun render(value: Any?, indent: String, sort: Boolean, depth: Int): String {
        val pad = indent.repeat(depth)
        val inner = indent.repeat(depth + 1)
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is String -> quote(value)
            is RawNumber -> value.text
            is List<*> ->
                if (value.isEmpty()) "[]"
                else value.joinToString(",\n", "[\n", "\n$pad]") { inner + render(it, indent, sort, depth + 1) }
            is Map<*, *> -> {
                if (value.isEmpty()) return "{}"
                val entries = value.entries.let { if (sort) it.sortedBy { e -> e.key as String } else it.toList() }
                entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
                    inner + quote(k as String) + ": " + render(v, indent, sort, depth + 1)
                }
            }
            else -> quote(value.toString())
        }
    }

    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        s.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}

/** A number kept exactly as written, so formatting never changes a value's precision. */
internal class RawNumber(val text: String)

/** Just enough JSON to read a document and say where it stopped making sense. */
internal class JsonReader(private val src: String) {
    private var i = 0

    fun readDocument(): Any? {
        val value = readValue()
        skipSpace()
        if (i < src.length) fail("unexpected text after the document")
        return value
    }

    private fun fail(what: String): Nothing =
        throw IllegalArgumentException("invalid JSON at offset $i: $what")

    private fun skipSpace() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun expect(c: Char) {
        skipSpace()
        if (i >= src.length || src[i] != c) fail("expected '$c'")
        i++
    }

    private fun readValue(): Any? {
        skipSpace()
        if (i >= src.length) fail("the document ends early")
        return when (val c = src[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't', 'f' -> readKeyword()
            'n' -> readKeyword()
            else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipSpace()
        if (i < src.length && src[i] == '}') { i++; return map }
        while (true) {
            skipSpace()
            val key = readString()
            expect(':')
            map[key] = readValue()
            skipSpace()
            if (i >= src.length) fail("the object is never closed")
            when (src[i]) {
                ',' -> i++
                '}' -> { i++; return map }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipSpace()
        if (i < src.length && src[i] == ']') { i++; return list }
        while (true) {
            list.add(readValue())
            skipSpace()
            if (i >= src.length) fail("the array is never closed")
            when (src[i]) {
                ',' -> i++
                ']' -> { i++; return list }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (i >= src.length) fail("the string is never closed")
            when (val c = src[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= src.length) fail("the escape is never finished")
                    when (val e = src[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > src.length) fail("the \\u escape is too short")
                            val hex = src.substring(i, i + 4)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: fail("'\\u$hex' is not hex"))
                            i += 4
                        }
                        else -> fail("unknown escape '\\$e'")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): RawNumber {
        val start = i
        if (i < src.length && src[i] == '-') i++
        while (i < src.length && (src[i].isDigit() || src[i] in ".eE+-")) i++
        val text = src.substring(start, i)
        if (text.toDoubleOrNull() == null) fail("'$text' is not a number")
        return RawNumber(text)
    }

    private fun readKeyword(): Any? {
        listOf("true" to true, "false" to false, "null" to null).forEach { (word, value) ->
            if (src.startsWith(word, i)) { i += word.length; return value }
        }
        fail("unexpected keyword")
    }
}
