package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import flow.ui.theme.FlowType
import flow.ui.theme.Palette
import flow.ui.theme.Size

// In-app top strip (themed title bar area): brand + run controls.
// The File/Edit/View menus live in the native system menu bar (see AppMenuBar).
@Composable
fun MenuBar(
    ws: Workspace,
    leadingInset: Dp = 0.dp,
    onTitleDoubleClick: (() -> Unit)? = null,
    // The JBR custom title bar (see Main.kt) tells the OS how tall the bar is and repositions the
    // traffic lights on it, but doesn't make clicking-and-dragging it move the window — that's a
    // separate native behavior a real title bar gets for free. This fires on a press nothing
    // underneath already consumed, so Main.kt can start that move itself (JBR's WindowMove API).
    onTitleBarPress: (() -> Unit)? = null,
) {
    val active = ws.active
    Column {
        Row(
            Modifier.fillMaxWidth().height(Size.toolbar)
                // double-click on the title strip toggles fullscreen (like a native title bar)
                .then(
                    if (onTitleDoubleClick != null) Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onTitleDoubleClick() })
                    } else Modifier
                )
                .then(
                    if (onTitleBarPress != null) Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            // requireUnconsumed (the default) means this never fires for a press a
                            // child button already consumed — only the bar's own background.
                            awaitFirstDown()
                            onTitleBarPress()
                        }
                    } else Modifier
                )
                // left: room for native traffic lights. Right: enough that the last control's
                // centre lands on the right rail's centre line — the rail is Size.activityBar
                // wide and its icons are centred in it, so a settings button that stops 8dp from
                // the edge sits 2dp inside the button below it, which is exactly the kind of
                // not-quite that the eye picks up and cannot name.
                .padding(
                    start = 10.dp + leadingInset,
                    end = Size.activityBar / 2 - Size.iconButton / 2,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppLogo(Size.iconLarge)
            Txt("Flow", FlowType.bodyStrong, Palette.textPrimary)

            Spacer(Modifier.weight(1f))

            // One Primary per region (CLAUDE.md P4): Run is the only filled button in the title
            // bar. Stop is a borderless Ghost icon, active only while a run is in progress — and
            // only then does it read as danger (§5: red only appears while actually running).
            val running = active?.running == true
            // 28dp controls in a 32dp strip: 2dp of air top and bottom, which is what makes them
            // read as sitting in the bar rather than filling it
            FlowButton(ws.t("start"), FlowButtonVariant.Primary, enabled = active != null && !running, icon = Lucide.Play) {
                active?.startRun()
            }
            FlowIconButton(Lucide.Square, enabled = running, tint = if (running) Palette.danger else null, onClick = { active?.stopRun() })
            Box(Modifier.width(1.dp).height(16.dp).background(Palette.borderSubtle))
            FlowIconButton(Lucide.Settings, onClick = { ws.showSettings = !ws.showSettings })
        }

    }
}
