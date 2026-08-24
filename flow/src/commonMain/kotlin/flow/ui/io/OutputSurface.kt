package flow.ui.io

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.model.DrawOp
import flow.model.Drawing
import flow.model.OutputColor
import flow.model.OutputEventKind
import flow.model.Region
import flow.platform.Platform
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Shows [data] using an installed output.
 *
 * The output runs in its own process and cannot reach this screen, so it draws into a canvas that
 * records the calls; what arrives here is that recording, and each call becomes a composable of its
 * own. Nothing of the extension is composed — only what it said it wanted — which is what keeps the
 * UI as separate as the process is.
 *
 * Realising the recording as composables rather than as one canvas is what makes the text
 * selectable and the clickable parts hover: they are real Text and real clickable boxes, laid out
 * where the output put them.
 *
 * The output is told only how wide it may be. It draws as tall as the data needs and says so, and
 * the extra is scrolled here — so scrolling costs nothing and never waits on the extension.
 */
@Composable
fun OutputSurface(
    ws: Workspace,
    viewId: String,
    data: ByteArray,
    options: Map<String, String>,
    modifier: Modifier = Modifier,
    onOptions: ((Map<String, String>) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val densityScope = LocalDensity.current
    val density = densityScope.density

    // measured once in this app's own monospace font and handed to the output, which has no other
    // way to know how many columns fit in the width it is given
    val monoCharWidth = remember(measurer, densityScope) {
        // measured at a large size and scaled down, so rounding in the layout costs two decimals
        // rather than a whole pixel per character
        val em = 100f
        val probe = measurer.measure(
            "0",
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = with(densityScope) { em.dp.toSp() }),
        )
        probe.size.width / (em * density)
    }

    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value
        val viewportDp = maxHeight.value

        var drawing by remember { mutableStateOf<Drawing?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }

        // Redrawn whenever any of what the output was given changes — including the width, since it
        // lays out to it. Rounded first so a drag that resizes by fractions of a point does not
        // fire a request per frame. The revision is in here so that replacing the extension redraws
        // what it had already drawn; nothing else about the request changes when an update lands.
        val widthKey = widthDp.toInt()
        LaunchedEffect(viewId, data, options, widthKey, ws.extensionsRevision) {
            failure = null
            runCatching { Platform.drawOutput(viewId, data, options, widthKey.toFloat(), monoCharWidth) }
                .onSuccess { drawing = it; failure = null }
                .onFailure { drawing = null; failure = it.message ?: ws.t("viewFailed") }
        }

        val shown = drawing
        when {
            failure != null -> Box(Modifier.fillMaxSize().padding(12.dp)) {
                Txt(failure!!, 12.sp, Palette.errorSoft)
            }
            shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt(ws.t("viewDrawing"), 12.sp, Palette.faintText)
            }
            else -> {
                val scope = rememberCoroutineScope()
                val wantsHover = ws.outputInfo(viewId)?.wantsHover == true

                fun send(kind: String, region: Region?) {
                    scope.launch {
                        val next = runCatching {
                            Platform.outputEvent(viewId, kind, region?.id, region?.x ?: 0f, region?.y ?: 0f, options)
                        }.getOrDefault(options)
                        if (next != options) onOptions?.invoke(next)
                    }
                }

                Drawing(
                    drawing = shown,
                    viewportDp = viewportDp,
                    onClick = if (onOptions == null) null else ({ send(OutputEventKind.CLICK, it) }),
                    onHover = if (onOptions == null || !wantsHover) null
                    else ({ send(OutputEventKind.HOVER, it) }),
                )
            }
        }
    }
}

/**
 * Lays a drawing out as composables, at the positions the output gave them.
 *
 * Everything is placed absolutely inside one tall box, which is what a recording describes. The
 * whole of it sits in a [SelectionContainer], so a drag across a hex dump selects it and copies as
 * the text it is.
 */
@Composable
internal fun Drawing(
    drawing: Drawing,
    viewportDp: Float,
    onClick: ((Region) -> Unit)? = null,
    onHover: ((Region) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    // the images a drawing refers to are decoded once, not on every frame it is shown
    val images = remember(drawing) {
        drawing.ops.filterIsInstance<DrawOp.Image>().associateWith { decodePng(it.png) }
    }

    Box(modifier.fillMaxSize().verticalScroll(scroll)) {
        Box(Modifier.height(maxOf(drawing.contentHeight, viewportDp).dp)) {
            // Only what the scroll has brought into view is composed: a long hex dump is tens of
            // thousands of calls, and all but a screenful are off-screen. Everything else would be
            // a composable that is laid out, measured and never seen.
            val top = scroll.value / density
            val bottom = top + viewportDp

            SelectionContainer {
                Box(Modifier.fillMaxSize()) {
                    drawing.ops.forEach { op -> if (visible(op, top, bottom)) Op(op, images) }
                }
            }

            // the clickable parts go over the top, in the order they were declared, so the last one
            // declared is the one on top — which is the rule the output was told
            drawing.regions.forEach { region ->
                if (region.y + region.h >= top && region.y <= bottom) {
                    RegionBox(region, onClick, onHover)
                }
            }
        }
    }
}

/** One recorded call, as the composable that shows it. */
@Composable
private fun Op(op: DrawOp, images: Map<DrawOp.Image, ImageBitmap?>) {
    when (op) {
        is DrawOp.Text -> Txt(
            op.text,
            op.size.sp,
            colorOf(op.color),
            mono = op.mono,
            maxLines = 1,
            modifier = Modifier.offset(op.x.dp, op.y.dp),
        )
        is DrawOp.Rect -> Box(
            Modifier
                .offset(op.x.dp, op.y.dp)
                .size(op.w.dp, op.h.dp)
                .then(
                    if (op.filled) Modifier.background(colorOf(op.color))
                    else Modifier.border(1.dp, colorOf(op.color)),
                ),
        )
        is DrawOp.Line -> {
            // a line the output drew is horizontal or vertical in every use there has been; a box
            // one pixel across is what that is, and it composes like everything else here
            val horizontal = kotlin.math.abs(op.y2 - op.y1) < kotlin.math.abs(op.x2 - op.x1)
            Box(
                Modifier
                    .offset(minOf(op.x1, op.x2).dp, minOf(op.y1, op.y2).dp)
                    .then(
                        if (horizontal) {
                            Modifier.width(kotlin.math.abs(op.x2 - op.x1).dp).height(op.stroke.dp)
                        } else {
                            Modifier.width(op.stroke.dp).height(kotlin.math.abs(op.y2 - op.y1).dp)
                        },
                    )
                    .background(colorOf(op.color)),
            )
        }
        is DrawOp.Image -> images[op]?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.offset(op.x.dp, op.y.dp).size(op.w.dp, op.h.dp),
            )
        }
    }
}

/** A part the output said can be acted on: a real clickable box, so it hovers like anything else. */
@Composable
private fun RegionBox(region: Region, onClick: ((Region) -> Unit)?, onHover: ((Region) -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered, region.id) { if (hovered) onHover?.invoke(region) }

    Box(
        Modifier
            .offset(region.x.dp, region.y.dp)
            .size(region.w.dp, region.h.dp)
            .hoverable(source)
            .then(if (onClick == null) Modifier else Modifier.plainClick { onClick(region) })
            .then(
                // a hint that it can be pressed, without the output having to say so
                if (hovered && onClick != null) Modifier.background(Palette.hoverBg.copy(alpha = 0.4f))
                else Modifier,
            ),
    )
}

/** Whether an op falls inside the scrolled window, in the output's own coordinates. */
private fun visible(op: DrawOp, top: Float, bottom: Float): Boolean {
    val (a, b) = when (op) {
        is DrawOp.Text -> op.y to op.y + op.size * 2f // generous: wrapping is the output's business
        is DrawOp.Rect -> op.y to op.y + op.h
        is DrawOp.Line -> minOf(op.y1, op.y2) to maxOf(op.y1, op.y2)
        is DrawOp.Image -> op.y to op.y + op.h
    }
    return b >= top && a <= bottom
}

/**
 * An output may name one of the theme's colours instead of choosing one, which is how it stays
 * legible when the user switches between light and dark.
 */
private fun colorOf(argb: Int): Color = when (argb) {
    OutputColor.DEFAULT_TEXT -> Palette.text
    OutputColor.MUTED_TEXT -> Palette.dimText
    OutputColor.ACCENT -> Palette.accent
    OutputColor.ERROR -> Palette.errorSoft
    OutputColor.SURFACE -> Palette.holeBg
    OutputColor.BORDER -> Palette.border
    OutputColor.SELECTION -> Palette.accent.copy(alpha = 0.22f)
    else -> Color(argb)
}
