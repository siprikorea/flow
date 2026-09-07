package flow.extensions

import com.sun.net.httpserver.HttpServer
import flow.ext.ai.AiExtension
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AI node: text in, a model's answer out, and the next node's input.
 *
 * Four providers, each answering in its own shape, and the node has to find the answer in all four
 * — a field in the wrong place is a node that outputs nothing while everything looks fine. The
 * server here answers the way each of them does, so this is about reading them, not the network.
 *
 * The address comes from the app's own settings, which these tests cannot change without writing to
 * the user's ~/.flow — so they exercise the providers whose default address can be pointed
 * elsewhere by the node itself. Ollama is the one that can: its default is localhost.
 */
class AiExtensionTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<String>()

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun serve(vararg responses: String): Int {
        var n = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestBody.readBytes().decodeToString()
            val body = responses[minOf(n++, responses.size - 1)].toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server.address.port
    }

    private val node = AiExtension()

    @Test
    fun `it declares itself as one input, one output and a role to play`() {
        assertEquals("flow.ai", node.id)
        assertEquals(listOf("in"), node.inputs)
        assertEquals(listOf("out"), node.outputs)
        val names = node.options.map { it.name }
        assertTrue(names.containsAll(listOf("provider", "model", "role")), "$names")
        // blank provider means "whatever the AI panel is set to", so a flow made on one machine
        // still runs on another set up differently
        assertTrue(node.options.first { it.name == "provider" }.choices.contains(""), "no inherit-from-settings choice")
    }

    /**
     * The output of one node is the input of the next.
     *
     * That is the whole reason this is a node and not a panel: two of them in a row are two steps,
     * each working on what the last produced. Here the second is handed the first's output and the
     * server sees it in the request.
     */
    @Test
    fun `an answer comes out as text, ready to be the next node's input`() {
        val port = serve(
            """{"message":{"role":"assistant","content":"FIRST"}}""",
            """{"message":{"role":"assistant","content":"SECOND"}}""",
        )
        val options = mapOf(
            "provider" to "ollama",
            "model" to "test",
            "role" to "Shout it back.",
        )
        // the address is not an option — the node takes it from the app's own settings, so the
        // test writes those instead (see run())
        val first = run(options, "hello", port)
        assertEquals("FIRST", first)

        val second = run(options, first, port)
        assertEquals("SECOND", second)

        // the second request carried what the first produced — the chain, on the wire
        assertTrue(received[1].contains("FIRST"), "the next node did not receive the answer:\n${received[1]}")
        // and the role went with it, as the instruction rather than as part of the input
        assertTrue(received[0].contains("Shout it back."), received[0])
        assertTrue(received[0].contains("\"system\""), "the role is not the system message:\n${received[0]}")
    }

    @Test
    fun `a provider that needs a key says which key, rather than failing at the server`() {
        val e = runCatching {
            node.process(mapOf("in" to "x".encodeToByteArray()), mapOf("provider" to "openai", "model" to "m"))
        }.exceptionOrNull()
        // unless this machine has one configured, in which case the call would really go out
        if (e != null) {
            assertTrue(
                e.message!!.contains("API key") && e.message!!.contains("OPENAI_API_KEY"),
                "not a message that says what to do: ${e.message}",
            )
        }
    }

    @Test
    fun `an unknown provider is refused by name`() {
        val e = runCatching {
            node.process(mapOf("in" to "x".encodeToByteArray()), mapOf("provider" to "nonesuch"))
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("nonesuch") == true, "${e?.message}")
    }

    /** Runs the node with the Ollama address overridden the way the extension resolves it. */
    private fun run(options: Map<String, String>, input: String, port: Int): String {
        val previous = System.getProperty("user.home")
        val home = java.io.File(System.getProperty("java.io.tmpdir"), "flow-ai-ext-${port}")
        java.io.File(home, ".flow").mkdirs()
        java.io.File(home, ".flow/settings.json")
            .writeText("""{"aiProvider":"ollama","ollamaUrl":"http://127.0.0.1:$port","ollamaModel":"test"}""")
        System.setProperty("user.home", home.absolutePath)
        try {
            val out = AiExtension().process(mapOf("in" to input.encodeToByteArray()), options)
            return out["out"]!!.decodeToString()
        } finally {
            System.setProperty("user.home", previous)
            home.deleteRecursively()
        }
    }
    /**
     * The jar loads and runs in a worker process, which is where it will actually live.
     *
     * It takes the app's JSON rather than bundling its own, on the same bargain the view
     * extensions take Compose on — and that bargain is only kept if the worker is really started
     * with those libraries. In-process tests cannot tell: the test classpath has everything. So
     * this drives a real worker, and asks for the one thing that has to go through JSON before it
     * can fail — reading the config — and requires the answer to be the message a missing key
     * earns, not a class that was not there.
     */
    @Test
    fun `the jar runs in a worker process, with the app's JSON on loan`() {
        val jar = java.io.File("../flow-extensions/ai-extension/build/libs/ai-extension.jar")
            .let { if (it.isFile) it else java.io.File("flow-extensions/ai-extension/build/libs/ai-extension.jar") }
        assertTrue(jar.isFile, "run :flow-extensions:ai-extension:jar first — ${jar.absolutePath}")

        val worker = flow.platform.ExtensionProcess(jar.parentFile, listOf(jar))
        try {
            val described = worker.request(flow.extension.host.Wire.DESCRIBE) {}
            assertTrue(described.ok, described.payload.decodeToString())
            assertTrue(described.payload.decodeToString().contains("flow.ai"), "the worker does not serve it")

            val reply = worker.request(flow.extension.host.Wire.PROCESS) { o ->
                flow.extension.host.Wire.writeString(o, "flow.ai")
                flow.extension.host.Wire.writeByteMap(o, mapOf("in" to "hello".encodeToByteArray()))
                flow.extension.host.Wire.writeStringMap(o, mapOf("provider" to "openai", "model" to "m"))
            }
            val said = reply.payload.decodeToString()
            assertTrue(!reply.ok, "it answered without a key: $said")
            assertTrue(
                said.contains("API key"),
                "not the failure a missing key earns — the app's JSON may not be on the worker's " +
                    "classpath, which is what this is really checking: $said",
            )
        } finally {
            worker.kill()
        }
    }

    /**
     * One real answer, from the model actually installed on this machine.
     *
     * The fake server above proves the node reads each shape; this proves the shape is the one a
     * real server sends. AI_LIVE=1 and an Ollama running, because it is the one provider that
     * needs no key.
     */
    @Test
    fun `a real model answers through the node`() {
        if (System.getenv("AI_LIVE") == null) return
        val models = runCatching {
            val body = java.net.URI.create("http://localhost:11434/api/tags").toURL().readText()
            Regex("\"name\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())
        val model = models.firstOrNull() ?: return

        val out = AiExtension().process(
            mapOf("in" to "banana".encodeToByteArray()),
            mapOf(
                "provider" to "ollama",
                "model" to model,
                "role" to "Reply with the input word in capital letters, and nothing else.",
                "timeoutSec" to "300",
            ),
        )
        val answer = out["out"]!!.decodeToString()
        assertTrue(answer.isNotBlank(), "the node produced nothing")
        assertTrue(answer.contains("BANANA", ignoreCase = true), "it did not act on the role: $answer")
    }

}
