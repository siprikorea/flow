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
    override val version = "1.0.1"
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

    override val portDescriptions = mapOf(
        "_module" to "Encode bytes as base64 text, or decode base64 back to bytes. Use it to carry binary through something that only takes text. It is an encoding, not encryption — it hides nothing.",
        "in" to "On encode, the bytes to encode (Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.). On decode, the base64 text.",
        "out" to "The base64 text, or the decoded bytes.",
    )

    override val optionDescriptions = mapOf(
        "mode" to "encode turns bytes into base64; decode turns base64 back into bytes.",
        "variant" to "standard uses + and /; url uses - and _, which survive being put in a URL or a filename. JWTs and most web APIs use url.",
        "padding" to "Whether to write the trailing '=' that rounds the output to a multiple of four. Most decoders accept either; some strict ones do not.",
    )

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
