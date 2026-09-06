package flow.ai

import flow.model.AI_ERR_OLLAMA_DOWN
import flow.model.DEFAULT_OLLAMA_URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One real turn against a local Ollama, and what happens when there isn't one.
 *
 * The second half runs anywhere: a server that is not there is the ordinary case, and the panel has
 * to say so rather than hang or throw. The first is off unless AI_LIVE is set, because it needs a
 * model pulled onto the machine and takes as long as that model takes to think.
 *
 * The part worth testing live is the tool loop. Unlike Claude Code, which is an agent in its own
 * right, Ollama is only a model — the decision to call a tool, the call, and feeding the result
 * back are all Flow's code here, and every one of them fails the same way from the outside: an
 * answer that sounds right and never looked anything up.
 */
class OllamaLiveTest {

    @Test
    fun `a server that isn't there is reported, not thrown`() {
        // a port nothing is on: the reply carries the reason, and the panel turns that id into a
        // sentence telling the user to start the server
        val reply = Ollama.ask(
            prompt = "hello",
            sessionId = null,
            model = "whatever",
            baseUrl = "http://127.0.0.1:1",
            systemPrompt = "You are a test.",
        ) {}
        assertEquals(AI_ERR_OLLAMA_DOWN, reply.error?.substringBefore(" "), "got: ${reply.error}")
    }

    @Test
    fun `no models means no models, rather than an empty list of them`() {
        assertTrue(Ollama.models("http://127.0.0.1:1").isEmpty())
    }

    @Test
    fun `the model can reach Flow's own tools`() {
        if (System.getenv("AI_LIVE") == null) return
        val model = Ollama.models(DEFAULT_OLLAMA_URL).firstOrNull() ?: return

        val streamed = StringBuilder()
        val reply = Ollama.ask(
            prompt = "Call list_nodes, then reply with the single word CALLED followed by the id of " +
                "any one processor it listed. If you cannot call it, reply with only NOTOOL.",
            sessionId = null,
            model = model,
            baseUrl = DEFAULT_OLLAMA_URL,
            systemPrompt = FlowPrompt.systemPrompt(null),
        ) { streamed.append(it) }

        assertTrue(reply.error == null, "the run failed: ${reply.error}")
        assertTrue(reply.sessionId != null, "no session came back, so a second turn could not continue")
        assertTrue(streamed.isNotEmpty(), "nothing was streamed, so a long run would show nothing")
        assertTrue(!reply.text.contains("NOTOOL"), "the tools were not reachable: ${reply.text}")
        // Not the exact wording asked for — a small local model rarely obeys a reply format — but
        // names it can only have by having called the tool. Two of them, so one lucky guess at a
        // word like "Hash" is not enough.
        val real = flow.mcp.McpServer.invoke("list_nodes", kotlinx.serialization.json.JsonObject(emptyMap()))
            .lines().mapNotNull { line -> Regex("'([^']+)'").find(line)?.groupValues?.get(1) }
            .filter { it.length > 3 }
        val mentioned = real.filter { reply.text.contains(it, ignoreCase = true) }
        assertTrue(
            mentioned.size >= 2,
            "it did not report what list_nodes returned (matched ${mentioned.size} of ${real.size}): ${reply.text}",
        )
    }
}
