package flow.sleep

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType

/**
 * Identity pass-through; "ms" only paces the canvas run animation (read directly off the
 * node's params by the editor's run-simulation step, not used inside process()).
 */
class SleepExtension : ModuleExtension {
    override val id = "flow.sleep"
    override val displayName = "Sleep"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(ExtensionOption("ms", OptionType.NUMBER, "1000"))

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        mapOf("out" to inputs["in"])
}
