package flow.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failed call says what to change.
 *
 * The client here is increasingly a model, and a model does not read a sentence and think again —
 * it retries what it did. So a failure has to carry the thing that makes the next attempt
 * different: a code to branch on, the port at fault, and one sentence saying what to do about it.
 *
 * And it must carry nothing else. A key, an IV or a plaintext in an error message is a secret
 * written into a transcript the client keeps, which for a server whose whole job is handling those
 * is the worst thing it could leak.
 */
class ToolErrorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun call(name: String, arguments: String = "{}"): JsonObject {
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
        return json.parseToJsonElement(McpTestServer.request(request)).jsonObject
    }

    /** The tool result out of the JSON-RPC reply around it. */
    private fun failure(result: JsonObject): JsonObject = result["result"]!!.jsonObject

    /**
     * An unknown tool never reaches a tool, so the protocol answers it: JSON-RPC -32602, naming
     * what was asked for. That is the SDK's own answer and the spec's, and it is the one thing
     * here a client can already handle without reading a result at all.
     */
    @Test
    fun `an unknown tool is refused by the protocol, naming what was asked for`() {
        val error = call("nonesuch")["error"]!!.jsonObject
        assertEquals(-32602, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["data"]!!.jsonPrimitive.content.contains("nonesuch"), "$error")
    }

    /**
     * The assistant inside the app calls the same tools without a protocol between, so for it the
     * same mistake has to be an answer rather than a transport error — with the code and the way
     * forward every other failure carries.
     */
    @Test
    fun `an unknown tool asked for in the app is an answer, with a code and a way forward`() {
        val answered = FlowTools.call("nonesuch", JsonObject(emptyMap()))
        assertTrue(answered.isError)
        val detail = answered.failure!!
        assertEquals(ToolFailure.NOT_FOUND, detail["code"]!!.jsonPrimitive.content)
        assertTrue(answered.text.contains(ToolFailure.NOT_FOUND), answered.text)
    }

    @Test
    fun `a tool refused before it ran says which argument`() {
        // no path given, so it is refused rather than run
        val out = failure(call("read_flow"))
        assertTrue(out["isError"]!!.jsonPrimitive.boolean)
        val detail = out["structuredContent"]!!.jsonObject
        assertTrue(detail["code"] != null && detail["hint"] != null, "$detail")
    }

    /* ───────── what the JCE throws, narrowed ───────── */

    @Test
    fun `a padding failure names the three things that could have caused it`() {
        val f = ToolFailure.fromCrypto(javax.crypto.BadPaddingException("x"), "decrypt")
        assertEquals(ToolFailure.DECRYPT_FAILED, f.code)
        // the one failure that cannot honestly be narrowed further — so it says so rather than
        // guessing which of the three it was
        listOf("key", "IV", "padding").forEach {
            assertTrue(f.hint.contains(it, ignoreCase = true), "the hint does not mention $it: ${f.hint}")
        }
    }

    @Test
    fun `a key the algorithm cannot use is a key problem, on the key port`() {
        val f = ToolFailure.fromCrypto(java.security.InvalidKeyException("bad"), "encrypt")
        assertEquals(ToolFailure.KEY_LENGTH_MISMATCH, f.code)
        assertEquals("key", f.port)
        assertTrue(f.hint.contains("16"), f.hint)
    }

    @Test
    fun `an IV of the wrong size is an IV problem, on the iv port`() {
        val f = ToolFailure.fromCrypto(java.security.InvalidAlgorithmParameterException("bad"), "encrypt")
        assertEquals(ToolFailure.IV_MISMATCH, f.code)
        assertEquals("iv", f.port)
    }

    @Test
    fun `an algorithm the runtime does not have reads as a combination problem`() {
        val f = ToolFailure.fromCrypto(javax.crypto.NoSuchPaddingException("nope"), "encrypt")
        assertEquals(ToolFailure.INVALID_PORT_COMBINATION, f.code)
    }

    /**
     * A GCM tag mismatch is a failed decryption, not an internal error.
     *
     * It used to be reported as INTERNAL — "Not a failure this server knows how to narrow" — for
     * the one failure the mode exists to produce. A caller reading that cannot tell a tampered
     * ciphertext from a broken server.
     */
    @Test
    fun `a tag mismatch is the one GCM failure, and does not pretend to know which cause`() {
        val f = ToolFailure.fromCrypto(javax.crypto.AEADBadTagException("Tag mismatch"), "decrypt")
        assertEquals(ToolFailure.DECRYPT_FAILED, f.code)
        listOf("key", "aad", "tagLength", "altered").forEach {
            assertTrue(f.hint.contains(it, ignoreCase = true), "the hint does not mention $it: ${f.hint}")
        }
    }

    /**
     * A module that refused an argument gets to say so in its own words.
     *
     * It is the only thing that knows which argument and why — "'in' is not a number, so it cannot
     * be compared as one" is worth more than any sentence this server could write about it, and
     * INVALID_OPTION tells a caller it is theirs to fix.
     */
    @Test
    fun `an argument the module refused is the caller's to fix, in the module's own words`() {
        val f = ToolFailure.fromCrypto(
            IllegalArgumentException("'in' is not a number, so it cannot be compared as one"),
            "Branch",
        )
        assertEquals(ToolFailure.INVALID_OPTION, f.code)
        assertTrue(f.hint.contains("is not a number"), f.hint)
    }

    /**
     * The rule that matters most, and the one a later change is most likely to break: whatever a
     * failure says, it never says what was in a port.
     */
    @Test
    fun `no failure repeats the value it was given`() {
        val secret = "S3CRET-KEY-MATERIAL"
        val causes = listOf(
            javax.crypto.BadPaddingException(secret),
            javax.crypto.AEADBadTagException(secret),
            java.security.InvalidKeyException(secret),
            java.security.InvalidAlgorithmParameterException(secret),
            javax.crypto.IllegalBlockSizeException(secret),
        )
        causes.forEach { cause ->
            val f = ToolFailure.fromCrypto(cause, "decrypt")
            assertTrue(!f.hint.contains(secret), "the hint quoted the value: ${f.hint}")
            assertTrue(!(f.message ?: "").contains(secret), "the message quoted the value: ${f.message}")
            assertTrue(
                !f.toJson().toString().contains(secret),
                "the response quoted the value: ${f.toJson()}",
            )
        }
    }
}
