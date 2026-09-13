package flow.keypairgen

import flow.extension.ModuleOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * Asymmetric key pair generator. Takes no input; each run produces a fresh key pair, PKCS#8-encoded
 * on "privateKey" and X.509-encoded on "publicKey" — the encodings flow.signature's "key" input
 * expects directly. For "EC", keySize picks a standard named curve of that bit length (e.g. 256 -> secp256r1).
 */
class KeyPairGenModule : ModuleExtension {
    override val id = "flow.keypairgen"
    override val displayName = "Key Pair Generator"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = emptyList<String>()
    override val outputs = listOf("publicKey", "privateKey")
    override val options = listOf(
        ModuleOption("algorithm", OptionType.SELECT, "RSA", listOf("RSA", "DSA", "EC")),
        ModuleOption("keySize", OptionType.NUMBER, "2048"),
    )

    override val portDescriptions = mapOf(
        "_module" to "Generate a public/private key pair. Use it to make keys for Signature or for RSA in Cipher. For a symmetric key use Key Generator.",
        "publicKey" to "The public key, X.509 encoded — what Cipher encrypts with and Signature verifies with.",
        "privateKey" to "The private key, PKCS#8 encoded — what Cipher decrypts with and Signature signs with. Treat it as a secret.",
    )

    override val optionDescriptions = mapOf(
        "algorithm" to "RSA for encryption or signatures; EC for signatures at a much smaller key size; DSA only for old systems that require it.",
        "keySize" to "Bits. 2048 is the practical floor for RSA, 3072 or 4096 for longer-lived keys; EC uses 256/384/521, which are far stronger per bit.",
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
