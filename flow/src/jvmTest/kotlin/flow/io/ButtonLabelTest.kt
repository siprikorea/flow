package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import flow.ui.common.FlowButton
import flow.ui.common.FlowButtonVariant
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A button's label sits in the middle of the button.
 *
 * A button that is only as wide as its own content looks centred whatever the arrangement, so this
 * is about the ones given a width — the pair in a confirm dialog, "Run from here" down the side of
 * the property panel. Those had their label against the left edge, and nothing failed: it is a
 * thing you have to look at. So this looks, in pixels.
 */
class ButtonLabelTest {

    private val density = 2f
    private val w = 600
    private val h = 120
    private val margin = 20.dp

    /** One button, as wide as the frame allows, drawn on the panel's own ground. */
    private fun render(label: String, variant: FlowButtonVariant, icon: Boolean): Bitmap {
        val scene = ImageComposeScene(w, h, density = Density(density), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            Box(Modifier.fillMaxSize().background(Palette.panelBg).padding(margin)) {
                FlowButton(
                    label,
                    variant,
                    icon = if (icon) Lucide.Play else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {}
            }
        }
        val image = scene.render()
        return Bitmap().also {
            it.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL))
            image.readPixels(it)
            scene.close()
        }
    }

    /**
     * The columns the label's ink reaches, as a fraction of the button's own width.
     *
     * The row scanned is the middle of the button, where the letters are; anything that differs
     * from the button's fill by more than a rounding error is ink. The fill is read from inside the
     * button rather than assumed, so this does not care which variant is drawn — and the scan stays
     * clear of the button's own outline, which is ink too and would drag the middle towards
     * whichever edge is drawn.
     */
    private fun inkSpan(b: Bitmap): Pair<Int, Int> {
        val y = h / 2
        val left = (margin.value * density).toInt() + OUTLINE
        val right = w - left
        val fill = b.getColor(left + 1, y)
        var first = -1
        var last = -1
        for (x in left..right) {
            if (!near(b.getColor(x, y), fill)) {
                if (first < 0) first = x
                last = x
            }
        }
        assertTrue(first > 0, "no label was drawn")
        return first to last
    }

    /** Enough to clear a 1dp outline at this density, and far less than any label is wide. */
    private val OUTLINE = 8

    private fun near(a: Int, b: Int): Boolean = listOf(16, 8, 0).all { shift ->
        abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= 4
    }

    private fun assertCentred(label: String, variant: FlowButtonVariant, icon: Boolean = false) {
        val bitmap = render(label, variant, icon)
        val (first, last) = inkSpan(bitmap)
        val centre = (first + last) / 2.0
        val buttonCentre = w / 2.0
        assertTrue(
            abs(centre - buttonCentre) <= 3,
            "'$label' sits at $centre, and the button's middle is $buttonCentre",
        )
    }

    @Test
    fun `a label is centred in a button that was given a width`() {
        assertCentred("Cancel", FlowButtonVariant.Secondary)
        assertCentred("Delete", FlowButtonVariant.Danger)
        assertCentred("Open component", FlowButtonVariant.Secondary)
    }

    /** With an icon it is the pair that is centred, not the text with the icon hung off it. */
    @Test
    fun `an icon and its label are centred together`() {
        assertCentred("Run from here", FlowButtonVariant.Primary, icon = true)
    }
}
