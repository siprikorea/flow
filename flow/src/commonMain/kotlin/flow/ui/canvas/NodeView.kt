package flow.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.EditorState
import flow.core.PORT_INSET
import flow.core.PORT_SIZE
import flow.core.portPos
import flow.core.portY
import flow.core.snapF
import flow.model.compFile
import flow.model.findDef
import flow.model.indexOfPort
import flow.model.isComp
import flow.ui.common.KindBadge
import flow.ui.common.Txt
import flow.ui.common.moveCursorIcon
import flow.ui.common.rememberHover
import flow.ui.common.resizeCursorIcon
import flow.ui.theme.Mono
import flow.ui.theme.Palette
import kotlin.math.max

@Composable
internal fun NodeView(state: EditorState, node: flow.model.Node, timeMs: Long) {
    val density = state.density
    val selected = node.id in state.selNodes
    val comp = isComp(node.type)
    val pluginMod = !comp && state.ws.moduleInfo(node.type) != null
    val cat = when {
        comp -> "component"
        pluginMod -> "pluginmod"
        else -> findDef(node.type)?.cat ?: "transform"
    }
    // validation error (unconnected ports) — only flagged after an open/save validation
    val validationError = if (state.showValidation) state.nodeConnectionError(node) else null
    // a module that threw while processing (bad key/IV size, etc.) on the last run — click the
    // node to see the full message in the properties panel
    val processError = state.nodeErrors[node.id]
    // The border says what state the node is in — running, done, failed — so selection is not shown
    // there: a selected node that has just failed still has to read as failed. It gets a ring drawn
    // outside it instead, which is visible whatever the border is doing.
    val borderColor = when {
        processError != null -> Palette.error
        node.status == "running" -> Palette.accent
        node.status == "done" -> Palette.doneBorder
        node.status == "error" -> Palette.error
        validationError != null -> Palette.error
        else -> Palette.nodeBorder
    }
    val statusText = when (node.status) {
        "running" -> state.t("stRunning")
        "done" -> state.t("stDone")
        "error" -> state.t("stNoInput")
        else -> null
    }
    val statusColor = when (node.status) {
        "running" -> Palette.accentSoft
        "done" -> Palette.successText
        else -> Palette.errorSoft
    }
    // bottom label: a processing error takes priority, then run status, then the validation error
    val bottomMsg = if (processError != null) state.t("stError") else statusText ?: validationError
    val bottomColor = if (processError != null) Palette.errorSoft else if (statusText != null) statusColor else Palette.errorSoft
    // kind badge (top-left): I=input / O=output / P=processor / C=component
    val kindLetter = when {
        node.type == "cin" -> "I"
        node.type == "cout" -> "O"
        comp -> "C"
        else -> "P"
    }
    // the two ends of a flow read as different things, so they are coloured apart: input yellow,
    // output blue. Processors are purple (installed ones pink = their non-built-in mark).
    val kindColor = when {
        node.type == "cin" -> Palette.catIo
        node.type == "cout" -> Palette.catOut
        comp -> Palette.catComponent
        pluginMod -> Palette.catPlugin
        else -> Palette.catTransform
    }

    Box(
        Modifier
            .offset(node.x.dp, node.y.dp)
            .size(node.w.dp, node.h.dp)
            .drawBehind {
                // The selection ring sits outside the node, so it never competes with the border
                // for what the node's own state is — a selected node that has just failed still
                // reads as failed.
                //
                // It is a glow with a bright ring inside it rather than another crisp edge: a
                // running node already has a crisp accent border, and selection has to be a
                // different thing to look at, not the same thing in a slightly different blue.
                if (selected) {
                    // A ring outside the node, not on it: the border says what state the node is
                    // in — running, done, failed — and a selected node has to stay readable as
                    // whichever of those it is. The gap in the canvas colour is what keeps the two
                    // apart, so this reads as a halo rather than as a second border.
                    val moat = 2.5f * density
                    val ring = 5f * density
                    drawRoundRect(
                        color = Palette.canvasBg,
                        topLeft = Offset(-moat, -moat),
                        size = Size(size.width + moat * 2, size.height + moat * 2),
                        cornerRadius = CornerRadius(9f * density + moat),
                        style = Stroke(2.5f * density),
                    )
                    drawRoundRect(
                        // the lighter accent, so it is not the same blue as a running border
                        color = Palette.accentHover,
                        topLeft = Offset(-ring, -ring),
                        size = Size(size.width + ring * 2, size.height + ring * 2),
                        cornerRadius = CornerRadius(9f * density + ring),
                        style = Stroke(1.5f * density),
                    )
                }
                // nodepulse: expanding border ring (1.2s)
                if (node.status == "running") {
                    val p = (timeMs % 1200) / 1200f
                    val spread = p * 9f * density
                    drawRoundRect(
                        color = Palette.accent.copy(alpha = (1 - p) * 0.45f),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(9f * density + spread),
                        style = Stroke(2.5f * density),
                    )
                }
            }
            // a faint wash of the accent as well as the ring, so a selected node is still
            // distinguishable where the ring is clipped — at the edge of the canvas, or under
            // another node overlapping it
            .background(Palette.nodeBg, RoundedCornerShape(9.dp))
            .then(
                if (!selected) Modifier
                else Modifier.background(Palette.accent.copy(alpha = 0.16f), RoundedCornerShape(9.dp)),
            )
            .border(1.5.dp, borderColor, RoundedCornerShape(9.dp))
            .pointerHoverIcon(moveCursorIcon())
            .pointerInput(node.id, node.type) {
                // whole node is draggable (ports/resize handle consume their own events):
                // down = select (Cmd/Win toggles), drag = move all selected nodes,
                // click-click without movement = double-click (opens a component)
                var lastDown = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.ws.projectFocused = false
                    down.consume() // consume so the canvas can't clear the selection
                    val mods = currentEvent.keyboardModifiers
                    if (mods.isCtrlPressed || mods.isMetaPressed) state.toggleNode(node.id) // Cmd/Win = add to selection
                    else if (node.id !in state.selNodes) state.selectNode(node.id)
                    state.menu = null
                    val snap0 = state.snapshot()
                    val starts = state.nodes.filter { it.id in state.selNodes }
                        .associate { it.id to Offset(it.x, it.y) }
                    var acc = Offset.Zero
                    var moved = false
                    drag(down.id) { ch ->
                        acc += (ch.position - ch.previousPosition) / density
                        starts.forEach { (id, p) ->
                            val nx = snapF(p.x + acc.x)
                            val ny = snapF(p.y + acc.y)
                            if (nx != p.x || ny != p.y) moved = true
                            state.moveNode(id, nx, ny)
                        }
                        ch.consume()
                    }
                    if (moved) {
                        state.pushHistory(snap0) // push once on mouseup
                        lastDown = 0L
                    } else {
                        val now = down.uptimeMillis
                        val isDouble = now - lastDown <= viewConfiguration.doubleTapTimeoutMillis
                        val boundary = node.type == "cin" || node.type == "cout"
                        when {
                            // double-click a component opens its editor; a boundary opens its data editor
                            isDouble && comp -> { state.ws.openComponentFile(compFile(node.type)); lastDown = 0L }
                            isDouble && boundary -> { state.ws.openDataEditor(state, node.id); lastDown = 0L }
                            // double-click a module reveals its properties panel
                            isDouble -> { state.ws.showProps = true; lastDown = 0L }
                            else -> lastDown = now
                        }
                    }
                }
            }
    ) {
        // header 28px: drag to move. Tinted per group (io/component) for distinction
        val io = node.type == "cin" || node.type == "cout"
        val headerTint = when {
            comp -> Palette.compHeaderTint
            io -> Palette.ioHeaderTint
            pluginMod -> Palette.pluginHeaderTint
            else -> Palette.moduleHeaderTint
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Palette.nodeHeaderBg, RoundedCornerShape(topStart = 7.5.dp, topEnd = 7.5.dp))
                .background(headerTint, RoundedCornerShape(topStart = 7.5.dp, topEnd = 7.5.dp))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(kindLetter, kindColor)
            Spacer(Modifier.width(6.dp))
            Txt(node.label, 12.sp, Palette.text, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            if (node.status == "running") {
                // spinner: components/modules may take a while — show a rotating arc while running
                val angle = ((timeMs % 900L) / 900f) * 360f
                Canvas(Modifier.size(11.dp)) {
                    drawArc(
                        color = Palette.accent,
                        startAngle = angle,
                        sweepAngle = 260f,
                        useCenter = false,
                        style = Stroke(width = 2f * density, cap = StrokeCap.Round),
                    )
                }
            } else {
                Box(Modifier.size(8.dp).background(Palette.statusDot(node.status), CircleShape))
            }
        }

        // status/validation label (bottom): ellipsized to fit, full text on hover
        bottomMsg?.let {
            Box(Modifier.matchParentSize().padding(bottom = 6.dp), contentAlignment = Alignment.BottomCenter) {
                NodeStatusLabel(it, bottomColor)
            }
        }

        // ports are drawn in a separate overlay pass (NodePortsView) so their
        // opaque circles always sit above every node rectangle, never behind one

        // resize handle (bottom-right L, min 120×60)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset((-3).dp, (-3).dp)
                .size(13.dp)
                .pointerHoverIcon(resizeCursorIcon())
                .drawBehind {
                    val w = 2f * density
                    drawLine(Palette.resizeHandle, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    drawLine(Palette.resizeHandle, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                }
                .pointerInput(node.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val snap0 = state.snapshot()
                        val start = state.nodeById(node.id) ?: return@awaitEachGesture
                        var acc = Offset.Zero
                        var moved = false
                        drag(down.id) { ch ->
                            acc += (ch.position - ch.previousPosition) / density
                            val nw = max(120f, snapF(start.w + acc.x))
                            val nh = max(60f, snapF(start.h + acc.y))
                            if (nw != start.w || nh != start.h) moved = true
                            state.resizeNode(node.id, nw, nh)
                            ch.consume()
                        }
                        if (moved) state.pushHistory(snap0)
                    }
                }
        )
    }
}

// Bottom node label (run status / validation error). Ellipsized to one line so a
// long message never overflows the node box; when it's actually truncated, hovering
// shows the full text in a tooltip that disappears the moment the cursor leaves.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeStatusLabel(msg: String, color: Color) {
    var truncated by remember(msg) { mutableStateOf(false) }
    val label: @Composable () -> Unit = {
        BasicText(
            text = msg,
            style = TextStyle(
                color = color,
                fontSize = 10.sp,
                fontFamily = Mono,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.hasVisualOverflow },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
    if (truncated) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(Palette.dropdownBg, RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) { Txt(msg, 11.sp, Palette.text) }
            },
            delayMillis = 350,
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            content = label,
        )
    } else {
        label()
    }
}

// Overlay pass: renders every node's ports on top of all node rectangles so the
// opaque port circles are never occluded by (or show) a neighbouring rectangle.
@Composable
internal fun NodePortsView(state: EditorState, node: flow.model.Node) {
    Box(
        Modifier
            .offset(node.x.dp, node.y.dp)
            .size(node.w.dp, node.h.dp)
    ) {
        node.inputs.forEachIndexed { i, port -> PortView(state, node, "in", i, port.name) }
        node.outputs.forEachIndexed { i, port -> PortView(state, node, "out", i, port.name) }
    }
}

@Composable
private fun PortView(state: EditorState, node: flow.model.Node, kind: String, idx: Int, name: String) {
    val density = state.density
    val (hoverSrc, hovered) = rememberHover()
    val cy = portY(node, (if (kind == "in") node.inputs else node.outputs).size, idx) - node.y
    val connected = if (kind == "in")
        state.edges.any { it.to.node == node.id && it.to.port == name }
    else
        state.edges.any { it.from.node == node.id && it.from.port == name }
    // After an open/save validation the node border already reddens, but that only says the node
    // has a problem — marking the port itself is what tells the user which one is still to wire.
    val flagged = state.showValidation && !connected

    Box(
        Modifier
            // inside the box, not straddling its edge: a port belongs to the node, and one hanging
            // half outside reads as something stuck on rather than part of it
            .offset(if (kind == "in") PORT_INSET.dp else (node.w - PORT_SIZE - PORT_INSET).dp, (cy - 7.5f).dp)
            .size(PORT_SIZE.dp)
            .hoverable(hoverSrc)
            // solid (opaque) fill so edges/grid never show behind the circle
            .background(if (hovered) Palette.accent else Palette.nodeHeaderBg, CircleShape)
            .border(
                2.5.dp,
                when {
                    connected -> Palette.accent
                    flagged -> Palette.error
                    else -> Palette.portBorder
                },
                CircleShape,
            )
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(node.id, kind, name) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (kind == "out") {
                        val fresh = state.nodeById(node.id) ?: return@awaitEachGesture
                        var cur = portPos(fresh, "out", fresh.outputs.indexOfPort(name).coerceAtLeast(0))
                        state.wire = flow.core.Wire(node.id, name, cur)
                        drag(down.id) { ch ->
                            cur += (ch.position - ch.previousPosition) / density
                            state.wire = flow.core.Wire(node.id, name, cur)
                            ch.consume()
                        }
                        state.completeWire(cur)
                    } else {
                        drag(down.id) { it.consume() }
                    }
                }
            }
    )
    // the port's name, beside the circle rather than under it — the circle sits inside the node
    // now, so the room the label used to have is where the circle is
    Box(
        Modifier
            .offset(0.dp, (cy - 6f).dp)
            .width(node.w.dp)
            .padding(horizontal = (PORT_INSET + PORT_SIZE + 5f).dp)
    ) {
        Txt(
            name, 10.sp, if (flagged) Palette.errorSoft else Palette.subText, mono = true,
            modifier = Modifier.align(if (kind == "in") Alignment.CenterStart else Alignment.CenterEnd),
        )
    }
}
