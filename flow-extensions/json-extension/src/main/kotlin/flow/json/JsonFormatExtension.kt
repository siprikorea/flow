package flow.json

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType

/**
 * Reads JSON: re-formats a document, or takes one field out of it.
 *
 * The second is what makes JSON usable in a flow at all. Almost everything that answers in JSON —
 * an MCP tool, an HTTP service, a JWT payload — answers with a document wrapped around the one
 * value the next node wants, and without a way to reach into it the only options are to hash the
 * whole document or to cut it up by byte offset and hope the shape never changes.
 *
 * A path that finds nothing is an error rather than an empty output, and this is the whole point:
 * a renamed field that silently produces nothing is a flow that keeps running and signs, posts or
 * encrypts emptiness. The failure says how far the path got and what was actually there.
 *
 * The parser is written here rather than pulled in, so the module stays a single jar with nothing
 * to collide with. It is only asked to recognise well-formed JSON — anything it cannot read is
 * reported with the offset, which is more use than a generic "invalid JSON".
 */
class JsonFormatExtension : ProcessorExtension {
    override val id = "flow.json"
    override val displayName = "JSON"
    override val version = "1.1.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        // empty means the whole document, which is what this module did before it could extract
        ExtensionOption("path", OptionType.TEXT, ""),
        ExtensionOption("indent", OptionType.SELECT, "2", listOf("2", "4", "tab")),
        // sorting makes two documents comparable; off by default, since order can carry meaning
        ExtensionOption("sortKeys", OptionType.SELECT, "false", listOf("false", "true")),
    )

    override val portDescriptions = mapOf(
        "_module" to "Read JSON: take one field out of a document with 'path', or leave 'path' empty " +
            "to re-format the whole thing. Extracting is what makes a JSON answer usable by the next " +
            "node — a token out of a login response, one field out of an MCP tool's result — because " +
            "a string comes out as its text, ready to be hashed, signed or posted. It never changes " +
            "a value: numbers keep exactly the digits they were written with.",
        "in" to "The JSON text, as UTF-8.",
        "out" to "The value at 'path', or the whole document re-indented when no path is given. A " +
            "string comes out as its own text with no quotes around it; an object or array comes out " +
            "as JSON. A path that finds nothing is an error, not an empty result.",
    )

    override val optionDescriptions = mapOf(
        "path" to "Which field to take, as 'user.name', 'items[0].id', or 'headers[\"content-type\"]' " +
            "for a key with a dot or a bracket in it. Empty means the whole document. Nothing there is " +
            "an error that says how far the path got and what it found instead, so a renamed field " +
            "stops the flow rather than quietly emptying it.",
        "indent" to "Spaces per level, or a tab. Applies to the document, and to an object or array " +
            "taken out of it — a single string or number is not indented at all.",
        "sortKeys" to "Sort object keys, so two documents that differ only in key order come out identical.",
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
        val document = JsonReader(text).readDocument()
        val path = options["path"].orEmpty().trim()
        val value = if (path.isEmpty()) document else JsonPath(path).select(document)
        // A string is handed on as its own text: quoting it would make the next node hash the
        // quotes too, which is the mistake this module exists to spare a flow. Everything else is
        // JSON, which for a number or a boolean is the same characters either way.
        val out = if (path.isNotEmpty() && value is String) value else render(value, indent, sort, 0)
        return mapOf("out" to out.encodeToByteArray())
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

/**
 * One path into a document: `user.name`, `items[0].id`, `headers["content-type"]`.
 *
 * A deliberate subset — no wildcards, no filters, no recursive descent. What a flow needs is to
 * reach a known field in a known shape, and the small grammar means a path that is wrong is wrong
 * in a way that can be explained: it names the step it got to and what was there instead.
 */
internal class JsonPath(private val path: String) {

    fun select(document: Any?): Any? {
        var here: Any? = document
        var reached = ""
        steps().forEach { step ->
            here = step.of(here, reached, path)
            reached = if (reached.isEmpty()) step.shown else reached + step.shown
        }
        return here
    }

    /** The path, cut into the steps it is made of. Anything the grammar does not allow fails here. */
    private fun steps(): List<Step> {
        val steps = ArrayList<Step>()
        var i = 0
        while (i < path.length) {
            when {
                path[i] == '.' -> {
                    if (steps.isEmpty()) fail("a path does not start with '.'")
                    i++
                    val name = path.substring(i).takeWhile { it != '.' && it != '[' }
                    if (name.isEmpty()) fail("there is nothing after the '.'")
                    steps += Step.Key(name)
                    i += name.length
                }
                path[i] == '[' -> {
                    val close = path.indexOf(']', i).takeIf { it > 0 } ?: fail("a '[' is never closed")
                    val inside = path.substring(i + 1, close).trim()
                    steps += when {
                        inside.length >= 2 && inside.first() == '"' && inside.last() == '"' ->
                            Step.Key(inside.substring(1, inside.length - 1))
                        inside.toIntOrNull() != null && inside.toInt() >= 0 -> Step.Index(inside.toInt())
                        else -> fail("'[$inside]' is neither an index nor a quoted key")
                    }
                    i = close + 1
                }
                else -> {
                    if (steps.isNotEmpty()) fail("expected '.' or '[' at '${path.substring(i)}'")
                    val name = path.takeWhile { it != '.' && it != '[' }
                    steps += Step.Key(name)
                    i += name.length
                }
            }
        }
        if (steps.isEmpty()) fail("the path is empty")
        return steps
    }

    private fun fail(what: String): Nothing =
        throw IllegalArgumentException("'$path' is not a usable path: $what")

    private sealed class Step(val shown: String) {

        abstract fun of(value: Any?, reached: String, path: String): Any?

        protected fun stop(reached: String, path: String, what: String): Nothing =
            throw IllegalArgumentException(
                "'$path' found nothing: " + (if (reached.isEmpty()) "the document" else "'$reached'") + " $what",
            )

        class Key(private val name: String) : Step(if (name.any { it == '.' || it == '[' }) """["$name"]""" else ".$name") {
            override fun of(value: Any?, reached: String, path: String): Any? {
                val map = value as? Map<*, *> ?: stop(reached, path, "is ${describe(value)}, so it has no '$name'")
                if (!map.containsKey(name)) {
                    // the keys are the shape of the document, not its contents, and they are what a
                    // caller needs to fix the path — a renamed field is the usual reason to be here
                    val had = map.keys.take(8).joinToString(", ")
                    val more = if (map.size > 8) ", and ${map.size - 8} more" else ""
                    stop(reached, path, "has no '$name'" + if (map.isEmpty()) " (it is empty)" else " — it has $had$more")
                }
                return map[name]
            }
        }

        class Index(private val at: Int) : Step("[$at]") {
            override fun of(value: Any?, reached: String, path: String): Any? {
                val list = value as? List<*> ?: stop(reached, path, "is ${describe(value)}, so it has no [$at]")
                if (at >= list.size) {
                    stop(reached, path, if (list.isEmpty()) "is an empty array" else "has ${list.size} items, so there is no [$at]")
                }
                return list[at]
            }
        }

        companion object {
            /** What something is, without saying what it holds — a path error is not a place to print values. */
            fun describe(value: Any?): String = when (value) {
                null -> "null"
                is Map<*, *> -> "an object"
                is List<*> -> "an array"
                is String -> "a string"
                is Boolean -> "a boolean"
                else -> "a number"
            }
        }
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
