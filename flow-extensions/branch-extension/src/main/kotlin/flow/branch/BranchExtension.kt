package flow.branch

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension

/**
 * Send the value one way or the other.
 *
 * Everything else in a flow happens to every value that reaches it; this is the one node that
 * decides. The value goes out 'then' or 'else' — never both — and the side that was not taken
 * carries nothing, which the engine reads as "this branch did not run" and skips everything
 * downstream of it. That is what makes a branch worth having: the Slack node on the failing side
 * stays quiet when nothing failed, rather than posting an empty message.
 *
 * The value is passed through untouched, so a branch can sit in the middle of a chain without
 * changing what flows along it.
 */
class BranchExtension : ProcessorExtension {
    override val id = "flow.branch"
    override val displayName = "Branch"
    override val version = "1.0.0"
    override val inputs = listOf("in", "compare")
    override val outputs = listOf("then", "else")

    override val options = listOf(
        ExtensionOption(
            "test", OptionType.SELECT, "equals",
            listOf("equals", "contains", "startsWith", "endsWith", "matches", "isEmpty", "greaterThan", "lessThan"),
        ),
        ExtensionOption("value", OptionType.TEXT, ""),
        ExtensionOption("caseSensitive", OptionType.SELECT, "true", listOf("true", "false")),
    )

    /** 'compare' replaces the option, and a flow that does not need it leaves it unconnected. */
    override fun optionalInputsFor(values: Map<String, String>) = listOf("compare")

    /** isEmpty asks about the input alone, and the numeric tests do not care about case. */
    override fun optionsFor(values: Map<String, String>): List<ExtensionOption> {
        val test = values["test"] ?: "equals"
        return options.filter { option ->
            when (option.name) {
                "value" -> test != "isEmpty"
                "caseSensitive" -> test in textTests
                else -> true
            }
        }
    }

    override val portDescriptions = mapOf(
        "_module" to "Send the value one way or the other. Everything else in a flow runs on every " +
            "value that reaches it; this decides. The value goes out 'then' when the test passes and " +
            "'else' when it does not — never both — and whatever hangs off the other side does not " +
            "run at all, so a message node on the failing branch stays quiet when nothing failed.",
        "in" to "The value to test, and the value that comes out whichever way it goes — it is passed " +
            "through untouched. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "compare" to "What to compare against, when it comes from the flow rather than being typed in; " +
            "read as UTF-8 text, like the value it is compared with. " +
            "Connected, it replaces the 'value' option — which is how one branch compares two computed " +
            "values, a fresh digest against a stored one. Leave it unconnected to use the option.",
        "then" to "The input, when the test passed. Nothing when it did not, which stops this side of the flow.",
        "else" to "The input, when the test failed. Nothing when it passed, which stops this side of the flow.",
    )

    override val optionDescriptions = mapOf(
        "test" to "How to decide. equals/contains/startsWith/endsWith and matches (a regular " +
            "expression) read both sides as text; isEmpty asks only whether the input has any bytes; " +
            "greaterThan and lessThan read both sides as decimal numbers and fail the run if either " +
            "is not one, rather than quietly comparing them as text.",
        "value" to "What to compare against, as text. Ignored when 'compare' is connected, and unused " +
            "by isEmpty.",
        "caseSensitive" to "Whether 'A' and 'a' are the same for the text tests. On by default: a " +
            "digest, a token or a base64 value compared case-insensitively is a comparison that passes " +
            "when it should not.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val value = inputs["in"]
        val test = options["test"]?.takeIf { it.isNotEmpty() } ?: "equals"
        // the port wins over the option: it is the one that can carry a value the flow computed
        val against = inputs["compare"]?.decodeToString() ?: options["value"].orEmpty()
        val passed = decide(test, value, against, options["caseSensitive"] != "false")
        // nothing, not an empty value, on the side not taken — an empty value is a value, and would
        // go on to be hashed, posted or encrypted as one
        return mapOf("then" to value.takeIf { passed }, "else" to value.takeIf { !passed })
    }

    private fun decide(test: String, value: ByteArray?, against: String, caseSensitive: Boolean): Boolean {
        if (test == "isEmpty") return value == null || value.isEmpty()
        val text = (value ?: ByteArray(0)).decodeToString()
        if (test in numericTests) {
            val left = number(text, "in")
            val right = number(against, "compare")
            return if (test == "greaterThan") left > right else left < right
        }
        val a = if (caseSensitive) text else text.lowercase()
        val b = if (caseSensitive) against else against.lowercase()
        return when (test) {
            "equals" -> a == b
            "contains" -> a.contains(b)
            "startsWith" -> a.startsWith(b)
            "endsWith" -> a.endsWith(b)
            "matches" -> runCatching { Regex(b).containsMatchIn(a) }
                .getOrElse { throw IllegalArgumentException("'$against' is not a usable regular expression: ${it.message}") }
            else -> throw IllegalArgumentException("'$test' is not a test this module knows")
        }
    }

    /**
     * A number, or a failure that says which side was not one.
     *
     * Falling back to a text comparison here would make "10" less than "9" and give an answer that
     * looks right until the day it does not.
     */
    private fun number(text: String, side: String): Double =
        text.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("'$side' is not a number, so it cannot be compared as one")

    private companion object {
        val textTests = setOf("equals", "contains", "startsWith", "endsWith", "matches")
        val numericTests = setOf("greaterThan", "lessThan")
    }
}
