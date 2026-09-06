package flow.io

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import flow.core.EditorState
import flow.core.Workspace
import flow.model.FlowFile
import flow.model.Node
import flow.ui.canvas.NodeView
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A node can be dragged bigger by its corner.
 *
 * This is here because the handle was deleted once — a design-guide pass took it out on the
 * grounds that the guide drops manual node resize — and nothing failed when it went, so the
 * first thing that noticed was someone trying to resize a node. The gesture is the feature, so
 * the gesture is what is tested: press the corner, drag, and see the node's own width and height
 * follow.
 */
class NodeResizeTest {

    private val node = Node(
        id = "n1", type = "flow.hash", label = "SHA-256",
        x = 0f, y = 0f, w = 180f, h = 90f,
        inputs = emptyList(), outputs = emptyList(),
    )

    /** An editor holding one node, and a scene drawing it at 1:1 so screen and node coordinates agree. */
    private fun scene(state: EditorState) = ImageComposeScene(
        400, 300, density = Density(1f), coroutineContext = Dispatchers.Unconfined,
    ) {
        ApplyTheme(Theme.DARK)
        Box(Modifier.fillMaxSize()) { NodeView(state, state.nodes.first(), 0L) }
    }

    private fun editor(): EditorState {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        return EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "a.flow").also {
            it.load(FlowFile(nodes = listOf(node)))
            it.density = 1f
        }
    }

    /** Drags from [from] by [by], a few steps so the gesture reads as a drag rather than a jump. */
    private fun ImageComposeScene.drag(from: Offset, by: Offset) {
        sendPointerEvent(PointerEventType.Press, from)
        render()
        repeat(4) { step ->
            sendPointerEvent(PointerEventType.Move, from + by * ((step + 1) / 4f))
            render()
        }
        sendPointerEvent(PointerEventType.Release, from + by)
        render()
    }

    @Test
    fun `dragging the corner resizes the node`() {
        val state = editor()
        val scene = scene(state)
        try {
            scene.render()
            // the handle sits 3dp in from the bottom-right corner and is 13dp across
            scene.drag(from = Offset(node.w - 7f, node.h - 7f), by = Offset(40f, 30f))
            val after = state.nodeById("n1")!!
            assertEquals(220f, after.w, "width did not follow the drag")
            assertEquals(120f, after.h, "height did not follow the drag")
            // the node was not moved by the same gesture
            assertEquals(0f, after.x)
            assertEquals(0f, after.y)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a node cannot be dragged smaller than its contents`() {
        val state = editor()
        val scene = scene(state)
        try {
            scene.render()
            scene.drag(from = Offset(node.w - 7f, node.h - 7f), by = Offset(-400f, -400f))
            val after = state.nodeById("n1")!!
            assertEquals(120f, after.w)
            assertEquals(60f, after.h)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `one drag is one undo step`() {
        val state = editor()
        val scene = scene(state)
        try {
            scene.render()
            scene.drag(from = Offset(node.w - 7f, node.h - 7f), by = Offset(40f, 30f))
            state.undo()
            val after = state.nodeById("n1")!!
            assertEquals(180f, after.w, "undo did not put the size back in one step")
            assertEquals(90f, after.h)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `dragging the body still moves the node instead of resizing it`() {
        val state = editor()
        val scene = scene(state)
        try {
            scene.render()
            scene.drag(from = Offset(node.w / 2, node.h / 2), by = Offset(40f, 30f))
            val after = state.nodeById("n1")!!
            assertEquals(180f, after.w, "the body drag resized instead of moving")
            assertEquals(90f, after.h)
            assertTrue(after.x != 0f || after.y != 0f, "the node did not move")
        } finally {
            scene.close()
        }
    }
}
