package dataflow.ui.tools

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
import dataflow.core.DragModule
import dataflow.core.Workspace
import dataflow.model.CompDef
import dataflow.model.IO_DEFS
import dataflow.model.ModuleDef
import dataflow.model.REGISTRY
import dataflow.ui.common.Txt
import dataflow.ui.common.rememberHover
import dataflow.ui.theme.Palette

@Composable
internal fun ModulePalette(ws: Workspace) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader(ws.t("moduleList"))
        REGISTRY.filter { !it.plugin }.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.cat, it.ins.size, it.outs.size) }

        SectionHeader(ws.t("installedPlugins"), dot = Palette.catSource, top = 14.dp)
        REGISTRY.filter { it.plugin }.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.cat, it.ins.size, it.outs.size, plugin = true) }

        if (ws.installedModules.isNotEmpty()) {
            SectionHeader(ws.t("installedModules"), dot = Palette.catPlugin, top = 14.dp)
            ws.installedModules.forEach { m ->
                PaletteCard(ws, m.id, m.name, "pluginmod", m.inputs.size, m.outputs.size)
            }
        }

        SectionHeader(ws.t("ioSection"), dot = Palette.catIo, top = 14.dp)
        IO_DEFS.forEach { PaletteCard(ws, it.type, it.name[ws.lang] ?: it.type, it.cat, it.ins.size, it.outs.size) }

        SectionHeader(ws.t("componentsSection"), dot = Palette.catComponent, top = 14.dp)
        if (ws.components.isEmpty()) {
            Txt(ws.t("dragHint"), 11.sp, Palette.dimText, modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
        }
        ws.components.forEach { c: CompDef ->
            PaletteCard(ws, "comp:${c.file}", c.name, "component", c.ins.size, c.outs.size)
        }
    }
}

@Composable
private fun SectionHeader(text: String, dot: Color? = null, top: androidx.compose.ui.unit.Dp = 4.dp) {
    Row(
        Modifier.padding(start = 2.dp, top = top, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).background(dot, RoundedCornerShape(3.dp)))
        Txt(text.uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun PaletteCard(
    ws: Workspace,
    type: String,
    label: String,
    cat: String,
    ins: Int,
    outs: Int,
    plugin: Boolean = false,
) {
    val (hoverSrc, hovered) = rememberHover()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val borderColor = when {
        hovered -> Color(0xFF3D4557)
        plugin -> Palette.runFromBorder
        else -> Palette.border
    }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .hoverable(hoverSrc)
            .background(Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .pointerInput(type) {
                // 드래그 → ws.dragModule 갱신 → 릴리스 시 활성 문서에 드랍
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val glyph = when {
            type == "cin" -> "▸"
            type == "cout" -> "◼"
            type.startsWith("comp:") -> "◆"
            cat == "pluginmod" -> "⬢"
            else -> "●"
        }
        Txt(glyph, 12.sp, Palette.catColor(cat), weight = FontWeight.Bold)
        Txt(label, 12.5.sp, Palette.text, weight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        if (plugin) {
            Box(
                Modifier.border(1.dp, Palette.pluginBadgeBorder, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Txt("PLUGIN", 9.sp, Palette.catSource, weight = FontWeight.Bold) }
        }
        Txt("$ins→$outs", 10.5.sp, Palette.dimText, mono = true)
    }
}
