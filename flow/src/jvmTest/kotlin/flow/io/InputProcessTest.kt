package flow.io

import flow.extension.host.Wire
import flow.platform.ExtensionProcess
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The input path, through a real worker process.
 *
 * The unit tests call an input directly, which proves the conversion but not that it survives the
 * trip: the request is framed by Kotlin here, read by Java in another module, and the two only
 * agree by convention. This drives the built jar in its own JVM, exactly as the editor does.
 *
 * Nothing under the user's home is touched — the worker is pointed straight at the built jar.
 */
class InputProcessTest {

    // one jar per extension, which is what installing one of them gets you
    private fun jarFor(module: String): File =
        File("../flow-extensions/$module-extension/build/libs/$module-extension.jar")
            .let { if (it.isFile) it else File("flow-extensions/$module-extension/build/libs/$module-extension.jar") }

    private val workers = mutableMapOf<String, ExtensionProcess>()

    private fun worker(module: String): ExtensionProcess = workers.getOrPut(module) {
        val jar = jarFor(module)
        assertTrue(jar.isFile, "run :flow-extensions:$module-extension:jar first — ${jar.absolutePath}")
        ExtensionProcess(jar.parentFile, listOf(jar))
    }

    private fun moduleOf(id: String) = if (id == "flow.input.hex") "hexinput" else "stringinput"

    @AfterTest
    fun stop() {
        workers.values.forEach { it.kill() }
    }

    private fun parse(id: String, text: String, options: Map<String, String> = emptyMap()): Pair<ByteArray, String?> {
        val reply = worker(moduleOf(id)).request(Wire.INPUT_PARSE) { o ->
            Wire.writeString(o, id)
            Wire.writeString(o, text)
            Wire.writeStringMap(o, options)
        }
        assertTrue(reply.ok, "the worker refused: ${reply.payload.decodeToString()}")
        val stream = DataInputStream(ByteArrayInputStream(reply.payload))
        val bytes = Wire.readBytes(stream) ?: ByteArray(0)
        return bytes to Wire.readString(stream).takeIf { it.isNotEmpty() }
    }

    private fun format(id: String, data: ByteArray, options: Map<String, String> = emptyMap()): String {
        val reply = worker(moduleOf(id)).request(Wire.INPUT_FORMAT) { o ->
            Wire.writeString(o, id)
            Wire.writeBytes(o, data)
            Wire.writeStringMap(o, options)
        }
        assertTrue(reply.ok, "the worker refused: ${reply.payload.decodeToString()}")
        return Wire.readString(DataInputStream(ByteArrayInputStream(reply.payload)))
    }

    @Test
    fun `each jar provides exactly the one input it is named for, and says how wide it writes`() {
        // a jar with two extensions in it cannot be installed by halves: the store keeps a folder
        // per id and would take both. One each is what makes a row in the list mean what it says.
        listOf(
            Triple("hexinput", "flow.input.hex", 3),
            Triple("stringinput", "flow.input.string", 0),
        ).forEach { (module, expectedId, expectedWidth) ->
            val reply = worker(module).request(Wire.INPUT_DESCRIBE) {}
            assertTrue(reply.ok, reply.payload.decodeToString())
            val input = DataInputStream(ByteArrayInputStream(reply.payload))
            val found = (0 until input.readInt()).map {
                val id = Wire.readString(input)
                Wire.readString(input) // name
                Wire.readString(input) // version
                val charsPerByte = input.readInt()
                repeat(input.readInt()) { // options
                    Wire.readString(input); Wire.readString(input); Wire.readString(input)
                    Wire.readStringList(input)
                }
                id to charsPerByte
            }
            assertEquals(listOf(expectedId to expectedWidth), found, "$module-extension.jar")
        }
    }

    @Test
    fun `hex survives the round trip through the pipe for any byte at all`() {
        val data = ByteArray(256) { it.toByte() }
        val (back, _) = parse("flow.input.hex", format("flow.input.hex", data))
        assertContentEquals(data, back)
    }

    @Test
    fun `text survives the round trip for what text can hold`() {
        // and only for that: bytes that are not text come back as the replacement character, which
        // is why the editor never writes a formatted value back unless the user typed into it
        val data = "hello — 안녕".encodeToByteArray()
        val (back, _) = parse("flow.input.string", format("flow.input.string", data))
        assertContentEquals(data, back)
    }

    @Test
    fun `unicode survives the pipe in both directions`() {
        val text = "안녕하세요 — Flow"
        val (bytes, _) = parse("flow.input.string", text)
        assertContentEquals(text.encodeToByteArray(), bytes)
        assertEquals(text, format("flow.input.string", bytes))
    }

    @Test
    fun `a problem comes back with the bytes rather than in place of them`() {
        val (bytes, problem) = parse("flow.input.hex", "41 4Q")
        // the value is still whatever could be read; the note is beside it
        assertContentEquals(byteArrayOf(0x41, 0x04), bytes)
        assertTrue(problem != null && problem.contains("Q"), "problem was $problem")
    }

    @Test
    fun `no problem is an empty note, not a missing reply`() {
        val (_, problem) = parse("flow.input.hex", "41 42")
        assertEquals(null, problem)
    }

    @Test
    fun `options cross the pipe and change the answer`() {
        assertEquals("0a0b", format("flow.input.hex", byteArrayOf(0x0A, 0x0B), mapOf("grouping" to "none", "case" to "lower")))
        assertEquals("0A 0B", format("flow.input.hex", byteArrayOf(0x0A, 0x0B), mapOf("grouping" to "bytes", "case" to "upper")))
    }

    @Test
    fun `asking for an input the jar does not have is an error, not a crash`() {
        val reply = worker("hexinput").request(Wire.INPUT_FORMAT) { o ->
            Wire.writeString(o, "com.example.nope")
            Wire.writeBytes(o, ByteArray(0))
            Wire.writeStringMap(o, emptyMap())
        }
        assertTrue(!reply.ok)
        assertTrue(reply.payload.decodeToString().contains("not in this extension"), reply.payload.decodeToString())
        // and the worker is still there afterwards, which is the point of the isolation
        assertEquals("41", format("flow.input.hex", byteArrayOf(0x41)))
    }
}
