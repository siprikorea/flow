package flow.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
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
 */
@Composable
fun FlowMarkdown(content: String, modifier: Modifier = Modifier.fillMaxWidth()) {
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

    // The content: String overload parses on a coroutine and shows an empty Box while that's in
    // flight — fine for a document opened once, but a streamed reply changes content on every
    // chunk, so that empty flash happened on every chunk too: the text visibly blanked out and
    // reappeared instead of just growing. rememberMarkdownState's immediate=true parses inline on
    // the composing thread instead, which is what the content overload can't be told to do —
    // there's no flash to begin with since there's never a frame with nothing parsed yet.
    val state = rememberMarkdownState(content = content, immediate = true)
    Markdown(
        markdownState = state,
        modifier = modifier,
        colors = DefaultMarkdownColors(
            text = Palette.text,
            codeBackground = Palette.holeBg,
            inlineCodeBackground = Palette.holeBg,
            dividerColor = Palette.border,
            tableBackground = Palette.holeBg,
        ),
        typography = DefaultMarkdownTypography(
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
        ),
    )
}
