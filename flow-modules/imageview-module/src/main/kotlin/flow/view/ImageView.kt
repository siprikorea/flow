package flow.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
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
 * Shows a result that is a picture — a QR code, a chart, anything a module rendered.
 *
 * A window rather than a panel, because the reason to look at an image is usually to look at it
 * properly. It scales to the window, so resizing is how you zoom.
 */
class ImageView : ViewExtension {
    override val id = "flow.view.image"
    override val displayName = "Image"
    override val version = "2.4.0"
    override val description = "Show a result as a picture, with what the file says about itself — size, format, EXIF."

    override fun open(data: ByteArray, options: Map<String, String>) {
        // The title bar is the system's, and its colour comes from the appearance the process is
        // running in — so a viewer opened from a dark Flow on a light Mac would wear a light title
        // bar over dark contents. Set before any window exists, which is when it is read.
        val dark = options["theme"] != "light"
        System.setProperty(
            "apple.awt.application.appearance",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
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
                    // Asked for a moment ago, so it belongs in front. A process with no dock
                    // icon is a background one as far as macOS is concerned, and toFront alone
                    // does not lift a background app's window above the app that is in front —
                    // raising it on top and letting go immediately does.
                    LaunchedEffect(Unit) { window.bringForward() }
                    Content(data, background, muted)
                }
            }
        }
    }
}

/**
 * The viewer's contents, with no window around them — see the ASN.1 view for why this is public.
 */
@Composable
fun ImagePanel(data: ByteArray, dark: Boolean = true) {
    Content(
        data = data,
        background = if (dark) Color(0xFF14171F) else Color(0xFFF7F8FA),
        muted = if (dark) Color(0xFF8A93A6) else Color(0xFF6B7484),
    )
}

@Composable
private fun Content(data: ByteArray, background: Color, muted: Color) {
    // decoded once, and read once: neither the bytes nor what they say changes while the window is up
    val bitmap = remember(data) { decode(data) }
    val sections = remember(data) { runCatching { ImageMeta.read(data) }.getOrDefault(emptyList()) }
    // A picture is what the window is for, so the details start folded away — one line saying what
    // there is, and the rest on a click. Anything with EXIF in it has more than fits beside an image.
    var showDetails by remember(data) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(background).padding(12.dp)) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "this is not an image format that can be shown (${data.size} bytes)",
                    color = muted, fontSize = 12.sp, fontFamily = JetBrainsMono,
                )
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary(bitmap, data, sections),
                color = muted, fontSize = 11.sp, fontFamily = JetBrainsMono,
                modifier = Modifier.weight(1f),
            )
            if (sections.isNotEmpty()) {
                Text(
                    if (showDetails) "hide details" else "details",
                    color = muted, fontSize = 11.sp, fontFamily = JetBrainsMono,
                    modifier = Modifier
                        .clickable { showDetails = !showDetails }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.fillMaxSize()) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (showDetails) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(muted.copy(alpha = 0.25f)))
                Details(sections, muted, Modifier.width(240.dp).fillMaxHeight())
            }
        }
    }
}

/** The one line that is always there: what it is, how big, and whether there is more to see. */
private fun summary(bitmap: ImageBitmap, data: ByteArray, sections: List<ImageMeta.Section>): String {
    val format = ImageMeta.format(data)
    val parts = listOfNotNull(
        "${bitmap.width} × ${bitmap.height}",
        format,
        ImageMeta.size(data.size.toLong()),
        "EXIF".takeIf { sections.any { section -> section.title == "camera" || section.title == "exposure" } },
    )
    return parts.joinToString(" · ")
}

/** Everything the file says, grouped as it was read: a heading, then label and value per line. */
@Composable
private fun Details(sections: List<ImageMeta.Section>, muted: Color, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
        sections.forEach { section ->
            Text(
                section.title.uppercase(),
                color = muted.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            section.rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        label,
                        color = muted.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = JetBrainsMono,
                        modifier = Modifier.width(92.dp),
                    )
                    // a value is whatever the file said — a comment can be a paragraph, so it wraps
                    Text(value, color = muted, fontSize = 11.sp, fontFamily = JetBrainsMono, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The bytes came from a module, so they are not trusted to be an image at all. */
private fun decode(data: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()

/** Brings a window to the front from a process that has no dock icon. */
private fun java.awt.Window.bringForward() {
    // A process with no dock icon is a background app to macOS, and a background app's window does
    // not come out in front of the one that is — not by toFront, and not by being briefly pinned on
    // top either. Asking to be brought to the foreground is the request that actually means it.
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }
    toFront()
    requestFocus()
}

/**
 * JetBrains Mono, so a column of hex is a column.
 *
 * The file is in the module contract's jar, which this process has on its classpath along with
 * the worker and the Compose the app lends it — the app's own jar is not there, so naming the same
 * resource path is how both sides end up with the same face.
 */
private val JetBrainsMono = FontFamily(
    Font("flow/fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
    Font("flow/fonts/JetBrainsMono-Medium.ttf", FontWeight.Medium),
    Font("flow/fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold),
)
