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
import androidx.compose.ui.graphics.Path
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
import flow.model.indexOfPort
import flow.model.isComp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import flow.ui.common.LucideIcon
import flow.ui.common.Txt
import flow.ui.common.moveCursorIcon
import flow.ui.common.rememberHover
import flow.ui.theme.FlowType
import flow.ui.theme.Mono
import flow.ui.theme.Palette
// aliased: this file already has androidx.compose.ui.geometry.Size for canvas draw sizes
import flow.ui.theme.Size as FlowSize

@Composable
internal fun NodeView(state: EditorState, node: flow.model.Node, timeMs: Long) {
    val density = state.density
    val selected = node.id in state.selNodes
    val comp = isComp(node.type)
    // validation error (unconnected ports) — only flagged after an open/save validation
    val validationError = if (state.showValidation) state.nodeConnectionError(node) else null
    // a module that threw while processing (bad key/IV size, etc.) on the last run — click the
    // node to see the full message in the properties panel
    val processError = state.nodeErrors[node.id]
    // The border says what state the node is in — running, done, failed — so selection is not shown
    // there: a selected node that has just failed still has to read as failed. It gets a ring drawn
    // outside it instead, which is visible whatever the border is doing.
    val borderColor = when {
        processError != null -> Palette.danger
        node.status == "running" -> Palette.accent
        node.status == "done" -> Palette.success
        node.status == "error" -> Palette.danger
        validationError != null -> Palette.danger
        else -> Palette.border
    }
    val statusText = when (node.status) {
        "running" -> state.t("stRunning")
        "done" -> state.t("stDone")
        "error" -> state.t("stNoInput")
        else -> null
    }
    val statusColor = when (node.status) {
        "running" -> Palette.accent
        "done" -> Palette.success
        else -> Palette.danger
    }
    // bottom label: a processing error takes priority, then run status, then the validation error
    val bottomMsg = if (processError != null) state.t("stError") else statusText ?: validationError
    val bottomColor = if (processError != null) Palette.danger else if (statusText != null) statusColor else Palette.danger
    // kind icon (top-left): the two ends of a flow read as different things — input/output get
    // their own glyph and colour; every other kind (built-in, installed, component) is a processor
    val kindIcon = when {
        node.type == "cin" -> Lucide.LogIn
        node.type == "cout" -> Lucide.LogOut
        else -> Lucide.Cpu
    }
    val kindColor = when {
        node.type == "cin" -> Palette.catInput
        node.type == "cout" -> Palette.catOutput
        else -> Palette.catProcessor
    }

    Box(
        Modifier
            .offset(node.x.dp, node.y.dp)
            .size(node.w.dp, node.h.dp)
            .drawBehind {
                // Selection is a 3dp accent-20% ring outside the node, not on it — the border says
                // what state the node is in (running/done/failed) and stays readable as whichever
                // of those it is; the ring is a different, separate thing outside it (CLAUDE.md §5).
                if (selected) {
                    val ring = 3f * density
                    drawRoundRect(
                        color = Palette.accent.copy(alpha = 0.20f),
                        topLeft = Offset(-ring, -ring),
                        size = Size(size.width + ring * 2, size.height + ring * 2),
                        cornerRadius = CornerRadius(9f * density + ring),
                        style = Stroke(ring),
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
            // The body colour never changes for selection — only the border and the ring outside
            // it do (CLAUDE.md §5: "본문 배경은 절대 바꾸지 않는다"). A category or run-state wash
            // over the whole card was the single most visible thing wrong with the old node.
            .background(Palette.panel, RoundedCornerShape(9.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(9.dp))
            .pointerHoverIcon(moveCursorIcon())
            .pointerInput(node.id, node.type) {
                // whole node is draggable (ports consume their own events):
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
        // header: drag to move. Background is always `raised` — flat grey, no per-kind tint — the
        // category shows on the icon alone (CLAUDE.md §5: "카테고리 색은 아이콘에만. 헤더 전체 채색 금지")
        Row(
            Modifier
                .fillMaxWidth()
                .height(FlowSize.nodeHeader)
                .background(Palette.raised, RoundedCornerShape(topStart = 7.5.dp, topEnd = 7.5.dp))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(kindIcon, kindColor, FlowSize.icon)
            Spacer(Modifier.width(6.dp))
            Txt(node.label, 12.sp, Palette.textPrimary, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            // status dot (6dp): nothing while idle, a slow pulse while running, a flat colour once
            // the run has an outcome — CLAUDE.md §5
            when (node.status) {
                "running" -> {
                    val p = (timeMs % 1200L) / 1200f
                    val alpha = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(p * kotlin.math.PI)).toFloat()
                    Box(Modifier.size(6.dp).background(Palette.accent.copy(alpha = alpha), CircleShape))
                }
                "done" -> Box(Modifier.size(6.dp).background(Palette.success, CircleShape))
                "error" -> Box(Modifier.size(6.dp).background(Palette.danger, CircleShape))
            }
        }

        // status/validation label (bottom): ellipsized to fit, full text on hover
        bottomMsg?.let {
            Box(Modifier.matchParentSize().padding(bottom = 6.dp), contentAlignment = Alignment.BottomCenter) {
                NodeStatusLabel(it, bottomColor)
            }
        }

        // ports are drawn in a separate pass (NodePortsView), called right after this node in
        // CanvasView — above this node's own body, but still in the same stacking order as the
        // nodes themselves, so an overlapping node's ports don't float above it

        // No resize handle: the guide drops manual node resize entirely (CLAUDE.md §5), so the
        // node's own drag no longer has a corner affordance to share the corner with.
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
                ) { Txt(msg, 11.sp, Palette.textPrimary) }
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
            // The hit/position box keeps the existing PORT_SIZE footprint (so the wire endpoint
            // math in Geometry.kt — shared with the edge-drawing code — doesn't move); the guide's
            // 10dp circle draws smaller than that, centred inside it.
            .offset(if (kind == "in") PORT_INSET.dp else (node.w - PORT_SIZE - PORT_INSET).dp, (cy - PORT_SIZE / 2f).dp)
            .size(PORT_SIZE.dp)
            .hoverable(hoverSrc)
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
            },
        contentAlignment = Alignment.Center,
    ) {
        // 10dp, 1.5dp stroke: empty (panel fill + tertiary ring) unconnected, solid accent
        // connected — CLAUDE.md §5. Hovering an open port previews the accent it would take.
        Box(
            Modifier
                .size(10.dp)
                .background(if (connected) Palette.accent else Palette.panel, CircleShape)
                .border(
                    1.5.dp,
                    when {
                        connected -> Palette.accent
                        flagged -> Palette.danger
                        hovered -> Palette.accent
                        else -> Palette.textTertiary
                    },
                    CircleShape,
                )
        )
    }
    // the port's name, beside the circle rather than under it — the circle sits inside the node
    // now, so the room the label used to have is where the circle is
    Box(
        Modifier
            .offset(0.dp, (cy - 6f).dp)
            .width(node.w.dp)
            .padding(horizontal = (PORT_INSET + PORT_SIZE + 5f).dp)
    ) {
        Txt(
            name, FlowType.mono, if (flagged) Palette.danger else Palette.textSecondary,
            modifier = Modifier.align(if (kind == "in") Alignment.CenterStart else Alignment.CenterEnd),
        )
    }
}
