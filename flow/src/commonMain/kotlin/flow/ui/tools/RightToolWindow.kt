package flow.ui.tools

import androidx.compose.runtime.Composable
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelRight
import flow.core.Workspace
import flow.ui.common.ActivityButton
import flow.ui.common.ActivityRail
import flow.ui.common.LucideIcon
import flow.ui.theme.Size

// The rail on the right window edge. The properties panel it toggles is docked inside the content
// frame (see App) rather than hanging off the rail, so the frame stays one sheet with one border.
// Settings lives in the title bar now (see MenuBar), not on this rail.
@Composable
fun RightRail(ws: Workspace) {
    ActivityRail {
        ActivityButton(selected = ws.showProps, onClick = { ws.showProps = !ws.showProps }) { tint ->
            LucideIcon(Lucide.PanelRight, tint, Size.iconLarge)
        }
    }
}
