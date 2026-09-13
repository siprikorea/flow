package flow.mcp

import flow.core.EditorState
import flow.core.Workspace
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where a secret is allowed to appear, and where it is not.
 *
 * A key or a password reaches this program from a person and then has many chances to be written
 * down — into the flow file, into a log, into an error message, into whatever a client keeps of a
 * conversation. Each of those is permanent and none of them is noticed at the time.
 */
class SensitiveValueTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Built rather than written inline: a Kotlin string cannot hold ${'$'}{...} without interpolating it. */
    private val ENV_REF = "${'$'}{ENV:FLOW_TEST_KEY_THAT_IS_NOT_SET}"

    /**
     * A saved flow carries no port values.
     *
     * This already holds — flowJson() strips them — and that is exactly why it is worth a test:
     * it is two lines in the middle of a serializer, nothing else depends on them, and a refactor
     * that dropped them would put every key someone had typed into a file they then share.
     */
    @Test
    fun `saving a flow writes no port data, whatever was typed into it`() {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val doc = EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "secret.flow")
        val privateKey = "-----BEGIN PRIVATE KEY-----MIIEvQIBADANBg-----END PRIVATE KEY-----"
        doc.load(
            FlowFile(
                nodes = listOf(
                    Node(
                        id = "cin_1", type = "cin", label = "key", x = 0f, y = 0f, w = 120f, h = 60f,
                        inputs = emptyList(),
                        outputs = listOf(Port("out", privateKey.encodeToByteArray())),
                    ),
                ),
            ),
        )
        val saved = doc.flowJson()
        assertTrue(!saved.contains("PRIVATE KEY"), "the key is in the file as text")
        // and not as the hex the port serializer would write either
        val hex = privateKey.encodeToByteArray().joinToString("") { "%02x".format(it) }
        assertTrue(!saved.contains(hex), "the key is in the file as hex")
        assertTrue(!saved.contains("\"data\""), "a port wrote its value into the file:\n$saved")
    }

    /* ───────── the ports that hold secrets say so ───────── */

    @Test
    fun `the modules that take a key or a password name those ports as sensitive`() {
        val expected = mapOf(
            "flow.cipher" to "key", "flow.mac" to "key", "flow.jwt" to "secret",
            "flow.keystore" to "password", "flow.signature" to "key",
            "flow.hotp" to "secret", "flow.totp" to "secret",
        )
        val installed = Platform.installedModuleInfos().associateBy { it.id }
        var checked = 0
        expected.forEach { (id, port) ->
            val module = installed[id] ?: return@forEach
            checked++
            assertTrue(
                port in module.sensitiveInputs,
                "$id does not mark '$port' sensitive: ${module.sensitiveInputs}",
            )
        }
        assertTrue(checked > 0, "no modules installed to check")
    }

    /**
     * A secret port's schema offers the way to use it without sending it.
     *
     * If the only documented way to pass a key is to put it in the call, then every call that uses
     * one puts a key in the transcript. The reference has to be visible in the schema or nobody
     * uses it.
     */
    @Test
    fun `a sensitive port's description offers the environment reference`() {
        val cipher = Platform.installedModuleInfos().find { it.id == "flow.cipher" } ?: return
        val schema = ModuleTools.schema(cipher)
        val key = schema["properties"]!!.jsonObject["key"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(key.contains("${'$'}{ENV:"), "the key port does not mention the reference: ${'$'}key")
        val input = schema["properties"]!!.jsonObject["in"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(!input.contains("${'$'}{ENV:"), "a port that is not sensitive advertises it anyway")
    }

    /* ───────── the reference itself ───────── */

    private fun call(name: String, arguments: String) =
        json.parseToJsonElement(
            McpTestServer.request(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""",
            ),
        ).jsonObject["result"]!!.jsonObject

    @Test
    fun `an environment reference that is not set says which variable, not what was wanted`() {
        if (Platform.installedModuleInfos().none { it.id == "flow.cipher" }) return
        val out = call(
            "flow_cipher",
            "{\"in\":\"x\",\"key\":\"" + ENV_REF + "\"}",
        )
        assertTrue(out["isError"]!!.jsonPrimitive.boolean, out.toString())
        val hint = out["structuredContent"]!!.jsonObject["hint"]!!.jsonPrimitive.content
        assertTrue(hint.contains("FLOW_TEST_KEY_THAT_IS_NOT_SET"), hint)
    }

    /**
     * Whatever a call does, it does not repeat the key back.
     *
     * The failure paths are the ones that leak: a caller sends a key, something is wrong, and the
     * message quotes what it was given. Here the key is deliberately the wrong length, so the call
     * fails — and the response is searched for it.
     */
    @Test
    fun `a failing call does not echo the key it was given`() {
        if (Platform.installedModuleInfos().none { it.id == "flow.cipher" }) return
        val key = "hex:00112233445566778899aabb"  // 12 bytes: not a length AES accepts
        val out = call("flow_cipher", """{"in":"secret text","key":"$key","algorithm":"AES"}""")
        val whole = out.toString()
        assertTrue(!whole.contains("00112233445566778899aabb"), "the response repeated the key:\n$whole")
        assertTrue(!whole.contains("secret text"), "the response repeated the plaintext:\n$whole")
    }
    /**
     * A file that already carries a value is reported, not passed over.
     *
     * Saving strips port data, so a file with some in it was written by an older build or edited
     * by hand — and nothing would ever have said so. The ports that carry values here are keys and
     * plaintexts, and a value in a file is a value in version control and in every copy of it.
     */
    @Test
    fun `validating a flow reports a value left in the file, without repeating it`() {
        val secret = "hunter2-the-actual-key"
        val flow = FlowFile(
            nodes = listOf(
                Node(
                    id = "cin_1", type = "cin", label = "key", x = 0f, y = 0f, w = 120f, h = 60f,
                    inputs = emptyList(), outputs = listOf(Port("out", secret.encodeToByteArray())),
                    params = emptyMap(), status = "idle",
                ),
            ),
            edges = emptyList(), seq = 1,
        )
        val problems = validateFlow(flow)
        val said = problems.filter { it.contains("cin_1.out") }
        assertTrue(said.isNotEmpty(), "a saved value went unreported: $problems")
        assertTrue(said.single().contains("${secret.length} bytes"), said.single())
        assertTrue(
            problems.none { it.contains("hunter2") },
            "the report repeated the value it was warning about: $problems",
        )
    }

    @Test
    fun `a flow with nothing left in its ports is not reported`() {
        val flow = FlowFile(
            nodes = listOf(
                Node(
                    id = "cin_1", type = "cin", label = "key", x = 0f, y = 0f, w = 120f, h = 60f,
                    inputs = emptyList(), outputs = listOf(Port("out", ByteArray(0))),
                    params = emptyMap(), status = "idle",
                ),
            ),
            edges = emptyList(), seq = 1,
        )
        assertTrue(validateFlow(flow).none { it.contains("bytes of value") }, "${validateFlow(flow)}")
    }

}
