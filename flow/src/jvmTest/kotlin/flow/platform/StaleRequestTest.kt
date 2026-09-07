package flow.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A request to run a flow expires.
 *
 * open_flow, set_flow_input and start_flow all leave a file in ~/.flow for the running app to pick
 * up, and each of them means now. Nothing expired them: a request nobody claimed — written while
 * the app was closing, or by Claude Desktop against an app that quit a moment after — stayed on
 * disk, and the next launch ran it. From the outside that is a flow starting by itself long after
 * anyone asked for anything, which is exactly what was reported, twice.
 *
 * These tests write the sentinel files directly, because what is being checked is the reading side
 * — a file that is already old when the app finds it.
 */
class StaleRequestTest {

    private val dir = File(System.getProperty("user.home"), ".flow")
    private val runRequest = File(dir, "run-request.json")
    private val openRequest = File(dir, "open-request.txt")

    // the user's own ~/.flow is the one the app uses, so anything left here would be acted on
    @AfterTest
    fun clean() {
        runRequest.delete()
        openRequest.delete()
    }

    private fun writeRun(ageMs: Long) {
        dir.mkdirs()
        runRequest.writeText("""{"path":"a.flow","start":true,"inputs":{}}""")
        runRequest.setLastModified(System.currentTimeMillis() - ageMs)
    }

    @Test
    fun `a request made a moment ago is acted on`() {
        writeRun(ageMs = 200)
        val request = Platform.takePendingFlowRun()
        assertEquals("a.flow", request?.path)
        assertTrue(request!!.start)
        assertFalse(runRequest.exists(), "the request was left for a second reader to run again")
    }

    @Test
    fun `a request left over from an earlier session is dropped, not run`() {
        writeRun(ageMs = 60 * 60 * 1000)
        assertNull(Platform.takePendingFlowRun(), "a flow would have started by itself")
        assertFalse(runRequest.exists(), "the stale request is still there to fire next launch")
    }

    @Test
    fun `the same goes for a flow waiting to be opened`() {
        dir.mkdirs()
        openRequest.writeText("a.flow")
        openRequest.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000)
        assertNull(Platform.takePendingOpenFlow())
        assertFalse(openRequest.exists())
    }

    @Test
    fun `nothing pending is nothing to do`() {
        assertNull(Platform.takePendingFlowRun())
        assertNull(Platform.takePendingOpenFlow())
    }
}
