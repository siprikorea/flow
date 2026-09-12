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

// window edge the rail sits on (where its selection bar is drawn)
enum class RailSide { LEFT, RIGHT }

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
 * The hover is a rounded box behind the icon rather than a band across the rail — the same shape
 * an icon button has anywhere else in the program, and the same shape the IDEs next door use. A
 * full-width wash made the rail look like a list of rows, which it is not: these are buttons, and
 * a button should be the size of the thing you are aiming at.
 *
 * Selection stays an accent icon plus a 2dp bar on the window's edge, not a filled box — a blue
 * box behind a selected icon is the one thing this app's own guide rules out.
 */
@Composable
fun ActivityButton(
    side: RailSide,
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
    Box(
        Modifier.fillMaxWidth().height(Size.chrome).hoverable(hoverSrc).plainClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(if (side == RailSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(2.dp).height(16.dp).background(Palette.accent)
            )
        }
        Box(
            Modifier
                .size(Size.iconButton)
                .background(
                    if (hovered) Palette.hoverOverlay else Color.Transparent,
                    RoundedCornerShape(Radius.surface),
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
    }
}
