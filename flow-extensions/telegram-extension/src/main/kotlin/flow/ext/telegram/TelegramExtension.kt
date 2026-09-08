package flow.ext.telegram

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
 * Puts what a flow produced into Telegram.
 *
 * The same job the Slack node does, for the place people actually watch on a phone: the input is
 * sent to a chat, and the message's id comes out, so a second one can reply to the first.
 *
 * A bot token is the only credential, and one bot can talk to many chats — so the token is a
 * setting, entered once, and the chat is a node option. The token is kept where keys are kept, not
 * in the settings file and not in the flow, because a flow is a document people share and a bot
 * token is enough to post as that bot forever.
 */
class TelegramExtension : ProcessorExtension {
    override val id = "flow.telegram"
    override val displayName = "Telegram"
    override val version = "1.0.0"
    override val inputs = listOf("in")
    override val outputs = listOf("out")

    override val options = listOf(
        // a numeric id, or @channelname for a public one. Blank takes the extension's own.
        ExtensionOption("chatId", OptionType.TEXT, ""),
        // Telegram renders the text as the chosen kind of markup, and refuses the whole message
        // when it does not parse — so plain text is the default, and formatting is a choice
        ExtensionOption("parseMode", OptionType.SELECT, "", listOf("", "MarkdownV2", "HTML")),
        // the id of a message to reply to — the output of another Telegram node
        ExtensionOption("replyTo", OptionType.TEXT, ""),
        // arrives without a sound, for something that is worth recording and not worth waking up for
        ExtensionOption("silent", OptionType.SELECT, "false", listOf("false", "true")),
        ExtensionOption("timeoutSec", OptionType.NUMBER, "30"),
    )

    override val settings = listOf(
        ExtensionOption("botToken", OptionType.TEXT, ""),
        ExtensionOption("chatId", OptionType.TEXT, ""),
        ExtensionOption("apiUrl", OptionType.TEXT, DEFAULT_API),
    )

    override val secretSettings = listOf("botToken")

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val text = inputs["in"]?.decodeToString().orEmpty()
        require(text.isNotEmpty()) { "nothing to send — the 'in' port is empty" }

        val token = options["botToken"].orEmpty().trim()
        require(token.isNotEmpty()) {
            "no bot token — get one from @BotFather and put it in Settings ▸ Extensions ▸ Telegram"
        }
        val chat = options["chatId"].orEmpty().trim()
        require(chat.isNotEmpty()) {
            "no chat — set one on this node, or in Settings ▸ Extensions ▸ Telegram"
        }
        val timeout = options["timeoutSec"]?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 30L

        val body = buildJsonObject {
            put("chat_id", chat)
            put("text", text)
            options["parseMode"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { put("parse_mode", it) }
            options["replyTo"].orEmpty().trim().toLongOrNull()?.let { put("reply_to_message_id", it) }
            if (options["silent"] == "true") put("disable_notification", true)
        }

        // the token is part of the path, which is why it must never end up anywhere a URL is logged
        val url = options["apiUrl"].orEmpty().ifBlank { DEFAULT_API }.trimEnd('/')
        val reply = post("$url/bot$token/sendMessage", body, timeout)
        if (reply.bool("ok") != true) {
            // description is Telegram's own sentence about it — "chat not found", "bot was blocked"
            throw IllegalStateException(
                "Telegram refused it: ${reply.string("description") ?: "no reason given"}",
            )
        }
        val id = (reply["result"] as? JsonObject)?.get("message_id")?.let { (it as? JsonPrimitive)?.content }
        return mapOf("out" to id.orEmpty().encodeToByteArray())
    }

    private companion object {
        const val DEFAULT_API = "https://api.telegram.org"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val http: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        }

        /**
         * Telegram answers a refusal with a normal body and an HTTP error, and the body is the
         * useful half — so it is parsed either way and the caller decides what `ok: false` means.
         */
        fun post(url: String, body: JsonObject, timeoutSec: Long): JsonObject {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), body)))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            return runCatching { json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException(
                    "Telegram's answer was not JSON (HTTP ${response.statusCode()}): ${response.body().take(300)}",
                )
        }

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    }
}
