package flow.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Claude Code is handed before the user says anything.
 *
 * Both halves fail quietly if they are wrong. A system prompt that names a tool that does not exist
 * sends the assistant looking for it; an MCP config that does not parse means no tools at all, and
 * the only sign is an assistant that cannot do the one thing this panel is for.
 */
class FlowPromptTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the prompt names the tools that actually exist`() {
        val prompt = FlowPrompt.systemPrompt("/tmp/project")
        // the names as the MCP server registers them; a rename that misses this leaves the
        // assistant calling something that is not there
        listOf("list_nodes", "list_flows", "read_flow", "validate_flow", "run_flow", "build_flow", "save_flow", "open_flow").forEach {
            assertTrue(prompt.contains(it), "the prompt does not mention $it")
        }
    }

    @Test
    fun `the prompt says what a node is`() {
        val prompt = FlowPrompt.systemPrompt(null)
        assertTrue(prompt.contains("cin"), prompt)
        assertTrue(prompt.contains("cout"), prompt)
        assertTrue(prompt.contains("comp:"), prompt)
    }

    @Test
    fun `the prompt says where the work is, or that there is nowhere`() {
        assertTrue(FlowPrompt.systemPrompt("/tmp/project").contains("/tmp/project"))
        // with no folder open there is nothing to read or save, and saying so is better than
        // letting it try
        assertTrue(FlowPrompt.systemPrompt(null).contains("No folder is open"))
    }

    @Test
    fun `the mcp config parses, and points at a java that is there`() {
        val config = FlowPrompt.mcpConfig("/tmp/project")
        assertTrue(config != null && config.isFile, "no config was written")
        val root = json.parseToJsonElement(config!!.readText()) as JsonObject
        val server = root["mcpServers"]!!.jsonObject["flow"]!!.jsonObject

        val command = server["command"]!!.jsonPrimitive.content
        assertTrue(File(command).canExecute(), "$command is not something that can be run")

        val args = server["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(args.containsAll(listOf("flow.cli.CliKt", "--mcp")), "$args")
        assertTrue(args[args.indexOf("-cp") + 1].isNotBlank(), "the classpath is empty")
        // it talks over a pipe: a dock icon for the length of every question is not wanted
        assertTrue("-Djava.awt.headless=true" in args, "the server would open a display: $args")
        assertTrue("-Dapple.awt.UIElement=true" in args, "the server would take a dock icon: $args")
        // the server is another process and the app remembers no folder between runs, so the one
        // that is open has to be told to it — without this every answer is "no project folder"
        assertEquals("/tmp/project", args[args.indexOf("--project") + 1])
    }

    @Test
    fun `the classpath in the config is quoted, whatever is in it`() {
        // it is written by hand rather than serialized, and a path with a quote or a backslash in
        // it would otherwise produce a file that does not parse — which reads as "no tools"
        val config = FlowPrompt.mcpConfig("/tmp/project")!!
        val text = config.readText()
        assertTrue(runCatching { json.parseToJsonElement(text) }.isSuccess, text.take(200))
    }
}
