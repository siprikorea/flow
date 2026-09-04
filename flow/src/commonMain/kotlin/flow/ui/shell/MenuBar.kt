package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Square
import flow.core.Workspace
import flow.ui.common.AppLogo
import flow.ui.common.FlowButton
import flow.ui.common.FlowButtonVariant
import flow.ui.common.FlowIconButton
import flow.ui.common.Txt
import flow.ui.theme.Palette
import flow.ui.theme.Size

// In-app top strip (themed title bar area): brand + run controls.
// The File/Edit/View menus live in the native system menu bar (see AppMenuBar).
@Composable
fun MenuBar(ws: Workspace, leadingInset: Dp = 0.dp, onTitleDoubleClick: (() -> Unit)? = null) {
    val active = ws.active
    Column {
        Row(
            Modifier.fillMaxWidth().height(Size.toolbar).background(Palette.panelBg)
                // double-click on the title strip toggles fullscreen (like a native title bar)
                .then(
                    if (onTitleDoubleClick != null) Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onTitleDoubleClick() })
                    } else Modifier
                )
                .padding(start = 10.dp + leadingInset, end = 10.dp), // left: room for native traffic lights
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppLogo(18.dp)
            Txt("Flow", 14.sp, Palette.textPrimary, weight = FontWeight.Bold)

            Spacer(Modifier.weight(1f))

            // One Primary per region (CLAUDE.md P4): Run is the only filled button in the title
            // bar. Stop is a borderless Ghost icon, active only while a run is in progress — and
            // only then does it read as danger (§5: red only appears while actually running).
            val running = active?.running == true
            FlowButton(ws.t("start"), FlowButtonVariant.Primary, enabled = active != null && !running, icon = Lucide.Play) {
                active?.startRun()
            }
            FlowIconButton(Lucide.Square, enabled = running, tint = if (running) Palette.danger else null, onClick = { active?.stopRun() })
            Box(Modifier.width(1.dp).height(20.dp).background(Palette.borderSubtle))
            FlowIconButton(Lucide.Settings, onClick = { ws.showSettings = !ws.showSettings })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}
