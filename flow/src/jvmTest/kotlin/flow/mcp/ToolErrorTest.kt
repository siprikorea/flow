package flow.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        return json.parseToJsonElement(McpServer.handleForTest(request)!!).jsonObject
    }

    private fun failure(result: JsonObject): JsonObject = result["result"]!!.jsonObject

    @Test
    fun `an unknown tool is a failure with a code and a way forward`() {
        val out = failure(call("nonesuch"))
        assertTrue(out["isError"]!!.jsonPrimitive.boolean)
        val detail = out["structuredContent"]!!.jsonObject
        assertEquals(ToolFailure.NOT_FOUND, detail["code"]!!.jsonPrimitive.content)
        assertTrue(detail["hint"]!!.jsonPrimitive.content.contains("tools/list"))
        // the text half carries the same thing, for a client that only shows the string
        val text = out["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains(ToolFailure.NOT_FOUND), text)
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
     * The rule that matters most, and the one a later change is most likely to break: whatever a
     * failure says, it never says what was in a port.
     */
    @Test
    fun `no failure repeats the value it was given`() {
        val secret = "S3CRET-KEY-MATERIAL"
        val causes = listOf(
            javax.crypto.BadPaddingException(secret),
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
