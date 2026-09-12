package flow.ui.props

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import flow.core.EditorState
import flow.model.Edge
import flow.model.Node
import flow.model.OptDef
import flow.model.OptType
import flow.model.compFile
import flow.model.findDef
import flow.model.isComp
import flow.ui.common.DtxField
import flow.ui.common.FlowButton
import flow.ui.common.FlowButtonVariant
import flow.ui.common.FlowDangerIconButton
import flow.ui.common.LucideIcon
import flow.ui.common.ResizeDivider
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.FlowType
import flow.ui.theme.Palette
import flow.ui.theme.Size

@Composable
fun PropsPanel(state: EditorState) {
    // text edit: snapshot on focus, push once on blur
    var focusSnap by remember { mutableStateOf<String?>(null) }
    val onFocusChange: (Boolean) -> Unit = { focused ->
        if (focused) {
            state.textEditing = true
            focusSnap = state.snapshot()
        } else {
            state.textEditing = false
            focusSnap?.let { if (it != state.snapshot()) state.pushHistory(it) }
            focusSnap = null
        }
    }

    Row {
        ResizeDivider(Palette.frameBorder) {
            // divider is on the panel's left edge, so dragging right shrinks it
            state.ws.propsWidth = (state.ws.propsWidth - it).coerceIn(200f, 560f)
        }
        Column(
            Modifier
                .width(state.ws.propsWidth.dp)
                .fillMaxHeight()
                .background(Palette.panelBg)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val singleNode = if (state.selNodes.size == 1 && state.selEdges.isEmpty())
                state.nodeById(state.selNodes.first()) else null
            val singleEdge = if (state.selEdges.size == 1 && state.selNodes.isEmpty())
                state.edges.find { it.id == state.selEdges.first() } else null
            val total = state.selNodes.size + state.selEdges.size
            when {
                singleNode != null -> NodeProps(state, singleNode, onFocusChange)
                singleEdge != null -> EdgeProps(state, singleEdge)
                total > 1 -> MultiProps(state, onFocusChange)
                else -> Txt(
                    state.t("propsEmpty"), 12.sp, Palette.textTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Txt(text.uppercase(), 11.sp, Palette.textTertiary, weight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun NodeProps(state: EditorState, node: Node, onFocusChange: (Boolean) -> Unit) {
    val comp = isComp(node.type)
    val def = findDef(node.type)
    val title = when {
        comp -> node.label
        def != null -> def.name[state.lang] ?: node.type
        else -> node.type
    }
    // same icon + colour as the node's own header on the canvas — the inspector and the
    // selected node read as the same thing (CLAUDE.md §5)
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

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LucideIcon(kindIcon, kindColor, Size.icon)
        Txt(title, FlowType.bodyStrong, Palette.textPrimary)
    }

    // the module's exception message from the last run, if this node failed to process
    state.nodeErrors[node.id]?.let { message ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(state.t("errorLabel"))
            Box(
                Modifier.fillMaxWidth()
                    .background(Palette.danger.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.danger.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(10.dp),
            ) { Txt(message, 12.sp, Palette.danger) }
        }
    }

    if (comp) {
        FlowButton(state.t("openComponent"), FlowButtonVariant.Secondary) {
            state.ws.openFile(compFile(node.type))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(state.t("labelName"))
        DtxField(node.label, { v -> state.updateNode(node.id) { it.copy(label = v) } }, onFocusChange = onFocusChange)
    }

    // node ids are managed internally (unique within the component) and not shown

    // an installed extension module (not a component, not a built-in cin/cout) — its ports are
    // fixed by the extension, not user-editable, though they may vary with its option values
    val isModule = !comp && def == null && state.ws.moduleInfo(node.type) != null

    // module options: typed editors (text / number / select) for the module's
    // predefined option names. Built-ins come from the registry; extensions from
    // their ModuleInfo. Components have no options (excluded).
    val options = when {
        comp -> emptyList()
        def != null -> def.options
        else -> state.ws.moduleOptions(node.type, node.params)
    }
    if (options.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(state.t("labelOptions"))
            options.forEach { opt ->
                OptionEditor(opt, node.params[opt.name] ?: opt.default, onFocusChange) { v ->
                    if (isModule) state.setModuleOption(node.id, opt.name, v)
                    else state.updateNode(node.id) { it.copy(params = it.params + (opt.name to v)) }
                }
            }
        }
    } else if (!comp && def == null && node.params.isNotEmpty()) {
        // plugin modules have untyped params -> plain text fields
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionLabel(state.t("labelParams"))
            node.params.forEach { (key, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(key, 11.sp, Palette.textTertiary, mono = true, maxLines = 1, modifier = Modifier.width(76.dp))
                    DtxField(
                        value,
                        { v -> state.updateNode(node.id) { it.copy(params = it.params + (key to v)) } },
                        modifier = Modifier.weight(1f),
                        onFocusChange = onFocusChange,
                    )
                }
            }
        }
    }

    PortSection(state, node, "in", editable = !isModule, onFocusChange)
    PortSection(state, node, "out", editable = !isModule, onFocusChange)

    // one Primary per region (CLAUDE.md P4): run is the region's Primary action, delete is an
    // icon button behind a confirm popover, not a second full-width button competing with it.
    // "Start from here" only makes sense for a module/component — a cin has no incoming edge to
    // run from and a cout has nothing downstream, so neither gets the button, only delete.
    val runnable = node.type != "cin" && node.type != "cout"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (runnable) {
            FlowButton(state.t("runFromHere"), FlowButtonVariant.Primary, icon = Lucide.Play, modifier = Modifier.weight(1f)) {
                state.runFromSelection(node.id)
            }
        }
        FlowDangerIconButton(Lucide.Trash2, state.t("confirmDeleteModule"), state.t("cancel"), state.t("deleteModule")) {
            state.deleteNode(node.id)
        }
    }
}

// One option row: the option name + a typed editor (text / number / select).
@Composable
private fun OptionEditor(opt: OptDef, value: String, onFocusChange: (Boolean) -> Unit, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(opt.name, 11.sp, Palette.textTertiary, mono = true, maxLines = 1)
        when (opt.type) {
            OptType.SELECT -> OptionSelect(opt.choices, value, onChange)
            OptType.NUMBER -> DtxField(
                value,
                { v -> if (v.isEmpty() || v.matches(NUMERIC)) onChange(v) },
                mono = true, onFocusChange = onFocusChange,
            )
            OptType.TEXT -> DtxField(value, onChange, mono = true, onFocusChange = onFocusChange)
        }
    }
}

private val NUMERIC = Regex("^-?\\d*\\.?\\d*$")

// A compact dropdown for SELECT options.
@Composable
private fun OptionSelect(choices: List<String>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var boxWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.onGloballyPositioned { boxWidth = it.size.width }) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.raised, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .plainClick { open = !open }
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(value, 12.sp, Palette.textPrimary, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
            Txt(if (open) "▲" else "▼", 9.sp, Palette.textTertiary)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, 34),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        // match the select box width so the menu isn't full-screen wide
                        .width(with(density) { boxWidth.toDp() })
                        .background(Palette.overlay, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                        .padding(5.dp),
                ) {
                    choices.forEach { choice ->
                        val (src, hovered) = rememberHover()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hoverable(src)
                                .background(
                                    if (hovered) Palette.hoverOverlay else Color.Transparent,
                                    RoundedCornerShape(5.dp),
                                )
                                .plainClick { onChange(choice); open = false }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(choice, 12.sp, if (choice == value) Palette.accent else Palette.textPrimary, mono = true, modifier = Modifier.weight(1f))
                            if (choice == value) Txt("✓", 11.sp, Palette.accent)
                        }
                    }
                }
            }
        }
    }
}

// editable = true for cin/cout/component nodes (arbitrary user-defined ports). false for
// extension modules — their ports are fixed by the extension (see ModuleExtension.inputsFor/
// outputsFor), so this shows plain names only, no rename/add/remove.
@Composable
private fun PortSection(state: EditorState, node: Node, kind: String, editable: Boolean, onFocusChange: (Boolean) -> Unit) {
    val list = if (kind == "in") node.inputs else node.outputs
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionLabel(state.t(if (kind == "in") "labelInputs" else "labelOutputs"))
        list.forEachIndexed { i, port ->
            if (editable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DtxField(
                        port.name, { v -> state.renamePort(node.id, kind, i, v) },
                        modifier = Modifier.weight(1f), mono = true, fontSize = 11.5.sp,
                        onFocusChange = onFocusChange,
                    )
                    Txt(
                        "×", 13.sp, Palette.textTertiary,
                        modifier = Modifier.plainClick { state.removePort(node.id, kind, i) }.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            } else {
                Txt(port.name, 11.5.sp, Palette.textPrimary, mono = true, maxLines = 1)
            }
        }
        if (editable) {
            FlowButton(state.t("addPort"), FlowButtonVariant.Ghost, icon = Lucide.Plus, compact = true) {
                state.addPort(node.id, kind)
            }
        }
    }
}

// multi-selection: show only simultaneously-editable items (common params)
@Composable
private fun MultiProps(state: EditorState, onFocusChange: (Boolean) -> Unit) {
    val nodeCount = state.selNodes.size
    Txt(
        state.t("multiSelected").replace("{n}", (state.selNodes.size + state.selEdges.size).toString()),
        FlowType.bodyStrong, Palette.textPrimary,
    )

    val keys = state.commonParamKeys()
    if (nodeCount > 0 && keys.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionLabel(state.t("commonParams"))
            keys.forEach { key ->
                val values = state.nodes.filter { it.id in state.selNodes }.mapNotNull { it.params[key] }
                val common = if (values.distinct().size == 1) values.first() else ""
                var text by remember(key, common) { mutableStateOf(common) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(key, 11.sp, Palette.textSecondary, mono = true, maxLines = 1, modifier = Modifier.width(76.dp))
                    DtxField(
                        text,
                        { v -> text = v; state.setParamForSelected(key, v) },
                        modifier = Modifier.weight(1f),
                        onFocusChange = onFocusChange,
                    )
                }
            }
        }
    }

    FlowDangerIconButton(Lucide.Trash2, state.t("confirmDeleteSelected"), state.t("cancel"), state.t("deleteSelected")) {
        state.deleteSelection()
    }
}

@Composable
private fun EdgeProps(state: EditorState, edge: Edge) {
    Txt(state.t("edgeTitle"), FlowType.bodyStrong, Palette.textPrimary)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.raised, RoundedCornerShape(6.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Txt(
            "${edge.from.node} [${edge.from.port}] → ${edge.to.node} [${edge.to.port}]",
            11.5.sp, Palette.textSecondary, mono = true,
        )
    }
    FlowDangerIconButton(Lucide.Trash2, state.t("confirmDeleteEdge"), state.t("cancel"), state.t("deleteEdge")) {
        state.deleteEdge(edge.id)
    }
}
