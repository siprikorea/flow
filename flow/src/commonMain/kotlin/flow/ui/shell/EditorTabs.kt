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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette

@Composable
fun EditorTabs(ws: Workspace) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Palette.tabBarBg)
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
                        name = doc.fileName.removeSuffix(".flow"),
                        active = i == ws.activeIndex && ws.activeData == null,
                        dotColor = if (ws.isComponentFile(doc.fileName)) Palette.catComponent else Palette.dimText,
                        dirty = doc.dirty,
                        onSelect = { ws.select(i) },
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
            // data-editor tabs (in/out sample data)
            ws.dataTabs.forEach { tab ->
                key(tab) {
                    Tab(
                        name = tab.title,
                        active = ws.activeData === tab,
                        dotColor = Palette.catIo,
                        dirty = false,
                        onSelect = { ws.selectDataTab(tab) },
                        onClose = { ws.closeDataTab(tab) },
                    )
                }
            }
            Box(
                Modifier.plainClick { ws.newComponent() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Txt("+", 15.sp, Palette.subText) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

@Composable
private fun Tab(
    name: String,
    active: Boolean,
    dotColor: Color,
    dirty: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val (hoverSrc, hovered) = rememberHover()
    // IntrinsicSize.Max gives fillMaxWidth a real width inside the horizontal scroller,
    // so the active-tab top indicator (VS Code style) actually renders
    Column(Modifier.width(IntrinsicSize.Max)) {
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) Palette.accent else Color.Transparent))
        Row(
            Modifier
                .height(32.dp)
                .background(if (active) Palette.tabActiveBg else if (hovered) Palette.hoverBg else Palette.tabBarBg)
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
            Box(
                Modifier.width(8.dp).height(8.dp)
                    .background(dotColor, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            )
            Txt(
                name, 12.sp,
                if (active) Palette.text else Palette.menuText,
                weight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            // dirty: a dot (•) normally, close (×) on hover
            if (dirty && !hovered) {
                Txt("●", 10.sp, Palette.accentSoft, modifier = Modifier.padding(horizontal = 3.dp))
            } else {
                Txt(
                    "×", 13.sp, if (hovered || active) Palette.subText else Color.Transparent,
                    modifier = Modifier.plainClick(onClose).padding(horizontal = 3.dp),
                )
            }
        }
    }
}
