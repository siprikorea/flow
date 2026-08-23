package flow.merge

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension

/** Joins the connected inputs (in port order) with the "sep" separator. */
class MergeExtension : ModuleExtension {
    override val id = "flow.merge"
    override val displayName = "Merge"
    override val inputs = listOf("a", "b")
    override val outputs = listOf("out")
    override val options = listOf(ExtensionOption("sep", default = ""))

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val vals = this.inputs.mapNotNull { inputs[it]?.decodeToString() }
        val sep = options["sep"] ?: ""
        return mapOf("out" to (if (vals.isEmpty()) null else vals.joinToString(sep).encodeToByteArray()))
    }
}
