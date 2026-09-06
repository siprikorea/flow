package flow.ai

import flow.mcp.McpServer
import flow.model.AI_ERR_OLLAMA_DOWN
import flow.model.AI_ERR_OLLAMA_NO_MODEL
import flow.model.AI_ERR_OLLAMA_STEPS
import flow.model.AiReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The other assistant: a model running on this machine, through Ollama's HTTP API.
 *
 * Where Claude Code is a whole agent in a subprocess — it keeps the conversation, decides when to
 * call a tool, and does the calling — Ollama is only a model. Everything the agent part does has to
 * happen here: the transcript is held in this object rather than resumed by a session id, and the
 * tool loop below is the one Claude Code would otherwise be running.
 *
 * The tools are Flow's own, the same ones the MCP server serves, called in this process instead of
 * over a pipe. A tool that leaves a request behind for the app to act on (open_flow, start_flow)
 * still does; it is just this same process that picks it up.
 */
internal object Ollama {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    /**
     * Conversations, by the id handed back to the panel.
     *
     * Ollama has no notion of a session — every request carries the whole transcript — so the
     * transcript lives here, including the tool calls and their results, which the panel never
     * sees but the model needs in order to remember what it already looked up.
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, MutableList<JsonObject>>()

    /** The stream being read, so a turn can be stopped: closing it ends the read. */
    private val running = AtomicReference<InputStream?>(null)

    /**
     * Set by [stop], cleared when a turn begins.
     *
     * Read rather than inferring a stop from there being no stream: a request that never connected
     * has no stream either, so inferring it turned "the server isn't there" into "the user pressed
     * Stop" — and the loop, believing the turn was over, went round again until it ran out of steps.
     */
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun stop() {
        cancelled.set(true)
        running.getAndSet(null)?.let { runCatching { it.close() } }
    }

    /** What the server has pulled. Empty when it cannot be reached — the panel says so either way. */
    fun models(baseUrl: String): List<String> = runCatching {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/tags"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        (json.parseToJsonElement(body) as JsonObject)["models"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
    }.getOrDefault(emptyList())

    /**
     * One turn, tool calls and all.
     *
     * Each pass asks the model, streams whatever prose it produces straight to [onText], and then
     * looks at whether it asked for a tool. If it did, the tool runs, its output goes back as
     * another message, and the model is asked again — that loop is what makes this an assistant
     * rather than a chat box. It stops when a pass comes back with no tool call, which is the model
     * saying it is finished.
     */
    fun ask(
        prompt: String,
        sessionId: String?,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        onText: (String) -> Unit,
    ): AiReply {
        val url = baseUrl.trimEnd('/').ifBlank { flow.model.DEFAULT_OLLAMA_URL }
        val chosen = model.ifBlank { models(url).firstOrNull() ?: return AiReply(error = AI_ERR_OLLAMA_NO_MODEL) }

        val id = sessionId ?: newSession(systemPrompt)
        val messages = sessions.getOrPut(id) { mutableListOf(systemMessage(systemPrompt)) }
        messages += message("user", prompt)

        val said = StringBuilder()
        cancelled.set(false)

        repeat(MAX_STEPS) {
            val turn = runCatching { one(url, chosen, messages, said, onText) }
                .getOrElse { e ->
                    // stop() closes the stream out from under the read, which surfaces here as an
                    // IOException rather than a clean end. Whatever it had said stands, and the
                    // exit is not a failure to report.
                    if (cancelled.get()) return AiReply(text = said.toString().trim(), sessionId = id)
                    return AiReply(text = said.toString().trim(), sessionId = id, error = reason(e, url))
                }
            if (cancelled.get()) return AiReply(text = said.toString().trim(), sessionId = id)

            messages += turn.assistant
            if (turn.calls.isEmpty()) {
                val answer = said.toString().trim()
                return if (answer.isEmpty() && turn.error != null) AiReply(sessionId = id, error = turn.error)
                else AiReply(text = answer, sessionId = id)
            }
            turn.calls.forEach { call ->
                messages += toolResult(call.name, McpServer.invoke(call.name, call.args))
            }
        }

        // Out of steps: the model kept asking for tools and never wrote an answer. Whatever prose it
        // did produce is worth keeping — it is usually a running commentary on what it was doing.
        return AiReply(text = said.toString().trim(), sessionId = id, error = AI_ERR_OLLAMA_STEPS)
    }

    /** What one request to the model produced. */
    private class Turn(val assistant: JsonObject, val calls: List<Call>, val error: String?)

    private class Call(val name: String, val args: JsonObject)

    private fun one(
        url: String,
        model: String,
        messages: List<JsonObject>,
        said: StringBuilder,
        onText: (String) -> Unit,
    ): Turn {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", true)
            put("tools", toolSchema())
        }
        val request = HttpRequest.newBuilder(URI.create("$url/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), body)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        running.set(stream)

        val content = StringBuilder()
        val calls = mutableListOf<Call>()
        var error: String? = null

        stream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
                event.text("error")?.let { error = it }
                val message = event["message"] as? JsonObject ?: return@forEach
                message.text("content")?.let { chunk ->
                    content.append(chunk)
                    said.append(chunk)
                    onText(chunk)
                }
                (message["tool_calls"] as? JsonArray)?.forEach { entry ->
                    val fn = (entry as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
                    val name = fn.text("name") ?: return@forEach
                    // arguments come back as an object from Ollama, but a model that has been
                    // fine-tuned on the OpenAI shape sometimes emits the JSON as a string
                    val args = when (val raw = fn["arguments"]) {
                        is JsonObject -> raw
                        is JsonPrimitive -> runCatching { json.parseToJsonElement(raw.content) as JsonObject }
                            .getOrDefault(JsonObject(emptyMap()))
                        else -> JsonObject(emptyMap())
                    }
                    calls += Call(name, args)
                }
            }
        }
        running.compareAndSet(stream, null)

        val assistant = buildJsonObject {
            put("role", "assistant")
            put("content", content.toString())
            if (calls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        calls.forEach { call ->
                            add(
                                buildJsonObject {
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.args)
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
        return Turn(assistant, calls, error)
    }

    /** Flow's tools in the shape Ollama's /api/chat takes them — the MCP schemas, rewrapped. */
    private fun toolSchema(): JsonArray = buildJsonArray {
        McpServer.toolSpecs().forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.schema)
                    }
                },
            )
        }
    }

    fun forget(sessionId: String?) {
        sessionId?.let { sessions.remove(it) }
    }

    private fun newSession(systemPrompt: String): String {
        val id = "ollama-" + java.util.UUID.randomUUID().toString().take(8)
        sessions[id] = mutableListOf(systemMessage(systemPrompt))
        return id
    }

    private fun systemMessage(prompt: String) = message("system", prompt)

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun toolResult(name: String, output: String) = buildJsonObject {
        put("role", "tool")
        put("tool_name", name)
        put("content", output)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /**
     * Why a request did not get through, said in terms of what to do about it.
     *
     * "Connection refused" is the one that matters — it means the server is not running, which is
     * the normal state of Ollama on a machine that hasn't started it, not a fault.
     */
    private fun reason(e: Throwable, url: String): String {
        val message = e.message ?: e::class.simpleName ?: "the request failed"
        return if (e is java.net.ConnectException || message.contains("Connection refused")) {
            "$AI_ERR_OLLAMA_DOWN ($url)"
        } else {
            message
        }
    }


    /**
     * How many times round the tool loop before giving up.
     *
     * A local model that has lost the thread will call the same tool over and over; this is the
     * ceiling on how long the panel humours that. Generous enough for real work — building a flow,
     * saving it and running it is four or five — and short of an afternoon.
     */
    private const val MAX_STEPS = 24
}
