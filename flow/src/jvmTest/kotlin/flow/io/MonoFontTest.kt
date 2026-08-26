package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import flow.extension.ProcessorExtension
import flow.ui.theme.Mono
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The face the app draws hex in is the one that ships with it.
 *
 * A missing font file is the quietest failure there is: Compose falls back to whatever the machine
 * calls monospace, everything still draws, and the only sign is that the columns are a different
 * width on someone else's machine than on yours.
 */
class MonoFontTest {

    private val weights = listOf("Regular", "Medium", "Bold")

    private fun render(family: FontFamily, weight: FontWeight = FontWeight.Normal): ByteArray {
        val scene = ImageComposeScene(320, 60, density = Density(2f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                BasicText(
                    "0123456789 ABCDEF",
                    style = TextStyle(color = Color.Black, fontSize = 15.sp, fontFamily = family, fontWeight = weight),
                )
            }
        }
        return scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes.also { scene.close() }
    }

    @Test
    fun `every weight the interface asks for is on the classpath`() {
        weights.forEach { weight ->
            val resource = "flow/fonts/JetBrainsMono-$weight.ttf"
            assertNotNull(
                Thread.currentThread().contextClassLoader.getResource(resource),
                "$resource is not on the classpath — the app would fall back to the machine's own font",
            )
        }
    }

    @Test
    fun `the font ships in the jar an extension worker gets`() {
        // A view extension draws its window in another process, whose classpath is the worker, the
        // contract and the lent Compose — not the app. So the font has to travel in the contract's
        // jar; moving it into the app's resources would leave every view falling back silently.
        val contract = File(ProcessorExtension::class.java.protectionDomain.codeSource.location.toURI())
        weights.forEach { weight ->
            val fromContract = ProcessorExtension::class.java.classLoader
                .getResource("flow/fonts/JetBrainsMono-$weight.ttf")
            assertNotNull(fromContract, "JetBrainsMono-$weight.ttf is not reachable at all")
            // the whole path, not the name: "build/resources/main" beside the classes would also
            // contain "main" and would pass a looser check while shipping in nothing at all
            assertTrue(
                fromContract.toString().contains(contract.absolutePath),
                "the font is not inside the extension contract ($contract), so a view extension's " +
                    "process would not find it: $fromContract",
            )
        }
    }

    @Test
    fun `the text is drawn in the shipped face, not the machine's`() {
        // The point of shipping a font is that it is used. If this ever matches, the resource was
        // not found and Compose quietly used the platform's monospace instead.
        assertTrue(
            !render(Mono).contentEquals(render(FontFamily.Monospace)),
            "the shipped face draws exactly like the platform default — it was not loaded",
        )
    }

    @Test
    fun `each weight is a real face rather than a smeared one`() {
        val drawn = weights.indices.map { render(Mono, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold)[it]) }
        assertEquals(3, drawn.map { it.toList() }.distinct().size, "two weights drew the same picture")
    }
}
