package flow.split

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension

/** Splits "in" by the "sep" separator into its two output ports (remainder in the last). */
class SplitExtension : ModuleExtension {
    override val id = "flow.split"
    override val displayName = "Split"
    override val inputs = listOf("in")
    override val outputs = listOf("a", "b")
    override val options = listOf(ExtensionOption("sep", default = ","))

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val input = inputs["in"]?.decodeToString() ?: return outputs.associateWith { null }
        val sep = options["sep"] ?: ","
        val parts = if (sep.isEmpty()) listOf(input) else input.split(sep, limit = outputs.size)
        return outputs.mapIndexed { i, name -> name to parts.getOrNull(i)?.encodeToByteArray() }.toMap()
    }
}
