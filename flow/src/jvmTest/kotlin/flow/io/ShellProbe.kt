package flow.io

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
import flow.ui.shell.App
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * The window's own chrome, rendered whole and then corner by corner.
 *
 * The frame around the content is a thing you can only judge by looking at it: whether the margin
 * is the same on all four sides, whether the corners are the same corner, whether a panel inside
 * paints over the line that holds it. Skipped unless SHELL_OUT names a directory.
 */
class ShellProbe {

    private fun node(id: String, label: String, type: String, x: Float, y: Float, status: String = "idle") = Node(
        id = id, type = type, label = label, x = x, y = y, w = 180f, h = 100f,
        inputs = if (type == "cin") emptyList() else listOf(Port("in")),
        outputs = if (type == "cout") emptyList() else listOf(Port("out")),
        status = status,
    )

    private fun flow() = FlowFile(
        nodes = listOf(
            node("msg", "Message", "cin", 60f, 90f),
            node("hash", "Hash", "flow.hash", 300f, 90f, status = "done"),
            node("out", "Signed", "cout", 540f, 90f),
        ),
        edges = listOf(
            Edge("e1", PortRef("msg", "out"), PortRef("hash", "in")),
            Edge("e2", PortRef("hash", "out"), PortRef("out", "in")),
        ),
    )

    @Test
    fun `draw the shell and its corners`() {
        val dir = System.getenv("SHELL_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val w = 1400
        val h = 900

        listOf(Theme.DARK to "", Theme.LIGHT to "-light").forEach { (theme, suffix) ->
            listOf("open" to true, "folded" to false).forEach { (state, left) ->
                val scene = ImageComposeScene(w, h, density = Density(1.4f)) {
                    ApplyTheme(theme)
                    Box(Modifier.fillMaxSize()) {
                        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                        ws.docs.add(EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "sign.flow").also { it.load(flow()) })
                        ws.activeIndex = 0
                        ws.theme = theme // App applies the workspace's own theme, not the scene's
                        ws.leftTab = "modules"
                        ws.showLeft = left
                        App(ws)
                    }
                }
                val image = scene.render()
                File(dir, "shell-$state$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                // the four corners at 1:1, where every question about the frame actually lives
                val c = 280
                listOf(
                    "tl" to (0 to 0),
                    "tr" to (w - c to 0),
                    "bl" to (0 to h - c),
                    "br" to (w - c to h - c),
                ).forEach { (name, at) ->
                    val (x, y) = at
                    val surface = Surface.makeRasterN32Premul(c, c)
                    surface.canvas.drawImage(image, -x.toFloat(), -y.toFloat())
                    File(dir, "corner-$state-$name$suffix.png")
                        .writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    surface.close()
                }
                scene.close()
                println("WROTE shell-$state$suffix")
            }
        }
    }
}
