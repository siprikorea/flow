package flow.split

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension

/** Splits "in" by the "sep" separator into its two output ports (remainder in the last). */
class SplitExtension : ProcessorExtension {
    override val id = "flow.split"
    override val displayName = "Split"
    override val version = "1.0.2"
    override val category = "other"
    override val inputs = listOf("in")
    override val outputs = listOf("a", "b")
    override val options = listOf(ExtensionOption("sep", default = ","))

    override val portDescriptions = mapOf(
        "_module" to "Split text in two at the first occurrence of a separator. Use it to take apart a joined value; for cutting at a byte position use Slice.",
        "in" to "The text to split.",
        "a" to "Everything before the first separator.",
        "b" to "Everything after it. Empty if the separator does not occur.",
    )

    override val optionDescriptions = mapOf(
        "sep" to "The separator to split on, as text.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val input = inputs["in"]?.decodeToString() ?: return outputs.associateWith { null }
        val sep = options["sep"] ?: ","
        val parts = if (sep.isEmpty()) listOf(input) else input.split(sep, limit = outputs.size)
        return outputs.mapIndexed { i, name -> name to parts.getOrNull(i)?.encodeToByteArray() }.toMap()
    }
}
