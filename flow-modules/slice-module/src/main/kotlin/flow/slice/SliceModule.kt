package flow.slice

import flow.extension.ModuleOption
import flow.extension.ModuleExtension
import flow.extension.OptionType

/**
 * Takes a fixed byte range out of "in" — the counterpart to flow.split, which cuts on a separator
 * and so can't address bytes by position.
 *
 * "out" carries [offset, offset+length) and "rest" everything after it, which is the shape of a
 * prepended IV or nonce: offset 0, length 16 gives the IV on "out" and the ciphertext on "rest".
 * A negative offset counts back from the end; a blank length runs to the end. A range that doesn't
 * fit is an error rather than a short result, so a truncated key or IV can't slip downstream.
 */
class SliceModule : ModuleExtension {
    override val id = "flow.slice"
    override val displayName = "Slice"
    override val version = "1.0.2"
    override val category = "other"
    override val inputs = listOf("in")
    override val outputs = listOf("out", "rest")
    override val options = listOf(
        ModuleOption("offset", OptionType.NUMBER, "0"),
        ModuleOption("length", OptionType.NUMBER, ""),
    )

    override val portDescriptions = mapOf(
        "_module" to "Take a range of bytes out of the input and hand back the rest separately. Use it to split a fixed-size header from a body, or an IV from a ciphertext.",
        "in" to "The bytes to cut. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "out" to "The bytes from 'offset', 'length' of them.",
        "rest" to "Everything else, in order — what came before the slice and what came after.",
    )

    override val optionDescriptions = mapOf(
        "offset" to "Where the slice starts, in bytes from the beginning.",
        "length" to "How many bytes to take. Left empty, everything from the offset to the end.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return outputs.associateWith { null }
        val size = data.size

        val rawOffset = (options["offset"] ?: "0").trim().ifEmpty { "0" }.toIntOrNull()
            ?: error("offset must be a whole number")
        val start = if (rawOffset < 0) size + rawOffset else rawOffset
        require(start in 0..size) { "offset $rawOffset is outside the $size byte input" }

        val lengthText = (options["length"] ?: "").trim()
        val length = if (lengthText.isEmpty()) size - start else {
            lengthText.toIntOrNull()?.also { require(it >= 0) { "length must not be negative" } }
                ?: error("length must be a whole number")
        }
        require(start + length <= size) {
            "slice of $length bytes at $start runs past the $size byte input"
        }

        val end = start + length
        return mapOf(
            "out" to data.copyOfRange(start, end),
            "rest" to data.copyOfRange(end, size),
        )
    }
}
