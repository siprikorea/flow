package flow.mcp

import flow.cli.RunnableComponent
import flow.cli.listComponents
import flow.cli.runComponent
import flow.model.OptType
import flow.platform.Platform
import flow.util.bytesToHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

/**
 * MCP server over stdio: exposes every component and installed module as a tool, so an MCP client
 * (Claude Desktop/Code, …) can list them and run them.
 *
 * Speaks JSON-RPC 2.0, one message per line, implementing initialize / tools/list / tools/call /
 * ping. Only protocol messages go to stdout — anything else is written to stderr.
 */
object McpServer {
    private const val PROTOCOL_VERSION = "2024-11-05"
    // marks a byte-valued port on the way out and on the way back in
    private const val HEX_PREFIX = "hex:"
    private const val SERVER_NAME = "flow"
    private const val SERVER_VERSION = "1.0.0"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun run() {
        System.err.println("[flow-mcp] listening on stdio")
        generateSequence(::readLine).forEach { line ->
            if (line.isBlank()) return@forEach
            val response = runCatching { handle(line) }.getOrElse { e ->
                error(JsonNull, -32603, e.message ?: "internal error")
            }
            if (response != null) {
                println(json.encodeToString(JsonObject.serializer(), response))
                System.out.flush()
            }
        }
    }

    // null = the message was a notification, which takes no reply
    private fun handle(line: String): JsonObject? {
        val request = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return error(JsonNull, -32700, "parse error")
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content ?: return error(id ?: JsonNull, -32600, "missing method")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null) return null // notification (e.g. notifications/initialized)

        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", toolList()) })
            "tools/call" -> result(id, callTool(params))
            else -> error(id, -32601, "unknown method: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        // echo the client's protocol version when it names one, so a newer client isn't refused
        val version = params["protocolVersion"]?.jsonPrimitive?.content ?: PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
        }
    }

    /* ───────── tools ───────── */

    // A tool name must be [a-zA-Z0-9_-]; component and module names are not that restricted.
    private fun toolName(prefix: String, raw: String): String =
        prefix + "_" + raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

    private fun portDescription(port: String) =
        "input port '$port': text, or bytes as '${HEX_PREFIX}EB F6 …' (the form binary output comes back in)"

    private class Tool(val name: String, val description: String, val schema: JsonObject, val call: (JsonObject) -> String)

    // Rebuilt per request so flows saved while the server runs show up without a restart.
    private fun tools(): List<Tool> {
        val out = mutableListOf<Tool>()
        val taken = mutableSetOf<String>()
        fun unique(name: String): String {
            if (taken.add(name)) return name
            var n = 2
            while (!taken.add("$name$n")) n++
            return "$name$n"
        }

        listComponents().forEach { target ->
            val comp = target.comp
            val name = unique(toolName("flow", comp.name))
            val where = if (target.installed) "installed component" else "project flow"
            out += Tool(
                name = name,
                description = "Run the Flow $where '${comp.name}' (${comp.ins.joinToString(", ")} → ${comp.outs.joinToString(", ")})",
                schema = objectSchema(comp.ins.map { StringField(it, portDescription(it)) }),
                call = { args -> runComponentTool(target, args) },
            )
        }

        Platform.installedModuleInfos().forEach { module ->
            val name = unique(toolName("module", module.id))
            val fields = module.inputs.map { StringField(it, portDescription(it)) } +
                module.options.map { opt ->
                    StringField(
                        opt.name,
                        "option '${opt.name}'" + if (opt.default.isNotEmpty()) " (default: ${opt.default})" else "",
                        enum = opt.choices.takeIf { opt.type == OptType.SELECT },
                        number = opt.type == OptType.NUMBER,
                        required = false,
                    )
                }
            out += Tool(
                name = name,
                description = "Run the Flow module '${module.name}' (${module.id}): " +
                    "${module.inputs.joinToString(", ")} → ${module.outputs.joinToString(", ")}",
                schema = objectSchema(fields),
                call = { args -> runModuleTool(module.id, module.inputs, module.options.map { it.name }, args) },
            )
        }
        return out
    }

    private fun toolList(): JsonArray = buildJsonArray {
        tools().forEach { tool ->
            addJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", tool.schema)
            }
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content ?: return toolError("missing tool name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = tools().find { it.name == name } ?: return toolError("unknown tool: $name")
        return runCatching { toolText(tool.call(args)) }
            .getOrElse { e -> toolError(e.message ?: e::class.simpleName ?: "error") }
    }

    private fun runComponentTool(target: RunnableComponent, args: JsonObject): String {
        val inputs = target.comp.ins.associateWith { port -> argBytes(args, port) }
        val (result, errors) = runComponent(target, inputs)
        val body = target.comp.outs.joinToString("\n") { "$it = ${render(result[it])}" }
        if (errors.isEmpty()) return body
        return body + "\n" + errors.entries.joinToString("\n") { (node, message) -> "! $node: $message" }
    }

    private fun runModuleTool(id: String, ports: List<String>, optionNames: List<String>, args: JsonObject): String {
        val options = optionNames.mapNotNull { name -> args[name]?.let { name to argText(args, name) } }.toMap()
        val inputs = (Platform.moduleInputsFor(id, options) ?: ports)
            .associateWith { port -> args[port]?.let { argBytes(args, port) } }
        val result = Platform.moduleProcess(id, inputs, options)
        val outs = Platform.moduleOutputsFor(id, options) ?: result.keys.toList()
        return outs.joinToString("\n") { "$it = ${render(result[it])}" }
    }

    // arguments arrive as JSON values; anything non-string is written out plainly
    private fun argText(args: JsonObject, key: String): String {
        val value = args[key] ?: return ""
        return (value as? JsonPrimitive)?.content ?: value.toString()
    }

    /**
     * Port bytes for one argument: plain text, unless it carries the [HEX_PREFIX] that binary
     * output is rendered with — which is what lets one tool's output feed the next one's input.
     * Both "hex:EB F6" and "hex:EBF6" are accepted; anything else under the prefix is an error
     * rather than a silent fallback to text.
     */
    private fun argBytes(args: JsonObject, key: String): ByteArray {
        val text = argText(args, key)
        if (!text.startsWith(HEX_PREFIX)) return text.encodeToByteArray()
        val digits = text.removePrefix(HEX_PREFIX).filterNot { it.isWhitespace() }
        require(digits.length % 2 == 0 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "'$key' is not valid hex: give whole byte pairs after '$HEX_PREFIX', e.g. ${HEX_PREFIX}EB F6"
        }
        return ByteArray(digits.length / 2) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /* ───────── schema + result helpers ───────── */

    private class StringField(
        val name: String,
        val description: String,
        val enum: List<String>? = null,
        val number: Boolean = false,
        val required: Boolean = true,
    )

    private fun objectSchema(fields: List<StringField>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { field ->
                putJsonObject(field.name) {
                    put("type", if (field.number) "number" else "string")
                    put("description", field.description)
                    field.enum?.let { choices -> putJsonArray("enum") { choices.forEach { add(it) } } }
                }
            }
        }
        putJsonArray("required") { fields.filter { it.required }.forEach { add(it.name) } }
    }

    private fun toolText(text: String): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        put("isError", false)
    }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", message)
            }
        }
        put("isError", true)
    }

    // port bytes as text when they are text, else hex — an MCP client only carries text here
    private fun render(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val text = bytes.decodeToString()
        val roundTrips = text.encodeToByteArray().contentEquals(bytes)
        val printable = text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        return if (roundTrips && printable) text else HEX_PREFIX + bytesToHex(bytes)
    }

    /* ───────── JSON-RPC envelopes ───────── */

    private fun result(id: JsonElement, payload: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", payload)
    }

    private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}
