package flow.engine

// 작은 수식 평가기: 숫자, 변수(x/value = 입력), + - * / %, 비교(> < >= <= == !=), 괄호, 단항 -.
// map 의 산술식과 filter 의 비교식을 지원한다.
object Expr {
    fun evalToString(expr: String, input: String?): String? {
        val r = eval(expr, input) ?: return input // 평가 불가 시 입력 그대로 통과
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

        // 비교 (다음 우선순위: 덧셈)
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
            // 식별자 (x / value / 기타 → 입력)
            if (peek()?.isLetter() == true) {
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                return x // x, value, 그 외 식별자 모두 입력값으로 취급
            }
            // 숫자
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun skipWs() { while (i < s.length && s[i] == ' ') i++ }
    }
}
