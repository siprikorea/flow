package flow.core

import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import flow.model.RUN_LIVE
import flow.model.RUN_MODES
import flow.model.RUN_ONCE
import flow.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Live mode: the flow runs as the input is edited, and the output is what the input produces now.
 *
 * The thing to keep true is what counts as a change. A run writes status back onto the nodes, and a
 * flow that is dragged across the canvas is the same flow — either of those counted as an edit and
 * live mode would run itself in a circle, or run for nothing, on a flow that can send a message.
 * So: values, options and wiring are the change; everything else is not.
 */
class LiveRunTest {

    private fun document(mode: String, value: String = "one"): EditorState {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val ws = Workspace(scope)
        ws.runMode = mode
        val doc = EditorState(scope, ws, "a.flow")
        doc.load(
            FlowFile(
                nodes = listOf(
                    Node("in1", "cin", "text", 0f, 0f, 180f, 100f, emptyList(), listOf(Port("out", value.encodeToByteArray()))),
                    Node("out1", "cout", "result", 300f, 0f, 180f, 100f, listOf(Port("in")), emptyList()),
                ),
                edges = listOf(Edge("e1", PortRef("in1", "out"), PortRef("out1", "in"))),
            ),
        )
        return doc
    }

    /** Waits for the output to say [expected], or gives up — a run happens off the UI thread. */
    private fun awaitOutput(doc: EditorState, expected: String, node: String = "out1"): Boolean = runBlocking {
        withTimeoutOrNull(5_000) {
            while (doc.runOutputs[node]?.decodeToString() != expected) delay(20)
            true
        } == true
    }

    /**
     * Asked of the settings rather than of a Workspace: a Workspace reads this machine's own
     * settings file, so asserting against one would be asserting about whoever ran the test.
     */
    @Test
    fun `one-time is what a flow does unless it is asked otherwise`() {
        assertEquals(RUN_ONCE, Settings().runMode)
    }

    /** The settings page offers them in this order, and the order is part of the answer. */
    @Test
    fun `live is offered first`() {
        assertEquals(listOf(RUN_LIVE, RUN_ONCE), RUN_MODES)
    }

    @Test
    fun `live mode runs the flow when the input changes, with no Start`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"), "opening a flow in live mode produced no output")

        doc.setCinInputs(mapOf("text" to "two"))

        assertTrue(awaitOutput(doc, "two"), "the input changed and the output did not follow it")
    }

    /** Wiring is an input too: the result of a flow depends on it exactly as much as the values. */
    @Test
    fun `and when the wiring changes`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))

        doc.deleteEdge("e1")

        assertTrue(awaitOutput(doc, ""), "the connection was cut and the old result stood")
    }

    @Test
    fun `one-time mode waits to be asked`() {
        val doc = document(RUN_ONCE)
        doc.setCinInputs(mapOf("text" to "two"))
        runBlocking { delay(600) }

        assertTrue(doc.runOutputs.isEmpty(), "one-time mode ran the flow on its own")

        doc.startRun()

        assertTrue(awaitOutput(doc, "two"), "Start produced nothing")
    }

    /**
     * Moving a node changes the file; it does not change the answer. Live mode has to tell those
     * apart, or every drag re-runs a flow that may send a message or write a file.
     */
    @Test
    fun `moving a node is not a reason to run`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))
        val ran = doc.runOutputs

        doc.moveNode("in1", 40f, 80f)
        doc.selectNode("in1")
        runBlocking { delay(600) }

        assertSame(ran, doc.runOutputs, "the flow was run again for a node that only moved")
    }

    /**
     * A run started by hand owns the pass while it animates. What it must not do is swallow an edit
     * made while it plays — live mode promised that the output says what the input says now.
     */
    @Test
    fun `a change made while a started run plays is not dropped`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))

        doc.startRun()
        doc.setCinInputs(mapOf("text" to "during"))

        assertTrue(awaitOutput(doc, "during"), "the edit was lost behind the animation")
    }

    /**
     * Live mode is a run that is already going.
     *
     * So the title bar reads the way it does while an animation plays: Start spent, Stop there to
     * press. `inProgress` is what both buttons are asked, and it is true the whole time live is on,
     * not only during the moment a pass animates.
     */
    @Test
    fun `live mode reads as a run in progress`() {
        val doc = document(RUN_LIVE)
        assertTrue(doc.liveRunning, "live mode was not running")
        assertTrue(doc.inProgress, "Start would have been offered while live mode is on")
    }

    /** And Stop stops it: that is what there is to press, so it has to do something. */
    @Test
    fun `stop puts live mode down, and nothing runs after it`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))

        doc.stopRun()

        assertFalse(doc.liveRunning, "live mode was still running after Stop")
        assertFalse(doc.inProgress, "Start was still not on offer after Stop")

        doc.setCinInputs(mapOf("text" to "after stop"))
        runBlocking { delay(600) }
        assertEquals("one", doc.runOutputs["out1"]?.decodeToString(), "a stopped live mode ran anyway")
    }

    /** Start picks it back up — otherwise Stop would be a one-way door out of the setting. */
    @Test
    fun `start picks live mode back up`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))
        doc.stopRun()

        doc.startRun()

        assertTrue(doc.liveRunning, "Start did not pick live mode back up")
        doc.setCinInputs(mapOf("text" to "again"))
        assertTrue(awaitOutput(doc, "again"), "live mode did not follow the input after Start")
    }

    /**
     * A live pass is a run like any other, and is shown as one: the nodes go through running on
     * the canvas rather than the answer quietly changing under the cursor.
     */
    @Test
    fun `a live pass is animated, like a run that was started by hand`() {
        val doc = document(RUN_LIVE)
        assertTrue(awaitOutput(doc, "one"))

        doc.setCinInputs(mapOf("text" to "watch this"))

        assertTrue(
            runBlocking {
                withTimeoutOrNull(3_000) {
                    while (!doc.running) delay(5)
                    true
                } == true
            },
            "nothing on the canvas said the flow was running",
        )
    }

    /**
     * Only what the change reaches runs again.
     *
     * Two inputs, two outputs, nothing shared. Changing one leaves the other side standing at
     * "done" — it is never taken back to idle, because it is never part of the pass. That is what
     * keeps a module with a consequence from firing for an edit on the other side of the canvas.
     */
    @Test
    fun `a change runs the side it reaches and leaves the other alone`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val ws = Workspace(scope)
        ws.runMode = RUN_LIVE
        val doc = EditorState(scope, ws, "two.flow")
        doc.load(
            FlowFile(
                nodes = listOf(
                    Node("inA", "cin", "a", 0f, 0f, 180f, 100f, emptyList(), listOf(Port("out", "A".encodeToByteArray()))),
                    Node("outA", "cout", "ra", 300f, 0f, 180f, 100f, listOf(Port("in")), emptyList()),
                    Node("inB", "cin", "b", 0f, 200f, 180f, 100f, emptyList(), listOf(Port("out", "B".encodeToByteArray()))),
                    Node("outB", "cout", "rb", 300f, 200f, 180f, 100f, listOf(Port("in")), emptyList()),
                ),
                edges = listOf(
                    Edge("ea", PortRef("inA", "out"), PortRef("outA", "in")),
                    Edge("eb", PortRef("inB", "out"), PortRef("outB", "in")),
                ),
            ),
        )
        assertTrue(
            runBlocking {
                withTimeoutOrNull(5_000) {
                    while (doc.nodeById("inB")?.status != "done") delay(20)
                    true
                } == true
            },
            "the first pass never finished",
        )

        // watch the untouched side while the touched one is run again
        var disturbed = false
        val watch = scope.launch {
            while (true) {
                if (doc.nodeById("inB")?.status != "done") disturbed = true
                delay(5)
            }
        }
        doc.setCinInputs(mapOf("a" to "A2"))
        assertTrue(awaitOutput(doc, "A2", "outA"), "the side that changed did not run")
        watch.cancel()

        assertFalse(disturbed, "the side the change never reached was run again")
        assertEquals("B", doc.runOutputs["outB"]?.decodeToString(), "the other side's result did not survive")
    }

    @Test
    fun `the setting is remembered`() {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        ws.runMode = RUN_LIVE
        assertTrue(ws.settingsJson().contains("\"runMode\": \"live\""), ws.settingsJson())
    }
}
