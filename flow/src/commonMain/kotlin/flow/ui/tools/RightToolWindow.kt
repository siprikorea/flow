package flow.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelRight
import flow.core.Workspace
import flow.ui.common.ActivityButton
import flow.ui.common.ActivityRail
import flow.ui.common.LucideIcon
import flow.ui.common.RailSide
import flow.ui.props.PropsPanel
import flow.ui.theme.Palette
import flow.ui.theme.Size

// Right side: the open panel + the rail (properties). Pressing an open one hides it.
// Settings lives in the title bar now (see MenuBar), not on this rail.
@Composable
fun RightToolWindow(ws: Workspace) {
    val active = ws.active
    Row {
        // the props panel belongs to the canvas; hide it on a data-editor tab
        if (ws.showProps && active != null) PropsPanel(active)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.panelBorder))
        ActivityRail {
            ActivityButton(RailSide.RIGHT, selected = ws.showProps, onClick = { ws.showProps = !ws.showProps }) { tint ->
                LucideIcon(Lucide.PanelRight, tint, Size.iconLarge)
            }
        }
    }
}
