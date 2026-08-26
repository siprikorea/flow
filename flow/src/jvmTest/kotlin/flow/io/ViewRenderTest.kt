package flow.io

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import flow.qr.QrExtension
import flow.view.Asn1Panel
import flow.view.ImagePanel
import java.security.KeyPairGenerator
import kotlin.test.Test

/**
 * The two viewers draw, whatever they are handed.
 *
 * A view opens a window in its own process, so a failure in here is not a stack trace in the app's
 * log — it is a window that never appears. Worse, it has already happened: rows keyed by an offset
 * two elements shared threw during layout, the window went down with it, and the flag that says a
 * loop is running was left set, so every later attempt was quietly added to a list and never shown.
 *
 * The inputs are the ones a user actually reaches a viewer with: a real key, the base64 an output
 * shows them, bytes that are not the format at all, and something cut off in the middle.
 */
class ViewRenderTest {

    private fun draws(content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(1000, 700, density = Density(1.5f), content = content)
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    private fun rsaKey() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

    private fun inputs() = listOf(
        "a real key" to rsaKey(),
        // what an output shows, and the thing the user pointed the viewer at when it broke
        "base64 text" to "MEUCIQDx4kZ2vQpZ8mJ3nT1aBcDeFgHiJkLmNoPqRsTuVwXyZg==".encodeToByteArray(),
        "not DER at all" to ByteArray(200) { (it * 17).toByte() },
        "cut off mid-value" to byteArrayOf(0x30, 0x0A, 0x02, 0x01),
        "a length that runs off the end" to byteArrayOf(0x30, 0x84.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        "nothing" to ByteArray(0),
    )

    @Test
    fun `the ASN·1 viewer draws every kind of input, in both themes`() {
        inputs().forEach { (what, data) ->
            listOf(true, false).forEach { dark ->
                runCatching { draws { Asn1Panel(data, dark) } }
                    .onFailure { throw AssertionError("the ASN.1 viewer failed to draw $what (dark=$dark)", it) }
            }
        }
    }

    @Test
    fun `the image viewer draws, and says so when the bytes are not a picture`() {
        // a real picture, made the way the app makes one: a processor's output
        val png = QrExtension().process(
            mapOf("in" to "flow".encodeToByteArray()),
            mapOf("correction" to "M", "moduleSize" to "6", "quietZone" to "4"),
        )["out"]!!
        listOf("a real png" to png, "not a picture" to ByteArray(64) { it.toByte() }).forEach { (what, data) ->
            listOf(true, false).forEach { dark ->
                runCatching { draws { ImagePanel(data, dark) } }
                    .onFailure { throw AssertionError("the image viewer failed to draw $what (dark=$dark)", it) }
            }
        }
    }
}
