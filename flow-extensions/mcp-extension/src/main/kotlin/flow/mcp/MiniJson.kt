package flow.mcp

/**
 * Just enough JSON for the MCP handshake: parses into Map / List / String / Double / Boolean / null,
 * and writes strings back out. An extension jar carries no dependencies, so this stands in for a
 * JSON library.
 */
internal object MiniJson {

    fun parse(text: String): Any? = Reader(text).let { r ->
        val value = r.value()
        r.skipSpace()
        value
    }

    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    // map lookups over a parsed document, e.g. path(doc, "result", "content")
    @Suppress("UNCHECKED_CAST")
    fun path(root: Any?, vararg keys: String): Any? =
        keys.fold(root) { node, key -> (node as? Map<String, Any?>)?.get(key) }

    private class Reader(private val s: String) {
        private var i = 0

        fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skipSpace()
            if (i >= s.length) return null
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (i < s.length) {
                skipSpace()
                val key = str()
                skipSpace()
                require(s[i] == ':') { "expected ':' at $i" }
                i++
                map[key] = value()
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> error("expected ',' or '}' at $i")
                }
            }
            error("unterminated object")
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (i < s.length) {
                list += value()
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> error("expected ',' or ']' at $i")
                }
            }
            error("unterminated array")
        }

        private fun str(): String {
            require(s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape '\\$e' at $i")
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "unexpected literal at $i" }
            i += word.length
            return value
        }
    }
}
