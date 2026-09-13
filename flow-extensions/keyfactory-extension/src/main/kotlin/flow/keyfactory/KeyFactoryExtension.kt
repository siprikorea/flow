package flow.keyfactory

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.DESedeKeySpec
import javax.crypto.spec.PBEKeySpec

/**
 * Builds a key from existing key material, covering both JCA factories (algorithm names as in the
 * Java Security Standard Algorithm Names spec):
 *
 * - PBKDF2WithHmac*: derives a secret key from "password" and "salt" (SecretKeyFactory)
 * - DES / DESede: turns raw "key" bytes into a parity-adjusted secret key (SecretKeyFactory)
 * - RSA / DSA / EC / DiffieHellman / XDH / EdDSA and the named curves: re-derives an encoded
 *   "key" through KeyFactory, which also validates it — X.509 for a public key, PKCS#8 for a
 *   private one, either as DER or PEM, and a certificate also serves as a public key. "out"
 *   always carries the DER encoding, the form flow.cipher and flow.signature expect.
 *   PKCS#12 and JKS key stores are loaded by flow.keystore, which feeds this or those directly
 *
 * "out" always carries the key's encoded form.
 */
class KeyFactoryExtension : ProcessorExtension {
    override val id = "flow.keyfactory"
    override val displayName = "Key Factory"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = listOf("password", "salt", "key") // full set; see inputsFor
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption(
            "algorithm", OptionType.SELECT, "PBKDF2WithHmacSHA256",
            listOf(
                "PBKDF2WithHmacSHA1", "PBKDF2WithHmacSHA224", "PBKDF2WithHmacSHA256",
                "PBKDF2WithHmacSHA384", "PBKDF2WithHmacSHA512",
                "DES", "DESede",
                "RSA", "DSA", "EC", "DiffieHellman",
                "XDH", "X25519", "X448", "EdDSA", "Ed25519", "Ed448",
            ),
        ),
        // asymmetric algorithms only: which half of the pair the input holds
        ExtensionOption("keyType", OptionType.SELECT, "private", listOf("private", "public")),
        // PBKDF2 only
        ExtensionOption("iterations", OptionType.NUMBER, "65536"),
        ExtensionOption("keySize", OptionType.NUMBER, "256"),
    )

    private fun algorithmOf(options: Map<String, String>) = options["algorithm"] ?: "PBKDF2WithHmacSHA256"

    private fun isPbkdf2(algorithm: String) = algorithm.startsWith("PBKDF2")

    private fun isSecret(algorithm: String) = isPbkdf2(algorithm) || algorithm == "DES" || algorithm == "DESede"

    override fun inputsFor(options: Map<String, String>): List<String> =
        if (isPbkdf2(algorithmOf(options))) listOf("password", "salt") else listOf("key")

    // keySize/iterations only mean something for PBKDF2, keyType only for the asymmetric factories
    override fun optionsFor(values: Map<String, String>): List<ExtensionOption> {
        val algorithm = algorithmOf(values)
        return options.filter {
            when (it.name) {
                "iterations", "keySize" -> isPbkdf2(algorithm)
                "keyType" -> !isSecret(algorithm)
                else -> true
            }
        }
    }

    // failures (empty salt, key material that doesn't match the algorithm, a wrong keyType for the
    // encoding) propagate — the host surfaces the exception message as visible output
    /** the passphrase, and an encoded key — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("password", "key")

    override val portDescriptions = mapOf(
        "_module" to "Turn a passphrase into a key (PBKDF2), or rebuild a key object from encoded bytes. Use PBKDF2 whenever a human-chosen secret has to become a key — hashing a password with Hash is not a substitute, because a digest is fast and PBKDF2 is deliberately slow.",
        "password" to "The passphrase, as text or 'hex:'/'b64:' bytes. Used only by the PBKDF2 algorithms.",
        "salt" to "The salt, 16 bytes or more, 'hex:'/'b64:' or text. Different per password, and stored alongside the result — it is not secret.",
        "key" to "An encoded key to rebuild, when the algorithm is not PBKDF2: X.509 for a public key, PKCS#8 for a private one. Give the encoding as 'hex:' or 'b64:' bytes — these are binary, so text is almost never what is meant.",
        "out" to "The derived or rebuilt key as raw bytes.",
    )

    override val optionDescriptions = mapOf(
        "algorithm" to "A PBKDF2 variant to derive from a passphrase; a named key algorithm (RSA, EC, Ed25519 and so on) to rebuild an encoded key.",
        "keyType" to "For rebuilding: whether the bytes are a private (PKCS#8) or public (X.509) key. Ignored by PBKDF2.",
        "iterations" to "PBKDF2 work factor. Higher is slower for everyone, including an attacker; 600000 is a current figure for SHA-256, and the default here is deliberately conservative rather than fast.",
        "keySize" to "Bits of derived key. 256 for an AES-256 key.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val algorithm = algorithmOf(options)
        val out = when {
            isPbkdf2(algorithm) -> {
                val password = inputs["password"] ?: return mapOf("out" to null)
                val salt = inputs["salt"] ?: ByteArray(0)
                val iterations = (options["iterations"] ?: "65536").trim().toInt()
                val keySize = (options["keySize"] ?: "256").trim().toInt()
                val spec = PBEKeySpec(password.decodeToString().toCharArray(), salt, iterations, keySize)
                SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
            }
            algorithm == "DES" || algorithm == "DESede" -> {
                val key = inputs["key"] ?: return mapOf("out" to null)
                val spec = if (algorithm == "DES") DESKeySpec(key) else DESedeKeySpec(key)
                SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
            }
            else -> {
                val key = inputs["key"] ?: return mapOf("out" to null)
                if (options["keyType"] == "public") KeyMaterial.publicKey(key, algorithm).encoded
                else KeyMaterial.privateKey(key, algorithm).encoded
            }
        }
        return mapOf("out" to out)
    }
}
