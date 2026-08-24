package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import flow.model.DrawOp
import flow.model.Drawing
import flow.model.Region
import flow.ui.io.DrawingCanvas
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.security.KeyPairGenerator
import kotlin.test.Test

/** Draws the ASN.1 output to a file so it can be looked at. Skipped unless RENDER_OUT is set. */
class Asn1RenderProbe {

    private fun FakeCanvas.toDrawing() = Drawing(
        contentHeight = height ?: 0f,
        ops = ops.mapNotNull { op ->
            when (op) {
                is FakeCanvas.Text -> DrawOp.Text(op.x, op.y, op.text, op.size, op.color, op.mono)
                is FakeCanvas.Rect -> DrawOp.Rect(op.x, op.y, op.w, op.h, op.color, op.filled)
                is FakeCanvas.Line -> DrawOp.Line(op.x1, op.y1, op.x2, op.y2, op.color, op.stroke)
                is FakeCanvas.Image -> DrawOp.Image(op.x, op.y, op.w, op.h, op.bytes)
                is FakeCanvas.Region -> null
            }
        },
        regions = ops.filterIsInstance<FakeCanvas.Region>().map { Region(it.x, it.y, it.w, it.h, it.id) },
    )

    @Test
    fun `draw the asn1 output`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val width = 1000f
            val canvas = FakeCanvas(width = width, monoCharWidth = 0.6f)
            // pick the algorithm OID, which is the row with something to say about it
            val probe = FakeCanvas(width = width, monoCharWidth = 0.6f)
            flow.output.Asn1Output().draw(probe, key, emptyMap())
            val oidText = probe.texts.first { it.text.contains("OBJECT IDENTIFIER") }
            val row = probe.regions.last { it.id.startsWith("s") && it.y <= oidText.y && oidText.y < it.y + it.h }
            val selected = mapOf("asn1.selected" to row.id.removePrefix("s"))
            flow.output.Asn1Output().draw(canvas, key, selected)

            val drawing = canvas.toDrawing()
            val scene = ImageComposeScene((width * 2).toInt() + 40, 1000, density = Density(2f)) {
                ApplyTheme(theme)
                Box(Modifier.fillMaxSize().background(Palette.holeBg).padding(6.dp)) {
                    DrawingCanvas(drawing, viewportDp = 500f)
                }
            }
            val out = File(dir, "asn1-$theme.png")
            scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes.let { out.writeBytes(it) }
            scene.close()
            println("WROTE ${out.absolutePath}")
        }
    }
}
