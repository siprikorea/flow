package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import flow.ui.theme.Frame
import flow.ui.theme.Palette
import flow.ui.theme.Radius

/**
 * A rounded, bordered sheet on the window's ground — the shape everything in this program is
 * shown in.
 *
 * There are two of them: the content frame that holds the editor and the panels docked to it, and
 * a tool window shown as a card of its own beside it. They are the same composable on purpose,
 * because the point is that the border is one weight and one colour and one radius wherever it
 * appears — the moment each region draws its own edge, the corners stop lining up and the window
 * turns back into a grid of boxes.
 *
 * Two details do the work:
 *
 * - the content is **clipped** to the shape, so whatever is inside — a tab strip along the top, a
 *   canvas that paints to its own edges — is cut to the same rounded corner instead of squaring it
 *   off from the inside;
 * - the border is drawn **over** the content rather than under it, because a child that fills the
 *   sheet would otherwise paint across the very line that defines it. The overlay has no gesture
 *   of its own, so it is invisible to the pointer.
 */
@Composable
fun WindowSurface(
    modifier: Modifier = Modifier,
    fill: Color = Palette.panelBg,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.window)
    Box(modifier.clip(shape).background(fill, shape)) {
        content()
        Box(Modifier.matchParentSize().border(Frame.border, Palette.frameBorder, shape))
    }
}
