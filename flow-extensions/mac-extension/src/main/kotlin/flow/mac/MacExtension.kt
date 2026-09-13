package flow.mac

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keyed message digest (HMAC family) module. Outputs the lowercase hex MAC of "in" as ASCII bytes. */
class MacExtension : ProcessorExtension {
    override val id = "flow.mac"
    override val displayName = "MAC"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = listOf("in", "key")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption(
            "algo", OptionType.SELECT, "HmacSHA256",
            listOf(
                "HmacMD5",
                "HmacSHA1", "HmacSHA224", "HmacSHA256", "HmacSHA384", "HmacSHA512",
                "HmacSHA512/224", "HmacSHA512/256",
                "HmacSHA3-224", "HmacSHA3-256", "HmacSHA3-384", "HmacSHA3-512",
            ),
        ),
    )

    /** the HMAC secret — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("key")

    override val portDescriptions = mapOf(
        "_module" to "Compute a keyed message authentication code, proving the input came from someone holding the key and was not altered. Use it to authenticate a message; use Hash when there is no key, and Signature when the verifier must not be able to forge.",
        "in" to "The bytes to authenticate. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "key" to "The shared secret, as raw bytes: 'hex:', 'b64:', or text taken as UTF-8. Any length; HMAC folds it to the hash's block size.",
        "out" to "The code as lowercase hex.",
    )

    override val optionDescriptions = mapOf(
        "algo" to "HmacSHA256 unless something else requires otherwise. The SHA-1 and MD5 variants are for old protocols; SHA3 variants where a different family is wanted.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val key = inputs["key"] ?: return mapOf("out" to null)
        val algo = options["algo"] ?: "HmacSHA256"
        // an unknown algorithm or an empty key throws — the host surfaces that as visible output
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key, algo))
        val hex = mac.doFinal(data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return mapOf("out" to hex.encodeToByteArray())
    }
}
