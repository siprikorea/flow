package flow.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import flow.extension.ViewExtension
import flow.extension.ViewWindow
import kotlin.concurrent.thread

/**
 * Shows a result that is a picture — a QR code, a chart, anything a processor rendered.
 *
 * A window rather than a panel, because the reason to look at an image is usually to look at it
 * properly. It scales to the window, so resizing is how you zoom.
 */
class ImageView : ViewExtension {
    override val id = "flow.view.image"
    override val displayName = "Image"
    override val version = "2.0.0"
    override val description = "Show a result as a picture, for processors that produce an image."

    override fun open(data: ByteArray, options: Map<String, String>) {
        val dark = options["theme"] != "light"
        val background = if (dark) Color(0xFF14171F) else Color(0xFFF7F8FA)
        val muted = if (dark) Color(0xFF8A93A6) else Color(0xFF6B7484)
        val size = 720f
        val corner = ViewWindow.centeredOn(options, size, size)
        thread(name = "image-view", isDaemon = false) {
            application {
                Window(
                    onCloseRequest = ::exitApplication,
                    state = rememberWindowState(
                        width = size.dp, height = size.dp,
                        // over the window that opened it; centred on screen when it said nothing
                        position = corner?.let { (x, y) -> WindowPosition.Absolute(x.dp, y.dp) }
                            ?: WindowPosition(Alignment.Center),
                    ),
                    title = "Image",
                ) {
                    Content(data, background, muted)
                }
            }
        }
    }
}

@Composable
private fun Content(data: ByteArray, background: Color, muted: Color) {
    // decoded once: the bytes do not change while the window is up
    val bitmap = remember(data) { decode(data) }
    Column(Modifier.fillMaxSize().background(background).padding(12.dp)) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "this is not an image format that can be shown (${data.size} bytes)",
                    color = muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
            return@Column
        }
        Text(
            "${bitmap.width} × ${bitmap.height}",
            color = muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The bytes came from a processor, so they are not trusted to be an image at all. */
private fun decode(data: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
