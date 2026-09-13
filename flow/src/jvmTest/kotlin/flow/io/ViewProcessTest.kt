package flow.io

import flow.module.host.Wire
import flow.platform.ModuleProcess
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A view, through a real worker process.
 *
 * A view opens a window, which a test cannot check without putting one on the screen — so what is
 * checked here is everything up to that: the jar loads in a worker of its own, says what it
 * provides, and refuses what it does not have rather than crashing.
 *
 * Nothing under the user's home is touched — the worker is pointed straight at the built jar.
 */
class ViewProcessTest {

    private fun jarFor(module: String): File =
        File("../flow-modules/$module-module/build/libs/$module-module.jar")
            .let { if (it.isFile) it else File("flow-modules/$module-module/build/libs/$module-module.jar") }

    private val workers = mutableMapOf<String, ModuleProcess>()

    private fun worker(module: String): ModuleProcess = workers.getOrPut(module) {
        val jar = jarFor(module)
        assertTrue(jar.isFile, "run :flow-modules:$module-module:jar first — ${jar.absolutePath}")
        ModuleProcess(jar.parentFile, listOf(jar))
    }

    @AfterTest
    fun stop() {
        workers.values.forEach { it.kill() }
    }

    private fun describe(module: String): List<List<String>> {
        val reply = worker(module).request(Wire.VIEW_DESCRIBE) {}
        assertTrue(reply.ok, reply.payload.decodeToString())
        val input = DataInputStream(ByteArrayInputStream(reply.payload))
        return (0 until input.readInt()).map {
            listOf(
                Wire.readString(input), // id
                Wire.readString(input), // name
                Wire.readString(input), // version
                Wire.readString(input), // description
            )
        }
    }

    @Test
    fun `each jar provides exactly the one view it is named for`() {
        // a jar with two modules in it cannot be installed by halves: the store keeps a folder
        // per id and would take both. One each is what makes a row in the list mean what it says.
        assertEquals(listOf("flow.view.asn1"), describe("asn1view").map { it[0] })
        assertEquals(listOf("flow.view.image"), describe("imageview").map { it[0] })
    }

    @Test
    fun `a view says what it is called and what it is for`() {
        val (_, name, version, description) = describe("asn1view").single().let {
            listOf(it[0], it[1], it[2], it[3])
        }
        assertEquals("ASN.1", name)
        assertTrue(version.isNotBlank())
        assertTrue(description.isNotBlank(), "a view with no description is a row with nothing on it")
    }

    @Test
    fun `the compose a view needs comes from the app, not from the jar`() {
        // A view is written in Compose but must not carry it: Compose ships a native library per
        // platform, and a jar with them all would be a hundred megabytes for every view. The host
        // lends its own, which is why a jar this small can open a window at all.
        val jar = jarFor("asn1view")
        assertTrue(jar.length() < 1_000_000, "${jar.name} is ${jar.length()} bytes — is Compose bundled?")
    }

    @Test
    fun `asking for a view the jar does not have is an error, not a crash`() {
        val reply = worker("asn1view").request(Wire.VIEW_OPEN) { o ->
            Wire.writeString(o, "com.example.nope")
            Wire.writeBytes(o, ByteArray(0))
            Wire.writeStringMap(o, emptyMap())
        }
        assertTrue(!reply.ok)
        assertTrue(reply.payload.decodeToString().contains("not in this install"), reply.payload.decodeToString())
        // and the worker is still there afterwards, which is the point of the isolation
        assertEquals(listOf("flow.view.asn1"), describe("asn1view").map { it[0] })
    }

    /**
     * Actually opens a window.
     *
     * Off unless VIEW_WINDOW is set, because it puts a window on the screen of whoever runs it. It
     * is here because everything short of this passed while no window appeared: the classes that
     * open one were missing from the worker's classpath, and the only way to know is to try.
     */
    @Test
    fun `a view really opens a window`() {
        if (System.getenv("VIEW_WINDOW") == null) return
        val der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 42)
        val reply = worker("asn1view").request(Wire.VIEW_OPEN) { o ->
            Wire.writeString(o, "flow.view.asn1")
            Wire.writeBytes(o, der)
            Wire.writeStringMap(o, mapOf("theme" to "dark"))
        }
        // the worker waits to see whether the window fails before answering, so OK means it is up
        assertTrue(reply.ok, "no window: ${reply.payload.decodeToString()}")
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}
