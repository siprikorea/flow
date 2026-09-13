package flow.mcp

import flow.mcp.stdio.McpAnswer
import flow.mcp.stdio.McpStdio
import flow.mcp.stdio.McpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Flow's tools, on the wire.
 *
 * The protocol itself is the official MCP Java SDK's, wrapped by :flow-mcp — initialize, the
 * capability handshake, tools/list, tools/call, ping, the framing, and the version negotiation
 * that moves with the spec are all its work now rather than ours. What is left here is the join:
 * [FlowTools]' tools, described the way the protocol wants them and called the way Flow means
 * them.
 *
 * The tool list is settled when the server starts. It used to be rebuilt per request, which read
 * as though a processor installed while the server ran would be picked up; it never was — this
 * process scans the extension folder once and caches it, and an install happens in the app's
 * process, not this one. A client that wants a newly installed processor restarts the server,
 * which is what a client does anyway.
 */
object FlowMcpServer {
    private const val SERVER_NAME = "flow"
    private const val SERVER_VERSION = "1.0.0"

    private val json = Json { ignoreUnknownKeys = true }

    fun run() {
        System.err.println("[flow-mcp] listening on stdio")
        McpStdio.serve(SERVER_NAME, SERVER_VERSION, tools())
    }

    /** Every Flow tool, as the protocol describes one. */
    fun tools(): List<McpTool> = FlowTools.specs().map { spec ->
        McpTool(
            name = spec.name,
            description = spec.description,
            schema = spec.schema.toString(),
            call = { args -> answer(spec.name, args) },
        )
    }

    /**
     * One call: the arguments arrive as JSON text (the SDK's Jackson and Flow's kotlinx.serialization
     * meet as a string rather than as a type), and what the tool answers goes back as both the text
     * every client shows and the object a client that reads `structuredContent` can branch on.
     */
    private fun answer(name: String, args: String): McpAnswer {
        val arguments = runCatching { json.parseToJsonElement(args) as JsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val answered = FlowTools.call(name, arguments)
        return McpAnswer(
            text = answered.text,
            structured = answered.failure?.toString(),
            isError = answered.isError,
        )
    }
}
