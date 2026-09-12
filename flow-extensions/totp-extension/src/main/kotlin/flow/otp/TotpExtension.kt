package flow.otp

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension

/**
 * Time-based one-time password (RFC 6238).
 *
 * The counter is the clock divided by the step, so the code changes on the step boundary rather
 * than a fixed interval after it is asked for — which is what makes two devices agree.
 */
class TotpExtension : ProcessorExtension {
    override val id = "flow.totp"
    override val displayName = "TOTP"
    override val version = "1.1.0"
    override val inputs = listOf("secret")
    override val outputs = listOf("out")
    override val options = Otp.options() + listOf(
        ExtensionOption("timeStep", OptionType.NUMBER, "30"),
    )

    /** the shared secret — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("secret")

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val secret = Otp.secretBytes(inputs["secret"] ?: ByteArray(0))
        val step = options["timeStep"]?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 30
        val counter = System.currentTimeMillis() / 1000 / step
        val code = Otp.code(secret, counter, Otp.algorithmOf(options), Otp.digitsOf(options))
        return mapOf("out" to code.encodeToByteArray())
    }
}
