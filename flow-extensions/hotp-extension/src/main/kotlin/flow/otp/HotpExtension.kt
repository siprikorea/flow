package flow.otp

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension

/** Counter-based one-time password (RFC 4226). */
class HotpExtension : ProcessorExtension {
    override val id = "flow.hotp"
    override val displayName = "HOTP"
    override val version = "1.1.2"
    override val category = "crypto"
    override val inputs = listOf("secret", "counter")
    override val outputs = listOf("out")
    override val options = Otp.options()

    /** the shared secret — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("secret")

    override val portDescriptions = mapOf(
        "_module" to "A counter-based one-time password (RFC 4226). Use it where the two sides keep a counter in step; for the usual authenticator-app code, which moves with the clock, use TOTP.",
        "secret" to "The shared secret. Raw bytes as 'hex:'/'b64:', or the base32 string an authenticator app shows.",
        "counter" to "The counter value, as decimal text. It must match the other side's.",
        "out" to "The code, zero-padded to the chosen number of digits.",
    )

    override val optionDescriptions = mapOf(
        "algorithm" to "HmacSHA1 is what RFC 4226 specifies and what almost every implementation expects. The others only work if the other side agrees.",
        "digits" to "How many digits the code has. 6 is near-universal.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val secret = Otp.secretBytes(inputs["secret"] ?: ByteArray(0))
        val text = (inputs["counter"] ?: ByteArray(0)).decodeToString().trim()
        val counter = text.toLongOrNull()
            ?: error(if (text.isEmpty()) "no counter on 'counter'" else "'$text' is not a counter")
        val code = Otp.code(secret, counter, Otp.algorithmOf(options), Otp.digitsOf(options))
        return mapOf("out" to code.encodeToByteArray())
    }
}
