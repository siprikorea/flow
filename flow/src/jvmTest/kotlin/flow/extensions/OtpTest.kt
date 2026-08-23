package flow.extensions

import flow.otp.HotpExtension
import flow.otp.TotpExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HOTP against the vectors in RFC 4226 appendix D. They are the definition of correct here — a
 * one-time password that is merely plausible is worthless.
 */
class OtpTest {
    private val secret = "12345678901234567890".encodeToByteArray()

    private fun hotp(counter: Long, options: Map<String, String> = emptyMap()) =
        HotpExtension().process(
            mapOf("secret" to secret, "counter" to counter.toString().encodeToByteArray()),
            options,
        )["out"]!!.decodeToString()

    @Test
    fun `the RFC 4226 vectors`() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code -> assertEquals(code, hotp(counter.toLong()), "counter $counter") }
    }

    @Test
    fun `a base32 secret is the same secret`() {
        // authenticator apps hand out base32, so both spellings have to give the same code
        val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".encodeToByteArray()
        val fromBase32 = HotpExtension().process(
            mapOf("secret" to base32, "counter" to "0".encodeToByteArray()), emptyMap(),
        )["out"]!!.decodeToString()
        assertEquals("755224", fromBase32)
    }

    @Test
    fun `the digit count is honoured`() {
        assertTrue(hotp(0, mapOf("digits" to "8")).matches(Regex("""\d{8}""")))
    }

    @Test
    fun `a counter that is not a number is refused rather than guessed at`() {
        val e = runCatching {
            HotpExtension().process(
                mapOf("secret" to secret, "counter" to "later".encodeToByteArray()), emptyMap(),
            )
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("counter") == true, "got: ${e?.message}")
    }

    @Test
    fun `a time-based code is a code of the right shape`() {
        val out = TotpExtension().process(mapOf("secret" to secret), emptyMap())["out"]!!.decodeToString()
        assertTrue(out.matches(Regex("""\d{6}""")), "got '$out'")
    }
}
