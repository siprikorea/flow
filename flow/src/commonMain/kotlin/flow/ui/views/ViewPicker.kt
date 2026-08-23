package flow.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import flow.core.Workspace
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette

/**
 * Picks how an output is shown: raw bytes, or one of the installed views.
 *
 * Raw is always on the list and always first. A view is a way of reading the same data, not a
 * replacement for it, so there is never a state where the bytes cannot be got back to.
 */
@Composable
fun ViewPicker(ws: Workspace, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = listOf<Pair<String?, String>>(null to ws.t("viewRaw")) +
        ws.enabledViews.map { it.id to it.name }
    val label = choices.find { it.first == selected }?.second ?: ws.t("viewRaw")

    androidx.compose.foundation.layout.Box {
        Row(
            Modifier
                .background(Palette.holeBg, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .plainClick { open = !open }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(ws.t("viewLabel"), 10.5.sp, Palette.dimText)
            Txt(label, 11.5.sp, Palette.text, modifier = Modifier.padding(horizontal = 6.dp))
            Txt(if (open) "▲" else "▼", 8.sp, Palette.dimText)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, 30),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(180.dp)
                        .background(Palette.dropdownBg, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(8.dp))
                        .padding(5.dp),
                ) {
                    choices.forEach { (id, name) ->
                        val (src, hovered) = rememberHover()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hoverable(src)
                                .background(
                                    if (hovered) Palette.dropdownHover else Color.Transparent,
                                    RoundedCornerShape(5.dp),
                                )
                                .plainClick { onSelect(id); open = false }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(
                                name, 12.sp,
                                if (id == selected) Palette.accent else Palette.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (id == selected) Txt("✓", 11.sp, Palette.accent)
                        }
                    }
                }
            }
        }
    }
}
