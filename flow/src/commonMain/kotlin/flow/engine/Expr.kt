package flow.engine

// Small expression evaluator: numbers, variables (x/value = input), + - * / %, comparisons (> < >= <= == !=), parentheses, unary -.
// Supports map's arithmetic and filter's comparison expressions.
object Expr {
    fun evalToString(expr: String, input: String?): String? {
        val r = eval(expr, input) ?: return input // pass the input through unchanged if it can't be evaluated
        return fmt(r)
    }

    fun evalToBool(expr: String, input: String?): Boolean = (eval(expr, input) ?: 0.0) != 0.0

    fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun eval(expr: String, input: String?): Double? =
        runCatching { Parser(expr, input?.toDoubleOrNull() ?: 0.0).parse() }.getOrNull()

    private class Parser(val s: String, val x: Double) {
        var i = 0

        fun parse(): Double {
            val v = comparison()
            skipWs()
            if (i < s.length) error("trailing")
            return v
        }

        // comparison (next precedence: addition)
        private fun comparison(): Double {
            var left = add()
            skipWs()
            for (op in listOf(">=", "<=", "==", "!=", ">", "<")) {
                if (s.startsWith(op, i)) {
                    i += op.length
                    val right = add()
                    val b = when (op) {
                        ">" -> left > right; "<" -> left < right
                        ">=" -> left >= right; "<=" -> left <= right
                        "==" -> left == right; "!=" -> left != right
                        else -> false
                    }
                    left = if (b) 1.0 else 0.0
                    skipWs()
                }
            }
            return left
        }

        private fun add(): Double {
            var v = mul()
            while (true) {
                skipWs()
                when {
                    peek() == '+' -> { i++; v += mul() }
                    peek() == '-' -> { i++; v -= mul() }
                    else -> return v
                }
            }
        }

        private fun mul(): Double {
            var v = unary()
            while (true) {
                skipWs()
                when {
                    peek() == '*' -> { i++; v *= unary() }
                    peek() == '/' -> { i++; v /= unary() }
                    peek() == '%' -> { i++; v %= unary() }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skipWs()
            if (peek() == '-') { i++; return -unary() }
            if (peek() == '+') { i++; return unary() }
            return atom()
        }

        private fun atom(): Double {
            skipWs()
            if (peek() == '(') {
                i++
                val v = comparison()
                skipWs()
                if (peek() == ')') i++
                return v
            }
            // identifier (x / value / anything -> input)
            if (peek()?.isLetter() == true) {
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                return x // x, value and any other identifier are treated as the input value
            }
            // number
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun skipWs() { while (i < s.length && s[i] == ' ') i++ }
    }
}
