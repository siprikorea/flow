package flow.cipher

import flow.extension.ExtensionOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cipher module. "in" is the plaintext/ciphertext and "key" the key.
 *
 * Symmetric algorithms take raw key bytes (use an upstream Hash or Key Factory module to derive a
 * fixed-size key from a passphrase). "iv" is optional: when connected it's used as-is and "out" is
 * just the ciphertext; when not, a random IV/nonce is generated on encrypt and prepended to "out",
 * and decrypt reads it back off the front of "in". ECB needs no IV either way, and GCM's auth tag
 * is already part of the JCE output.
 *
 * RSA encrypts with a public key (X.509, a certificate, or either in PEM) and decrypts with a
 * private key (PKCS#8, DER or PEM) — the encodings flow.keypairgen and flow.keystore produce. It
 * has no IV or mode, and its padding choices are its own: PKCS1Padding or OAEP. RSA only covers
 * data smaller than the modulus (245 bytes for a 2048-bit key under PKCS#1), so bulk data is
 * normally encrypted symmetrically with an RSA-wrapped key.
 */
class CipherExtension : ModuleExtension {
    override val id = "flow.cipher"
    override val displayName = "Cipher"
    override val inputs = listOf("in", "key", "iv")
    override val outputs = listOf("out")

    private val symmetricPaddings = listOf("PKCS5Padding", "NoPadding")
    private val rsaPaddings = listOf("PKCS1Padding", "OAEPWithSHA-256AndMGF1Padding", "OAEPWithSHA-1AndMGF1Padding", "NoPadding")

    override val options = listOf(
        ExtensionOption("operation", OptionType.SELECT, "encrypt", listOf("encrypt", "decrypt")),
        ExtensionOption("algorithm", OptionType.SELECT, "AES", listOf("AES", "DES", "DESede", "Blowfish", "RSA")),
        ExtensionOption("mode", OptionType.SELECT, "CBC", listOf("ECB", "CBC", "CFB", "OFB", "CTR", "GCM")),
        ExtensionOption("padding", OptionType.SELECT, symmetricPaddings.first(), symmetricPaddings),
    )

    private val random = SecureRandom()
    private val gcmNonceSize = 12
    private val gcmTagBits = 128

    private fun isRsa(values: Map<String, String>) = (values["algorithm"] ?: "AES") == "RSA"

    // RSA has no IV, no mode, and its own padding list
    override fun inputsFor(options: Map<String, String>): List<String> =
        if (isRsa(options)) listOf("in", "key") else inputs

    override fun optionsFor(values: Map<String, String>): List<ExtensionOption> {
        if (!isRsa(values)) return options
        return options.mapNotNull { opt ->
            when (opt.name) {
                "mode" -> null
                "padding" -> ExtensionOption("padding", OptionType.SELECT, rsaPaddings.first(), rsaPaddings)
                else -> opt
            }
        }
    }

    // a padding left over from another algorithm would only produce a confusing
    // NoSuchAlgorithmException, so fall back to the one this algorithm starts with
    private fun paddingFor(values: Map<String, String>): String {
        val allowed = if (isRsa(values)) rsaPaddings else symmetricPaddings
        return values["padding"]?.takeIf { it in allowed } ?: allowed.first()
    }

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val key = inputs["key"] ?: return mapOf("out" to null)
        val encrypt = (options["operation"] ?: "encrypt") == "encrypt"
        val padding = paddingFor(options)

        // let failures (e.g. a key/IV of the wrong size for the algorithm) propagate — the host
        // surfaces the exception message as visible output instead of a silent empty result.
        if (isRsa(options)) {
            val cipher = Cipher.getInstance("RSA/ECB/$padding")
            if (encrypt) cipher.init(Cipher.ENCRYPT_MODE, KeyMaterial.publicKey(key, "RSA"))
            else cipher.init(Cipher.DECRYPT_MODE, KeyMaterial.privateKey(key, "RSA"))
            return mapOf("out" to cipher.doFinal(data))
        }

        val explicitIv = inputs["iv"]
        val algorithm = options["algorithm"] ?: "AES"
        val mode = options["mode"] ?: "CBC"
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
