package flow.keyfactory

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
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
 *   "key" through KeyFactory, which also validates it — X.509 in/out for a public key,
 *   PKCS#8 for a private one, the encodings flow.keypairgen produces and flow.signature expects
 *
 * "out" always carries the key's encoded form.
 */
class KeyFactoryExtension : ModuleExtension {
    override val id = "flow.keyfactory"
    override val displayName = "Key Factory"
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
                val factory = KeyFactory.getInstance(algorithm)
                if (options["keyType"] == "public") factory.generatePublic(X509EncodedKeySpec(key)).encoded
                else factory.generatePrivate(PKCS8EncodedKeySpec(key)).encoded
            }
        }
        return mapOf("out" to out)
    }
}
