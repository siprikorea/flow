package flow.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import flow.ui.theme.FlowType
import flow.ui.theme.FlowTextStyle
import flow.ui.theme.Mono
import flow.ui.theme.Palette

/**
 * Claude's replies come back as Markdown — headings, lists, fenced code, **emphasis** — and until
 * a plain content-parsing setup printed that source text verbatim, then flashed empty on every
 * streamed chunk (parsing on a coroutine, one frame with nothing parsed yet each time). This uses
 * StreamingMarkdownState instead, built for exactly this case: [content] is a growing full string
 * on every recomposition, and only the new suffix since last time is appended, so the already-
 * rendered part is never torn down and reparsed from scratch as more arrives — no flash, and far
 * less of the re-render-as-syntax-completes flicker a full reparse causes (`**hel` rendering
 * plainly until the closing `**` arrives, at which point a whole-document reparse restyles
 * everything before it in one frame).
 *
 * Colors and type sizes come from Palette/FlowType rather than the library's own Material-flavored
 * defaults — deliberately not the -m2/-m3 modules, which pull in a Material theme this app has no
 * other use for — so a reply reads like the rest of Flow's chrome rather than a foreign component.
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

    val streamState = rememberStreamingMarkdownState()
    // What's already been appended, so only the new tail goes in on the next chunk rather than
    // the whole string again (append() only ever adds). A message's text otherwise only grows —
    // save_flow's rare final .trim() can make it a few characters *shorter* than the last chunk;
    // that one case is left alone rather than reset, since re-feeding the whole document from
    // scratch would defeat the point of appending in the first place for the sake of trailing
    // whitespace nobody would see anyway.
    var applied by remember { mutableStateOf("") }
    LaunchedEffect(content) {
        when {
            content.length > applied.length && content.startsWith(applied) -> {
                streamState.append(content.substring(applied.length))
                applied = content
            }
            // first content this composable instance has seen (a new message, or an old one
            // scrolled into view for the first time) — nothing to diff against yet
            applied.isEmpty() && content.isNotEmpty() -> {
                streamState.append(content)
                applied = content
            }
        }
    }

    Markdown(
        streamingMarkdownState = streamState,
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
