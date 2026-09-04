package flow.ui.tools

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import flow.core.DragModule
import flow.core.Workspace
import flow.model.CompDef
import flow.model.IO_DEFS
import flow.model.REGISTRY
import flow.ui.common.FlowListRow
import flow.ui.common.FlowSectionHeader
import flow.ui.common.Txt
import flow.ui.theme.FlowType
import flow.ui.theme.Palette

@Composable
internal fun ModulePalette(ws: Workspace) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        // The two ends of a flow get a section each — they are what a flow is built between, and
        // looking for the way in among a list of processors is looking in the wrong place.
        Section(ws, "inputs", ws.t("inputSection")) {
            IO_DEFS.filter { it.type == "cin" }.forEach {
                PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.ins.size, it.outs.size)
            }
        }
        Section(ws, "outputs", ws.t("outputSection")) {
            IO_DEFS.filter { it.type == "cout" }.forEach {
                PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.ins.size, it.outs.size)
            }
        }
        // Built-ins, installed extensions and the user's own components all read as one kind —
        // "processor" — per the design guide's 3-colour category scheme; they stay split into two
        // sections because that grouping (mine vs. shipped) is still useful to find things by.
        Section(ws, "modules", ws.t("processorSection")) {
            REGISTRY.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.ins.size, it.outs.size) }
            ws.installedModules.forEach { m -> PaletteCard(ws, m.id, m.name, m.inputs.size, m.outputs.size) }
        }
        Section(ws, "components", ws.t("componentsSection")) {
            if (ws.components.isEmpty()) {
                Txt(ws.t("dragHint"), FlowType.small, Palette.textTertiary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            ws.components.forEach { c: CompDef -> PaletteCard(ws, "comp:${c.file}", c.name, c.ins.size, c.outs.size) }
        }
    }
}

// Collapsible category section; the open/closed state is kept in the workspace.
@Composable
private fun Section(ws: Workspace, key: String, title: String, content: @Composable () -> Unit) {
    val expanded = ws.isSectionOpen(key)
    FlowSectionHeader(title, expanded, onToggle = { ws.toggleSection(key) })
    if (expanded) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun PaletteCard(
    ws: Workspace,
    type: String,
    label: String,
    ins: Int,
    outs: Int,
    // seeds a param on the dropped node, so an input or output card lands as a boundary node
    // already set to that way of writing or showing its value
    param: Pair<String, String>? = null,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    // category icon + colour by kind — input/output boundary get their own glyph, everything else
    // (built-in processor, installed module, component) reads as a processor
    val icon = when {
        type == "cin" -> Lucide.LogIn
        type == "cout" -> Lucide.LogOut
        else -> Lucide.Cpu
    }
    val color = when {
        type == "cin" -> Palette.catInput
        type == "cout" -> Palette.catOutput
        else -> Palette.catProcessor
    }
    FlowListRow(
        label = label,
        icon = icon,
        iconTint = color,
        trailing = { Txt("$ins→$outs", FlowType.mono, Palette.textTertiary) },
        modifier = Modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(type, param, label) {
                // drag -> update ws.dragModule -> drop onto the active document on release
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // with no canvas showing there is nowhere for the module to land, so the drag
                    // never starts rather than leaving a ghost with nothing to drop onto
                    if (!ws.canvasOpen) return@awaitEachGesture
                    try {
                        drag(down.id) { ch ->
                            ws.dragModule = DragModule(
                                type, origin + ch.position, param?.let { mapOf(it) }.orEmpty(), label,
                            )
                            ch.consume()
                        }
                        ws.active?.dropModule()
                    } finally {
                        // A gesture can also end by being cancelled — the panel switching, the
                        // list re-sorting this card out from under itself — and then the lines
                        // above never run. The ghost must not outlive the gesture either way.
                        ws.dragModule = null
                    }
                }
            },
        onClick = null,
    )
}
