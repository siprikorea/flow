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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // IntrinsicSize.Max gives fillMaxWidth a real width inside the horizontal scroller,
    // so the active-tab top indicator (VS Code style) actually renders
    Column(Modifier.width(IntrinsicSize.Max)) {
        Row(
            Modifier
                .height(Size.tabBar - 2.dp)
                .background(if (active) Palette.panel else if (hovered) Palette.hoverOverlay else Color.Transparent)
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
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // What is open is a flow, and this is what a flow looks like everywhere else in the
            // program — the same mark the window and the dock carry. A coloured dot said only
            // "this tab has a colour"; an icon says what kind of file the tab is.
            AppLogo(Size.icon)
            Txt(
                name, 12.sp,
                // unsaved is the name's own colour now, not a dot in the corner the close button
                // had to be given up for — the file the user is working in is exactly the one
                // whose close button has to be there when they reach for it
                when {
                    dirty -> Palette.warning
                    active -> Palette.textPrimary
                    else -> Palette.textTertiary
                },
                weight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            // always on the open file, on hover for the rest
            Txt(
                "×", 13.sp, if (hovered || active) Palette.textSecondary else Color.Transparent,
                modifier = Modifier.plainClick(onClose).padding(horizontal = 3.dp),
            )
        }
        // the open tab is underlined, the way an IDE marks the file you are in — under the tab,
        // against the editor it belongs to, rather than a rule floating above it
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) Palette.accent else Color.Transparent))
    }
}
