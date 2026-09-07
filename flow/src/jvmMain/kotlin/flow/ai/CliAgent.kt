package flow.ai

import flow.model.AI_CLAUDE
import flow.model.AI_GEMINI
import flow.model.AI_OPENAI
import flow.model.AiReply
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * An assistant that is a command, run as a child process — one run per turn.
 *
 * Not a terminal. A terminal would mean a pseudo-terminal and a VT100 parser, nearly all of it
 * plumbing for the one program we want to run; and what is wanted here is a conversation, not a
 * shell. So each turn is one non-interactive run, which prints its answer and exits, and the
 * conversation is carried by the session id it hands back.
 *
 * The point of doing it from inside Flow rather than leaving the user to a terminal is what can be
 * arranged around it: the flow tools are already connected, the working directory is already the
 * open folder, and the system prompt already says what a flow is.
 */
internal object CliAgent {

    private val specs = mapOf(
        AI_CLAUDE to ClaudeCodeSpec,
        AI_OPENAI to CodexSpec,
        AI_GEMINI to GeminiCliSpec,
    )

    fun handles(provider: String): Boolean = provider in specs

    /**
     * The run in progress, so it can be stopped.
     *
     * One at a time — a turn is a conversation and two would be two of them — so one reference is
     * enough, and stopping is ending the process rather than asking it to stop.
     */
    private val running = AtomicReference<Process?>(null)

    fun stop() {
        running.getAndSet(null)?.destroyForcibly()
    }

    /**
     * Where a CLI is, or null if it is not installed.
     *
     * PATH is searched first, then where each installer puts it. A GUI application on macOS
     * inherits a PATH that has almost nothing on it, so the fallbacks are not an edge case — they
     * are the usual way one is found.
     */
    fun path(provider: String): String? {
        val spec = specs[provider] ?: return null
        val fromPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .asSequence()
            .map { File(it, spec.command) }
            .firstOrNull { it.isFile && it.canExecute() }
        if (fromPath != null) return fromPath.absolutePath
        val home = System.getProperty("user.home")
        return spec.fallbackPaths
            .map { File(it.replace("\$HOME", home)) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /**
     * One turn.
     *
     * [onText] is called as the answer arrives, so a long task shows its working rather than
     * nothing. The reply's session id carries the conversation to the next turn.
     */
    fun ask(
        provider: String,
        prompt: String,
        sessionId: String?,
        model: String,
        workingDir: File?,
        projectRoot: String?,
        systemPrompt: String,
        onText: (String) -> Unit,
    ): AiReply {
        val spec = specs[provider] ?: return AiReply(error = "no command for '$provider'")
        val cli = path(provider) ?: return AiReply(error = NOT_INSTALLED)

        // A directory of Flow's own for anything a CLI insists on reading off disk, so nothing is
        // written into the folder the user keeps flows in.
        val scratch = File(System.getProperty("java.io.tmpdir"), "flow-cli-$provider").apply { mkdirs() }
        val settingsDir = spec.prepare(scratch, systemPrompt)
        val mcpConfig = mcpConfigFor(spec, projectRoot, settingsDir)

        val command = listOf(cli) + spec.args(prompt, model, systemPrompt, mcpConfig, sessionId)
        val builder = ProcessBuilder(command)
            .directory(spec.workingDir(scratch) ?: workingDir)
            .redirectErrorStream(false)
        builder.environment().putAll(spec.environment(systemPrompt, scratch))

        val process = runCatching { builder.start() }
            .getOrElse { return AiReply(error = it.message ?: "could not start ${File(cli).name}") }
        running.set(process)

        // The question is an argument, so nothing is ever written to the child — but it inherits a
        // pipe for stdin all the same, and a CLI that also accepts a prompt on stdin waits for that
        // pipe to end before it answers. Gemini's -p says so outright ("appended to input on stdin
        // (if any)"). Left open, the turn never finishes and the panel sits on "thinking…" forever.
        runCatching { process.outputStream.close() }

        val text = StringBuilder()
        var session: String? = null
        var result: String? = null

        // destroyForcibly() (stop(), above) closes this stream out from under a blocking read,
        // which surfaces here as a plain IOException ("Stream closed") rather than a clean end of
        // stream. Caught rather than left to propagate: 'stopped' already tells a deliberate stop
        // apart from a real failure, and without this the exception skipped that check entirely,
        // reporting a stop the user asked for as "could not run it: Stream closed".
        val readError = runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = spec.read(line)
                    event.session?.let { session = it }
                    event.result?.let { result = it }
                    event.text?.let { chunk ->
                        text.append(chunk)
                        onText(chunk)
                    }
                    // not an event this build knows how to read, and nothing else has been said:
                    // showing it beats dropping the only thing the run produced
                    if (event.text == null && event.result == null && event.session == null &&
                        !line.startsWith("{")
                    ) {
                        text.append(line).append('\n')
                        onText(line + "\n")
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
            code != 0 -> AiReply(
                error = stderr.ifBlank { readError?.message ?: "${spec.command} exited with $code" },
                sessionId = session,
            )
            else -> AiReply(
                error = stderr.ifBlank { readError?.message ?: "${spec.command} said nothing" },
                sessionId = session,
            )
        }
    }

    /**
     * Points a CLI at Flow's own MCP server.
     *
     * The server is this application's code, so it is started the same way this process was — no
     * separate install, and no version of the tools other than the one running. Which file it goes
     * in depends on who is reading it: Claude takes a config on the command line, Gemini reads a
     * settings file beside where it runs, and Codex is given the command directly and needs neither.
     */
    private fun mcpConfigFor(spec: CliSpec, projectRoot: String?, settingsDir: File?): File? = when (spec) {
        is GeminiCliSpec -> settingsDir?.let { dir ->
            File(dir, "settings.json").apply { writeText(FlowPrompt.mcpServersJson(projectRoot)) }
        }
        is CodexSpec -> FlowPrompt.mcpLauncher(projectRoot)
        else -> FlowPrompt.mcpConfig(projectRoot)
    }

    /** Said when the CLI is not there, which is the one failure worth explaining rather than reporting. */
    const val NOT_INSTALLED = "cli-not-installed"
}
