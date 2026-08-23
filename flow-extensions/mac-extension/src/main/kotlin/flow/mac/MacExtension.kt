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
