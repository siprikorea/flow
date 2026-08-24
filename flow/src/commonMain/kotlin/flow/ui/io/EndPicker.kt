package flow.ui.io

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
 * Picks which extension one end of a flow uses: how a value is written, or how a result is shown.
 *
 * [none] names the choice of no extension at all, where there is one. An output has it, because
 * seeing the bytes themselves is always a reasonable thing to want and no output should be able to
 * take that away. An input does not: a value has to be written as something.
 */
@Composable
fun EndPicker(
    ws: Workspace,
    label: String,
    selected: String?,
    choices: List<Pair<String, String>>,
    none: String?,
    // shown when nothing is selected and there is no "none" to fall back to — a menu that does
    // something rather than one that holds a value
    placeholder: String? = null,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options: List<Pair<String?, String>> =
        (if (none != null) listOf<Pair<String?, String>>(null to none) else emptyList()) + choices
    val shown = options.find { it.first == selected }?.second
        ?: placeholder ?: none ?: choices.firstOrNull()?.second.orEmpty()

    androidx.compose.foundation.layout.Box {
        Row(
            Modifier
                .background(Palette.holeBg, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .plainClick { open = !open }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(label, 10.5.sp, Palette.dimText)
            Txt(shown, 11.5.sp, Palette.text, modifier = Modifier.padding(horizontal = 6.dp))
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
                    options.forEach { (id, name) ->
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
