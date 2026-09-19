package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.model.RUN_LIVE
import flow.model.versionOfTag
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.FlowType
import flow.ui.theme.Palette
import flow.ui.theme.Space
import flow.ui.theme.Size
import kotlin.math.roundToInt

@Composable
fun StatusBar(ws: Workspace) {
    val active = ws.active
    // No fill and no rule above it: the status bar sits on the window's ground, below the content
    // frame, so the frame's border stays the only line on that edge.
    Column {
        Row(
            Modifier.fillMaxWidth().height(Size.statusBar).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // caption/tertiary throughout — left is the operating hint, right is the numbers.
            // The hint is the part that gives way: it is the same sentence every time and can be
            // read short, while the counts and the zoom are the only changing numbers on screen
            // and were being pushed off the end of the bar by it.
            Txt(
                ws.t("statusHint"), FlowType.caption, Palette.textTertiary,
                modifier = Modifier.weight(1f, fill = false), maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Txt(
                "${active?.nodes?.size ?: 0} ${ws.t("modules")} · ${active?.edges?.size ?: 0} ${ws.t("connections")}",
                FlowType.mono, Palette.textTertiary,
            )
            if (active != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt("−", FlowType.body, Palette.textSecondary, modifier = Modifier.plainClick { active.zoom = (active.zoom - 0.1f).coerceAtLeast(0.3f) }.padding(horizontal = 6.dp))
                    Txt(
                        "${(active.zoom * 100).roundToInt()}%", FlowType.mono, Palette.textTertiary,
                        modifier = Modifier.plainClick { active.zoom = 1f },
                    )
                    Txt("+", FlowType.body, Palette.textSecondary, modifier = Modifier.plainClick { active.zoom = (active.zoom + 0.1f).coerceAtMost(2.5f) }.padding(horizontal = 6.dp))
                }
            }
            // Live mode runs the flow on its own, and a result that changes with nobody pressing
            // anything needs an explanation somewhere permanent. One word, and it goes to the
            // setting that turned it on.
            if (ws.runMode == RUN_LIVE) {
                Txt(
                    ws.t("runLive"), FlowType.caption, Palette.accentHover,
                    modifier = Modifier.plainClick { ws.openSettings("run") },
                )
            }
            // An update is worth one word down here and nothing more: it is news, not a task, and
            // pressing it goes to the page that explains it rather than starting anything.
            if (ws.updateAvailable) {
                Txt(
                    ws.t("updateStatus").replace("{v}", versionOfTag(ws.latestRelease?.tag ?: "")),
                    FlowType.caption, Palette.accentHover,
                    modifier = Modifier.plainClick { ws.openSettings("update") },
                )
            }
            ws.saveTime?.let { Txt("${ws.t("autoSaved")} $it", FlowType.caption, Palette.autosave) }
        }
    }
}
