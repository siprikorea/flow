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
    override val version = "1.2.3"
    override val category = "ai"
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
        // the same two ways the AI panel reaches an assistant: its own command, already signed in,
        // or the API with a key. A node is one question, so the CLI here runs it and reads the
        // answer — no tools, no conversation.
        ExtensionOption("transport", OptionType.SELECT, "api", listOf("api", "cli")),
        ExtensionOption("apiKey", OptionType.TEXT, ""),
        ExtensionOption("model", OptionType.SELECT, "", listOf("")),
    )

    /** The key, which is not kept in the settings file and is not shown while it is typed. */
    override val secretSettings = listOf("apiKey")

    /**
     * The models the chosen provider will actually answer with.
     *
     * A model is a name a server either has or does not, so typing one is guessing: this asks the
     * server what it has, for the provider and key set right here, and the setting becomes a menu.
     * A provider with no key yet, or one that cannot be reached, comes back with nothing — which
     * the host shows as "no list came back" rather than an empty menu.
     */
    override fun settingsFor(values: Map<String, String>): List<ExtensionOption> {
        val cli = values["transport"] == VIA_CLI
        // a command brings its own account, so there is no key to ask for; and it will not say what
        // models it can run, so the name is typed rather than picked
        val shown = settings.filterNot { cli && it.name == "apiKey" }
            .map { if (cli && it.name == "model") it.copy(type = OptionType.TEXT, choices = emptyList()) else it }
        if (cli) return shown

        val provider = values["provider"].orEmpty().ifBlank { return shown }
        val api = apiFor(provider) ?: return shown
        val key = values["apiKey"].orEmpty().ifBlank { Config.apiKey(provider) }
        if (api.needsKey && key.isBlank()) return shown
        val url = Config.read().string(api.urlSetting)?.ifBlank { null } ?: api.defaultUrl
        val models = runCatching { api.models(url.trimEnd('/'), key) }.getOrDefault(emptyList())
        if (models.isEmpty()) return shown
        return shown.map { if (it.name == "model") it.copy(choices = listOf("") + models) else it }
    }

    override val portDescriptions = mapOf(
        "_module" to "Send the input to a language model with a role to play, and put its answer on the output. Use it for a step no fixed module can do — summarising, classifying, rewriting, drafting — and chain two of them to make two steps. It is not deterministic: the same input can give a different answer each run, so do not put it where an exact value is needed.",
        "in" to "The text to work on. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "out" to "The model's answer as text, ready to be the next node's input.",
    )

    override val optionDescriptions = mapOf(
        "provider" to "Which assistant answers. Left empty, whichever one the app is configured to use, so a flow built on one machine still runs on another.",
        "model" to "The model name. Left empty, the one configured for that provider in Settings.",
        "role" to "What to do with the input. This is the whole behaviour of the node, and what makes two of these in a row two different steps.",
        "timeoutSec" to "How long to wait for an answer before giving up. A local model on a slow machine can need minutes.",
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
            // a command picks its own default when not told; only the API has to be given one
            ?: if (options["transport"] == VIA_CLI) "" else throw IllegalStateException(
                "no model for $provider — set one on this node, or in Settings ▸ Extensions ▸ AI",
            )
        val role = options["role"].orEmpty().ifBlank { "Answer with the result only, and no explanation." }
        val timeout = options["timeoutSec"]?.trim()?.toLongOrNull()?.coerceIn(1, 3600) ?: 120L

        // A command is already signed in, so it needs no key and no address — which is the whole
        // reason to offer it: it is the way to try this node out on a machine set up for the CLI.
        if (options["transport"] == VIA_CLI) {
            return mapOf("out" to Cli.ask(provider, model, role, input, timeout).encodeToByteArray())
        }

        val url = settings.string(api.urlSetting)?.ifBlank { null } ?: api.defaultUrl
        // this extension's own setting first — Settings ▸ Extensions ▸ AI, which is where someone
        // configuring this node looks — then the app's AI panel key, then the environment
        val key = options["apiKey"]?.trim()?.ifBlank { null } ?: Config.apiKey(provider)
        if (api.needsKey && key.isEmpty()) {
            throw IllegalStateException(
                "no API key for $provider — enter one in Settings ▸ Extensions ▸ AI, " +
                    "or in Settings ▸ AI, or set ${api.envVar}",
            )
        }
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

        /** What this server offers, so the model setting can be a menu rather than a name to type. */
        fun models(url: String, key: String): List<String> = emptyList()
    }

    private fun apiFor(provider: String): Api? = when (provider) {
        "claude" -> Anthropic
        "openai" -> OpenAi
        "gemini" -> Gemini
        "ollama" -> Ollama
        else -> null
    }

    private object Anthropic : Api {
        override fun models(url: String, key: String): List<String> =
            get("$url/models", "x-api-key" to key)?.get("data")?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("id") }

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
        override fun models(url: String, key: String): List<String> =
            get("$url/models", "Authorization" to "Bearer $key")?.get("data")?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("id") }
                // the same list carries embeddings, speech and images, which are named after what
                // they do; a wrong guess here shows a model that will not answer rather than
                // hiding one that would
                .filterNot { id -> listOf("embedding", "whisper", "tts", "dall-e", "moderation", "audio", "image", "realtime", "transcribe").any { id.contains(it) } }
                .sorted()

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
        override fun models(url: String, key: String): List<String> =
            get("$url/models?key=$key")?.get("models")?.jsonArray.orEmpty()
                .mapNotNull { entry ->
                    val m = entry as? JsonObject ?: return@mapNotNull null
                    val methods = (m["supportedGenerationMethods"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    if (methods.none { it.contains("generateContent") }) return@mapNotNull null
                    m.string("name")?.removePrefix("models/")
                }
                .sorted()

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
        override fun models(url: String, key: String): List<String> =
            get("$url/api/tags")?.get("models")?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name") }

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
        const val VIA_CLI = "cli"
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

        /** A GET that returns a parsed body, or null if the server could not be reached or refused. */
        fun get(url: String, header: Pair<String, String>? = null): JsonObject? = runCatching {
            val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
            header?.let { b.header(it.first, it.second) }
            val response = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) null
            else json.parseToJsonElement(response.body()) as? JsonObject
        }.getOrNull()

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * The same assistants, as the commands they ship as.
     *
     * A node is one question with one answer, so this is not the agent the AI panel runs through a
     * CLI — no tools, no conversation, no session to resume. It is the command, the question, and
     * whatever it printed. What it buys is a way to use this node on a machine that is signed in to
     * a CLI and has no API key at all, which is the usual state of one.
     */
    private object Cli {

        fun ask(provider: String, model: String, role: String, input: String, timeoutSec: Long): String {
            val command = command(provider)
                ?: throw IllegalStateException("$provider has no command to run — use the API instead")
            val exe = find(command)
                ?: throw IllegalStateException(
                    "$command is not installed — install it, or set this node's transport to api",
                )
            // one shot, so the role goes at the top of the question: none of these takes a system
            // prompt on the command line in a way that survives being a single run
            val prompt = "$role\n\n$input"
            val args = when (provider) {
                "claude" -> buildList {
                    add("-p"); add(prompt)
                    if (model.isNotBlank()) { add("--model"); add(model) }
                }
                "openai" -> buildList {
                    add("exec"); add("--skip-git-repo-check")
                    if (model.isNotBlank()) { add("-m"); add(model) }
                    add(prompt)
                }
                "gemini" -> buildList {
                    add("-p"); add(prompt)
                    if (model.isNotBlank()) { add("-m"); add(model) }
                }
                // the one place the ollama command is worth having: no tools are wanted here
                else -> buildList {
                    add("run")
                    add(model.ifBlank { throw IllegalStateException("ollama needs a model name") })
                    add(prompt)
                }
            }

            val process = ProcessBuilder(listOf(exe) + args).redirectErrorStream(false).start()
            // nothing is ever written to it, and a command that also reads a prompt from stdin
            // waits for that pipe to end before it answers
            runCatching { process.outputStream.close() }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("$command did not answer within ${timeoutSec}s")
            }
            val answer = out.trim()
            if (answer.isEmpty()) {
                throw IllegalStateException(err.trim().ifEmpty { "$command said nothing" })
            }
            return answer
        }

        private fun command(provider: String): String? = when (provider) {
            "claude" -> "claude"
            "openai" -> "codex"
            "gemini" -> "gemini"
            "ollama" -> "ollama"
            else -> null
        }

        /**
         * PATH first, then where the installers put things.
         *
         * This runs in a worker of an application launched from the desktop, which inherits a PATH
         * with almost nothing on it — so the fallbacks are not an edge case, they are the usual way
         * one of these is found.
         */
        private fun find(command: String): String? {
            val onPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
                .map { File(it, command) }
                .firstOrNull { it.isFile && it.canExecute() }
            if (onPath != null) return onPath.absolutePath
            val home = System.getProperty("user.home")
            return listOf(
                "$home/.local/bin/$command",
                "$home/.claude/local/$command",
                "$home/.npm-global/bin/$command",
                "/opt/homebrew/bin/$command",
                "/usr/local/bin/$command",
            ).map(::File).firstOrNull { it.isFile && it.canExecute() }?.absolutePath
        }
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
