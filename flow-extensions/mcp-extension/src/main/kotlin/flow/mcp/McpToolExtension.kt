package flow.mcp

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Calls a tool on an external MCP server from inside a flow.
 *
 * "command" starts the server (stdio transport) and "tool" names the tool to call: the "in" port
 * becomes the argument named by "argument", any further arguments come from "arguments" as a JSON
 * object, and the tool's text content lands on "out". With "tool" left blank the module lists the
 * server's tools instead, which is the quickest way to see what a server offers.
 */
class McpToolExtension : ProcessorExtension {
    override val id = "flow.mcp"
    override val displayName = "MCP Tool"
    override val version = "1.0.1"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        // e.g. npx -y @modelcontextprotocol/server-everything
        ExtensionOption("command", OptionType.TEXT, ""),
        ExtensionOption("tool", OptionType.TEXT, ""),
        ExtensionOption("argument", OptionType.TEXT, "input"),
        // extra arguments as a JSON object, e.g. {"format":"json"}
        ExtensionOption("arguments", OptionType.TEXT, ""),
        ExtensionOption("timeoutSec", OptionType.NUMBER, "30"),
    )

    private fun toolOf(values: Map<String, String>) = values["tool"].orEmpty().trim()

    // listing mode takes no input, and the argument options only matter once a tool is named
    override fun inputsFor(options: Map<String, String>): List<String> =
        if (toolOf(options).isEmpty()) emptyList() else listOf("in")

    override fun optionsFor(values: Map<String, String>): List<ExtensionOption> {
        val listing = toolOf(values).isEmpty()
        return options.filter { if (it.name == "argument" || it.name == "arguments") !listing else true }
    }

    override val portDescriptions = mapOf(
        "_module" to "Call a tool on an external MCP server from inside a flow. Use it to reach something Flow has no module for. It starts the server as a child process on every run, so it is as fast as that server is.",
        "in" to "The value passed as the argument named by 'argument'. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes, though most MCP tools want text.",
        "out" to "The tool's text content, or the server's tool list when 'tool' is empty.",
    )

    override val optionDescriptions = mapOf(
        "command" to "The command line that starts the server over stdio, e.g. 'npx -y @modelcontextprotocol/server-everything'.",
        "tool" to "Which tool to call. Left empty, the module lists what the server offers instead, which is the quickest way to find out.",
        "argument" to "The name of the argument the 'in' port feeds.",
        "arguments" to "Any further arguments, as a JSON object.",
        "timeoutSec" to "How long to wait for the server to answer before giving up.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val command = options["command"].orEmpty().trim()
        require(command.isNotEmpty()) { "set 'command' to the MCP server command line" }
        val tool = toolOf(options)
        val timeout = options["timeoutSec"]?.trim()?.toLongOrNull() ?: 30L

        val text = Session(command, timeout).use { session ->
            session.initialize()
            if (tool.isEmpty()) session.listTools() else {
                val argument = options["argument"].orEmpty().ifBlank { "input" }
                val extra = options["arguments"].orEmpty().trim()
                session.callTool(tool, argument, inputs["in"]?.decodeToString().orEmpty(), extra)
            }
        }
        return mapOf("out" to text.encodeToByteArray())
    }

    /** One server process and the JSON-RPC exchange over its stdio. */
    private class Session(command: String, private val timeoutSec: Long) : AutoCloseable {
        private val process = ProcessBuilder(splitCommand(command)).redirectErrorStream(false).start()
        private val stdin: BufferedWriter = process.outputStream.bufferedWriter()
        private val lines = ArrayBlockingQueue<String>(256)
        private var nextId = 1

        init {
            // both pipes need draining or the server blocks once a pipe buffer fills
            reader(process.inputStream.bufferedReader()) { lines.offer(it) }
            reader(process.errorStream.bufferedReader()) { /* server logs: ignored */ }
        }

        private fun reader(source: BufferedReader, onLine: (String) -> Unit) {
            Thread {
                runCatching { source.forEachLine(onLine) }
            }.apply { isDaemon = true }.start()
        }

        fun initialize() {
            request(
                "initialize",
                """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"flow","version":"1.0.0"}}""",
            )
            notify("notifications/initialized")
        }

        fun listTools(): String {
            val tools = MiniJson.path(request("tools/list", "{}"), "result", "tools") as? List<*>
                ?: return "(no tools)"
            return tools.joinToString("\n") { entry ->
                val name = MiniJson.path(entry, "name") ?: "?"
                val description = MiniJson.path(entry, "description") ?: ""
                "$name — $description"
            }
        }

        fun callTool(tool: String, argument: String, input: String, extraJson: String): String {
            val extra = extraJson.takeIf { it.isNotEmpty() }
                ?.let { MiniJson.parse(it) as? Map<*, *> ?: error("'arguments' must be a JSON object") }
                .orEmpty()
            val args = buildString {
                append("{")
                append(MiniJson.quote(argument)).append(":").append(MiniJson.quote(input))
                extra.forEach { (key, value) ->
                    append(",").append(MiniJson.quote(key.toString())).append(":").append(literal(value))
                }
                append("}")
            }
            val response = request(
                "tools/call",
                """{"name":${MiniJson.quote(tool)},"arguments":$args}""",
            )
            MiniJson.path(response, "error", "message")?.let { error("MCP error: $it") }
            val content = MiniJson.path(response, "result", "content") as? List<*> ?: return ""
            val text = content.mapNotNull { MiniJson.path(it, "text") as? String }.joinToString("\n")
            if (MiniJson.path(response, "result", "isError") == true) error(text.ifBlank { "tool reported an error" })
            return text
        }

        // JSON for a value that came out of MiniJson (used for the extra arguments)
        private fun literal(value: Any?): String = when (value) {
            null -> "null"
            is String -> MiniJson.quote(value)
            is Boolean -> value.toString()
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            else -> MiniJson.quote(value.toString())
        }

        private fun send(line: String) {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
        }

        private fun notify(method: String) = send("""{"jsonrpc":"2.0","method":${MiniJson.quote(method)}}""")

        // send a request and return the first response carrying its id
        private fun request(method: String, paramsJson: String): Any? {
            val id = nextId++
            send("""{"jsonrpc":"2.0","id":$id,"method":${MiniJson.quote(method)},"params":$paramsJson}""")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            while (true) {
                val wait = deadline - System.nanoTime()
                require(wait > 0) { "timed out waiting for '$method' after ${timeoutSec}s" }
                val line = lines.poll(wait, TimeUnit.NANOSECONDS)
                    ?: error("timed out waiting for '$method' after ${timeoutSec}s")
                val message = runCatching { MiniJson.parse(line) }.getOrNull() ?: continue
                if ((MiniJson.path(message, "id") as? Double)?.toInt() == id) return message
            }
        }

        override fun close() {
            runCatching { stdin.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}

// splits a command line on spaces, keeping "quoted segments" together
internal fun splitCommand(command: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    command.forEach { c ->
        when {
            quote != null && c == quote -> quote = null
            quote != null -> current.append(c)
            c == '"' || c == '\'' -> quote = c
            c.isWhitespace() -> if (current.isNotEmpty()) { parts += current.toString(); current.clear() }
            else -> current.append(c)
        }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}
