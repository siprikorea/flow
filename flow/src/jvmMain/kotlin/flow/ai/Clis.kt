package flow.ai

import flow.mcp.McpServer
import java.io.File

/**
 * The assistants that come as a command, described the way the HTTP ones are.
 *
 * A CLI is a whole agent already: it holds the conversation, decides to call a tool and calls it,
 * and it is signed in — nothing to configure, and where the sign-in is a subscription, nothing more
 * to pay. So Flow's job here is not the agent loop (see HttpAgent for that) but the arrangement
 * around it: point it at Flow's MCP server, hand it the system prompt, run one turn, and read the
 * answer out of whatever it prints.
 *
 * All three want those same four things and none of them spells any of it the same way. That is
 * what [CliSpec] is: the argument list, and where the answer is in the output.
 */
internal interface CliSpec {
    /** The command, as it is called on PATH. */
    val command: String

    /** Where it is installed when PATH does not have it, which for a GUI app is the usual case. */
    val fallbackPaths: List<String>

    /**
     * One turn's arguments.
     *
     * [mcpConfig] is a file written for whichever CLI wants a file; those that take the server on
     * the command line ignore it.
     */
    fun args(prompt: String, model: String, systemPrompt: String, mcpConfig: File?, sessionId: String?): List<String>

    /** What the process needs in its environment, beyond what it inherits. */
    fun environment(systemPrompt: String, scratch: File): Map<String, String> = emptyMap()

    /** A file this CLI needs on disk before it runs, written into [scratch]. */
    fun prepare(scratch: File, systemPrompt: String): File? = null

    /** Where the process runs. Null means the open folder. */
    fun workingDir(scratch: File): File? = null

    /** One line of output: the text to show, the session to resume, or nothing. */
    fun read(line: String): CliEvent
}

/** What one line of a CLI's output turned out to be. */
internal class CliEvent(val text: String? = null, val session: String? = null, val result: String? = null)

/* ───────── Claude Code ───────── */

internal object ClaudeCodeSpec : CliSpec {
    override val command = "claude"

    override val fallbackPaths = listOf(
        "\$HOME/.claude/local/claude",
        "\$HOME/.local/bin/claude",
        "\$HOME/.npm-global/bin/claude",
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
    )

    override fun args(
        prompt: String,
        model: String,
        systemPrompt: String,
        mcpConfig: File?,
        sessionId: String?,
    ): List<String> = buildList {
        add("-p")
        add(prompt)
        // streamed, so a run that takes a minute is not a minute of nothing — and with
        // --include-partial-messages, token by token rather than one whole message at a time,
        // so the panel can show an answer typing in rather than landing all at once.
        add("--output-format"); add("stream-json")
        add("--include-partial-messages")
        add("--verbose")
        if (model.isNotBlank()) { add("--model"); add(model) }
        add("--append-system-prompt"); add(systemPrompt)
        mcpConfig?.let {
            add("--mcp-config"); add(it.absolutePath)
            // Nothing runs without being allowed to, and nothing asks: this is not an interactive
            // session, so a tool that is not on this list is simply refused.
            add("--allowedTools"); add(FLOW_TOOLS.joinToString(","))
            // Without this, claude also connects to every MCP server configured in the user's own
            // settings — Slack, Gmail, an IDE bridge, whatever else — none of which this panel has
            // any business touching, and each one a server to spawn before it can answer at all.
            add("--strict-mcp-config")
        }
        sessionId?.let { add("--resume"); add(it) }
    }

    override fun read(line: String): CliEvent = claudeStreamJson(line)
}

/* ───────── Codex ───────── */

internal object CodexSpec : CliSpec {
    override val command = "codex"

    override val fallbackPaths = listOf(
        "\$HOME/.local/bin/codex",
        "\$HOME/.npm-global/bin/codex",
        "/opt/homebrew/bin/codex",
        "/usr/local/bin/codex",
    )

    /**
     * Codex takes its whole configuration on the command line, so the MCP server is named there
     * rather than in a file — `-c` overrides one key of what ~/.codex/config.toml would have said.
     *
     * There is no flag for a system prompt, so it goes at the top of the turn instead. That is
     * weaker than the others' system field, which is why the CLI is the second-best way to reach
     * OpenAI from here and the API is the default.
     */
    override fun args(
        prompt: String,
        model: String,
        systemPrompt: String,
        mcpConfig: File?,
        sessionId: String?,
    ): List<String> = buildList {
        add("exec")
        // the open folder is not a git repository as a rule, and codex refuses to run in one that
        // isn't unless told that is expected
        add("--skip-git-repo-check")
        add("--json")
        if (model.isNotBlank()) { add("-m"); add(model) }
        mcpConfig?.let {
            add("-c"); add("mcp_servers.flow.command=${toml(it.absolutePath)}")
        }
        sessionId?.let { add("resume"); add(it) }
        add("$systemPrompt\n\n---\n\n$prompt")
    }

    /** A TOML string literal: the path can contain anything a directory name can. */
    private fun toml(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override fun read(line: String): CliEvent {
        val event = runCatching { agentJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return CliEvent()
        // codex names its events by type; the ones that carry prose are the agent's own messages
        val message = event["msg"] as? kotlinx.serialization.json.JsonObject ?: event
        return when (message.str("type")) {
            "agent_message_delta" -> CliEvent(text = message.str("delta"))
            "agent_message" -> CliEvent(result = message.str("message"))
            "session_configured" -> CliEvent(session = message.str("session_id"))
            else -> CliEvent()
        }
    }
}

/* ───────── Gemini CLI ───────── */

internal object GeminiCliSpec : CliSpec {
    override val command = "gemini"

    override val fallbackPaths = listOf(
        "\$HOME/.local/bin/gemini",
        "\$HOME/.npm-global/bin/gemini",
        "/opt/homebrew/bin/gemini",
        "/usr/local/bin/gemini",
    )

    /**
     * Gemini reads its MCP servers from settings rather than from a flag, and the nearest scope it
     * will read is a .gemini/settings.json beside where it is run.
     *
     * So it is run in a scratch directory of Flow's own, not in the user's folder — writing a
     * .gemini into the folder they keep flows in would leave a file behind that is nothing to do
     * with them. The MCP server is told the project root directly (--project), so the working
     * directory is not what points it at the work.
     */
    override fun prepare(scratch: File, systemPrompt: String): File? {
        val dir = File(scratch, ".gemini").apply { mkdirs() }
        // the system prompt goes in a file too: GEMINI_SYSTEM_MD names one to use instead of the
        // CLI's own built-in instructions
        File(scratch, "system.md").writeText(systemPrompt)
        return dir
    }

    override fun workingDir(scratch: File): File = scratch

    override fun environment(systemPrompt: String, scratch: File): Map<String, String> =
        mapOf("GEMINI_SYSTEM_MD" to File(scratch, "system.md").absolutePath)

    override fun args(
        prompt: String,
        model: String,
        systemPrompt: String,
        mcpConfig: File?,
        sessionId: String?,
    ): List<String> = buildList {
        add("-p")
        add(prompt)
        // one turn, no questions: there is no terminal here to answer a prompt for approval
        add("--approval-mode"); add("yolo")
        add("-o"); add("stream-json")
        if (model.isNotBlank()) { add("-m"); add(model) }
        // only Flow's server, whatever else the user has configured
        add("--allowed-mcp-server-names"); add("flow")
    }

    override fun read(line: String): CliEvent {
        val event = runCatching { agentJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return CliEvent()
        return when (event.str("type")) {
            "content", "assistant" -> CliEvent(text = event.str("text") ?: event.str("content"))
            "result" -> CliEvent(result = event.str("response") ?: event.str("text"))
            else -> CliEvent()
        }
    }
}

/**
 * Claude Code's stream, which the panel's streaming was built around.
 *
 * Without --include-partial-messages, "assistant" events each carry a whole message's text at once
 * — which is what made replies land in one piece instead of typing in. With it, the same text
 * arrives split into content_block_delta/text_delta events; the whole-message event that follows is
 * redundant with what the deltas already built and is ignored.
 */
private fun claudeStreamJson(line: String): CliEvent {
    val event = runCatching { agentJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }
        .getOrNull() ?: return CliEvent()
    val session = event.str("session_id")
    val result = event.str("result")
    if (event.str("type") != "stream_event") return CliEvent(session = session, result = result)
    val inner = event["event"] as? kotlinx.serialization.json.JsonObject
        ?: return CliEvent(session = session, result = result)
    if (inner.str("type") != "content_block_delta") return CliEvent(session = session, result = result)
    val delta = inner["delta"] as? kotlinx.serialization.json.JsonObject
        ?: return CliEvent(session = session, result = result)
    val text = if (delta.str("type") == "text_delta") delta.str("text") else null
    return CliEvent(text = text, session = session, result = result)
}

/** Flow's tools, as the MCP server registers them under the name the config gives it. */
internal val FLOW_TOOLS: List<String> by lazy {
    McpServer.toolSpecs().map { "mcp__flow__${it.name}" }
}
