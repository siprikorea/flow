package flow.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One real turn, against the installed CLI.
 *
 * Off unless AI_LIVE is set: it starts Claude Code, which costs a request and takes twenty seconds.
 * It is here because every part of this only fails at the far end — a flag the CLI does not take,
 * an MCP config it cannot read, a tool it has but is not allowed to call. Each of those looks the
 * same from this side: an assistant that answers without having looked anything up.
 */
class ClaudeCliLiveTest {

    @Test
    fun `the assistant can reach Flow's own tools`() {
        if (System.getenv("AI_LIVE") == null) return

        val streamed = StringBuilder()
        val reply = ClaudeCli.ask(
            prompt = "Call list_nodes and reply with only the number of node types it returned, " +
                "or the single word NOTOOL if you cannot call it.",
            sessionId = null,
            model = "",
            workingDir = File(System.getProperty("user.dir")),
            mcpConfig = FlowPrompt.mcpConfig(null),
            systemPrompt = FlowPrompt.systemPrompt(null),
        ) { streamed.append(it) }

        assertTrue(reply.error == null, "the run failed: ${reply.error}")
        assertTrue(reply.sessionId != null, "no session came back, so a second turn could not continue")
        assertTrue(streamed.isNotEmpty(), "nothing was streamed, so a long run would show nothing")
        // the answer is a count, which it can only have by having called the tool — "NOTOOL" is
        // what it says when the tools are there but it was not allowed to use them
        assertTrue(
            reply.text.any { it.isDigit() } && !reply.text.contains("NOTOOL"),
            "the tools were not reachable: ${reply.text}",
        )
    }
}
