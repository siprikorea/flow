package dataflow.ui.shell

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
import dataflow.core.Workspace
import dataflow.ui.common.Txt
import dataflow.ui.common.plainClick
import dataflow.ui.theme.Palette
import kotlin.math.roundToInt

@Composable
fun StatusBar(ws: Workspace) {
    val active = ws.active
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(Palette.panelBg).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("statusHint"), 11.sp, Palette.dimText)
            Spacer(Modifier.weight(1f))
            Txt(
                "${active?.nodes?.size ?: 0} ${ws.t("modules")} · ${active?.edges?.size ?: 0} ${ws.t("connections")}",
                11.sp, Palette.dimText, mono = true,
            )
            if (active != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt("−", 12.sp, Palette.subText, modifier = Modifier.plainClick {
                        active.zoom = (active.zoom - 0.1f).coerceAtLeast(0.3f)
                    }.padding(horizontal = 6.dp))
                    Txt(
                        "${(active.zoom * 100).roundToInt()}%", 11.sp, Palette.dimText, mono = true,
                        modifier = Modifier.plainClick { active.zoom = 1f },
                    )
                    Txt("+", 12.sp, Palette.subText, modifier = Modifier.plainClick {
                        active.zoom = (active.zoom + 0.1f).coerceAtMost(2.5f)
                    }.padding(horizontal = 6.dp))
                }
            }
            ws.saveTime?.let { Txt("${ws.t("autoSaved")} $it", 11.sp, Palette.autosave) }
        }
    }
}
