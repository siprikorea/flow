package flow.ext.slack

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Puts what a flow produced into Slack.
 *
 * The end of a flow is usually a person: a digest to check, a signature that failed, a report that
 * finished. This is that last step — the input goes to a channel, and what comes out is the
 * message's id, so a second one of these can reply underneath the first.
 *
 * Two ways in, because Slack has two and they suit different situations. A bot token posts as an
 * app that can be told which channel per node, and can thread. An incoming webhook is a single URL
 * with a channel already chosen and no scopes to grant, which is what someone reaches for when they
 * want one flow to say one thing in one place. Whichever is configured is the one used; a token
 * wins if both are.
 *
 * Both are secrets, so they live in Settings ▸ Extensions ▸ Slack and are kept where keys are kept,
 * not in the settings file and not in the flow — a flow is a document people share.
 */
class SlackExtension : ProcessorExtension {
    override val id = "flow.slack"
    override val displayName = "Slack"
    override val version = "1.0.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override val options = listOf(
        // blank takes the one set for the extension, so a flow that names no channel still works
        // on a machine where one is configured
        ExtensionOption("channel", OptionType.TEXT, ""),
        // the id of a message to reply under — the output of another Slack node
        ExtensionOption("threadTs", OptionType.TEXT, ""),
        ExtensionOption("timeoutSec", OptionType.NUMBER, "30"),
    )

    override val settings = listOf(
        ExtensionOption("botToken", OptionType.TEXT, ""),
        ExtensionOption("webhookUrl", OptionType.TEXT, ""),
        ExtensionOption("channel", OptionType.TEXT, ""),
        ExtensionOption("apiUrl", OptionType.TEXT, DEFAULT_API),
    )

    /** Both credentials. Named rather than flagged on the option — see ProcessorExtension. */
    override val secretSettings = listOf("botToken", "webhookUrl")

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val text = inputs["in"]?.decodeToString().orEmpty()
        require(text.isNotEmpty()) { "nothing to post — the 'in' port is empty" }
        val timeout = options["timeoutSec"]?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 30L
        val token = options["botToken"].orEmpty().trim()
        val webhook = options["webhookUrl"].orEmpty().trim()

        return when {
            token.isNotEmpty() -> mapOf("out" to postAsApp(token, options, text, timeout).encodeToByteArray())
            webhook.isNotEmpty() -> mapOf("out" to postToWebhook(webhook, text, timeout).encodeToByteArray())
            else -> throw IllegalStateException(
                "Slack is not configured — put a bot token or an incoming webhook URL in " +
                    "Settings ▸ Extensions ▸ Slack",
            )
        }
    }

    /**
     * chat.postMessage, which needs a channel and gives back the message's id.
     *
     * Slack answers 200 with `{"ok":false,"error":"…"}` when it refuses, so the status code says
     * nothing — the body is what has to be read. `invalid_auth` and `channel_not_found` arrive that
     * way, and both are things the person configuring this can fix once they are told.
     */
    private fun postAsApp(token: String, options: Map<String, String>, text: String, timeoutSec: Long): String {
        val channel = options["channel"].orEmpty().trim()
        require(channel.isNotEmpty()) {
            "no channel — set one on this node, or in Settings ▸ Extensions ▸ Slack"
        }
        val body = buildJsonObject {
            put("channel", channel)
            put("text", text)
            options["threadTs"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { put("thread_ts", it) }
        }
        val url = options["apiUrl"].orEmpty().ifBlank { DEFAULT_API }.trimEnd('/')
        val reply = post("$url/chat.postMessage", body, timeoutSec) {
            header("Authorization", "Bearer $token")
        }
        if (reply.bool("ok") != true) {
            throw IllegalStateException("Slack refused it: ${reply.string("error") ?: "no reason given"}")
        }
        // the message id, so another node can reply under this one
        return reply.string("ts").orEmpty()
    }

    /**
     * An incoming webhook, which takes the text and answers "ok" as plain text.
     *
     * There is no message id to give back — the webhook does not return one — so what comes out is
     * what it said, which keeps the port meaning "the thing was posted" either way.
     */
    private fun postToWebhook(url: String, text: String, timeoutSec: Long): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("text", text) })))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val said = response.body().trim()
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Slack refused it: ${said.ifEmpty { "HTTP ${response.statusCode()}" }}")
        }
        return said
    }

    private companion object {
        const val DEFAULT_API = "https://slack.com/api"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val http: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        }

        fun post(
            url: String,
            body: JsonObject,
            timeoutSec: Long,
            headers: HttpRequest.Builder.() -> Unit,
        ): JsonObject {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), body)))
                .apply(headers)
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            return runCatching { json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException(
                    "Slack's answer was not JSON (HTTP ${response.statusCode()}): ${response.body().take(300)}",
                )
        }

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    }
}
