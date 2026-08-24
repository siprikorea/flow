package flow.otp

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension

/** Counter-based one-time password (RFC 4226). */
class HotpExtension : ProcessorExtension {
    override val id = "flow.hotp"
    override val displayName = "HOTP"
    override val version = "1.0.0"
    override val inputs = listOf("secret", "counter")
    override val outputs = listOf("out")
    override val options = Otp.options()

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val secret = Otp.secretBytes(inputs["secret"] ?: ByteArray(0))
        val text = (inputs["counter"] ?: ByteArray(0)).decodeToString().trim()
        val counter = text.toLongOrNull()
            ?: error(if (text.isEmpty()) "no counter on 'counter'" else "'$text' is not a counter")
        val code = Otp.code(secret, counter, Otp.algorithmOf(options), Otp.digitsOf(options))
        return mapOf("out" to code.encodeToByteArray())
    }
}
