package flow.securerandom

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.SecureRandom

/**
 * Random byte generator. Takes no input; each run produces "length" fresh random bytes on "out" —
 * plug it into flow.cipher's/flow.mac's "key" or "iv" input, or use as raw random data on its own.
 */
class SecureRandomExtension : ModuleExtension {
    override val id = "flow.securerandom"
    override val displayName = "Secure Random"
    override val inputs = emptyList<String>()
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("length", OptionType.NUMBER, "32"),
        // blank = SecureRandom()'s own default algorithm/provider choice
        ExtensionOption(
            "algorithm", OptionType.SELECT, "",
            listOf("", "NativePRNG", "NativePRNGBlocking", "NativePRNGNonBlocking", "SHA1PRNG", "DRBG"),
        ),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val length = (options["length"] ?: "32").trim().toInt().coerceAtLeast(0)
        val algorithm = options["algorithm"]?.trim().orEmpty()
        // an unavailable algorithm throws — the host surfaces that as visible output
        val random = if (algorithm.isEmpty()) SecureRandom() else SecureRandom.getInstance(algorithm)
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return mapOf("out" to bytes)
    }
}
