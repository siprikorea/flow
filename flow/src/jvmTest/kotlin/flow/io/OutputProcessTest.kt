package flow.io

import flow.extension.host.Wire
import flow.model.DrawOp
import flow.platform.ExtensionProcess
import flow.platform.OutputWire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole view path, through a real worker process.
 *
 * The unit tests above call a view directly, which proves what it draws but not that the drawing
 * survives the trip: the recording is written by Java in another module, read back by Kotlin here,
 * and the two only agree by convention. This drives the built jar in its own JVM, exactly as the
 * app does, so a change to either half that the other does not follow fails here.
 *
 * Nothing under the user's home is touched — the worker is pointed straight at the built jar.
 */
class OutputProcessTest {

    private val jar: File = File("../flow-extensions/output-extension/build/libs/output-extension.jar")
        .let { if (it.isFile) it else File("flow-extensions/output-extension/build/libs/output-extension.jar") }

    private val worker by lazy { ExtensionProcess(jar.parentFile, listOf(jar)) }

    @AfterTest
    fun stop() {
        worker.kill()
    }

    private fun draw(
        id: String,
        data: ByteArray,
        options: Map<String, String> = emptyMap(),
        width: Float = 800f,
    ): flow.model.Drawing {
        val reply = worker.request(Wire.OUTPUT_DRAW) { o ->
            Wire.writeString(o, id)
            Wire.writeBytes(o, data)
            Wire.writeStringMap(o, options)
            o.writeFloat(width)
            o.writeFloat(0.6f)
        }
        assertTrue(reply.ok, "the worker refused: ${reply.payload.decodeToString()}")
        return OutputWire.readDrawing(DataInputStream(ByteArrayInputStream(reply.payload)))
    }

    @Test
    fun `the jar the test drives was actually built`() {
        assertTrue(jar.isFile, "run :flow-extensions:output-extension:jar first — ${jar.absolutePath}")
    }

    @Test
    fun `the worker reports the views the jar provides`() {
        val reply = worker.request(Wire.OUTPUT_DESCRIBE) {}
        assertTrue(reply.ok, reply.payload.decodeToString())
        val input = DataInputStream(ByteArrayInputStream(reply.payload))
        val ids = (0 until input.readInt()).map {
            val id = Wire.readString(input)
            Wire.readString(input) // name
            Wire.readString(input) // version
            input.readBoolean() // wantsHover
            repeat(input.readInt()) { // options
                Wire.readString(input); Wire.readString(input); Wire.readString(input)
                Wire.readStringList(input)
            }
            id
        }
        assertEquals(
            listOf("flow.output.string", "flow.output.hex", "flow.output.asn1", "flow.output.image").sorted(),
            ids.sorted(),
        )
    }

    @Test
    fun `text drawn in the worker arrives with its position and its string intact`() {
        val drawing = draw("flow.output.string", "alpha\nbeta".encodeToByteArray())
        val texts = drawing.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(listOf("alpha", "beta"), texts.map { it.text })
        assertTrue(texts.all { it.mono }, "the monospace flag did not survive the pipe")
        // the second line sits below the first, which is the whole of what a layout is
        assertTrue(texts[1].y > texts[0].y)
        assertTrue(drawing.contentHeight > texts[1].y, "the height did not cover the drawing")
    }

    @Test
    fun `a hex dump of real size comes back whole`() {
        val drawing = draw("flow.output.hex", ByteArray(4096) { it.toByte() }, mapOf("bytesPerRow" to "16"))
        val texts = drawing.ops.filterIsInstance<DrawOp.Text>()
        // three columns for each of 256 rows
        assertEquals(256 * 3, texts.size)
        assertEquals("00000000", texts.first().text)
        assertTrue(texts.any { it.text == "00000FF0" }, "the last row's offset is missing")
    }

    @Test
    fun `image bytes cross the pipe unchanged`() {
        val png = flow.qr.QrExtension().process(
            mapOf("in" to "flow".encodeToByteArray()),
            mapOf("correction" to "L", "moduleSize" to "4", "quietZone" to "4"),
        )["out"]!!
        val drawing = draw("flow.output.image", png)
        val image = drawing.ops.filterIsInstance<DrawOp.Image>().single()
        assertTrue(image.png.contentEquals(png), "${image.png.size} bytes came back, ${png.size} went out")
    }

    @Test
    fun `unicode survives the pipe`() {
        val text = "안녕하세요 — Flow"
        val drawing = draw("flow.output.string", text.encodeToByteArray())
        assertEquals(text, drawing.ops.filterIsInstance<DrawOp.Text>().single().text)
    }

    @Test
    fun `a view that throws fails the call rather than the worker`() {
        val reply = worker.request(Wire.OUTPUT_DRAW) { o ->
            Wire.writeString(o, "flow.output.image")
            Wire.writeBytes(o, "not an image".encodeToByteArray())
            Wire.writeStringMap(o, emptyMap())
            o.writeFloat(800f)
            o.writeFloat(0.6f)
        }
        assertTrue(!reply.ok, "drawing rubbish as an image was reported as success")
        assertTrue(reply.payload.decodeToString().contains("not an image"), reply.payload.decodeToString())
        // and the worker is still there afterwards, which is the point of the isolation
        assertTrue(draw("flow.output.string", "still here".encodeToByteArray()).ops.isNotEmpty())
    }

    /** Sends one event the way the app does, and returns the options the view answered with. */
    private fun event(
        id: String,
        kind: String,
        region: String?,
        x: Float,
        y: Float,
        options: Map<String, String>,
    ): Map<String, String> {
        val reply = worker.request(Wire.OUTPUT_EVENT) { o ->
            Wire.writeString(o, id)
            Wire.writeString(o, kind)
            Wire.writeString(o, region ?: "")
            o.writeFloat(x)
            o.writeFloat(y)
            Wire.writeStringMap(o, options)
        }
        assertTrue(reply.ok, "the worker refused: ${reply.payload.decodeToString()}")
        return Wire.readStringMap(DataInputStream(ByteArrayInputStream(reply.payload)))
    }

    @Test
    fun `a view names the parts of its drawing that can be clicked`() {
        val drawing = draw("flow.output.asn1", derSequence())
        assertTrue(drawing.regions.isNotEmpty(), "no region crossed the pipe")
        // regions are not drawing calls and must not be replayed as any
        assertTrue(drawing.ops.none { it is DrawOp.Rect }, "a region was mistaken for something to draw")
    }

    @Test
    fun `clicking through the pipe changes what the view draws next`() {
        val der = derSequence()
        val open = draw("flow.output.asn1", der, mapOf("showOffsets" to "false"))
        val region = open.regions.first()

        val next = event(
            "flow.output.asn1", "click", region.id, region.x, region.y,
            mapOf("showOffsets" to "false"),
        )
        assertTrue(next.containsKey("asn1.collapsed"), "the view kept nothing about the click: $next")

        val shut = draw("flow.output.asn1", der, next)
        assertTrue(
            shut.ops.size < open.ops.size,
            "closing the branch drew ${shut.ops.size} calls against ${open.ops.size}",
        )
    }

    @Test
    fun `an event the view makes nothing of comes back unchanged`() {
        val options = mapOf("encoding" to "UTF-8")
        assertEquals(options, event("flow.output.string", "click", "whatever", 1f, 1f, options))
    }

    @Test
    fun `an event for a view the jar does not have is an error, not a crash`() {
        val reply = worker.request(Wire.OUTPUT_EVENT) { o ->
            Wire.writeString(o, "com.example.nope")
            Wire.writeString(o, "click")
            Wire.writeString(o, "x")
            o.writeFloat(0f)
            o.writeFloat(0f)
            Wire.writeStringMap(o, emptyMap())
        }
        assertTrue(!reply.ok)
        assertTrue(reply.payload.decodeToString().contains("not in this extension"), reply.payload.decodeToString())
    }

    /** SEQUENCE { INTEGER 42 } — the smallest thing with a branch to click. */
    private fun derSequence() = byteArrayOf(0x30, 0x03, 0x02, 0x01, 42)

    @Test
    fun `asking for a view the jar does not have is an error, not a crash`() {
        val reply = worker.request(Wire.OUTPUT_DRAW) { o ->
            Wire.writeString(o, "com.example.nope")
            Wire.writeBytes(o, ByteArray(0))
            Wire.writeStringMap(o, emptyMap())
            o.writeFloat(800f)
            o.writeFloat(0.6f)
        }
        assertTrue(!reply.ok)
        assertTrue(reply.payload.decodeToString().contains("not in this extension"), reply.payload.decodeToString())
    }
}
