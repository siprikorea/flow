package flow.ui.io

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.Workspace
import flow.model.DrawOp
import flow.model.Drawing
import flow.model.OutputColor
import flow.model.OutputEventKind
import flow.platform.Platform
import flow.ui.common.Txt
import flow.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Shows [data] using an installed view.
 *
 * The view runs in its own process and cannot reach this canvas, so it draws into one that records
 * the calls; what arrives here is that recording, replayed. Keeping it as calls rather than pixels
 * is what lets the text be laid out at this display's resolution and in the current theme.
 *
 * The view is told only how wide it may be. It draws as tall as the data needs and says so, and
 * the extra is scrolled here — so scrolling costs nothing and never waits on the extension.
 *
 * Clicks go the other way. A view names the parts of its drawing that can be acted on, so the
 * hit-testing happens here and only a click that actually landed on something crosses the pipe;
 * what comes back is the options to draw it with next, which [onOptions] is handed to keep.
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

    // measured once in this app's own monospace font and handed to the view, which has no other
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

        // Redrawn whenever any of what the view was given changes — including the width, since the
        // view lays out to it. Rounded first so a drag that resizes by fractions of a point does
        // not fire a request per frame.
        val widthKey = widthDp.toInt()
        LaunchedEffect(viewId, data, options, widthKey) {
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
                val scroll = rememberScrollState()
                // the images a drawing refers to are decoded once, not on every frame it is drawn
                val images = remember(shown) {
                    shown.ops.filterIsInstance<DrawOp.Image>().associateWith { decodePng(it.png) }
                }

                val scope = rememberCoroutineScope()
                val interactive = onOptions != null && shown.regions.isNotEmpty()
                val wantsHover = ws.outputInfo(viewId)?.wantsHover == true

                fun send(kind: String, position: Offset) {
                    val x = position.x / density
                    val y = position.y / density
                    val region = shown.regionAt(x, y)
                    // a click on nothing is still worth reporting for hover, but not for a tap: a
                    // view that named no region there has said it does not care
                    if (kind != OutputEventKind.HOVER && region == null) return
                    scope.launch {
                        val next = runCatching { Platform.outputEvent(viewId, kind, region?.id, x, y, options) }
                            .getOrDefault(options)
                        if (next != options) onOptions!!(next)
                    }
                }

                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Canvas(
                        Modifier.fillMaxWidth()
                            .height(maxOf(shown.contentHeight, viewportDp).dp)
                            .then(
                                if (!interactive) Modifier
                                else Modifier.pointerInput(shown, viewId, options) {
                                    detectTapGestures(
                                        onTap = { send(OutputEventKind.CLICK, it) },
                                        onDoubleTap = { send(OutputEventKind.DOUBLE_CLICK, it) },
                                    )
                                },
                            )
                            .then(
                                if (!interactive || !wantsHover) Modifier
                                else Modifier.pointerInput(shown, viewId, options) {
                                    // only a move that leaves one region for another is worth a
                                    // round trip; following the pointer pixel by pixel would be one
                                    // per frame, and a highlight only changes when the region does
                                    var last: String? = null
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val at = event.changes.firstOrNull()?.position ?: continue
                                            val here = shown.regionAt(at.x / density, at.y / density)?.id
                                            if (here != last) {
                                                last = here
                                                send(OutputEventKind.HOVER, at)
                                            }
                                        }
                                    }
                                },
                            ),
                    ) {
                        // only what the scroll has brought into view is worth drawing: a long hex
                        // dump is tens of thousands of calls, and all but a screenful are off-screen
                        val top = scroll.value / density
                        val bottom = top + viewportDp
                        shown.ops.forEach { op ->
                            if (visible(op, top, bottom)) replay(op, measurer, density, images)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Replays a finished drawing onto a canvas of its own.
 *
 * Split out from [OutputSurface] so a drawing can be put on screen without the process that made
 * it — which is what lets a change to how an output looks be rendered and looked at.
 */
@Composable
internal fun DrawingCanvas(drawing: Drawing, viewportDp: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val images = remember(drawing) {
        drawing.ops.filterIsInstance<DrawOp.Image>().associateWith { decodePng(it.png) }
    }
    Canvas(modifier.fillMaxWidth().height(maxOf(drawing.contentHeight, viewportDp).dp)) {
        drawing.ops.forEach { op -> replay(op, measurer, density, images) }
    }
}

/** Whether an op falls inside the scrolled window, in the output's own coordinates. */
private fun visible(op: DrawOp, top: Float, bottom: Float): Boolean {
    val (a, b) = when (op) {
        is DrawOp.Text -> op.y to op.y + op.size * 2f // generous: wrapping is the view's business
        is DrawOp.Rect -> op.y to op.y + op.h
        is DrawOp.Line -> minOf(op.y1, op.y2) to maxOf(op.y1, op.y2)
        is DrawOp.Image -> op.y to op.y + op.h
    }
    return b >= top && a <= bottom
}

private fun DrawScope.replay(
    op: DrawOp,
    measurer: TextMeasurer,
    density: Float,
    images: Map<DrawOp.Image, ImageBitmap?>,
) {
    fun px(v: Float) = v * density
    when (op) {
        is DrawOp.Text -> drawText(
            textMeasurer = measurer,
            text = op.text,
            topLeft = Offset(px(op.x), px(op.y)),
            style = TextStyle(
                color = colorOf(op.color),
                // sized in the same units as the coordinates — via dp, so the em height is what the
                // view asked for whatever the display's density or the system font scale
                fontSize = op.size.dp.toSp(),
                fontFamily = if (op.mono) FontFamily.Monospace else FontFamily.Default,
            ),
        )
        is DrawOp.Rect -> drawRect(
            color = colorOf(op.color),
            topLeft = Offset(px(op.x), px(op.y)),
            size = Size(px(op.w), px(op.h)),
            style = if (op.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(density),
        )
        is DrawOp.Line -> drawLine(
            color = colorOf(op.color),
            start = Offset(px(op.x1), px(op.y1)),
            end = Offset(px(op.x2), px(op.y2)),
            strokeWidth = px(op.stroke),
        )
        is DrawOp.Image -> images[op]?.let { bitmap ->
            drawImage(
                image = bitmap,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(px(op.x).toInt(), px(op.y).toInt()),
                dstSize = IntSize(px(op.w).toInt(), px(op.h).toInt()),
            )
        }
    }
}

/**
 * A view may name one of the theme's colours instead of choosing an ARGB value, which is how it
 * stays legible when the user switches between light and dark.
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
