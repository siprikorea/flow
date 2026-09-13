package flow.ui

import flow.core.EditorState
import flow.core.FocusRegion
import flow.core.Workspace
import flow.model.FlowFile
import flow.ui.shell.spaceRunsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Space runs the flow, and only where running a flow is what Space means.
 *
 * It used to mean it everywhere: a question typed into the AI panel with a space in it started the
 * flow. A shortcut with no modifier belongs to one region of the window, and the keyboard has to be
 * asked where it is before it is obeyed.
 *
 * The rule is what is checked here, not the key plumbing around it: a Compose KeyEvent cannot be
 * built outside Compose, so [spaceRunsFlow] is where the decision lives and this is it.
 */
class SpaceShortcutTest {

    private fun workspace(): Workspace {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val ws = Workspace(scope)
        ws.docs.add(EditorState(scope, ws, "a.flow").also { it.load(FlowFile()) })
        ws.activeIndex = 0
        return ws
    }

    @Test
    fun `the canvas answers space`() {
        val ws = workspace()
        ws.focus = FocusRegion.CANVAS
        assertTrue(spaceRunsFlow(ws), "the canvas did not take its own shortcut")
    }

    @Test
    fun `a text field keeps its own space`() {
        val ws = workspace()
        ws.focus = FocusRegion.TEXT
        assertFalse(spaceRunsFlow(ws), "typing a space would start the flow")
    }

    /** The tree is not the canvas either: it has its own shortcuts and Space is not one of them. */
    @Test
    fun `the project tree does not run the flow`() {
        val ws = workspace()
        ws.focus = FocusRegion.PROJECT
        assertFalse(spaceRunsFlow(ws))
    }

    @Test
    fun `a dialog and the settings screen keep the keyboard`() {
        val ws = workspace()
        ws.focus = FocusRegion.CANVAS
        ws.renameTarget = "a.flow"
        assertFalse(spaceRunsFlow(ws), "a rename dialog let the flow start")
        ws.renameTarget = null
        ws.showSettings = true
        assertFalse(spaceRunsFlow(ws), "the settings screen let the flow start")
    }
}
