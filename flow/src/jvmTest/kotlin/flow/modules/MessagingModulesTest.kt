package flow.modules

import com.sun.net.httpserver.HttpServer
import flow.ext.slack.SlackModule
import flow.ext.telegram.TelegramModule
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two nodes that carry a result out to a person.
 *
 * What is worth testing here is not that an HTTP request can be made — it is the two shapes of
 * failure these services have, both of which look like success from any distance. Slack answers a
 * refusal with HTTP 200 and `{"ok":false,"error":"invalid_auth"}`, so a node that trusts the status
 * code reports a message as sent that nobody received. Telegram puts its reason in `description`.
 * Either one silently swallowed is the worst kind of bug for this node in particular: the whole
 * point of it is to be the thing that tells you.
 *
 * Against a local server, so the tests need no token and post nothing to anyone.
 */
class MessagingModulesTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<Pair<String, String>>()

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /** Serves [body] with [status] and records the path and body of what it was sent. */
    private fun serve(body: String, status: Int = 200): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestURI.path to exchange.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun slack(vararg options: Pair<String, String>) = SlackModule().process(
        mapOf("in" to "the report".encodeToByteArray()),
        mapOf("timeoutSec" to "10") + options,
    )["out"]!!.decodeToString()

    private fun telegram(vararg options: Pair<String, String>) = TelegramModule().process(
        mapOf("in" to "the report".encodeToByteArray()),
        mapOf("timeoutSec" to "10") + options,
    )["out"]!!.decodeToString()

    /* ───────── Slack ───────── */

    @Test
    fun `slack posts as an app and hands back the message id`() {
        val url = serve("""{"ok":true,"channel":"C1","ts":"1712345678.000100"}""")
        val id = slack(
            "botToken" to "xoxb-test", "apiUrl" to url, "channel" to "#builds",
        )
        // the id is what makes two of these a thread rather than two messages
        assertEquals("1712345678.000100", id)
        assertEquals("/chat.postMessage", received.single().first)
        assertTrue(received.single().second.contains("\"channel\":\"#builds\""), received.single().second)
        assertTrue(received.single().second.contains("the report"))
    }

    @Test
    fun `slack replies in a thread when given a message to reply under`() {
        val url = serve("""{"ok":true,"ts":"2"}""")
        slack("botToken" to "xoxb-test", "apiUrl" to url, "channel" to "#builds", "threadTs" to "1")
        assertTrue(received.single().second.contains("\"thread_ts\":\"1\""), received.single().second)
    }

    /**
     * Slack says no with a 200.
     *
     * A node that reads the status code and stops there reports a message as delivered that was
     * refused — and this node exists to be the thing that tells you something happened.
     */
    @Test
    fun `slack refusing with an ok false body is a failure, not a delivery`() {
        val url = serve("""{"ok":false,"error":"invalid_auth"}""")
        val failure = runCatching {
            slack("botToken" to "xoxb-bad", "apiUrl" to url, "channel" to "#builds")
        }.exceptionOrNull()
        assertTrue(failure != null, "a refused post was reported as sent")
        assertTrue(failure!!.message!!.contains("invalid_auth"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `slack posts to a webhook when that is what is configured`() {
        val url = serve("ok")
        val said = slack("webhookUrl" to "$url/services/T/B/x")
        assertEquals("ok", said)
        assertEquals("/services/T/B/x", received.single().first)
        assertTrue(received.single().second.contains("the report"))
    }

    @Test
    fun `slack with nothing configured says what to configure`() {
        val failure = runCatching { slack("channel" to "#builds") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("Settings") == true, "${failure?.message}")
    }

    @Test
    fun `slack with a token and no channel says so before sending anything`() {
        val url = serve("""{"ok":true,"ts":"1"}""")
        val failure = runCatching { slack("botToken" to "xoxb-test", "apiUrl" to url) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("channel") == true, "${failure?.message}")
        assertTrue(received.isEmpty(), "it posted without a channel")
    }

    /* ───────── Telegram ───────── */

    @Test
    fun `telegram sends to a chat and hands back the message id`() {
        val url = serve("""{"ok":true,"result":{"message_id":42,"text":"the report"}}""")
        val id = telegram("botToken" to "123:ABC", "apiUrl" to url, "chatId" to "-100200")
        assertEquals("42", id)
        // the token is part of the path, which is why it must never be logged as a URL
        assertEquals("/bot123:ABC/sendMessage", received.single().first)
        assertTrue(received.single().second.contains("\"chat_id\":\"-100200\""), received.single().second)
    }

    @Test
    fun `telegram carries the options that change how a message arrives`() {
        val url = serve("""{"ok":true,"result":{"message_id":7}}""")
        telegram(
            "botToken" to "123:ABC", "apiUrl" to url, "chatId" to "@channel",
            "parseMode" to "HTML", "replyTo" to "5", "silent" to "true",
        )
        val body = received.single().second
        assertTrue(body.contains("\"parse_mode\":\"HTML\""), body)
        assertTrue(body.contains("\"reply_to_message_id\":5"), body)
        assertTrue(body.contains("\"disable_notification\":true"), body)
    }

    @Test
    fun `telegram refusing says what it said, not just that it failed`() {
        val url = serve("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""", status = 400)
        val failure = runCatching {
            telegram("botToken" to "123:ABC", "apiUrl" to url, "chatId" to "nope")
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("chat not found") == true, "${failure?.message}")
    }

    @Test
    fun `telegram with no token says where to get one`() {
        val failure = runCatching { telegram("chatId" to "1") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("BotFather") == true, "${failure?.message}")
    }

    /* ───────── both ───────── */

    @Test
    fun `an empty input is refused rather than posted as an empty message`() {
        listOf(
            { SlackModule().process(mapOf("in" to ByteArray(0)), mapOf("webhookUrl" to "http://x")) },
            { TelegramModule().process(mapOf("in" to null), mapOf("botToken" to "t", "chatId" to "1")) },
        ).forEach { send ->
            val failure = runCatching { send() }.exceptionOrNull()
            assertTrue(failure != null, "an empty message was sent")
        }
    }

    @Test
    fun `the credentials are settings, and are the ones kept out of the settings file`() {
        assertEquals(listOf("botToken", "webhookUrl"), SlackModule().secretSettings)
        assertEquals(listOf("botToken"), TelegramModule().secretSettings)
        // and the connection details are settings, so they are entered once in Settings ▸ Modules
        assertTrue(SlackModule().settings.map { it.name }.containsAll(listOf("botToken", "webhookUrl", "channel", "apiUrl")))
        assertTrue(TelegramModule().settings.map { it.name }.containsAll(listOf("botToken", "chatId", "apiUrl")))
    }
}
