package flow.base64

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.util.Base64

/**
 * Base64 module extension. The "mode" option selects encoding or decoding.
 * - encode: input bytes -> Base64 text (ASCII bytes)
 * - decode: input Base64 text -> the original bytes
 */
class Base64Extension : ModuleExtension {
    override val id = "flow.base64"
    override val displayName = "Base64"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("mode", OptionType.SELECT, "encode", listOf("encode", "decode")),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val out = when (options["mode"] ?: "encode") {
            "decode" -> runCatching { Base64.getDecoder().decode(data.decodeToString().trim()) }.getOrNull()
            else -> Base64.getEncoder().encode(data)
        }
        return mapOf("out" to out)
    }
}
