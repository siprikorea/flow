package flow.mcp.stdio

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import java.io.InputStream
import java.io.OutputStream

/**
 * One tool, as a client sees it.
 *
 * JSON crosses this boundary as text on purpose. The caller has its own JSON library and the SDK
 * has Jackson; handing over a string keeps the two from having to agree on a type, and costs one
 * parse of a payload that was just parsed — which is nothing next to what a tool then does.
 */
class McpTool(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments, as JSON text. */
    val schema: String,
    /** The arguments as a JSON object, as text; answer with [McpAnswer]. */
    val call: (String) -> McpAnswer,
)

/**
 * What a tool answered.
 *
 * [text] is what every client shows. [structured] is the same answer as JSON for a client that
 * reads it — MCP's `structuredContent` — and is left out when there is nothing more to say than
 * the text.
 */
class McpAnswer(val text: String, val structured: String? = null, val isError: Boolean = false)

/**
 * An MCP server on stdin/stdout, built on the official Java SDK.
 *
 * The protocol lives here and nowhere else: initialize, the capability handshake, tools/list,
 * tools/call, ping, notifications, the framing, and the version negotiation that changes with the
 * spec. A caller supplies tools and knows none of it.
 */
object McpStdio {

    val jsonMapper: McpJsonMapper get() = McpJsonDefaults.getMapper()

    /**
     * Serves [tools] until the client closes the pipe.
     *
     * Returns as soon as the server is up. The transport reads stdin on its own non-daemon thread,
     * so the process stays alive for exactly as long as the client keeps the pipe open, and ends
     * when it doesn't — which is what a client expects of a server it started.
     */
    fun serve(
        name: String,
        version: String,
        tools: List<McpTool>,
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ): McpSyncServer {
        val mapper = jsonMapper
        return McpServer.sync(StdioServerTransportProvider(mapper, input, output))
            .serverInfo(name, version)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            // The SDK will check arguments against each tool's schema before calling it. Flow's
            // tools do their own checking and answer a bad call with a sentence saying what to fix,
            // and some accept more than the schema says — a nested array as a JSON string, which is
            // how some clients send one. Validating here would turn both of those into a protocol
            // error, so the tools stay the ones that decide what they accept.
            .validateToolInputs(false)
            .tools(tools.map { specificationFor(it, mapper) })
            .build()
    }

    private fun specificationFor(tool: McpTool, mapper: McpJsonMapper): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder(tool.name, mapper, tool.schema)
                    .description(tool.description)
                    .build(),
            )
            .callHandler { _, request ->
                val args = mapper.writeValueAsString(request.arguments() ?: emptyMap<String, Any?>())
                val answer = tool.call(args)
                val result = McpSchema.CallToolResult.builder()
                    .addTextContent(answer.text)
                    .isError(answer.isError)
                answer.structured?.let { result.structuredContent(mapper, it) }
                result.build()
            }
            .build()
}
