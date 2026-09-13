package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.FocusRegion
import flow.core.Workspace
import flow.util.flowLabel
import flow.ui.common.AppLogo
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette
import flow.ui.theme.Radius
import flow.ui.theme.Size

@Composable
fun EditorTabs(ws: Workspace) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(Size.tabBar).background(Palette.tabBarBg)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ws.docs.forEachIndexed { i, doc ->
                // keyed by the doc's own identity, not its list position: closing a tab shifts every
                // later one left, and without a key Compose reuses each slot's remembered state
                // (including the click/double-click gesture detector below) for whatever tab now
                // lands there — stale state that can make the first click after a close misbehave.
                key(doc) {
                    Tab(
                        name = flowLabel(doc.fileName),
                        active = i == ws.activeIndex,
                        dirty = doc.dirty,
                        // picking a tab puts you in the editor, so the canvas's own shortcuts
                        // answer again without having to click the canvas itself first
                        onSelect = { ws.select(i); ws.focus = FocusRegion.CANVAS },
                        onClose = { ws.requestClose(i) },
                        onDoubleClick = {
                            // leave only the canvas; double-click again restores both panels
                            val anyOpen = ws.showLeft || ws.showProps
                            ws.showLeft = !anyOpen
                            ws.showProps = !anyOpen
                        },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.frameBorder))
    }
}

@Composable
private fun Tab(
    name: String,
    active: Boolean,
    dirty: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val (hoverSrc, hovered) = rememberHover()
    // A tab is a chip, not a slab of the strip: rounded, with the strip showing above, below and
    // between them. That shape is the whole of how the open file is marked — a fill that sits
    // above the strip — so there is no rule over it or under it to say the same thing again.
    Box(Modifier.padding(horizontal = 2.dp, vertical = 3.dp)) {
        Row(
            Modifier
                .height(Size.tabBar - 6.dp)
                .clip(RoundedCornerShape(Radius.surface))
                .background(
                    when {
                        active -> Palette.raised
                        hovered -> Palette.hoverOverlay
                        else -> Color.Transparent
                    },
                )
                .hoverable(hoverSrc)
                // single click selects; double click toggles the side panels (canvas-only)
                .pointerInput(Unit) {
                    var last = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val now = down.uptimeMillis
                        if (now - last <= viewConfiguration.doubleTapTimeoutMillis) {
                            onDoubleClick(); last = 0L
                        } else {
                            onSelect(); last = now
                        }
                    }
                }
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // What is open is a flow, and this is what a flow looks like everywhere else in the
            // program — the same mark the window and the dock carry.
            AppLogo(Size.icon)
            Txt(
                name, 12.sp,
                if (active) Palette.textPrimary else Palette.textTertiary,
                weight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            // Unsaved, in a slot of its own that is always there — so a file being saved does not
            // change the width of its tab, and so it never takes the close button's place. That is
            // what it used to do, on exactly the tab whose close button was wanted.
            Box(
                Modifier.size(5.dp)
                    .background(if (dirty) Palette.warning else Color.Transparent, CircleShape),
            )
            // on every tab, the way the IDE next door does it
            Txt(
                "×", 13.sp, if (hovered || active) Palette.textSecondary else Palette.textTertiary,
                modifier = Modifier.plainClick(onClose).padding(horizontal = 2.dp),
            )
        }
    }
}
