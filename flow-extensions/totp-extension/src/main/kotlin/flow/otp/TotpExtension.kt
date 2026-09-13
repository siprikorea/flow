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
    override val version = "1.1.2"
    override val category = "crypto"
    override val inputs = listOf("secret")
    override val outputs = listOf("out")
    override val options = Otp.options() + listOf(
        ExtensionOption("timeStep", OptionType.NUMBER, "30"),
    )

    /** the shared secret — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("secret")

    override val portDescriptions = mapOf(
        "_module" to "A time-based one-time password (RFC 6238) — the code an authenticator app shows. Use it to check or produce that code; it reads the clock, so the two sides must agree on the time.",
        "secret" to "The shared secret. Raw bytes as 'hex:'/'b64:', or the base32 string an authenticator app shows.",
        "out" to "The code for the current time step, zero-padded to the chosen number of digits.",
    )

    override val optionDescriptions = mapOf(
        "algorithm" to "HmacSHA1 is what nearly every authenticator uses. The others only work if the other side agrees.",
        "digits" to "How many digits the code has. 6 is near-universal.",
        "timeStep" to "Seconds per code. 30 is the near-universal choice.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val secret = Otp.secretBytes(inputs["secret"] ?: ByteArray(0))
        val step = options["timeStep"]?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 30
        val counter = System.currentTimeMillis() / 1000 / step
        val code = Otp.code(secret, counter, Otp.algorithmOf(options), Otp.digitsOf(options))
        return mapOf("out" to code.encodeToByteArray())
    }
}
