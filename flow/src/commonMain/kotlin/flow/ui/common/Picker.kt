package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import flow.ui.theme.Palette

/**
 * One value out of a list, chosen from a menu anchored to whatever draws it.
 *
 * A list rather than a row of segments because some of these lists are long: the models an OpenAI
 * key can reach run to dozens, and a segmented control of thirty is not a control. The anchor is a
 * slot so the same menu serves both places that need one — a bare chip under the AI composer and a
 * bordered field in Settings — without either growing its own copy of the popup.
 */
@Composable
fun Picker(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    above: Boolean = false,
    onSelect: (String) -> Unit,
    anchor: @Composable (label: String, open: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.find { it.first == selected }?.second ?: selected

    Box(modifier) {
        Box(Modifier.plainClick { if (options.isNotEmpty()) open = !open }) {
            anchor(label, open)
        }
        if (open) {
            Popup(
                popupPositionProvider = if (above) AbovePosition else BelowPosition,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(260.dp)
                        .heightIn(max = 280.dp)
                        .background(Palette.dropdownBg, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                        .padding(5.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { (id, text) ->
                        val (src, hovered) = rememberHover()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hoverable(src)
                                .background(
                                    if (hovered) Palette.holeBg else Color.Transparent,
                                    RoundedCornerShape(5.dp),
                                )
                                .plainClick { onSelect(id); open = false }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(
                                text,
                                13.sp,
                                if (id == selected) Palette.accent else Palette.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (id == selected) Txt("✓", 12.sp, Palette.accent)
                        }
                    }
                }
            }
        }
    }
}

/** Directly above the anchor, left edges aligned — for a control sitting on the floor of a panel. */
private val AbovePosition = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
    )
}

/** Just under the anchor, and lifted back above it rather than off the bottom of a short window. */
private val BelowPosition = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + 2
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
