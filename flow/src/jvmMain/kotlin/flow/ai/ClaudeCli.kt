package flow.ai

import flow.model.AiReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
            // streamed, so a run that takes a minute is not a minute of nothing
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--append-system-prompt"); add(systemPrompt)
            mcpConfig?.let {
                add("--mcp-config"); add(it.absolutePath)
                // Nothing runs without being allowed to, and nothing asks: this is not an
                // interactive session, so a tool that is not on this list is simply refused. The
                // list is Flow's own tools and only those — they read the open folder and return
                // file contents, and none of them writes anything or runs anything.
                add("--allowedTools"); add(FLOW_TOOLS.joinToString(","))
            }
            sessionId?.let { add("--resume"); add(it) }
        }

        val process = runCatching {
            ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
        }.getOrElse { return AiReply(error = it.message ?: "could not start ${File(cli).name}") }

        val text = StringBuilder()
        var session: String? = null
        var result: String? = null

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
                event.assistantText()?.let { chunk ->
                    text.append(chunk)
                    onText(chunk)
                }
            }
        }

        val stderr = process.errorStream.bufferedReader().readText().trim()
        val code = process.waitFor()

        val answer = (result ?: text.toString()).trim()
        return when {
            answer.isNotEmpty() -> AiReply(text = answer, sessionId = session)
            code != 0 -> AiReply(error = stderr.ifBlank { "claude exited with $code" }, sessionId = session)
            else -> AiReply(error = stderr.ifBlank { "claude said nothing" }, sessionId = session)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** The text of an assistant turn, which arrives as content blocks rather than a string. */
    private fun JsonObject.assistantText(): String? {
        if (string("type") != "assistant") return null
        val content = (this["message"] as? JsonObject)?.get("content") as? JsonArray ?: return null
        val parts = content.jsonArray.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "text") null else obj["text"]?.jsonPrimitive?.content
        }
        return parts.joinToString("").takeIf { it.isNotBlank() }
    }

    /** Said when the CLI is not there, which is the one failure worth explaining rather than reporting. */
    const val NOT_INSTALLED = "claude-not-installed"

    /** Flow's tools, as the MCP server registers them under the name the config gives it. */
    private val FLOW_TOOLS = listOf(
        "mcp__flow__list_nodes",
        "mcp__flow__list_flows",
        "mcp__flow__read_flow",
        "mcp__flow__validate_flow",
        "mcp__flow__build_flow",
    )
}
