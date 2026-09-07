package flow.ai

import flow.mcp.McpServer
import flow.model.AI_ERR_STEPS
import flow.model.AiReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent Flow runs for the providers that are only a model.
 *
 * Claude Code is an agent in its own right: it holds the conversation, decides when to call a tool,
 * and calls it. OpenAI, Gemini and Ollama do none of that — they answer one request and forget it.
 * So the agent part happens here, once, for all three: the transcript is kept in this process, and
 * the loop below is the one Claude Code would otherwise be running. Ask; stream whatever prose
 * comes back; run whatever tools were asked for; put the results in the transcript; ask again; stop
 * when a pass comes back with nothing asked for, which is the model saying it is finished.
 *
 * Everything the three do differently — the address, the shape of a request, how a stream is read,
 * how a message is written down — is in [AiApi], and nothing else about them differs.
 *
 * The tools are Flow's own, the same ones the MCP server serves, called in this process instead of
 * over a pipe. A tool that leaves a request behind for the app to act on (open_flow, start_flow)
 * still does; it is just this same process that picks it up.
 */
internal class HttpAgent(private val api: AiApi) {

    /**
     * Conversations, by the id handed back to the panel.
     *
     * None of these servers has a notion of a session — every request carries the whole transcript
     * — so the transcript lives here, including the tool calls and their results, which the panel
     * never shows but the model needs in order to remember what it already looked up.
     */
    private val sessions = ConcurrentHashMap<String, MutableList<JsonObject>>()

    /** The stream being read, so a turn can be stopped: closing it ends the read. */
    private val running = AtomicReference<InputStream?>(null)

    /**
     * Set by [stop], cleared when a turn begins.
     *
     * Read rather than inferring a stop from there being no stream: a request that never connected
     * has no stream either, so inferring it turned "the server isn't there" into "the user pressed
     * Stop" — and the loop, believing the turn was over, went round again until it ran out of steps.
     */
    private val cancelled = AtomicBoolean(false)

    fun stop() {
        cancelled.set(true)
        running.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun forget(sessionId: String?) {
        sessionId?.let { sessions.remove(it) }
    }

    fun ask(prompt: String, sessionId: String?, setup: Call, onText: (String) -> Unit): AiReply {
        val id = sessionId ?: newSession()
        val transcript = sessions.getOrPut(id) { mutableListOf() }
        transcript += api.userMessage(prompt)

        val said = StringBuilder()
        cancelled.set(false)

        repeat(MAX_STEPS) {
            val turn = runCatching { one(setup, transcript, said, onText) }
                .getOrElse { e ->
                    // stop() closes the stream out from under the read, which surfaces here as an
                    // IOException rather than a clean end. Whatever it had said stands, and the
                    // exit is not a failure to report.
                    if (cancelled.get()) return AiReply(text = said.toString().trim(), sessionId = id)
                    return AiReply(text = said.toString().trim(), sessionId = id, error = api.reason(e, setup))
                }
            if (cancelled.get()) return AiReply(text = said.toString().trim(), sessionId = id)

            transcript += turn.assistant
            if (turn.calls.isEmpty()) {
                val answer = said.toString().trim()
                return if (answer.isEmpty() && turn.error != null) AiReply(sessionId = id, error = turn.error)
                else AiReply(text = answer, sessionId = id)
            }
            turn.calls.forEach { call ->
                transcript += api.toolResult(call, McpServer.invoke(call.name, call.args))
            }
        }

        // Out of steps: the model kept asking for tools and never wrote an answer. Whatever prose it
        // did produce is worth keeping — it is usually a running commentary on what it was doing.
        return AiReply(text = said.toString().trim(), sessionId = id, error = AI_ERR_STEPS)
    }

    private fun one(setup: Call, transcript: List<JsonObject>, said: StringBuilder, onText: (String) -> Unit): Turn {
        val response = http.send(api.request(setup, transcript), HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        running.set(stream)
        try {
            // A refused request answers with a body saying why, not with a stream — an expired key,
            // a model this account cannot use. Read as prose rather than parsed: what the server
            // says about it is more use than anything this could say instead.
            if (response.statusCode() !in 200..299) {
                val body = stream.bufferedReader().readText().trim()
                return Turn(api.assistantMessage("", emptyList()), emptyList(), httpError(response.statusCode(), body))
            }
            return api.read(stream) { chunk ->
                said.append(chunk)
                onText(chunk)
            }
        } finally {
            running.compareAndSet(stream, null)
        }
    }

    private fun httpError(code: Int, body: String): String {
        // servers put the useful sentence in {"error":{"message":…}} or {"error":…}
        val message = runCatching {
            when (val e = (json.parseToJsonElement(body) as JsonObject)["error"]) {
                is JsonObject -> (e["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> e.content
                else -> null
            }
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code: ${body.take(300).ifBlank { "no reason given" }}"
    }

    private fun newSession(): String = "${api.id}-" + java.util.UUID.randomUUID().toString().take(8)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val http: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        }

        /**
         * How many times round the tool loop before giving up.
         *
         * A model that has lost the thread will call the same tool over and over; this is the
         * ceiling on how long the panel humours that. Generous enough for real work — building a
         * flow, saving it and running it is four or five — and short of an afternoon.
         */
        const val MAX_STEPS = 24
    }
}

/** One request to a model: where to send it, as whom, and with which model. */
internal class Call(val url: String, val model: String, val apiKey: String, val systemPrompt: String)

/** A tool the model asked for. [id] is carried back on the result where the API pairs them up. */
internal class ToolCall(val name: String, val args: JsonObject, val id: String = "")

/** What one request produced: what to write down, what was asked for, and why not, if not. */
internal class Turn(val assistant: JsonObject, val calls: List<ToolCall>, val error: String? = null)

/**
 * What one HTTP provider does differently.
 *
 * Deliberately narrow: three APIs that look nothing alike on the wire are the same conversation
 * underneath, and the loop in [HttpAgent] is written once against this rather than three times
 * against them.
 */
internal interface AiApi {
    /** Names the provider's sessions, so an id says which agent holds the transcript. */
    val id: String

    /** The models the server offers, or empty if it cannot be asked. */
    fun models(url: String, apiKey: String): List<String>

    fun request(call: Call, transcript: List<JsonObject>): HttpRequest

    /** Reads one streamed response, passing prose to [onText] as it arrives. */
    fun read(stream: InputStream, onText: (String) -> Unit): Turn

    fun userMessage(text: String): JsonObject

    /** The assistant's own turn, as this API wants it written into the transcript. */
    fun assistantMessage(text: String, calls: List<ToolCall>): JsonObject

    fun toolResult(call: ToolCall, output: String): JsonObject

    /** Why a request never got through, said in terms of what to do about it. */
    fun reason(e: Throwable, call: Call): String =
        e.message ?: e::class.simpleName ?: "the request failed"
}

/* ───────── shared plumbing ───────── */

internal val agentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun jsonRequest(url: String, body: JsonObject): HttpRequest.Builder =
    HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(agentJson.encodeToString(JsonObject.serializer(), body)))

/**
 * Server-sent events, as OpenAI and Gemini both stream: `data: {…}` lines, blank lines between,
 * and for OpenAI a final `data: [DONE]`. Anything else in the stream is a comment or a keep-alive.
 */
internal fun readSse(stream: InputStream, onEvent: (JsonObject) -> Unit) {
    stream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: return@forEach
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            (runCatching { agentJson.parseToJsonElement(payload) as? JsonObject }.getOrNull())?.let(onEvent)
        }
    }
}

/** One JSON object per line, as Ollama streams. */
internal fun readJsonLines(stream: InputStream, onEvent: (JsonObject) -> Unit) {
    stream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            (runCatching { agentJson.parseToJsonElement(line) as? JsonObject }.getOrNull())?.let(onEvent)
        }
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** A GET that returns a parsed body, or null if the server could not be reached or refused. */
internal fun getJson(url: String, header: Pair<String, String>? = null): JsonObject? = runCatching {
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
    header?.let { builder.header(it.first, it.second) }
    val response = HttpAgent.http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) null
    else agentJson.parseToJsonElement(response.body()) as? JsonObject
}.getOrNull()
