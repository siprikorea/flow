package flow.keygen

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.security.SecureRandom
import javax.crypto.KeyGenerator

/**
 * Symmetric key generator. Takes no input; each run produces a fresh random key of the chosen
 * algorithm/size on "out" — plug it straight into flow.cipher's or flow.mac's "key" input.
 */
class KeyGenExtension : ProcessorExtension {
    override val id = "flow.keygen"
    override val displayName = "Key Generator"
    override val inputs = emptyList<String>()
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption(
            "algorithm", OptionType.SELECT, "AES",
            listOf(
                "AES", "DES", "DESede", "Blowfish", "ARCFOUR", "RC2",
                "HmacMD5", "HmacSHA1", "HmacSHA224", "HmacSHA256", "HmacSHA384", "HmacSHA512",
            ),
        ),
        // blank = provider default size for the chosen algorithm (e.g. 128 for AES)
        ExtensionOption("keySize", OptionType.NUMBER, ""),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val algorithm = options["algorithm"] ?: "AES"
        val keySize = options["keySize"]?.trim().orEmpty()
        val kg = KeyGenerator.getInstance(algorithm)
        // an out-of-range keySize for the algorithm throws — the host surfaces that as visible output
        if (keySize.isNotEmpty()) kg.init(keySize.toInt(), SecureRandom()) else kg.init(SecureRandom())
        return mapOf("out" to kg.generateKey().encoded)
    }
}
