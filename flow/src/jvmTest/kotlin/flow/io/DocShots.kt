package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import flow.core.DataTab
import flow.core.EditorState
import flow.core.Workspace
import flow.model.AiMessage
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import flow.qr.QrExtension
import flow.ui.canvas.CanvasView
import flow.ui.data.DataEditor
import flow.ui.props.PropsPanel
import flow.ui.shell.App
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import flow.ui.tools.AiPanel
import flow.ui.tools.ModulePalette
import flow.view.Asn1Panel
import flow.view.ImagePanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.security.KeyPairGenerator
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

    private fun workspace() = Workspace(CoroutineScope(Dispatchers.Unconfined))

    private fun editor(ws: Workspace, selected: String? = null): EditorState =
        EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "sign-a-message.flow").also {
            it.load(signingFlow())
            if (selected != null) it.selNodes = setOf(selected)
        }

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

    private fun rsaKey() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

    private fun qrPng() = QrExtension().process(
        mapOf("in" to "https://flow.app/sign-a-message".encodeToByteArray()),
        mapOf("correction" to "M", "moduleSize" to "8", "quietZone" to "4"),
    )["out"]!!

    @Test
    fun `take the pictures`() {
        val dir = System.getenv("DOC_SHOTS")?.let { File(it) } ?: return
        dir.mkdirs()

        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val suffix = if (theme == Theme.DARK) "" else "-light"
            val dark = theme == Theme.DARK

            // the whole program, as it is opened: the parts list, a flow, and what the selected
            // node is set to — one picture that answers "what is this"
            shot(dir, "app$suffix", theme, density = 1.15f) {
                val ws = workspace()
                ws.docs.add(editor(ws, selected = "sign"))
                ws.activeIndex = 0
                ws.leftTab = "modules"
                App(ws)
            }

            // the whole flow has to be in the frame, so the canvas is shown at a smaller scale
            // than the panels beside it
            shot(dir, "canvas$suffix", theme, density = 1.6f) {
                val ws = workspace()
                CanvasView(editor(ws, selected = "sign"), Modifier.fillMaxSize())
            }

            // a panel is narrower than the frame, so it sits in the middle of it rather than
            // being stretched into a shape it never has on screen
            shot(dir, "palette$suffix", theme) { Panel(320.dp) { ModulePalette(workspace()) } }

            shot(dir, "props$suffix", theme) {
                val ws = workspace()
                Panel(300.dp) { PropsPanel(editor(ws, selected = "sign")) }
            }

            shot(dir, "output$suffix", theme, density = 1.5f) {
                val ws = workspace()
                val doc = editor(ws)
                // hex, because the result of a signature is not text and showing it as text is a
                // screenful of replacement characters
                doc.updateNode("out") { it.copy(params = it.params + ("format" to "hex")) }
                doc.runOutputs = mapOf("out" to rsaKey())
                DataEditor(ws, DataTab(doc, "out"))
            }

            shot(dir, "asn1$suffix", theme, density = 1.5f) { Asn1Panel(rsaKey(), dark) }

            shot(dir, "image$suffix", theme, density = 1.5f) { ImagePanel(qrPng(), dark) }

            shot(dir, "assistant$suffix", theme) {
                val ws = workspace()
                ws.openProject(File(System.getProperty("java.io.tmpdir"), "flow-doc").apply { mkdirs() }.absolutePath)
                ws.aiMessages = listOf(
                    AiMessage(fromUser = true, text = "메시지를 SHA-256으로 해시하고 RSA 키로 서명하는 흐름을 만들어 줘"),
                    AiMessage(
                        fromUser = false,
                        // short lines on purpose: the panel is narrow and a wrapped diagram is
                        // not a diagram
                        text = "sign-a-message.flow 를 만들어\n폴더에 저장하고 열었습니다.\n\n" +
                            "  Message ─┐\n" +
                            "           ├→ Hash ─→ Signature ─→ Signed\n" +
                            "  Key ─────┘\n\n" +
                            "Hash 는 SHA-256,\nSignature 는 SHA256withRSA.\n\n" +
                            "Start 를 누르면 Signed 에\n서명이 들어옵니다.",
                    ),
                )
                Panel(400.dp) { AiPanel(ws) }
            }
        }
    }
}
