package flow.keypairgen

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * Asymmetric key pair generator. Takes no input; each run produces a fresh key pair, PKCS#8-encoded
 * on "privateKey" and X.509-encoded on "publicKey" — the encodings flow.signature's "key" input
 * expects directly. For "EC", keySize picks a standard named curve of that bit length (e.g. 256 -> secp256r1).
 */
class KeyPairGenExtension : ModuleExtension {
    override val id = "flow.keypairgen"
    override val displayName = "Key Pair Generator"
    override val inputs = emptyList<String>()
    override val outputs = listOf("publicKey", "privateKey")
    override val options = listOf(
        ExtensionOption("algorithm", OptionType.SELECT, "RSA", listOf("RSA", "DSA", "EC")),
        ExtensionOption("keySize", OptionType.NUMBER, "2048"),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val algorithm = options["algorithm"] ?: "RSA"
        val keySize = (options["keySize"] ?: "2048").trim().toInt()
        val kpg = KeyPairGenerator.getInstance(algorithm)
        // an unsupported keySize for the algorithm (e.g. a non-curve bit length for EC) throws —
        // the host surfaces that as visible output
        kpg.initialize(keySize, SecureRandom())
        val pair = kpg.generateKeyPair()
        return mapOf("publicKey" to pair.public.encoded, "privateKey" to pair.private.encoded)
    }
}
