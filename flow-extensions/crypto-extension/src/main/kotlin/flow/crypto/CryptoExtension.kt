package flow.crypto

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric block-cipher module. "in" is the plaintext/ciphertext, "key" is the raw key bytes
 * (use an upstream Hash module to derive a fixed-size key from a passphrase if needed).
 *
 * "iv" is optional. When connected, it's used as-is and "out" is just the ciphertext (or, on
 * decrypt, "in" is treated as ciphertext-only). When not connected, a random IV/nonce is
 * generated on encrypt and prepended to "out"; decrypt reads it back off the front of "in".
 * ECB needs no IV either way. GCM's auth tag is already part of the JCE output.
 */
class CryptoExtension : ModuleExtension {
    override val id = "flow.crypto"
    override val displayName = "Cipher"
    override val inputs = listOf("in", "key", "iv")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("operation", OptionType.SELECT, "encrypt", listOf("encrypt", "decrypt")),
        ExtensionOption("algorithm", OptionType.SELECT, "AES", listOf("AES", "DES", "DESede", "Blowfish")),
        ExtensionOption("mode", OptionType.SELECT, "CBC", listOf("ECB", "CBC", "CFB", "OFB", "CTR", "GCM")),
        ExtensionOption("padding", OptionType.SELECT, "PKCS5Padding", listOf("PKCS5Padding", "NoPadding")),
    )

    private val random = SecureRandom()
    private val gcmNonceSize = 12
    private val gcmTagBits = 128

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val key = inputs["key"] ?: return mapOf("out" to null)
        val explicitIv = inputs["iv"]
        val encrypt = (options["operation"] ?: "encrypt") == "encrypt"
        val algorithm = options["algorithm"] ?: "AES"
        val mode = options["mode"] ?: "CBC"
        val padding = options["padding"] ?: "PKCS5Padding"

        // let failures (e.g. a key/IV of the wrong size for the algorithm) propagate — the host
        // surfaces the exception message as visible output instead of a silent empty result.
        val cipher = Cipher.getInstance("$algorithm/$mode/$padding")
        val keySpec = SecretKeySpec(key, algorithm)
        val out = when (mode) {
            "ECB" -> {
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
                cipher.doFinal(data)
            }
            "GCM" -> {
                if (encrypt) {
                    val nonce = explicitIv ?: ByteArray(gcmNonceSize).also { random.nextBytes(it) }
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(gcmTagBits, nonce))
                    val ct = cipher.doFinal(data)
                    if (explicitIv != null) ct else nonce + ct
                } else if (explicitIv != null) {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(gcmTagBits, explicitIv))
                    cipher.doFinal(data)
                } else {
                    require(data.size > gcmNonceSize) { "ciphertext too short to contain a GCM nonce" }
                    val nonce = data.copyOfRange(0, gcmNonceSize)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(gcmTagBits, nonce))
                    cipher.doFinal(data, gcmNonceSize, data.size - gcmNonceSize)
                }
            }
            else -> {
                val ivSize = cipher.blockSize
                if (encrypt) {
                    val iv = explicitIv ?: ByteArray(ivSize).also { random.nextBytes(it) }
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
                    val ct = cipher.doFinal(data)
                    if (explicitIv != null) ct else iv + ct
                } else if (explicitIv != null) {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(explicitIv))
                    cipher.doFinal(data)
                } else {
                    require(data.size > ivSize) { "ciphertext too short to contain a prepended IV" }
                    val iv = data.copyOfRange(0, ivSize)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
                    cipher.doFinal(data, ivSize, data.size - ivSize)
                }
            }
        }

        return mapOf("out" to out)
    }
}
