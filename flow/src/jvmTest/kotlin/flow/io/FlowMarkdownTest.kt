package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import flow.ui.common.FlowMarkdown
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reply renders, and renders the same whether it arrived at once or a token at a time.
 *
 * Both halves of that have failed before. The renderer takes Compose as a compileOnly dependency,
 * so a version of it built against a newer Compose than this app uses resolves, compiles, and then
 * throws on the first frame — which reached the user as a crash dialog, not a build error; only
 * drawing one catches it. And the panel now feeds the parser the difference between frames rather
 * than the whole reply, which is only worth doing if what comes out is identical to parsing the
 * whole reply — so that is what is asserted, pixel for pixel.
 */
class FlowMarkdownTest {

    private val reply = """
        # A heading

        Some **bold** text and a bit of `inline code`, then a list:

        - first
        - second, which is long enough to wrap onto another line in a panel this narrow
        - third

        ```kotlin
        fun main() = println("hello")
        ```

        > and a quote to finish
    """.trimIndent()

    /** Renders [text] as one settled message, the way a reply that arrived before the panel opened is. */
    private fun whole(text: String): ByteArray = scene { set -> set(text, false) }

    /** Renders [text] the way a reply actually arrives: a few characters at a time, then done. */
    private fun streamed(text: String): ByteArray = scene { set ->
        var sent = 0
        while (sent < text.length) {
            sent = minOf(sent + 7, text.length)
            set(text.take(sent), true)
        }
        set(text, false)
    }

    /**
     * Drives one scene. [feed] is handed a setter; every call to it is a frame, which is what gives
     * the append coroutine its turn to run — the parse happens off the composition, so a chunk that
     * was never rendered was also never parsed.
     */
    private fun scene(feed: ((String, Boolean) -> Unit) -> Unit): ByteArray {
        var content by mutableStateOf("")
        var streaming by mutableStateOf(false)
        val scene = ImageComposeScene(420, 640, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            Box(Modifier.fillMaxSize().background(Palette.panelBg).padding(8.dp)) {
                FlowMarkdown(content, streaming = streaming)
            }
        }
        fun frame() = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        try {
            scene.render()
            feed { text, live ->
                content = text
                streaming = live
                // twice: the first frame lets the collector see the new text and append it, the
                // second draws what that produced
                scene.render()
                scene.render()
            }
            // The parse itself is not on the composition — append() suspends — so the last chunks
            // are still in flight when the last frame is drawn. Draw until two frames running are
            // the same and take that; the loop leaves as soon as it has settled, and the ceiling
            // is generous because this shares a machine with the rest of the suite — at one second
            // it went off occasionally under load, and an unsettled frame here reads as "the reply
            // drew nothing".
            var last = frame()
            repeat(120) {
                Thread.sleep(25)
                val next = frame()
                if (next.contentEquals(last)) return last
                last = next
            }
            return last
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a reply draws`() {
        val drawn = whole(reply)
        val blank = whole("")
        assertTrue(drawn.isNotEmpty(), "nothing came back")
        assertFalse(drawn.contentEquals(blank), "the reply drew the same as an empty panel")
    }

    @Test
    fun `a streamed reply ends up as the same picture as one that arrived whole`() {
        val a = whole(reply)
        val b = streamed(reply)
        System.getenv("RENDER_OUT")?.let {
            java.io.File(it).mkdirs()
            java.io.File(it, "md-whole.png").writeBytes(a)
            java.io.File(it, "md-streamed.png").writeBytes(b)
        }
        assertContentEquals(a, b, "streaming it produced a different frame")
    }
}
