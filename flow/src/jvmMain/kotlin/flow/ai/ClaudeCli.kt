package flow.ai

import flow.model.AiReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Claude Code, run as a child process — one run per turn.
 *
 * Not a terminal. A terminal would mean a pseudo-terminal and a VT100 parser, nearly all of it
 * plumbing for the one program we want to run; and what is wanted here is a conversation, not a
 * shell. So each turn is `claude -p`, which prints its answer and exits, and the conversation is
 * carried by the session id it hands back.
 *
 * The point of doing it from inside Flow rather than leaving the user to a terminal is what can be
 * arranged around it: the flow tools are already connected, the working directory is already the
 * open folder, and the system prompt already says what a flow is.
 */
internal object ClaudeCli {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The run in progress, so it can be stopped.
     *
     * One at a time — a turn is a conversation and two would be two of them — so one reference is
     * enough, and stopping is ending the process rather than asking it to stop.
     */
    private val running = java.util.concurrent.atomic.AtomicReference<Process?>(null)

    fun stop() {
        running.getAndSet(null)?.destroyForcibly()
    }

    /**
     * Where the CLI is, or null if it is not installed.
     *
     * PATH is searched first, then where npm and the installer put it. A GUI application on macOS
     * inherits a PATH that has almost nothing on it, so the fallbacks are not an edge case — they
     * are the usual way it is found.
     */
    fun path(): String? {
        val fromPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .asSequence()
            .map { File(it, "claude") }
            .firstOrNull { it.isFile && it.canExecute() }
        if (fromPath != null) return fromPath.absolutePath
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.claude/local/claude",
            "$home/.local/bin/claude",
            "$home/.npm-global/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    /**
     * One turn.
     *
     * [onText] is called as the answer arrives, so a long task shows its working rather than
     * nothing. The reply's session id carries the conversation to the next turn.
     */
    fun ask(
        prompt: String,
        sessionId: String?,
        model: String,
        workingDir: File?,
        mcpConfig: File?,
        systemPrompt: String,
        onText: (String) -> Unit,
    ): AiReply {
        val cli = path() ?: return AiReply(error = NOT_INSTALLED)

        val command = buildList {
            add(cli)
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
                // Nothing runs without being allowed to, and nothing asks: this is not an
                // interactive session, so a tool that is not on this list is simply refused. The
                // list is Flow's own tools and only those — they read the open folder and return
                // file contents, and none of them writes anything or runs anything.
                add("--allowedTools"); add(FLOW_TOOLS.joinToString(","))
                // Without this, claude also connects to every MCP server configured in the user's
                // own global/project settings — Slack, Gmail, Drive, Jira, an IDE bridge, whatever
                // else they happen to have — none of which this panel has any business touching.
                // Each one is a server this process now has to spawn or dial before it can answer
                // at all, which is real, avoidable latency on every single turn (and, if one of
                // them is slow or unreachable, a turn that never finishes). --bare would isolate
                // further still but also stops OAuth/keychain auth from being read, breaking
                // anyone not using a raw API key — this is the safe subset of that.
                add("--strict-mcp-config")
            }
            sessionId?.let { add("--resume"); add(it) }
        }

        val process = runCatching {
            ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
        }.getOrElse { return AiReply(error = it.message ?: "could not start ${File(cli).name}") }
        running.set(process)

        val text = StringBuilder()
        var session: String? = null
        var result: String? = null

        // destroyForcibly() (stop(), below) closes this stream out from under a blocking read,
        // which surfaces here as a plain IOException ("Stream closed") rather than a clean end of
        // stream. Caught rather than left to propagate: 'stopped' already tells a deliberate stop
        // apart from a real failure, and without this the exception skipped that check entirely,
        // reporting a stop the user asked for as "could not run it: Stream closed".
        val readError = runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    if (event == null) {
                        // not an event this build knows how to read — showing it is better than
                        // dropping it, since it is the only thing the run has said
                        text.append(line).append('\n')
                        onText(line + "\n")
                        return@forEach
                    }
                    event.string("session_id")?.let { session = it }
                    event.string("result")?.let { result = it }
                    event.textDelta()?.let { chunk ->
                        text.append(chunk)
                        onText(chunk)
                    }
                }
            }
        }.exceptionOrNull()

        val stderr = process.errorStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        val stopped = running.getAndSet(null) == null

        val answer = (result ?: text.toString()).trim()
        return when {
            // stopped on purpose: whatever it had said stands, and the exit is not a failure
            stopped -> AiReply(text = answer, sessionId = session)
            answer.isNotEmpty() -> AiReply(text = answer, sessionId = session)
            code != 0 -> AiReply(error = stderr.ifBlank { readError?.message ?: "claude exited with $code" }, sessionId = session)
            else -> AiReply(error = stderr.ifBlank { readError?.message ?: "claude said nothing" }, sessionId = session)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /**
     * One token-ish chunk of assistant text, from a `--include-partial-messages` stream_event.
     *
     * Without that flag, "assistant" events each carry a whole message's text at once — which is
     * what made replies land in one piece instead of typing in. With it, the same text arrives
     * split into `content_block_delta`/`text_delta` events instead; the later whole-message
     * "assistant" event that follows is redundant with what the deltas already built and is
     * ignored here.
     */
    private fun JsonObject.textDelta(): String? {
        if (string("type") != "stream_event") return null
        val event = this["event"] as? JsonObject ?: return null
        if (event.string("type") != "content_block_delta") return null
        val delta = event["delta"] as? JsonObject ?: return null
        if (delta.string("type") != "text_delta") return null
        return delta["text"]?.jsonPrimitive?.content
    }

    /** Said when the CLI is not there, which is the one failure worth explaining rather than reporting. */
    const val NOT_INSTALLED = "claude-not-installed"

    /** Flow's tools, as the MCP server registers them under the name the config gives it. */
    private val FLOW_TOOLS = listOf(
        "mcp__flow__list_nodes",
        "mcp__flow__list_flows",
        "mcp__flow__read_flow",
        "mcp__flow__validate_flow",
        "mcp__flow__run_flow",
        "mcp__flow__build_flow",
        "mcp__flow__save_flow",
        "mcp__flow__open_flow",
        "mcp__flow__set_flow_input",
        "mcp__flow__start_flow",
        "mcp__flow__stop_flow",
    )
}
