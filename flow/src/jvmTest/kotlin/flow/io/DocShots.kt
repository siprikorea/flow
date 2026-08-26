package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import flow.core.EditorState
import flow.core.Workspace
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import flow.ui.canvas.CanvasView
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import flow.ui.tools.AiPanel
import flow.ui.tools.ModulePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * The pictures for the product page, taken from the running interface rather than drawn.
 *
 * All one size, so they sit in a page without each one needing its own handling — a panel is shown
 * at its own width against the page's ground rather than stretched to fill the frame.
 *
 * Skipped unless DOC_SHOTS names a directory.
 */
class DocShots {

    private val width = 1600
    private val height = 1000

    private fun shot(
        dir: File,
        name: String,
        theme: String,
        density: Float = 2f,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width, height, density = Density(density)) {
            ApplyTheme(theme)
            Box(Modifier.fillMaxSize().background(Palette.appBg)) { content() }
        }
        File(dir, "$name.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("WROTE $name.png")
    }

    private fun node(id: String, label: String, type: String, x: Float, y: Float, status: String = "idle") = Node(
        id = id, type = type, label = label, x = x, y = y, w = 180f, h = 100f,
        inputs = if (type == "cin") emptyList() else listOf(Port("in")),
        outputs = if (type == "cout") emptyList() else listOf(Port("out")),
        status = status,
    )

    /** A flow that signs something: the shape a real one has, not three boxes in a row. */
    private fun signingFlow() = FlowFile(
        nodes = listOf(
            node("msg", "Message", "cin", 60f, 90f),
            node("key", "Key", "cin", 60f, 400f),
            node("hash", "Hash", "flow.hash", 300f, 90f, status = "done"),
            node("sign", "Signature", "flow.signature", 540f, 245f, status = "done"),
            node("out", "Signed", "cout", 780f, 245f),
        ),
        edges = listOf(
            Edge("e1", PortRef("msg", "out"), PortRef("hash", "in")),
            Edge("e2", PortRef("hash", "out"), PortRef("sign", "in")),
            Edge("e3", PortRef("key", "out"), PortRef("sign", "in")),
            Edge("e4", PortRef("sign", "out"), PortRef("out", "in")),
        ),
    )

    @Composable
    private fun Panel(width: Dp, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.width(width).fillMaxHeight()
                    .background(Palette.panelBg)
                    .border(1.dp, Palette.panelBorder),
            ) { content() }
        }
    }

    @Composable
    private fun canvas(flow: FlowFile, selected: String? = null) {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val state = EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "doc.flow").also {
            it.load(flow)
            if (selected != null) it.selNodes = setOf(selected)
        }
        CanvasView(state, Modifier.fillMaxSize())
    }

    @Test
    fun `take the pictures`() {
        val dir = System.getenv("DOC_SHOTS")?.let { File(it) } ?: return
        dir.mkdirs()

        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val suffix = if (theme == Theme.DARK) "" else "-light"

            // the whole flow has to be in the frame, so the canvas is shown at a smaller scale
            // than the panels beside it
            shot(dir, "canvas$suffix", theme, density = 1.6f) { canvas(signingFlow(), selected = "sign") }

            // a panel is narrower than the frame, so it sits in the middle of it rather than
            // being stretched into a shape it never has on screen
            shot(dir, "palette$suffix", theme) {
                Panel(320.dp) { ModulePalette(Workspace(CoroutineScope(Dispatchers.Unconfined))) }
            }

            shot(dir, "assistant$suffix", theme) {
                val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                // with a folder open, so the panel shows what it is for rather than asking for one
                ws.openProject(File(System.getProperty("java.io.tmpdir"), "flow-doc").apply { mkdirs() }.absolutePath)
                Panel(380.dp) { AiPanel(ws) }
            }
        }
    }
}
