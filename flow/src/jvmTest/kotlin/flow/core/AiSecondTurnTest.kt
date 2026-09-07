package flow.core

import com.sun.net.httpserver.HttpServer
import flow.model.AI_OPENAI
import flow.model.AI_VIA_API
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A second question gets an answer, whether or not the conversation was cleared first.
 *
 * Reported: after using the assistant, starting a new conversation and asking again produced
 * nothing at all. Everything that could cause that is state kept between turns — a session id, a
 * cancelled flag, a held process — so the way to catch it is to drive two turns through the real
 * Workspace rather than to read the one.
 *
 * The server is local and answers on cue, so this is about Flow's own state and not the network.
 */
class AiSecondTurnTest {

    private lateinit var server: HttpServer
    private var asked = 0

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            asked++
            val body = ("""data: {"choices":[{"delta":{"content":"answer $asked"}}]}""" + "\n\ndata: [DONE]\n\n")
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun workspace(url: String): Workspace {
        val ws = Workspace(CoroutineScope(Dispatchers.Default))
        // a folder, or the panel refuses to take a question at all
        ws.openProject(File(System.getProperty("java.io.tmpdir"), "flow-ai-turn").apply { mkdirs() }.absolutePath)
        ws.aiProvider = AI_OPENAI
        ws.setTransport(AI_OPENAI, AI_VIA_API)
        ws.openaiUrl = url
        ws.openaiModel = "gpt-test"
        ws.setApiKey(AI_OPENAI, "k", save = false)
        return ws
    }

    /** Asks, and waits for the turn to finish. */
    private fun turn(ws: Workspace, question: String): String {
        ws.askAi(question)
        val deadline = System.currentTimeMillis() + 20_000
        while (ws.aiStreaming && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(!ws.aiStreaming, "the turn never finished")
        return ws.aiMessages.last().text
    }

    @Test
    fun `a second question in the same conversation is answered`() {
        val ws = workspace(serve())
        assertEquals("answer 1", turn(ws, "first"))
        assertEquals("answer 2", turn(ws, "second"))
    }

    @Test
    fun `a question after starting a new conversation is answered`() {
        val ws = workspace(serve())
        assertEquals("answer 1", turn(ws, "first"))
        ws.clearAi()
        assertTrue(ws.aiMessages.isEmpty(), "the transcript was not cleared")
        assertEquals("answer 2", turn(ws, "second"))
    }

    /**
     * The same sequence against Claude Code, which is a different path entirely: a subprocess that
     * keeps the conversation itself and is asked to resume it by id. AI_LIVE=1, because it costs
     * two real turns.
     */
    @Test
    fun `claude code answers a second question, and one after a new conversation`() {
        if (System.getenv("AI_LIVE") == null) return
        val ws = Workspace(CoroutineScope(Dispatchers.Default))
        ws.openProject(File(System.getProperty("java.io.tmpdir"), "flow-ai-turn").apply { mkdirs() }.absolutePath)
        ws.aiProvider = flow.model.AI_CLAUDE
        ws.setTransport(flow.model.AI_CLAUDE, flow.model.AI_VIA_CLI)

        val first = turn(ws, "Reply with only the word ONE.")
        assertTrue(first.isNotBlank(), "the first turn said nothing")
        assertTrue(!first.contains("aiFailed"), first)

        ws.clearAi()
        val second = turn(ws, "Reply with only the word TWO.")
        assertTrue(second.isNotBlank(), "a new conversation got no answer at all: '$second'")
        assertTrue(!second.lowercase().contains("could not"), second)
    }

    /**
     * Switching how the same assistant is reached must not carry the session across.
     *
     * The API mints "claude-1a2b3c"; the CLI is asked to resume by id and would be handed that one,
     * which it has never heard of — `claude --resume claude-1a2b3c` fails, and the turn answers
     * nothing. The provider is the same, so guarding on provider alone did not catch it.
     */
    @Test
    fun `switching transport starts a new session instead of resuming the other one`() {
        val ws = workspace(serve())
        turn(ws, "first")
        val apiSession = ws.aiSessionForTest
        assertTrue(apiSession != null && apiSession.startsWith("openai-"), "no API session: $apiSession")

        ws.setTransport(AI_OPENAI, flow.model.AI_VIA_CLI)
        ws.askAi("second") // the guard runs before anything is sent
        assertTrue(ws.aiSessionForTest == null, "the CLI would be asked to resume an API session")
    }

    @Test
    fun `a question after stopping a turn is answered`() {
        val ws = workspace(serve())
        assertEquals("answer 1", turn(ws, "first"))
        ws.stopAi() // nothing is running; the flags it sets must not outlive the call
        assertEquals("answer 2", turn(ws, "second"))
    }
}
