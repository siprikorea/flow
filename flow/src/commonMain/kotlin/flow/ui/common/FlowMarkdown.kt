package flow.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import flow.ui.theme.FlowType
import flow.ui.theme.FlowTextStyle
import flow.ui.theme.Mono
import flow.ui.theme.Palette

/**
 * Claude's replies come back as Markdown — headings, lists, fenced code, **emphasis** — and until
 * now the AI panel printed that source text verbatim. This renders it for real, using
 * multiplatform-markdown-renderer's core module (deliberately not -m2/-m3: those pull in a
 * Material theme this app has no other use for) with colors and type sizes taken from Palette and
 * FlowType instead of the library's own Material-flavored defaults, so a reply reads like the rest
 * of Flow's chrome rather than a foreign component dropped into it.
 *
 * [streaming] says the text is still arriving. It picks between two renderers that draw the same
 * thing but pay for it differently, and neither one does both jobs:
 *
 *  - streaming: an incremental parse that is only handed what arrived since the last frame. Parsing
 *    the whole reply again per token is quadratic in its length, which at a few thousand characters
 *    is what the panel's slowness was. The cost of that is a tail it has not committed to yet —
 *    the last few words of a reply sit in the parser until more text settles them.
 *  - finished: one parse of the whole text, so the tail is there. immediate=true keeps it on the
 *    composing thread; the content overload parses on a coroutine and draws an empty box until it
 *    lands, which is a blank frame every time a message settles.
 */
@Composable
fun FlowMarkdown(content: String, streaming: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    fun style(t: FlowTextStyle, color: Color, weight: FontWeight = t.weight) = TextStyle(
        color = color,
        fontSize = t.size,
        fontWeight = weight,
        fontFamily = if (t.mono) Mono else null,
        letterSpacing = t.letterSpacing,
        lineHeight = t.lineHeight,
    )

    val body = style(FlowType.body, Palette.text)
    val mono = style(FlowType.mono, Palette.text)
    val colors = DefaultMarkdownColors(
        text = Palette.text,
        codeBackground = Palette.holeBg,
        inlineCodeBackground = Palette.holeBg,
        dividerColor = Palette.border,
        tableBackground = Palette.holeBg,
    )
    val typography = DefaultMarkdownTypography(
        h1 = style(FlowType.title, Palette.text, FontWeight.Bold),
        h2 = style(FlowType.bodyStrong, Palette.text, FontWeight.Bold),
        h3 = style(FlowType.bodyStrong, Palette.text),
        h4 = body,
        h5 = body,
        h6 = body,
        text = body,
        code = mono,
        inlineCode = mono,
        quote = style(FlowType.small, Palette.subText),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(style = SpanStyle(color = Palette.accent)),
        table = body,
    )

    if (streaming) Growing(content, colors, typography, modifier)
    else Settled(content, colors, typography, modifier)
}

/** One parse of the whole text, on the composing thread. What a message that has stopped growing gets. */
@Composable
private fun Settled(content: String, colors: MarkdownColors, typography: MarkdownTypography, modifier: Modifier) {
    Markdown(
        markdownState = rememberMarkdownState(content = content, immediate = true),
        modifier = modifier,
        colors = colors,
        typography = typography,
    )
}

/** Fed the difference between frames, so a reply costs its own length rather than its length squared. */
@Composable
private fun Growing(content: String, colors: MarkdownColors, typography: MarkdownTypography, modifier: Modifier) {
    // A streaming state can only be appended to, so text that is not a continuation of what it has
    // already been given — a different message in the same slot, or a reply cleared and retyped —
    // needs a new one. That is what the epoch is: bump it and `key` hands back a fresh state.
    var epoch by remember { mutableStateOf(0) }
    val state = key(epoch) { rememberStreamingMarkdownState() }
    val latest by rememberUpdatedState(content)

    // Keyed on the state, not on the content: append() suspends, and a LaunchedEffect re-keyed on
    // every chunk would cancel it half-done. snapshotFlow feeds this one long-lived collector
    // instead, and because each pass appends everything past `fed`, chunks that land mid-append are
    // picked up together by the next pass rather than lost.
    LaunchedEffect(state) {
        var fed = ""
        snapshotFlow { latest }.collect { text ->
            when {
                text.length == fed.length -> Unit
                text.startsWith(fed) -> {
                    state.append(text.substring(fed.length))
                    fed = text
                }
                else -> epoch++
            }
        }
    }

    Markdown(
        streamingMarkdownState = state,
        modifier = modifier,
        colors = colors,
        typography = typography,
    )
}
