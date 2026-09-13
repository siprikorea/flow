package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import flow.core.EditorState
import flow.core.Workspace
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import flow.ui.canvas.NodePortsView
import flow.ui.canvas.NodeView
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the canvas to a file, so a change to how something looks can actually be looked at.
 *
 * Not an assertion about anything — it draws and saves. Skipped unless RENDER_OUT names a
 * directory, so it costs nothing in an ordinary run.
 */
class RenderProbe {

    private fun node(id: String, label: String, type: String, x: Float, y: Float, status: String = "idle") = Node(
        id = id, type = type, label = label, x = x, y = y, w = 180f, h = 100f,
        inputs = if (type == "cin") emptyList() else listOf(Port("in")),
        outputs = if (type == "cout") emptyList() else listOf(Port("out")),
        status = status,
    )

    @Test
    fun `draw the canvas`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()

        val nodes = listOf(
            node("a", "Input", "cin", 40f, 40f),
            node("b", "Hash", "flow.hash", 260f, 40f),
            node("c", "Output", "cout", 480f, 40f),
            node("d", "Running", "flow.cipher", 40f, 190f, status = "running"),
            node("e", "Done", "flow.base64", 260f, 190f, status = "done"),
            node("f", "Failed", "flow.mac", 480f, 190f, status = "error"),
        )
        val flow = FlowFile(
            nodes = nodes,
            edges = listOf(
                Edge("e1", PortRef("a", "out"), PortRef("b", "in")),
                Edge("e2", PortRef("b", "out"), PortRef("c", "in")),
            ),
        )

        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            // one image per theme, with the second node of each row selected so the ring can be
            // judged against a plain node beside it and against every status colour
            listOf("b" to "selected-module", "f" to "selected-failed").forEach { (selectedId, name) ->
                val scene = ImageComposeScene(1400, 700, density = Density(2f)) {
                    ApplyTheme(theme)
                    val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                    val state = remember(ws) {
                        EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "probe.flow").also {
                            it.load(flow)
                            it.selNodes = setOf(selectedId)
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Palette.canvasBg)) {
                        state.nodes.forEach { n -> NodeView(state, n, timeMs = 300L) }
                        // ports are an overlay pass of their own, and where they sit is the thing
                        // under test as much as the node is
                        state.nodes.forEach { n -> NodePortsView(state, n) }
                    }
                }
                val out = File(dir, "$theme-$name.png")
                scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes.let { out.writeBytes(it) }
                scene.close()
                println("WROTE ${out.absolutePath}")
            }
        }
    }
}

private fun <T> remember(key: Any?, block: () -> T): T = block()
