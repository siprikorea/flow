package flow.extensions

import flow.jwt.JwtExtension
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifying a JWT — including refusing the ones that should not verify. */
class JwtTest {
    private val secret = "my-secret".encodeToByteArray()

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun token(header: String, payload: String, signWith: ByteArray? = secret, alg: String = "HmacSHA256"): String {
        val h = b64(header.encodeToByteArray())
        val p = b64(payload.encodeToByteArray())
        val sig = signWith?.let {
            Mac.getInstance(alg).apply { init(SecretKeySpec(it, alg)) }.doFinal("$h.$p".toByteArray())
        } ?: ByteArray(0)
        return "$h.$p.${b64(sig)}"
    }

    private fun verify(token: String, secret: ByteArray = this.secret, options: Map<String, String> = emptyMap()) =
        JwtExtension().process(mapOf("jwt" to token.encodeToByteArray(), "secret" to secret), options)

    private fun refusal(block: () -> Unit): String =
        runCatching(block).exceptionOrNull()?.message ?: error("it was accepted")

    @Test
    fun `a valid token comes apart into its three pieces`() {
        val out = verify(token("""{"alg":"HS256","typ":"JWT"}""", """{"sub":"1"}"""))
        assertEquals("""{"alg":"HS256","typ":"JWT"}""", out["header"]!!.decodeToString())
        assertEquals("""{"sub":"1"}""", out["payload"]!!.decodeToString())
        assertEquals(32, out["signature"]!!.size)
    }

    @Test
    fun `the wrong secret is refused`() {
        val message = refusal { verify(token("""{"alg":"HS256"}""", "{}"), "other".encodeToByteArray()) }
        assertContains(message, "signature")
    }

    @Test
    fun `a token signed with a different algorithm than expected is refused`() {
        // a token naming its own algorithm is how HMAC confusion gets in, so the caller's
        // expectation wins over the header
        val message = refusal { verify(token("""{"alg":"HS256"}""", "{}"), options = mapOf("expect" to "HS512")) }
        assertContains(message, "HS256")
        assertContains(message, "HS512")
    }

    @Test
    fun `alg none is refused even though it carries no signature to check`() {
        val message = refusal {
            verify(token("""{"alg":"none"}""", "{}", signWith = null), options = mapOf("expect" to "any"))
        }
        assertTrue(message.contains("none"), "got: $message")
    }

    @Test
    fun `something that is not a JWT is refused before anything else`() {
        assertContains(refusal { verify("not.a") }, "three")
        assertContains(refusal { verify("") }, "no JWT")
    }
}
