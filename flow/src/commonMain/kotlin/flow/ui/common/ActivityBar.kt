package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import flow.ui.theme.Palette
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

// One selection language for the whole rail: a 2dp accent bar on the rail's edge + an accent
// icon — no filled box behind the selected icon (CLAUDE.md §5: "파란 라운드 박스 배경 금지").
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
    val bg = if (!selected && hovered) Palette.hoverOverlay else Color.Transparent
    Box(
        Modifier.fillMaxWidth().height(Size.activityBar).background(bg).hoverable(hoverSrc).plainClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(if (side == RailSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(2.dp).height(14.dp).background(Palette.accent)
            )
        }
        icon(tint)
    }
}
