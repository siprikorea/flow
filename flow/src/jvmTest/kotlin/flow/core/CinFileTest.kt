package flow.core

import flow.model.Node
import flow.model.Port
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cin node can read its bytes from a file. The run and the data editor have to agree on when it
 * does: they did not, and a flow whose file had been moved encrypted an empty input into a
 * convincing block while the editor went on showing the bytes still held on the port.
 */
class CinFileTest {

    private fun cin(params: Map<String, String>, data: ByteArray = "TEST".encodeToByteArray()) =
        Node("cin_1", "cin", "Input", 0f, 0f, 180f, 100f, emptyList(), listOf(Port("out", data)), params, "idle")

    @Test
    fun `a node with no file reads from its port`() {
        assertNull(cinFileSize(cin(emptyMap())))
    }

    @Test
    fun `a path that no longer resolves is not a file`() {
        val gone = File(System.getProperty("java.io.tmpdir"), "flow-test-does-not-exist-${System.nanoTime()}")
        assertTrue(!gone.exists())
        assertNull(
            cinFileSize(cin(mapOf("dataFile" to gone.absolutePath))),
            "a dangling path counted as a file, which made the run use nothing at all",
        )
    }

    @Test
    fun `a file that is there is used, at its own size`() {
        val f = File.createTempFile("flow-test", ".bin").apply { writeBytes(ByteArray(11)); deleteOnExit() }
        assertEquals(11L, cinFileSize(cin(mapOf("dataFile" to f.absolutePath))))
    }

    @Test
    fun `an empty file is still a file`() {
        // zero bytes is a legitimate input; only "cannot be read" means fall back to the port
        val f = File.createTempFile("flow-test-empty", ".bin").apply { writeBytes(ByteArray(0)); deleteOnExit() }
        assertEquals(0L, cinFileSize(cin(mapOf("dataFile" to f.absolutePath))))
    }
}
