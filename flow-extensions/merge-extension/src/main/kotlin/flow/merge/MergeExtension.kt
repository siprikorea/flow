package flow.merge

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension

/** Joins the connected inputs (in port order) with the "sep" separator. */
class MergeExtension : ProcessorExtension {
    override val id = "flow.merge"
    override val displayName = "Merge"
    override val inputs = listOf("a", "b")
    override val outputs = listOf("out")
    override val options = listOf(ExtensionOption("sep", default = ""))

    /**
     * Both sides may be left out.
     *
     * Joining is defined over whatever arrived: one side missing gives the other on its own, and
     * both missing gives nothing at all. A schema demanding both would make a one-sided join look
     * like a mistake, which it is not.
     */
    override fun optionalInputsFor(values: Map<String, String>) = listOf("a", "b")

    override val portDescriptions = mapOf(
        "_module" to "Join two values into one, in port order, with an optional separator. Use it to " +
            "put two branches of a flow back together; it does not parse or re-encode either side.",
        "a" to "The first value. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes. Optional.",
        "b" to "The second value, same encodings. Optional — with only one side, that side comes out as-is.",
        "out" to "The two joined, with 'sep' between them.",
    )

    override val optionDescriptions = mapOf(
        "sep" to "What goes between the two. Empty by default, so the bytes are concatenated.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val vals = this.inputs.mapNotNull { inputs[it]?.decodeToString() }
        val sep = options["sep"] ?: ""
        return mapOf("out" to (if (vals.isEmpty()) null else vals.joinToString(sep).encodeToByteArray()))
    }
}
