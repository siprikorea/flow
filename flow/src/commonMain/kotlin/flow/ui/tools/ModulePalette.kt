package flow.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.DragModule
import flow.core.Workspace
import flow.model.CompDef
import flow.model.IO_DEFS
import flow.model.REGISTRY
import flow.ui.common.KindBadge
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.common.rememberHover
import flow.ui.theme.Palette

@Composable
internal fun ModulePalette(ws: Workspace) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // order: input/output -> modules -> components. Each category has its own
        // color; items under it share that color. Non-built-in items get an EXT mark.
        Section(ws, "io", ws.t("ioSection"), Palette.catIo) {
            IO_DEFS.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, Palette.catIo, it.ins.size, it.outs.size) }
        }
        Section(ws, "modules", ws.t("moduleList"), Palette.catTransform) {
            REGISTRY.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, Palette.catTransform, it.ins.size, it.outs.size) }
            ws.installedModules.forEach { m ->
                PaletteCard(ws, m.id, m.name, Palette.catTransform, m.inputs.size, m.outputs.size, ext = true)
            }
        }
        Section(ws, "components", ws.t("componentsSection"), Palette.catComponent) {
            if (ws.components.isEmpty()) {
                Txt(ws.t("dragHint"), 11.sp, Palette.dimText, modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
            }
            ws.components.forEach { c: CompDef ->
                PaletteCard(ws, "comp:${c.file}", c.name, Palette.catComponent, c.ins.size, c.outs.size, ext = c.installed)
            }
        }
    }
}

// Collapsible category section; the open/closed state is kept in the workspace.
@Composable
private fun Section(ws: Workspace, key: String, title: String, dot: Color? = null, content: @Composable () -> Unit) {
    val expanded = ws.isSectionOpen(key)
    val (hoverSrc, hovered) = rememberHover()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .hoverable(hoverSrc)
                .background(if (hovered) Palette.hoverBg else Color.Transparent, RoundedCornerShape(5.dp))
                .plainClick { ws.toggleSection(key) }
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // expanded = filled/bright, collapsed = dim → clear visual state
            Txt(if (expanded) "▾" else "▸", 15.sp, if (expanded) Palette.accent else Palette.subText, weight = FontWeight.Bold)
            if (dot != null) Box(Modifier.size(6.dp).background(dot, RoundedCornerShape(3.dp)))
            Txt(title.uppercase(), 11.sp, if (expanded) Palette.text else Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (expanded) {
            // a little gap between the header and its items
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
    }
}

@Composable
private fun PaletteCard(
    ws: Workspace,
    type: String,
    label: String,
    badgeColor: Color, // category color (same for all items in the category)
    ins: Int,
    outs: Int,
    ext: Boolean = false, // not built-in (installed) -> shown with an EXT mark
) {
    val (hoverSrc, hovered) = rememberHover()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val borderColor = if (hovered) Color(0xFF3D4557) else Palette.border
    // badge letter by kind (I/O/M/C); color comes from the category
    val letter = when {
        type == "cin" -> "I"
        type == "cout" -> "O"
        type.startsWith("comp:") -> "C"
        else -> "M"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .hoverable(hoverSrc)
            .background(Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .pointerInput(type) {
                // drag -> update ws.dragModule -> drop onto the active document on release
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    drag(down.id) { ch ->
                        ws.dragModule = DragModule(type, origin + ch.position)
                        ch.consume()
                    }
                    ws.active?.dropModule()
                }
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        KindBadge(letter, badgeColor, boxSize = 14.dp)
        Txt(label, 12.5.sp, Palette.text, weight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        if (ext) {
            Box(
                Modifier.border(1.dp, Palette.catPlugin.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Txt("EXT", 9.sp, Palette.catPlugin, weight = FontWeight.Bold) }
        }
        Txt("$ins→$outs", 10.5.sp, Palette.dimText, mono = true)
    }
}
