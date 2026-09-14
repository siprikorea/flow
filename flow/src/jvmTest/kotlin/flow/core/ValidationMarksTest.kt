package flow.core

import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The red marks are what the last check found, and an edit takes them down.
 *
 * They used to be a mode: once anything had been saved, the canvas checked every edit from then on.
 * So the same two actions were treated differently for reasons nobody could see — placing an input
 * and an output was quiet, and deleting the connector between them turned both of them red. Either
 * an edit is checked or it is not; while you are building, it is not, and the check happens when
 * you save or open.
 */
class ValidationMarksTest {

    private fun document(): EditorState {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val ws = Workspace(scope)
        val doc = EditorState(scope, ws, "a.flow")
        doc.load(
            FlowFile(
                nodes = listOf(
                    Node(id = "in1", type = "cin", label = "text", x = 0f, y = 0f, w = 180f, h = 100f, inputs = emptyList(), outputs = listOf(Port("out"))),
                    Node(id = "out1", type = "cout", label = "digest", x = 300f, y = 0f, w = 180f, h = 100f, inputs = listOf(Port("in")), outputs = emptyList()),
                ),
                edges = listOf(Edge("e1", PortRef("in1", "out"), PortRef("out1", "in"))),
            ),
        )
        return doc
    }

    @Test
    fun `deleting a connection takes the marks down rather than putting them up`() {
        val doc = document()
        doc.showValidation = true // as a save leaves it

        doc.deleteEdge("e1")

        assertFalse(doc.showValidation, "deleting a connection checked the flow instead of leaving it alone")
        assertTrue(doc.connectionWarning() != null, "the flow really is unconnected now — that is what a save would say")
    }

    @Test
    fun `so does deleting a node, pasting, or anything else that changes the flow`() {
        val doc = document()

        doc.showValidation = true
        doc.deleteNode("in1")
        assertFalse(doc.showValidation, "deleting a node")

        doc.showValidation = true
        doc.addNodeAt("cin", androidx.compose.ui.geometry.Offset(0f, 200f))
        assertFalse(doc.showValidation, "adding a node")
    }

    /** Undo and redo are changes too, and the marks were about neither of the states they land on. */
    @Test
    fun `undo and redo take them down`() {
        val doc = document()
        doc.deleteEdge("e1")

        doc.showValidation = true
        doc.undo()
        assertFalse(doc.showValidation, "undo")

        doc.showValidation = true
        doc.redo()
        assertFalse(doc.showValidation, "redo")
    }

    /** And a flow that is fully wired says so, whenever it is asked. */
    @Test
    fun `a connected flow has nothing to warn about`() {
        assertTrue(document().connectionWarning() == null)
    }
}
