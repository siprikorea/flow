package flow.ai

import com.sun.net.httpserver.HttpServer
import flow.model.AI_ERR_NO_KEY
import flow.model.AI_CLAUDE
import flow.model.AI_GEMINI
import flow.model.AI_OLLAMA
import flow.model.AI_OPENAI
import flow.model.AI_VIA_API
import flow.model.AiSetup
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent loop, against a server that answers the way each API does.
 *
 * The loop is the part of an assistant Flow actually writes — OpenAI, Gemini and Ollama each hand
 * back a model's answer and nothing else, so deciding a tool was asked for, running it, writing the
 * result down in the shape that API reads back, and asking again is all Flow's code. It is also the
 * part that cannot be checked by looking at it: every one of those three APIs disagrees about how
 * a tool call is spelled, and getting one wrong produces an assistant that answers confidently
 * without ever having called anything.
 *
 * A local server rather than the real ones, because the real ones need a paid key and would make
 * this a test of the network. What it serves is what they serve: one response asking for a tool,
 * then, once the tool result comes back in the transcript, a final answer quoting it. So a pass
 * means the call was understood, the tool ran, and the result reached the model in a form it could
 * read — and the requests it received are checked afterwards to be sure the second one really did
 * carry the tool's output.
 */
class AgentLoopTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<String>()

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /** Starts a server that replies with [responses] in order, recording what it was sent. */
    private fun serve(vararg responses: String): String {
        var n = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestBody.readBytes().decodeToString()
            val body = responses[minOf(n++, responses.size - 1)].toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun ask(setup: AiSetup): Pair<String, String> {
        val streamed = StringBuilder()
        val reply = Agents.ask("what nodes are there?", null, setup, "You are a test.") { streamed.append(it) }
        assertTrue(reply.error == null, "the turn failed: ${reply.error}")
        return reply.text to streamed.toString()
    }

    @Test
    fun `openai - a tool call streamed in fragments is assembled, run, and answered`() {
        // arguments arrive a few characters at a time, keyed by index — the shape that makes this
        // reader different from the other two
        val askForTool = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_nodes","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
            "data: [DONE]",
        ).joinToString("\n\n")
        val answer = listOf(
            """data: {"choices":[{"delta":{"content":"there are "}}]}""",
            """data: {"choices":[{"delta":{"content":"nodes"}}]}""",
            "data: [DONE]",
        ).joinToString("\n\n")

        val url = serve(askForTool, answer)
        val (text, streamed) = ask(AiSetup(AI_OPENAI, AI_VIA_API, "gpt-test", url, "k"))

        assertEquals("there are nodes", text)
        assertEquals("there are nodes", streamed, "the answer did not arrive in pieces")
        assertEquals(2, received.size, "the tool result was never sent back")
        assertTrue(
            received[1].contains("\"tool_call_id\":\"call_1\""),
            "the result was not paired with the call id OpenAI handed out:\n${received[1].take(400)}",
        )
        assertTrue(received[1].contains("boundary nodes"), "list_nodes' output is not in the transcript")
    }

    @Test
    fun `gemini - a functionCall is run and its output goes back as a functionResponse`() {
        val askForTool =
            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"list_nodes","args":{}}}]}}]}"""
        val answer = """data: {"candidates":[{"content":{"parts":[{"text":"there are nodes"}]}}]}"""

        val url = serve(askForTool, answer)
        val (text, _) = ask(AiSetup(AI_GEMINI, AI_VIA_API, "gemini-test", url, "k"))

        assertEquals("there are nodes", text)
        assertEquals(2, received.size)
        assertTrue(
            received[1].contains("functionResponse"),
            "Gemini's tool result has to be a functionResponse part:\n${received[1].take(400)}",
        )
        assertTrue(received[1].contains("boundary nodes"))
        // the system prompt rides beside the conversation, not in it
        assertTrue(received[0].contains("systemInstruction"), "the system prompt was not sent")
    }

    @Test
    fun `ollama - a tool call is run and its output goes back as a tool message`() {
        val askForTool =
            """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"list_nodes","arguments":{}}}]}}"""
        val answer = """{"message":{"role":"assistant","content":"there are nodes"}}"""

        val url = serve(askForTool, answer)
        val (text, _) = ask(AiSetup(AI_OLLAMA, AI_VIA_API, "llama-test", url, ""))

        assertEquals("there are nodes", text)
        assertEquals(2, received.size)
        assertTrue(received[1].contains("\"role\":\"tool\""), received[1].take(400))
        assertTrue(received[1].contains("boundary nodes"))
    }

    @Test
    fun `anthropic - a tool_use block is assembled from its fragments, run, and answered`() {
        // content arrives as numbered blocks: the call is announced, then its arguments come as
        // JSON fragments, then a text block holds the answer
        val askForTool = listOf(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"list_nodes"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"}"}}""",
        ).joinToString("\n\n")
        val answer = listOf(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there are "}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"nodes"}}""",
        ).joinToString("\n\n")

        val url = serve(askForTool, answer)
        val (text, streamed) = ask(AiSetup(AI_CLAUDE, AI_VIA_API, "claude-test", url, "k"))

        assertEquals("there are nodes", text)
        assertEquals("there are nodes", streamed, "the answer did not arrive in pieces")
        assertEquals(2, received.size, "the tool result was never sent back")
        assertTrue(
            received[1].contains("\"tool_use_id\":\"toolu_1\""),
            "the result was not paired with the id the block announced:\n${received[1].take(400)}",
        )
        assertTrue(received[1].contains("boundary nodes"), "list_nodes' output is not in the transcript")
        // this API makes the caller name a ceiling, and refuses a request without one
        assertTrue(received[0].contains("max_tokens"), "max_tokens is required: ${received[0].take(200)}")
        assertTrue(received[0].contains("input_schema"), "tools are declared with input_schema here")
    }

    @Test
    fun `a provider that needs a key says so instead of asking without one`() {
        val reply = Agents.ask("hello", null, AiSetup(AI_OPENAI, AI_VIA_API, "gpt-test", "http://127.0.0.1:1", ""), "sys") {}
        assertEquals(AI_ERR_NO_KEY, reply.error)
        assertTrue(received.isEmpty(), "it asked anyway")
    }

    @Test
    fun `a refusal is reported with what the server said, not as an empty answer`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"error":{"message":"Incorrect API key provided"}}""".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"

        val reply = Agents.ask("hello", null, AiSetup(AI_OPENAI, AI_VIA_API, "gpt-test", url, "bad"), "sys") {}
        assertEquals("Incorrect API key provided", reply.error)
    }

    @Test
    fun `each provider keeps its own conversation`() {
        val url = serve("""data: {"choices":[{"delta":{"content":"hi"}}]}""", "data: [DONE]")
        val first = Agents.ask("hello", null, AiSetup(AI_OPENAI, AI_VIA_API, "gpt-test", url, "k"), "sys") {}
        assertTrue(first.sessionId!!.startsWith("openai-"), "a session id should say whose it is: ${first.sessionId}")
    }
}
