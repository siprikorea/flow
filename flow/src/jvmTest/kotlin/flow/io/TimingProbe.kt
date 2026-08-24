package flow.io

import flow.extension.host.Wire
import flow.platform.ExtensionProcess
import flow.platform.OutputWire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Times a round trip to an output's process, so "it feels slow" can be answered with a number.
 * Skipped unless TIMING is set.
 */
class TimingProbe {

    private val jar = File("../flow-extensions/asn1output-extension/build/libs/asn1output-extension.jar")
        .let { if (it.isFile) it else File("flow-extensions/asn1output-extension/build/libs/asn1output-extension.jar") }
    private val worker by lazy { ExtensionProcess(jar.parentFile, listOf(jar)) }

    @AfterTest
    fun stop() = worker.kill()

    private fun draw(data: ByteArray, options: Map<String, String>): Pair<Int, Long> {
        val start = System.nanoTime()
        val reply = worker.request(Wire.OUTPUT_DRAW) { o ->
            Wire.writeString(o, "flow.output.asn1")
            Wire.writeBytes(o, data)
            Wire.writeStringMap(o, options)
            o.writeFloat(900f)
            o.writeFloat(0.6f)
        }
        val drawing = OutputWire.readDrawing(DataInputStream(ByteArrayInputStream(reply.payload)))
        return drawing.ops.size to (System.nanoTime() - start) / 1_000
    }

    private fun event(region: String, options: Map<String, String>): Pair<Map<String, String>, Long> {
        val start = System.nanoTime()
        val reply = worker.request(Wire.OUTPUT_EVENT) { o ->
            Wire.writeString(o, "flow.output.asn1")
            Wire.writeString(o, "click")
            Wire.writeString(o, region)
            o.writeFloat(0f)
            o.writeFloat(0f)
            Wire.writeStringMap(o, options)
        }
        val next = Wire.readStringMap(DataInputStream(ByteArrayInputStream(reply.payload)))
        return next to (System.nanoTime() - start) / 1_000
    }

    @Test
    fun `time a click and a redraw`() {
        if (System.getenv("TIMING") == null) return
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        println("data: ${key.size} bytes")

        // the first call also starts the JVM, which is the one cost that is not per click
        val (ops0, cold) = draw(key, emptyMap())
        println("cold draw: ${cold}us ($ops0 ops)")

        repeat(5) {
            val (ops, us) = draw(key, emptyMap())
            println("warm draw: ${us}us ($ops ops)")
        }

        var options = emptyMap<String, String>()
        repeat(5) {
            val (next, us) = event("n0", options)
            options = next
            println("click: ${us}us -> $next")
        }
    }
}
