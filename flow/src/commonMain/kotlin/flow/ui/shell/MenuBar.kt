package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
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
import flow.ui.common.GearGlyph
import flow.ui.common.PlayGlyph
import flow.ui.common.StopGlyph
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
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

            // Start / stop: icon-only, both always shown — only the one that applies is enabled.
            // Bordered like IntelliJ's run/stop toolbar buttons, not just a hover wash.
            val running = active?.running == true
            TitleIconButton(
                enabled = active != null && !running, tint = Palette.accent, borderColor = Palette.accent,
                onClick = { active?.startRun() },
            ) { t -> PlayGlyph(t) }
            TitleIconButton(
                enabled = running, tint = Palette.errorSoft, borderColor = Palette.dangerBorder,
                onClick = { active?.stopRun() },
            ) { t -> StopGlyph(t) }
            Box(Modifier.width(20.dp))
            // Settings: rightmost — the right panel's rail no longer carries this button
            TitleIconButton(enabled = true, tint = Palette.menuText, onClick = { ws.showSettings = !ws.showSettings }) { t ->
                GearGlyph(t)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
    }
}

// Flat icon button for the title strip: hover = faint wash, disabled = dimmed and inert.
// An optional [borderColor] outlines it (IntelliJ-style run/stop), rather than leaving it borderless.
@Composable
private fun TitleIconButton(
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    borderColor: Color? = null,
    icon: @Composable (Color) -> Unit,
) {
    val (hoverSrc, hovered) = rememberHover()
    val bg = if (enabled && hovered) Palette.hoverBg else Color.Transparent
    var m = Modifier.size(28.dp).background(bg, RoundedCornerShape(6.dp))
    if (borderColor != null) {
        m = m.border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
    }
    Box(
        m.hoverable(hoverSrc).plainClick { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        icon(if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}
