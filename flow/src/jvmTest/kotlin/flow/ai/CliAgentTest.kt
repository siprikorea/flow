package flow.ai

import flow.model.AI_CLAUDE
import flow.model.AI_GEMINI
import flow.model.AI_OPENAI
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each CLI is actually asked to do.
 *
 * These are three programs with three unrelated command lines, and every one of the things that has
 * to be arranged — non-interactive, one turn, Flow's MCP server and no other, the system prompt,
 * streamed output — is spelled differently by each. Getting one wrong does not fail loudly: the CLI
 * runs and answers, having never seen a tool, or stops to ask a question nobody can answer.
 *
 * So the arguments are asserted here, where they can be read against each CLI's own documentation,
 * rather than only being exercised by a live run. Only Claude Code can be run live from this
 * machine — codex and gemini are installed but not signed in — which is exactly why the other two
 * need this.
 */
class CliAgentTest {

    private val mcp = File("/tmp/flow-mcp-test.json")

    private fun args(spec: CliSpec, model: String = "", session: String? = null) =
        spec.args("build me a flow", model, "You are Flow's assistant.", mcp, session)

    @Test
    fun `claude runs one turn, streamed, with only Flow's tools`() {
        val a = args(ClaudeCodeSpec, model = "claude-opus-5")
        assertEquals("-p", a[0], "not a one-shot run: $a")
        assertTrue(a.containsAll(listOf("--output-format", "stream-json", "--include-partial-messages")),
            "the answer would land in one piece instead of typing in: $a")
        assertTrue(a.containsAll(listOf("--model", "claude-opus-5")))
        assertTrue(a.contains("--append-system-prompt"))
        assertTrue(a.containsAll(listOf("--mcp-config", mcp.absolutePath)))
        // without this it also dials every MCP server the user has configured of their own
        assertTrue(a.contains("--strict-mcp-config"), "other MCP servers would be connected too: $a")
        val allowed = a[a.indexOf("--allowedTools") + 1]
        assertTrue(allowed.startsWith("mcp__flow__"), "the tool allow-list is wrong: $allowed")
        assertEquals(FLOW_TOOLS.size, allowed.split(",").size, "not every flow tool is allowed")
    }

    @Test
    fun `claude continues a conversation rather than starting one`() {
        val a = args(ClaudeCodeSpec, session = "abc-123")
        assertTrue(a.containsAll(listOf("--resume", "abc-123")), a.toString())
    }

    @Test
    fun `codex runs non-interactively and is told where the flow server is`() {
        val a = args(CodexSpec, model = "gpt-5")
        assertEquals("exec", a[0], "codex defaults to an interactive session: $a")
        assertTrue(a.contains("--json"), "nothing could be read back: $a")
        // the open folder is not a git repository as a rule, and codex refuses to run in one
        assertTrue(a.contains("--skip-git-repo-check"), a.toString())
        assertTrue(a.containsAll(listOf("-m", "gpt-5")))
        val config = a[a.indexOf("-c") + 1]
        assertEquals("mcp_servers.flow.command=\"${mcp.absolutePath}\"", config)
        // it has no system-prompt flag, so the prompt has to carry it
        assertTrue(a.last().startsWith("You are Flow's assistant."), "the system prompt was dropped: ${a.last()}")
        assertTrue(a.last().endsWith("build me a flow"))
    }

    @Test
    fun `gemini runs one turn without stopping to ask permission`() {
        val a = args(GeminiCliSpec, model = "gemini-3-pro")
        assertTrue(a.containsAll(listOf("-p", "build me a flow")), "not headless: $a")
        // there is no terminal here to approve a tool call, so a run that asks would hang forever
        assertTrue(a.containsAll(listOf("--approval-mode", "yolo")), a.toString())
        assertTrue(a.containsAll(listOf("-o", "stream-json")))
        assertTrue(a.containsAll(listOf("-m", "gemini-3-pro")))
        assertTrue(a.containsAll(listOf("--allowed-mcp-server-names", "flow")), "other MCP servers too: $a")
    }

    /**
     * Gemini reads MCP servers from a settings file beside where it runs, so it runs somewhere of
     * Flow's own — writing a .gemini into the folder the user keeps flows in would leave a file
     * behind that has nothing to do with them.
     */
    @Test
    fun `gemini is given a scratch directory, not the user's folder`() {
        val scratch = File(System.getProperty("java.io.tmpdir"), "flow-cli-test-${System.nanoTime()}")
        scratch.mkdirs()
        try {
            val settingsDir = GeminiCliSpec.prepare(scratch, "You are Flow's assistant.")
            assertEquals(File(scratch, ".gemini"), settingsDir)
            assertEquals(scratch, GeminiCliSpec.workingDir(scratch))
            val env = GeminiCliSpec.environment("You are Flow's assistant.", scratch)
            val systemMd = File(env["GEMINI_SYSTEM_MD"]!!)
            assertTrue(systemMd.isFile, "the system prompt was not written where the CLI reads it")
            assertEquals("You are Flow's assistant.", systemMd.readText())
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `only the providers with a usable command have one`() {
        assertTrue(CliAgent.handles(AI_CLAUDE))
        assertTrue(CliAgent.handles(AI_OPENAI))
        assertTrue(CliAgent.handles(AI_GEMINI))
        // ollama's command cannot call tools, so reaching it that way would be an assistant that
        // can talk about flows and not touch one
        assertTrue(!CliAgent.handles(flow.model.AI_OLLAMA))
    }

    /**
     * One real turn, against the installed CLI.
     *
     * Off unless AI_LIVE is set: it starts Claude Code, which costs a request and takes twenty
     * seconds. It is here because every part of this only fails at the far end — a flag the CLI
     * does not take, an MCP config it cannot read, a tool it has but is not allowed to call. Each
     * of those looks the same from this side: an assistant that answers without having looked
     * anything up.
     */
    @Test
    fun `claude code can reach Flow's own tools`() {
        if (System.getenv("AI_LIVE") == null) return
        if (CliAgent.path(AI_CLAUDE) == null) return

        val streamed = StringBuilder()
        val reply = CliAgent.ask(
            provider = AI_CLAUDE,
            prompt = "Call list_nodes and reply with only the number of node types it returned, " +
                "or the single word NOTOOL if you cannot call it.",
            sessionId = null,
            model = "",
            workingDir = File(System.getProperty("user.dir")),
            projectRoot = null,
            systemPrompt = FlowPrompt.systemPrompt(null),
        ) { streamed.append(it) }

        assertTrue(reply.error == null, "the run failed: ${reply.error}")
        assertTrue(reply.sessionId != null, "no session came back, so a second turn could not continue")
        assertTrue(streamed.isNotEmpty(), "nothing was streamed, so a long run would show nothing")
        assertTrue(
            reply.text.any { it.isDigit() } && !reply.text.contains("NOTOOL"),
            "the tools were not reachable: ${reply.text}",
        )
    }
}
