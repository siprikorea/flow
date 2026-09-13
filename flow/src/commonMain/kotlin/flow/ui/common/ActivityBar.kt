package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import flow.ui.theme.Palette
import flow.ui.theme.Radius
import flow.ui.theme.Size

// Vertical icon rail on one window edge; each button toggles the panel it stands for.
//
// No background of its own: the rail is part of the window, not part of the content, and letting
// the window's gradient run behind it is what leaves the content frame as the only bordered thing
// on that edge — a rail with its own fill and its own divider reads as a third panel.
@Composable
fun ActivityRail(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(Size.activityBar).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * One button on a rail: an icon, centred, in a square the size of the strip.
 *
 * Both states are the same shape, filled differently: hovering fills it faintly, selecting fills it
 * with the accent at low opacity and turns the icon accent too, and hovering a selected one lays
 * the hover over the selection so it still answers the pointer. That is how the IDEs next door
 * show a tool window is open, and it is a shape the size of the thing you are aiming at rather
 * than a band across the rail.
 *
 * There is no bar on the window's edge any more. It said the same thing as the fill twice, from
 * somewhere the eye had no reason to be, and cut into the border the window otherwise keeps
 * unbroken.
 */
@Composable
fun ActivityButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    val tint = when {
        selected -> Palette.accent
        hovered -> Palette.textPrimary
        else -> Palette.textSecondary
    }
    val shape = RoundedCornerShape(Radius.surface)
    Box(
        Modifier.fillMaxWidth().height(Size.chrome).hoverable(hoverSrc).plainClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Size.iconButton)
                .background(if (selected) Palette.accentSubtle else Color.Transparent, shape)
                // over the selection, not instead of it: a selected button still lights up
                .background(if (hovered) Palette.hoverOverlay else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
    }
}
