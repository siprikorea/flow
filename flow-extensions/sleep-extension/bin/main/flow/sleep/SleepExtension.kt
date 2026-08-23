package flow.sleep

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType

/**
 * Passes its input through after waiting "ms" milliseconds.
 *
 * The wait happens here rather than in the editor's run animation: a module's behaviour is the
 * module's own business, and pacing it from the host meant the delay applied only on the canvas —
 * the same flow run from the CLI or over MCP went straight through.
 */
class SleepExtension : ModuleExtension {
    override val id = "flow.sleep"
    override val displayName = "Sleep"
    override val version = "1.1.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(ExtensionOption("ms", OptionType.NUMBER, "1000"))

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        // capped so a mistyped value cannot wedge a run for hours
        val ms = options["ms"]?.trim()?.toLongOrNull()?.coerceIn(0L, 600_000L) ?: 1000L
        if (ms > 0) Thread.sleep(ms)
        return mapOf("out" to inputs["in"])
    }
}
