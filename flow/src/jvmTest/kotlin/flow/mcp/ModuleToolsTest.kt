package flow.mcp

import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.OptType
import flow.platform.Platform
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
 * Every processor as a tool of its own, and the schema a caller is handed for it.
 *
 * A model reads JSON Schema literally and calls exactly what it says, so the schema is the
 * interface — a port marked required that is not costs a failed call every time, and an enum that
 * lists a value the module rejects costs one every time it is picked.
 *
 * The schema rules are checked as pure functions against a made-up module, so they hold wherever
 * this runs; the calls that need a real installed extension say so and step aside where there is
 * none, which is the case on a fresh CI runner.
 */
class ModuleToolsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A module shaped like cipher: a port that exists but need not be given. */
    private val sample = ModuleInfo(
        id = "flow.sample",
        name = "Sample",
        inputs = listOf("in", "key", "iv"),
        outputs = listOf("out"),
        options = listOf(
            OptDef("mode", OptType.SELECT, "CBC", listOf("ECB", "CBC")),
            OptDef("rounds", OptType.NUMBER, "3"),
        ),
        version = "1.0.0",
        optionalInputs = listOf("iv"),
        portDescriptions = mapOf("_module" to "Does the thing.", "iv" to "16 bytes, 'hex:' or 'b64:'. Optional."),
        optionDescriptions = mapOf("mode" to "CBC unless you need ECB."),
    )

    /* ───────── the schema ───────── */

    @Test
    fun `required is the ports that exist less the ones that may be left out`() {
        val required = ModuleTools.schema(sample)["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("in", "key"), required, "a port that need not be given was marked required")
    }

    @Test
    fun `an optional port is still in the schema, so a caller knows it exists`() {
        val properties = ModuleTools.schema(sample)["properties"]!!.jsonObject
        assertTrue("iv" in properties, "the optional port was dropped instead of being made optional")
        assertTrue(properties["iv"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("Optional"))
    }

    @Test
    fun `options are never required, and carry their choices and default`() {
        val schema = ModuleTools.schema(sample)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.none { it == "mode" || it == "rounds" }, "an option was required: $required")
        val mode = schema["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals(listOf("ECB", "CBC"), mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("CBC", mode["default"]!!.jsonPrimitive.content)
        assertEquals("CBC unless you need ECB.", mode["description"]!!.jsonPrimitive.content)
        // a NUMBER option is a number in the schema, not a string that happens to hold digits
        assertEquals("number", schema["properties"]!!.jsonObject["rounds"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a module with nothing optional requires all of its ports`() {
        val strict = sample.copy(optionalInputs = emptyList())
        assertEquals(
            listOf("in", "key", "iv"),
            ModuleTools.schema(strict)["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * Tool names go through OpenAI-style function calling on the way to a model, which accepts
     * `[A-Za-z0-9_-]` and nothing else — a dot in the name is a tool the client refuses to register.
     */
    @Test
    fun `tool names are ones a function-calling client will accept`() {
        assertEquals("flow_cipher", ModuleTools.toolName("flow.cipher"))
        assertEquals("flow_view_asn1", ModuleTools.toolName("flow.view.asn1"))
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(ModuleTools.toolName("weird id/with:stuff")))
    }

    /* ───────── the whole list, as a client receives it ───────── */

    private fun listTools(): List<JsonObject> {
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        return json.parseToJsonElement(McpServer.handleForTest(request)!!)
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `every tool has a name a client accepts, and no two share one`() {
        val names = listTools().map { it["name"]!!.jsonPrimitive.content }
        assertTrue(names.isNotEmpty())
        names.forEach { assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(it), "'$it' is not a usable tool name") }
        assertEquals(names.size, names.toSet().size, "two tools share a name: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    /**
     * A required property that is not a property at all is a schema no caller can satisfy — and
     * nothing else would notice, since the tool simply never succeeds.
     */
    @Test
    fun `nothing is required that the schema does not offer`() {
        listTools().forEach { tool ->
            val schema = tool["inputSchema"]!!.jsonObject
            val properties = (schema["properties"] as? JsonObject)?.keys.orEmpty()
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(
                properties.containsAll(required),
                "${tool["name"]}: required ${required - properties} is not in the schema",
            )
        }
    }

    /* ───────── calling one for real ───────── */

    private fun installed(id: String): ModuleInfo? = Platform.installedModuleInfos().find { it.id == id }

    private fun call(name: String, arguments: String): JsonObject {
        val request = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
        return json.parseToJsonElement(McpServer.handleForTest(request)!!).jsonObject["result"]!!.jsonObject
    }

    private fun text(result: JsonObject) = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `a module runs directly, with no flow to build first`() {
        if (installed("flow.hash") == null) return
        val out = call("flow_hash", """{"in":"abc","algo":"SHA-256"}""")
        assertTrue(!out["isError"]!!.jsonPrimitive.boolean, text(out))
        assertTrue(text(out).contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), text(out))
    }

    @Test
    fun `bytes go in and come back in a form that can be fed straight back`() {
        if (installed("flow.base64") == null) return
        // 'hex:' in, and the output is printable so it comes back as itself
        assertTrue(call("flow_base64", """{"in":"hex:68656c6c6f"}""").let { text(it) }.contains("aGVsbG8="))
        // a value that is not printable comes back as 'hex:', ready to be passed on unchanged
        val binary = call("flow_base64", """{"in":"//8=","mode":"decode"}""")
        assertTrue(text(binary).contains("hex:"), text(binary))
    }

    /**
     * The acceptance the whole item is for: an AES call with no `iv` at all.
     *
     * It used to be required, so a caller had to send `""` and hope. Now leaving it out means what
     * the module always meant by it — generate one and prepend it.
     */
    @Test
    fun `an AES call with no iv works, and is refused without a key`() {
        if (installed("flow.cipher") == null) return
        val encrypted = call(
            "flow_cipher",
            """{"in":"attack at dawn","key":"hex:000102030405060708090a0b0c0d0e0f","algorithm":"AES","mode":"CBC"}""",
        )
        assertTrue(!encrypted["isError"]!!.jsonPrimitive.boolean, text(encrypted))
        assertTrue(text(encrypted).startsWith("out: hex:"), text(encrypted))

        val missing = call("flow_cipher", """{"in":"x"}""")
        assertTrue(missing["isError"]!!.jsonPrimitive.boolean)
        val detail = missing["structuredContent"]!!.jsonObject
        assertEquals(ToolFailure.MISSING_PORT, detail["code"]!!.jsonPrimitive.content)
        assertEquals("key", detail["port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an option outside the enum is refused with the real choices`() {
        if (installed("flow.cipher") == null) return
        val out = call("flow_cipher", """{"in":"x","key":"hex:00","mode":"NOSUCH"}""")
        val detail = out["structuredContent"]!!.jsonObject
        assertEquals(ToolFailure.INVALID_OPTION, detail["code"]!!.jsonPrimitive.content)
        assertEquals("mode", detail["port"]!!.jsonPrimitive.content)
        assertTrue(detail["hint"]!!.jsonPrimitive.content.contains("CBC"), detail.toString())
    }
}
