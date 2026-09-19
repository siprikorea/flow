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
import kotlin.test.assertEquals
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
    private fun awaitOutput(doc: EditorState, expected: String): Boolean = runBlocking {
        withTimeoutOrNull(5_000) {
            while (doc.runOutputs["out1"]?.decodeToString() != expected) delay(20)
            true
        } == true
    }

    @Test
    fun `one-time is what a flow does unless it is asked otherwise`() {
        assertEquals(RUN_ONCE, Settings().runMode)
        assertEquals(RUN_ONCE, Workspace(CoroutineScope(Dispatchers.Unconfined)).runMode)
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

    @Test
    fun `the setting is remembered`() {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        ws.runMode = RUN_LIVE
        assertTrue(ws.settingsJson().contains("\"runMode\": \"live\""), ws.settingsJson())
    }
}
