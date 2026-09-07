package flow.ai

import flow.model.AI_ERR_OLLAMA_DOWN
import flow.model.AI_OLLAMA
import flow.model.AI_VIA_API
import flow.model.AiSetup
import flow.model.DEFAULT_OLLAMA_URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One real turn against a local Ollama, and what happens when there isn't one.
 *
 * AgentLoopTest covers the loop against a server that answers on cue; this covers the thing that
 * cannot be faked — a real model deciding for itself to call a tool, and its arguments arriving in
 * whatever shape that model happens to emit. Off unless AI_LIVE is set, because it needs a model
 * pulled onto the machine and takes as long as that model takes to think.
 *
 * The second half runs anywhere: a server that is not there is the ordinary case, and the panel has
 * to say so rather than hang or throw.
 */
class OllamaLiveTest {

    private fun setup(url: String = DEFAULT_OLLAMA_URL, model: String = "") =
        AiSetup(provider = AI_OLLAMA, transport = AI_VIA_API, model = model, url = url)

    @Test
    fun `a server that isn't there is reported, not thrown`() {
        // a port nothing is on: the reply carries the reason, and the panel turns that id into a
        // sentence telling the user to start the server
        val reply = Agents.ask("hello", null, setup("http://127.0.0.1:1", "whatever"), "You are a test.") {}
        assertEquals(AI_ERR_OLLAMA_DOWN, reply.error?.substringBefore(" "), "got: ${reply.error}")
    }

    @Test
    fun `no models means no models, rather than an empty list of them`() {
        assertTrue(Agents.models(setup("http://127.0.0.1:1")).isEmpty())
    }

    @Test
    fun `the model can reach Flow's own tools`() {
        if (System.getenv("AI_LIVE") == null) return
        val model = Agents.models(setup()).firstOrNull() ?: return

        val streamed = StringBuilder()
        val reply = Agents.ask(
            "Call list_nodes, then reply with the single word CALLED followed by the id of " +
                "any one processor it listed. If you cannot call it, reply with only NOTOOL.",
            null,
            setup(model = model),
            FlowPrompt.systemPrompt(null),
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
