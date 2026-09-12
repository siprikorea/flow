package flow.cipher

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
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
class CipherExtension : ProcessorExtension {
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

    /**
     * `iv` may always be left out.
     *
     * Not because it is unused — for CBC and friends it is essential — but because leaving it out
     * means something well defined: encrypt generates one and prepends it, decrypt reads it back
     * off the front. So a caller that has no IV to give is not making a mistake, and a schema that
     * demanded one would make it look like they were. ECB ignores it either way, and RSA does not
     * have the port at all (see inputsFor).
     */
    override fun optionalInputsFor(values: Map<String, String>) = listOf("iv")

    override val portDescriptions = mapOf(
        "_module" to "Encrypt or decrypt with AES, DES, DESede, Blowfish or RSA. Use it for the " +
            "actual encryption step of a flow; for deriving a key from a passphrase use Key Factory " +
            "first, and for hashing use Hash — this does not do either.",
        "in" to "The plaintext to encrypt, or the ciphertext to decrypt. Text is taken as UTF-8; " +
            "prefix with 'hex:' or 'b64:' for bytes. On decrypt this is almost always 'hex:' or 'b64:'.",
        "key" to "The key, as raw bytes: 'hex:' or 'b64:', or text taken as UTF-8. AES needs 16, 24 " +
            "or 32 bytes, DES 8, DESede 24. RSA takes an encoded key instead — X.509/certificate/PEM " +
            "to encrypt, PKCS#8 DER or PEM to decrypt — which is what Key Pair Generator and Key " +
            "Store produce. Derive a key from a passphrase with Key Factory rather than passing one here.",
        "iv" to "The initialisation vector, 16 bytes for AES (12 for GCM), as 'hex:' or 'b64:'. " +
            "Optional: leave it out and encrypt generates a random one and prepends it to the " +
            "output, while decrypt reads it back off the front. ECB does not use one; RSA has none.",
        "out" to "The ciphertext, or the recovered plaintext.",
    )

    override val optionDescriptions = mapOf(
        "operation" to "encrypt turns 'in' into ciphertext; decrypt turns it back.",
        "algorithm" to "AES for anything new. DES, DESede and Blowfish are for reading old data, " +
            "not for producing it. RSA is asymmetric and only covers data smaller than the key " +
            "(245 bytes for a 2048-bit key under PKCS#1) — encrypt a symmetric key with it, not bulk data.",
        "mode" to "How blocks are chained. CBC is the usual choice; GCM also authenticates, so a " +
            "tampered ciphertext fails to decrypt rather than producing rubbish; ECB leaks which " +
            "blocks are equal and is for compatibility only; CFB/OFB/CTR turn the block cipher into " +
            "a stream. Not used by RSA.",
        "padding" to "RSA takes PKCS1Padding or an OAEP padding (prefer OAEPWithSHA-256AndMGF1Padding " +
            "for anything new); block ciphers take PKCS5Padding, or NoPadding when the input is " +
            "already a whole number of blocks. The two sets are not interchangeable and a mismatch " +
            "is refused before anything runs.",
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
    /**
     * The padding, refusing a combination this algorithm cannot use.
     *
     * This used to fall back to the algorithm's first padding when given one from the other family
     * — so asking for AES with OAEP quietly encrypted with PKCS5Padding instead. Silently doing
     * something other than what was asked is the worst of the three options here: the caller
     * believes they used OAEP, and nothing ever says otherwise. Refusing says it once, before
     * anything is encrypted.
     *
     * A padding that was not given at all still defaults, which is what makes a call that names
     * only `algorithm: RSA` work.
     */
    private fun paddingFor(values: Map<String, String>): String {
        val algorithm = values["algorithm"] ?: "AES"
        val allowed = if (isRsa(values)) rsaPaddings else symmetricPaddings
        val given = values["padding"]?.takeIf { it.isNotEmpty() } ?: return allowed.first()
        require(given in allowed) {
            "'$given' is not a padding $algorithm can use. $algorithm takes " +
                allowed.joinToString(" or ") + ". RSA uses PKCS1Padding or an OAEP padding; block " +
                "ciphers use PKCS5Padding or NoPadding."
        }
        return given
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
