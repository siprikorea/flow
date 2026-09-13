package flow.ai

import flow.mcp.FlowTools
import flow.model.AI_ERR_OLLAMA_DOWN
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.net.http.HttpRequest

/**
 * The three HTTP assistants, each described only where it differs from the others.
 *
 * The conversation is the same one in all three cases — ask, stream, run the tools it asked for,
 * ask again — and that lives in HttpAgent. What is here is the wire: an address, a request body, a
 * stream to read, and how to write a turn down so the next request carries it.
 *
 * Two of the three are the same design with different names for everything, which is worth saying
 * because it explains the shape: OpenAI and Ollama both take a flat list of messages with roles and
 * a `tools` array of function declarations. Gemini does not — it has `contents` with `parts`,
 * carries the system prompt beside the conversation rather than inside it, and answers a tool with
 * another user turn instead of a role of its own.
 */

/* ───────── Ollama: a model on this machine ───────── */

internal object OllamaApi : AiApi {
    override val id = "ollama"

    override fun models(url: String, apiKey: String): List<String> =
        getJson("${url.trimEnd('/')}/api/tags")?.get("models")?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }

    override fun request(call: Call, transcript: List<JsonObject>): HttpRequest {
        val body = buildJsonObject {
            put("model", call.model)
            put("messages", JsonArray(listOf(systemMessage(call.systemPrompt)) + transcript))
            put("stream", true)
            put("tools", openAiStyleTools())
        }
        return jsonRequest("${call.url.trimEnd('/')}/api/chat", body).build()
    }

    override fun read(stream: InputStream, onText: (String) -> Unit): Turn {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var error: String? = null
        readJsonLines(stream) { event ->
            event.str("error")?.let { error = it }
            val message = event["message"] as? JsonObject ?: return@readJsonLines
            message.str("content")?.let { chunk ->
                text.append(chunk)
                onText(chunk)
            }
            (message["tool_calls"] as? JsonArray)?.forEach { entry ->
                val fn = (entry as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
                val name = fn.str("name") ?: return@forEach
                calls += ToolCall(name, argumentsOf(fn["arguments"]))
            }
        }
        return Turn(assistantMessage(text.toString(), calls), calls, error)
    }

    override fun userMessage(text: String) = message("user", text)

    override fun assistantMessage(text: String, calls: List<ToolCall>) = buildJsonObject {
        put("role", "assistant")
        put("content", text)
        if (calls.isNotEmpty()) {
            putJsonArray("tool_calls") {
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
            }
        }
    }

    override fun toolResult(call: ToolCall, output: String) = buildJsonObject {
        put("role", "tool")
        put("tool_name", call.name)
        put("content", output)
    }

    /**
     * "Connection refused" is the one worth translating: it means the server is not running, which
     * is the normal state of Ollama on a machine that hasn't started it, not a fault.
     */
    override fun reason(e: Throwable, call: Call): String {
        val message = e.message ?: e::class.simpleName ?: "the request failed"
        return if (e is java.net.ConnectException || message.contains("Connection refused")) {
            "$AI_ERR_OLLAMA_DOWN (${call.url})"
        } else {
            message
        }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun systemMessage(prompt: String) = message("system", prompt)
}

/* ───────── OpenAI ───────── */

internal object OpenAiApi : AiApi {
    override val id = "openai"

    /**
     * The chat models the key can use.
     *
     * /v1/models lists everything the account can reach — embeddings, speech, images — and the API
     * says nothing about which of them can hold a conversation. The ones that cannot are named
     * after what they do, so they are excluded by name; a wrong guess here shows a model that will
     * not answer rather than hiding one that would, which is the better way round.
     */
    override fun models(url: String, apiKey: String): List<String> {
        val body = getJson("${url.trimEnd('/')}/models", "Authorization" to "Bearer $apiKey") ?: return emptyList()
        return body["data"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("id") }
            .filterNot { id -> NOT_CHAT.any { id.contains(it) } }
            .sorted()
    }

    override fun request(call: Call, transcript: List<JsonObject>): HttpRequest {
        val body = buildJsonObject {
            put("model", call.model)
            put("messages", JsonArray(listOf(systemMessage(call.systemPrompt)) + transcript))
            put("stream", true)
            put("tools", openAiStyleTools())
        }
        return jsonRequest("${call.url.trimEnd('/')}/chat/completions", body)
            .header("Authorization", "Bearer ${call.apiKey}")
            .build()
    }

    /**
     * Tool calls arrive in pieces.
     *
     * Unlike the other two, OpenAI streams a call's arguments as a run of fragments that have to be
     * concatenated in order, keyed by the `index` that ties them together — the name comes in the
     * first fragment and the JSON a few characters at a time after it. Assembling them is the whole
     * reason this reader is longer than the others.
     */
    override fun read(stream: InputStream, onText: (String) -> Unit): Turn {
        val text = StringBuilder()
        val building = sortedMapOf<Int, Fragment>()
        var error: String? = null

        readSse(stream) { event ->
            event.str("error")?.let { error = it }
            val delta = (event["choices"] as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("delta") as? JsonObject } ?: return@readSse
            delta.str("content")?.let { chunk ->
                text.append(chunk)
                onText(chunk)
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { entry ->
                val call = entry as? JsonObject ?: return@forEach
                val index = (call["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val fragment = building.getOrPut(index) { Fragment() }
                call.str("id")?.let { fragment.id = it }
                (call["function"] as? JsonObject)?.let { fn ->
                    fn.str("name")?.let { fragment.name = it }
                    // not str(): an empty fragment is normal and must not be dropped, and the
                    // arguments of a call that takes none arrive as the empty string
                    ((fn["arguments"] as? JsonPrimitive)?.takeIf { it.isString })?.let {
                        fragment.arguments.append(it.content)
                    }
                }
            }
        }

        val calls = building.values.mapNotNull { fragment ->
            fragment.name?.let { ToolCall(it, argumentsOf(JsonPrimitive(fragment.arguments.toString())), fragment.id) }
        }
        return Turn(assistantMessage(text.toString(), calls), calls, error)
    }

    private class Fragment {
        var id: String = ""
        var name: String? = null
        val arguments = StringBuilder()
    }

    override fun userMessage(text: String) = message("user", text)

    override fun assistantMessage(text: String, calls: List<ToolCall>) = buildJsonObject {
        put("role", "assistant")
        put("content", text)
        if (calls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                // a string, not an object: this is the one place the API asks for
                                // JSON inside JSON, and it refuses the request if it gets an object
                                put("arguments", agentJson.encodeToString(JsonObject.serializer(), call.args))
                            }
                        },
                    )
                }
            }
        }
    }

    // paired by id, and the API rejects a result whose id it did not hand out
    override fun toolResult(call: ToolCall, output: String) = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", call.id)
        put("content", output)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun systemMessage(prompt: String) = message("system", prompt)

    private val NOT_CHAT = listOf(
        "embedding", "whisper", "tts", "dall-e", "moderation", "audio", "image", "realtime",
        "transcribe", "search", "similarity", "edit", "davinci", "babbage", "codex",
    )
}

/* ───────── Gemini ───────── */

internal object GeminiApi : AiApi {
    override val id = "gemini"

    override fun models(url: String, apiKey: String): List<String> {
        val body = getJson("${url.trimEnd('/')}/models?key=$apiKey") ?: return emptyList()
        return body["models"]?.jsonArray.orEmpty()
            .mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                // only the ones that can hold a conversation; the same list carries embedding models
                val methods = (model["supportedGenerationMethods"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                if (methods.none { it.contains("generateContent") }) return@mapNotNull null
                model.str("name")?.removePrefix("models/")
            }
            .sorted()
    }

    /**
     * The system prompt rides beside the conversation rather than inside it — Gemini has no system
     * role, and a system turn written as a user one is answered rather than obeyed.
     */
    override fun request(call: Call, transcript: List<JsonObject>): HttpRequest {
        val body = buildJsonObject {
            put("contents", JsonArray(transcript))
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", call.systemPrompt) }) }
            }
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            FlowTools.specs().forEach { tool ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.schema)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        // alt=sse, or the answer is a JSON array delivered in fragments that are not themselves
        // JSON — the same events, in a form nothing can read until the last byte arrives
        val endpoint = "${call.url.trimEnd('/')}/models/${call.model}:streamGenerateContent?alt=sse&key=${call.apiKey}"
        return jsonRequest(endpoint, body).build()
    }

    override fun read(stream: InputStream, onText: (String) -> Unit): Turn {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var error: String? = null

        readSse(stream) { event ->
            ((event["error"] as? JsonObject)?.str("message"))?.let { error = it }
            val parts = (event["candidates"] as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("content") as? JsonObject }
                ?.get("parts") as? JsonArray ?: return@readSse
            parts.forEach { entry ->
                val part = entry as? JsonObject ?: return@forEach
                part.str("text")?.let { chunk ->
                    text.append(chunk)
                    onText(chunk)
                }
                (part["functionCall"] as? JsonObject)?.let { fn ->
                    val name = fn.str("name") ?: return@let
                    calls += ToolCall(name, (fn["args"] as? JsonObject) ?: JsonObject(emptyMap()))
                }
            }
        }
        return Turn(assistantMessage(text.toString(), calls), calls, error)
    }

    override fun userMessage(text: String) = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
    }

    override fun assistantMessage(text: String, calls: List<ToolCall>) = buildJsonObject {
        put("role", "model")
        putJsonArray("parts") {
            if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
            calls.forEach { call ->
                add(
                    buildJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.name)
                            put("args", call.args)
                        }
                    },
                )
            }
        }
    }

    /**
     * A tool's output goes back as a user turn: Gemini has no tool role, and the result is a part
     * of the conversation rather than a message in it. The output has to be an object, so the text
     * the tool returned is wrapped in one.
     */
    override fun toolResult(call: ToolCall, output: String) = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") {
            add(
                buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", call.name)
                        putJsonObject("response") { put("result", output) }
                    }
                },
            )
        }
    }
}

/* ───────── Claude, over the Anthropic API ───────── */

internal object AnthropicApi : AiApi {
    override val id = "claude"

    override fun models(url: String, apiKey: String): List<String> {
        val body = getJson("${url.trimEnd('/')}/models", "x-api-key" to apiKey) ?: return emptyList()
        return body["data"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }
    }

    /**
     * The system prompt is a field of its own, and max_tokens is required — there is no default, and
     * a request without it is refused rather than answered at some sensible length.
     */
    override fun request(call: Call, transcript: List<JsonObject>): HttpRequest {
        val body = buildJsonObject {
            put("model", call.model)
            put("max_tokens", MAX_TOKENS)
            put("system", call.systemPrompt)
            put("messages", JsonArray(transcript))
            put("stream", true)
            putJsonArray("tools") {
                FlowTools.specs().forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            // named input_schema here, parameters everywhere else
                            put("input_schema", tool.schema)
                        },
                    )
                }
            }
        }
        return jsonRequest("${call.url.trimEnd('/')}/messages", body)
            .header("x-api-key", call.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .build()
    }

    /**
     * Content arrives as numbered blocks rather than one stream.
     *
     * A block is announced (content_block_start), filled by deltas, and closed; a tool call is a
     * block whose name arrives in the announcement and whose arguments arrive as JSON fragments
     * after it, so the index is what ties a call's pieces together — the same problem OpenAI has,
     * spelled differently.
     */
    override fun read(stream: InputStream, onText: (String) -> Unit): Turn {
        val text = StringBuilder()
        val building = sortedMapOf<Int, Fragment>()
        var error: String? = null

        readSse(stream) { event ->
            when (event.str("type")) {
                "error" -> ((event["error"] as? JsonObject)?.str("message"))?.let { error = it }
                "content_block_start" -> {
                    val index = intOf(event["index"])
                    val block = event["content_block"] as? JsonObject ?: return@readSse
                    if (block.str("type") == "tool_use") {
                        building[index] = Fragment(block.str("id").orEmpty(), block.str("name"))
                    }
                }
                "content_block_delta" -> {
                    val delta = event["delta"] as? JsonObject ?: return@readSse
                    when (delta.str("type")) {
                        "text_delta" -> delta.str("text")?.let { chunk ->
                            text.append(chunk)
                            onText(chunk)
                        }
                        "input_json_delta" -> {
                            val fragment = building[intOf(event["index"])] ?: return@readSse
                            ((delta["partial_json"] as? JsonPrimitive)?.takeIf { it.isString })?.let {
                                fragment.arguments.append(it.content)
                            }
                        }
                    }
                }
            }
        }

        val calls = building.values.mapNotNull { fragment ->
            fragment.name?.let {
                ToolCall(it, argumentsOf(JsonPrimitive(fragment.arguments.toString())), fragment.id)
            }
        }
        return Turn(assistantMessage(text.toString(), calls), calls, error)
    }

    private class Fragment(val id: String, val name: String?) {
        val arguments = StringBuilder()
    }

    private fun intOf(element: kotlinx.serialization.json.JsonElement?): Int =
        (element as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    override fun userMessage(text: String) = buildJsonObject {
        put("role", "user")
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    }

    override fun assistantMessage(text: String, calls: List<ToolCall>) = buildJsonObject {
        put("role", "assistant")
        putJsonArray("content") {
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
            calls.forEach { call ->
                add(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", call.args)
                    },
                )
            }
        }
    }

    /** A result is a user turn holding a tool_result block, paired to the call by the id given. */
    override fun toolResult(call: ToolCall, output: String) = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", call.id)
                    put("content", output)
                },
            )
        }
    }

    private const val ANTHROPIC_VERSION = "2023-06-01"

    /**
     * The ceiling on one answer, which this API makes the caller name.
     *
     * Large enough that an answer is never cut off mid-sentence in practice, and it costs nothing
     * to ask for: what is billed is what comes back, not what was allowed.
     */
    private const val MAX_TOKENS = 8192
}

/* ───────── shared between the two OpenAI-shaped APIs ───────── */

/** Flow's tools as a `tools` array of function declarations — the MCP schemas, rewrapped. */
private fun openAiStyleTools(): JsonArray = buildJsonArray {
    FlowTools.specs().forEach { tool ->
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

/**
 * A call's arguments, however they arrived.
 *
 * The APIs disagree, and so do models within one API: an object is what the shape says, but a model
 * fine-tuned on the other convention emits the JSON as a string. A call whose arguments cannot be
 * read at all is better run with none — the tool will say what it needed — than not run.
 */
private fun argumentsOf(raw: kotlinx.serialization.json.JsonElement?): JsonObject = when (raw) {
    is JsonObject -> raw
    is JsonPrimitive -> runCatching { agentJson.parseToJsonElement(raw.content) as JsonObject }
        .getOrDefault(JsonObject(emptyMap()))
    else -> JsonObject(emptyMap())
}
