package flow.base64

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.util.Base64

/**
 * Base64 module extension. The "mode" option selects encoding or decoding, "variant" picks the
 * alphabet — standard (+ /) or URL-safe (- _) — and "padding" whether the encoder writes trailing
 * '='. Decoding accepts input with or without padding, so the padding option only applies to
 * encoding.
 */
class Base64Extension : ProcessorExtension {
    override val id = "flow.base64"
    override val displayName = "Base64"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("mode", OptionType.SELECT, "encode", listOf("encode", "decode")),
        ExtensionOption("variant", OptionType.SELECT, "standard", listOf("standard", "url")),
        ExtensionOption("padding", OptionType.SELECT, "true", listOf("true", "false")),
    )

    private fun isDecode(values: Map<String, String>) = (values["mode"] ?: "encode") == "decode"

    // padding is an encoder setting; the decoder takes either form
    override fun optionsFor(values: Map<String, String>): List<ExtensionOption> =
        if (isDecode(values)) options.filterNot { it.name == "padding" } else options

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val url = (options["variant"] ?: "standard") == "url"
        // a malformed decode input throws — the host surfaces that as visible output
        val out = if (isDecode(options)) {
            val text = data.decodeToString().trim()
            (if (url) Base64.getUrlDecoder() else Base64.getDecoder()).decode(text)
        } else {
            val encoder = if (url) Base64.getUrlEncoder() else Base64.getEncoder()
            (if (options["padding"] == "false") encoder.withoutPadding() else encoder).encode(data)
        }
        return mapOf("out" to out)
    }
}
