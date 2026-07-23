package flow.ui.props

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.EditorState
import flow.model.Edge
import flow.model.Node
import flow.model.compFile
import flow.model.findDef
import flow.model.isComp
import flow.ui.common.DtxField
import flow.ui.common.ResizeDivider
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette

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
        ResizeDivider(Palette.panelBorder) {
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
                    state.t("propsEmpty"), 12.sp, Palette.faintText,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Txt(text.uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun PanelButton(text: String, borderColor: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .plainClick(onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 12.sp, textColor)
    }
}

@Composable
private fun NodeProps(state: EditorState, node: Node, onFocusChange: (Boolean) -> Unit) {
    val comp = isComp(node.type)
    val def = findDef(node.type)
    val cat = when {
        comp -> "component"
        def != null -> def.cat
        else -> "transform"
    }
    val title = when {
        comp -> node.label
        def != null -> def.name[state.lang] ?: node.type
        else -> node.type
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(Palette.catColor(cat), RoundedCornerShape(3.dp)))
        Txt(title, 13.5.sp, Palette.text, weight = FontWeight.SemiBold)
    }

    if (comp) {
        PanelButton(state.t("openComponent"), Palette.runFromBorder, Palette.accentHover) {
            state.ws.openFile(compFile(node.type))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(state.t("labelName"))
        DtxField(node.label, { v -> state.updateNode(node.id) { it.copy(label = v) } }, onFocusChange = onFocusChange)
    }

    // node ids are managed internally (unique within the component) and not shown

    if (node.params.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionLabel(state.t("labelParams"))
            node.params.forEach { (key, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(key, 11.sp, Palette.subText, mono = true, maxLines = 1, modifier = Modifier.width(76.dp))
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

    PortSection(state, node, "in", onFocusChange)
    PortSection(state, node, "out", onFocusChange)

    // available for both modules and components (any node can be a run start)
    PanelButton(state.t("runFromHere"), Palette.runFromBorder, Palette.accentHover) {
        state.runFromSelection(node.id)
    }
    PanelButton(state.t("deleteModule"), Palette.dangerBorder, Palette.errorSoft) {
        state.deleteNode(node.id)
    }
}

@Composable
private fun PortSection(state: EditorState, node: Node, kind: String, onFocusChange: (Boolean) -> Unit) {
    val list = if (kind == "in") node.inputs else node.outputs
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionLabel(state.t(if (kind == "in") "labelInputs" else "labelOutputs"))
        list.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DtxField(
                    name, { v -> state.renamePort(node.id, kind, i, v) },
                    modifier = Modifier.weight(1f), mono = true, fontSize = 11.5.sp,
                    onFocusChange = onFocusChange,
                )
                Txt(
                    "×", 13.sp, Palette.dimText,
                    modifier = Modifier.plainClick { state.removePort(node.id, kind, i) }.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Txt(
            state.t("addPort"), 11.5.sp, Palette.accent,
            modifier = Modifier.plainClick { state.addPort(node.id, kind) }.padding(vertical = 2.dp),
        )
    }
}

// multi-selection: show only simultaneously-editable items (common params)
@Composable
private fun MultiProps(state: EditorState, onFocusChange: (Boolean) -> Unit) {
    val nodeCount = state.selNodes.size
    Txt(
        state.t("multiSelected").replace("{n}", (state.selNodes.size + state.selEdges.size).toString()),
        13.5.sp, Palette.text, weight = FontWeight.SemiBold,
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
                    Txt(key, 11.sp, Palette.subText, mono = true, maxLines = 1, modifier = Modifier.width(76.dp))
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

    PanelButton(state.t("deleteSelected"), Palette.dangerBorder, Palette.errorSoft) {
        state.deleteSelection()
    }
}

@Composable
private fun EdgeProps(state: EditorState, edge: Edge) {
    Txt(state.t("edgeTitle"), 13.5.sp, Palette.text, weight = FontWeight.SemiBold)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.holeBg, RoundedCornerShape(6.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Txt(
            "${edge.from.node} [${edge.from.port}] → ${edge.to.node} [${edge.to.port}]",
            11.5.sp, Palette.menuText, mono = true,
        )
    }
    PanelButton(state.t("deleteEdge"), Palette.dangerBorder, Palette.errorSoft) {
        state.deleteEdge(edge.id)
    }
}
