package flow.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.ui.theme.FlowType
import flow.ui.theme.FlowTextStyle
import flow.ui.theme.Mono
import flow.ui.theme.Palette
import flow.ui.theme.Radius

@Composable
fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = if (mono) Mono else FontFamily.SansSerif,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// Same, but sized from one of the design guide's six named type tokens (FlowType.*) instead of a
// raw size/weight/letterSpacing tuple — the shape new call sites should reach for.
@Composable
fun Txt(
    text: String,
    style: FlowTextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    Txt(
        text, style.size, color, modifier,
        weight = style.weight, mono = style.mono, letterSpacing = style.letterSpacing,
        lineHeight = style.lineHeight, align = align, maxLines = maxLines,
    )
}

// Small lettered square marking a node kind: I=input, O=output, M=module, C=component.
@Composable
fun KindBadge(letter: String, color: Color, boxSize: Dp = 15.dp) {
    Box(
        Modifier.size(boxSize).background(color, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(letter, (boxSize.value * 0.62f).sp, Palette.appBg, weight = FontWeight.Bold)
    }
}

// clickable without ripple
fun Modifier.plainClick(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )
)

@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    return src to hovered
}

// The one text-field shape in the app (CLAUDE.md §6): bg `raised`, border `border`, radius
// `Radius.control`, focus = accent 1dp border + a 2dp accent-20% ring.
@Composable
fun DtxField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    textColor: Color = Palette.textPrimary,
    fontSize: TextUnit = FlowType.body.size,
    // an API key: shown as dots, since it is pasted in once and read by anyone looking over a
    // shoulder for the rest of the session otherwise
    mask: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.control)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(
            color = textColor,
            fontSize = fontSize,
            fontFamily = if (mono) Mono else FontFamily.SansSerif,
        ),
        cursorBrush = SolidColor(Palette.textPrimary),
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.raised, shape)
            .then(if (focused) Modifier.border(2.dp, Palette.accent.copy(alpha = 0.20f), shape) else Modifier)
            .border(1.dp, if (focused) Palette.accent else Palette.border, shape)
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            },
    )
}
