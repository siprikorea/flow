package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.ui.common.AppLogo
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette

// In-app top strip (themed title bar area): brand + run controls.
// The File/Edit/View menus live in the native system menu bar (see AppMenuBar).
@Composable
fun MenuBar(ws: Workspace, leadingInset: Dp = 0.dp, onTitleDoubleClick: (() -> Unit)? = null) {
    val active = ws.active
    Column {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Palette.panelBg)
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
            Txt("Flow", 14.sp, Palette.text, weight = FontWeight.Bold)

            Spacer(Modifier.weight(1f))

            // Run / stop toggle
            val running = active?.running == true
            if (running) {
                RunButton(ws.t("stop"), enabled = true, borderColor = Palette.dangerBorder, textColor = Palette.errorSoft) { active?.stopRun() }
            } else {
                RunButton(ws.t("start"), enabled = active != null, bg = Palette.accent, textColor = Palette.holeBg) { active?.startRun() }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

@Composable
private fun RunButton(
    label: String,
    enabled: Boolean,
    textColor: Color,
    bg: Color? = null,
    borderColor: Color? = null,
    onClick: () -> Unit,
) {
    var m = Modifier.background(bg ?: Color.Transparent, RoundedCornerShape(6.dp))
    if (borderColor != null) m = m.border(1.dp, borderColor, RoundedCornerShape(6.dp))
    Box(m.plainClick { if (enabled) onClick() }.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Txt(
            label, 12.5.sp,
            if (enabled) textColor else textColor.copy(alpha = 0.4f),
            weight = if (bg != null) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
