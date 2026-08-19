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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import flow.ui.theme.Palette

// window edge the rail sits on (where its selection bar is drawn)
enum class RailSide { LEFT, RIGHT }

// Vertical icon rail on one window edge; each button toggles the panel it stands for.
@Composable
fun ActivityRail(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(44.dp).fillMaxHeight().background(Palette.tabBarBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// hover = faint wash, selected = lit background + accent bar on the edge
@Composable
fun ActivityButton(
    side: RailSide,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    val tint = when {
        selected -> Palette.text
        hovered -> Palette.menuText
        else -> Palette.dimText
    }
    val bg = when {
        selected -> Palette.langActiveBg
        hovered -> Palette.hoverBg
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().height(44.dp).hoverable(hoverSrc).plainClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(bg, RoundedCornerShape(7.dp)))
        if (selected) {
            Box(
                Modifier
                    .align(if (side == RailSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(2.dp).height(24.dp).background(Palette.accent)
            )
        }
        icon(tint)
    }
}
