package flow.ext.ai

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A model, as a node in a flow.
 *
 * The input is the text to work on, "role" is what to do with it, and the answer comes out of
 * "out" as text — which is the whole point: the output of one of these is the input of the next,
 * so a chain of them is a pipeline of instructions, each one working on what the last produced.
 *
 * This is not the AI panel. The panel is an assistant that holds a conversation and can reach
 * Flow's own tools; this is one request with no memory and no tools, because a node in a flow is a
 * function — the same input and options give the same kind of answer, and nothing it does reaches
 * outside the value on its output port.
 *
 * Which assistant, and how to reach it, is the app's own configuration (Settings ▸ AI): the
 * addresses in ~/.flow/settings.json and the keys in ~/.flow/credentials.json. A node names a
 * provider and optionally a model; everything else is already set up once, for the panel, and there
 * is no reason to ask for it again per node.
 */
class AiExtension : ProcessorExtension {
    override val id = "flow.ai"
    override val displayName = "AI"
    override val version = "1.1.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override val options = listOf(
        // blank means the one the AI panel is set to, so a flow built on a machine set up for
        // Ollama still runs on one set up for OpenAI
        ExtensionOption("provider", OptionType.SELECT, "", listOf("", "claude", "openai", "gemini", "ollama")),
        ExtensionOption("model", OptionType.TEXT, ""),
        // what to do with the input. The node's whole behaviour, and the reason two of these in a
        // row are different steps rather than the same one twice.
        ExtensionOption("role", OptionType.TEXT, "Answer with the result only, and no explanation."),
        ExtensionOption("timeoutSec", OptionType.NUMBER, "120"),
    )

    /**
     * The same two, set once for every AI node instead of on each of them.
     *
     * A node's own value wins when it has one; blank means "whatever this is set to", which is
     * already how the node reads them, so a flow that names neither follows Settings ▸ Extensions,
     * and one that names neither there follows Settings ▸ AI.
     */
    override val settings = listOf(
        ExtensionOption("provider", OptionType.SELECT, "", listOf("", "claude", "openai", "gemini", "ollama")),
        ExtensionOption("model", OptionType.TEXT, ""),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val input = inputs["in"]?.decodeToString().orEmpty()
        val settings = Config.read()
        val provider = options["provider"]?.trim()?.ifBlank { null }
            ?: settings.string("aiProvider")?.ifBlank { null }
            ?: "claude"
        val api = apiFor(provider) ?: throw IllegalStateException("unknown AI provider '$provider'")

        val model = options["model"]?.trim()?.ifBlank { null }
            ?: settings.string(api.modelSetting)?.ifBlank { null }
            ?: api.defaultModel
            ?: throw IllegalStateException(
                "no model for $provider — set one on this node, or in Settings ▸ AI",
            )
        val url = settings.string(api.urlSetting)?.ifBlank { null } ?: api.defaultUrl
        val key = Config.apiKey(provider)
        if (api.needsKey && key.isEmpty()) {
            throw IllegalStateException(
                "no API key for $provider — enter one in Settings ▸ AI, or set ${api.envVar}",
            )
        }
        val role = options["role"].orEmpty().ifBlank { "Answer with the result only, and no explanation." }
        val timeout = options["timeoutSec"]?.trim()?.toLongOrNull()?.coerceIn(1, 3600) ?: 120L

        val answer = api.ask(url.trimEnd('/'), model, key, role, input, timeout)
        return mapOf("out" to answer.encodeToByteArray())
    }

    /* ───────── the providers, one request each ───────── */

    /**
     * Deliberately not streaming, and deliberately without tools.
     *
     * A node produces one value; there is nobody to show a half-written answer to, and a partial
     * one is not a value. So each of these is one request and one field read back out of it — much
     * less than the AI panel's agent needs, and the reason this does not share that code (which
     * lives in the app, on the other side of the process boundary an extension runs behind).
     */
    private interface Api {
        val urlSetting: String
        val modelSetting: String
        val defaultUrl: String
        val needsKey: Boolean
        val envVar: String
        /** Used when neither the node nor the app names one; null means the server must be asked. */
        val defaultModel: String? get() = null
        fun ask(url: String, model: String, key: String, role: String, input: String, timeoutSec: Long): String
    }

    private fun apiFor(provider: String): Api? = when (provider) {
        "claude" -> Anthropic
        "openai" -> OpenAi
        "gemini" -> Gemini
        "ollama" -> Ollama
        else -> null
    }

    private object Anthropic : Api {
        override val urlSetting = "claudeUrl"
        override val modelSetting = "aiModel"
        override val defaultUrl = "https://api.anthropic.com/v1"
        override val needsKey = true
        override val envVar = "ANTHROPIC_API_KEY"
        override val defaultModel = "claude-sonnet-5"

        override fun ask(url: String, model: String, key: String, role: String, input: String, timeoutSec: Long): String {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", role)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "user"); put("content", input) })
                }
            }
            val reply = post("$url/messages", body, timeoutSec) {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
            }
            return (reply["content"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.takeIf { p -> p.string("type") == "text" }?.string("text") }
                .joinToString("")
        }
    }

    private object OpenAi : Api {
        override val urlSetting = "openaiUrl"
        override val modelSetting = "openaiModel"
        override val defaultUrl = "https://api.openai.com/v1"
        override val needsKey = true
        override val envVar = "OPENAI_API_KEY"

        override fun ask(url: String, model: String, key: String, role: String, input: String, timeoutSec: Long): String {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "system"); put("content", role) })
                    add(buildJsonObject { put("role", "user"); put("content", input) })
                }
            }
            val reply = post("$url/chat/completions", body, timeoutSec) {
                header("Authorization", "Bearer $key")
            }
            return ((reply["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { it["message"] as? JsonObject }?.string("content").orEmpty()
        }
    }

    private object Gemini : Api {
        override val urlSetting = "geminiUrl"
        override val modelSetting = "geminiModel"
        override val defaultUrl = "https://generativelanguage.googleapis.com/v1beta"
        override val needsKey = true
        override val envVar = "GEMINI_API_KEY"

        override fun ask(url: String, model: String, key: String, role: String, input: String, timeoutSec: Long): String {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", input) }) }
                        },
                    )
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", role) }) }
                }
            }
            val reply = post("$url/models/$model:generateContent?key=$key", body, timeoutSec) {}
            return ((reply["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { it["content"] as? JsonObject }
                ?.let { it["parts"] as? JsonArray }.orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("text") }
                .joinToString("")
        }
    }

    private object Ollama : Api {
        override val urlSetting = "ollamaUrl"
        override val modelSetting = "ollamaModel"
        override val defaultUrl = "http://localhost:11434"
        override val needsKey = false
        override val envVar = ""

        override fun ask(url: String, model: String, key: String, role: String, input: String, timeoutSec: Long): String {
            val body = buildJsonObject {
                put("model", model)
                put("stream", false)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "system"); put("content", role) })
                    add(buildJsonObject { put("role", "user"); put("content", input) })
                }
            }
            val reply = post("$url/api/chat", body, timeoutSec) {}
            return (reply["message"] as? JsonObject)?.string("content").orEmpty()
        }
    }

    private companion object {
        const val MAX_TOKENS = 8192

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val http: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        }

        /**
         * One request, with the server's own words on a failure.
         *
         * A refusal — an expired key, a model the account cannot use — comes back as a body saying
         * so, and that sentence is more use than anything this could say instead. The host shows a
         * thrown message on the node, so it reaches the canvas.
         */
        fun post(
            url: String,
            body: JsonObject,
            timeoutSec: Long,
            headers: HttpRequest.Builder.() -> Unit,
        ): JsonObject {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), body)))
                .apply(headers)
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val parsed = runCatching { json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
            if (response.statusCode() !in 200..299) {
                val said = when (val e = parsed?.get("error")) {
                    is JsonObject -> e.string("message")
                    is JsonPrimitive -> e.content
                    else -> null
                }
                throw IllegalStateException(said ?: "HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
            return parsed ?: throw IllegalStateException("the server's answer was not JSON: ${response.body().take(300)}")
        }

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * What the app has already been told, read straight off disk.
     *
     * The same two files Settings ▸ AI writes. An extension runs in a worker process with none of
     * the app's own code on its classpath, so this reads them rather than asking — and reads them
     * per call, so changing a key or an address in Settings takes effect on the next run rather
     * than at the next restart.
     */
    private object Config {
        // resolved per call, not once: the doc above promises a key changed in Settings takes
        // effect on the next run, and a path captured at class-load would not
        private val dir: File get() = File(System.getProperty("user.home"), ".flow")

        fun read(): JsonObject = load(File(dir, "settings.json"))

        fun apiKey(provider: String): String {
            val name = when (provider) {
                "openai" -> "openai-key"
                "gemini" -> "gemini-key"
                "claude" -> "claude-key"
                else -> return ""
            }
            val stored = load(File(dir, "credentials.json")).string(name).orEmpty()
            if (stored.isNotEmpty()) return stored
            val env = when (provider) {
                "openai" -> "OPENAI_API_KEY"
                "gemini" -> "GEMINI_API_KEY"
                else -> "ANTHROPIC_API_KEY"
            }
            return System.getenv(env).orEmpty()
        }

        private fun load(file: File): JsonObject = runCatching {
            json.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
