package flow.otp

import flow.extension.ModuleOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-time passwords (RFC 4226 / 6238).
 *
 * The counter-based code is the whole of it: a time-based one is the same function over a counter
 * derived from the clock, so [TotpModule] works out that counter and hands it here.
 */
internal object Otp {
    val ALGORITHMS = listOf("HmacSHA1", "HmacSHA256", "HmacSHA512")
    val DIGITS = listOf("6", "7", "8")

    fun code(secret: ByteArray, counter: Long, algorithm: String, digits: Int): String {
        require(secret.isNotEmpty()) { "no secret" }
        val message = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        val hash = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }.doFinal(message)
        // RFC 4226 dynamic truncation: the low nibble of the last byte picks the 4-byte window
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * Secrets are usually written in base32 (that is what an authenticator app is given), so that
     * is accepted as well as raw bytes. A value that is not base32 is taken as the bytes it is.
     */
    fun secretBytes(raw: ByteArray): ByteArray {
        val text = raw.decodeToString().trim().uppercase().replace(" ", "").trimEnd('=')
        if (text.isEmpty() || text.any { it !in BASE32 }) return raw
        var buffer = 0L
        var bits = 0
        val out = ArrayList<Byte>(text.length * 5 / 8 + 1)
        text.forEach { c ->
            buffer = (buffer shl 5) or BASE32.indexOf(c).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return if (out.isEmpty()) raw else out.toByteArray()
    }

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun options() = listOf(
        ModuleOption("algorithm", OptionType.SELECT, "HmacSHA1", ALGORITHMS),
        ModuleOption("digits", OptionType.SELECT, "6", DIGITS),
    )

    fun algorithmOf(options: Map<String, String>) =
        options["algorithm"]?.takeIf { it in ALGORITHMS } ?: "HmacSHA1"

    fun digitsOf(options: Map<String, String>) =
        options["digits"]?.toIntOrNull()?.coerceIn(6, 8) ?: 6
}
