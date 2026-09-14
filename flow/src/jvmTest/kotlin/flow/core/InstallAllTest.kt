package flow.core

import flow.model.RegistryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Installing a whole list finishes, counts, and says what did not arrive.
 *
 * The failing half is the half worth pinning: twenty downloads is where a run gets stuck halfway
 * with a spinner that never stops, or buries the user under twenty identical dialogs. One of them
 * failing has to leave the counter at rest and name what failed, once.
 *
 * Nothing here reaches the network: the entries point at a file:// url the installer refuses
 * outright, which is the same shape as a download that never arrives.
 */
class InstallAllTest {

    private fun workspace() = Workspace(CoroutineScope(Dispatchers.Unconfined))

    /** Each entry is fetched off the main thread, so the run ends a moment after it is asked for. */
    private fun settle(ws: Workspace) {
        val deadline = System.currentTimeMillis() + 20_000
        while (ws.bulkInstalling && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(ws.bulkInstalling, "the run never finished")
    }

    private fun unreachable(id: String, name: String) =
        RegistryEntry(id = id, name = name, version = "1.0.0", file = "file:///nowhere/$id.jar")

    @Test
    fun `a list that cannot be fetched leaves nothing running and names every failure`() {
        val ws = workspace()
        ws.installAll(listOf(unreachable("flow.one", "One"), unreachable("flow.two", "Two")))
        settle(ws)

        assertEquals(0, ws.bulkDone, "the counter was left where it stopped")
        val error = ws.saveError
        assertTrue(error != null, "nothing was said about two modules that did not install")
        assertTrue(error!!.contains("One") && error.contains("Two"), "the failures are not named: $error")
        assertTrue(error.contains("2"), "the count is not there: $error")
    }

    /** And an empty list is not a run: no counter, no dialog, nothing to stop. */
    @Test
    fun `nothing to install is not an install`() {
        val ws = workspace()
        ws.installAll(emptyList())
        assertFalse(ws.bulkInstalling)
        assertEquals(null, ws.saveError)
    }

    /** A second press while one is running is ignored rather than starting the list twice. */
    @Test
    fun `one run at a time`() {
        val ws = workspace()
        ws.installAll(listOf(unreachable("flow.one", "One")))
        ws.installAll(listOf(unreachable("flow.two", "Two")))
        settle(ws)
        val error = ws.saveError
        assertTrue(error != null && error.contains("One"), "the first run did not report: $error")
        assertTrue(error!!.contains("Two").not(), "the second list ran as well: $error")
    }
}
