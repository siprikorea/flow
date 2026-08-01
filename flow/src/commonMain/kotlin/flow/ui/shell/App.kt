package flow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.DragModule
import flow.core.Workspace
import flow.model.findDef
import flow.model.isComp
import flow.platform.Platform
import flow.ui.canvas.CanvasView
import flow.ui.data.DataEditor
import flow.ui.common.DtxField
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.props.PropsPanel
import flow.ui.theme.Palette
import flow.ui.tools.LeftToolWindow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
fun App(ws: Workspace, leadingInset: Dp = 0.dp, onTitleDoubleClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(ws, leadingInset, onTitleDoubleClick)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LeftToolWindow(ws) // activity bar always visible; panel folds via ws.showLeft
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorTabs(ws)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val dataTab = ws.activeData
                        val active = ws.active
                        when {
                            dataTab != null -> DataEditor(ws, dataTab)
                            active != null -> CanvasView(active, Modifier.fillMaxSize())
                            else -> EmptyEditor(ws)
                        }
                    }
                }
                val active = ws.active
                // the props panel belongs to the canvas; hide it on a data-editor tab
                if (ws.showProps && active != null && ws.activeData == null) PropsPanel(active)
            }
            StatusBar(ws)
        }
        ws.dragModule?.let { DragGhost(it) }
        ws.closeConfirm?.let { i ->
            val name = ws.docs.getOrNull(i)?.fileName?.removeSuffix(".flow") ?: ""
            SaveCloseDialog(ws, name)
        }
        ws.fileDeleteConfirm?.let { FileDeleteDialog(ws, it.size) }
        ws.installConfirm?.let { OverwriteDialog(ws, it.label) }
        ws.renameTarget?.let { RenameDialog(ws, it) }
        ws.saveError?.let { SaveErrorDialog(ws, it) }
    }

    // Save the session (open tabs + UI); file contents are saved explicitly (Ctrl+S / close-confirm)
    LaunchedEffect(Unit) {
        snapshotFlow { ws.sessionJson() }
            .debounce(350)
            .collect { Platform.saveSession(it) }
    }
}

@Composable
private fun SaveCloseDialog(ws: Workspace, name: String) {
    // scrim: block clicks behind
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { } // keep card clicks from propagating to the scrim
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("unsavedTitle"), 14.sp, Palette.text)
            Txt(ws.t("unsavedBody").replace("{name}", name), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelClose() }
                DialogButton(ws.t("dontSave"), Palette.dangerBorder, Palette.errorSoft) { ws.confirmDiscardAndClose() }
                DialogButton(ws.t("save"), Palette.accent, Palette.holeBg, filled = true) { ws.confirmSaveAndClose() }
            }
        }
    }
}

@Composable
private fun FileDeleteDialog(ws: Workspace, count: Int) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelDeleteFiles() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("deleteFilesTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            Txt(ws.t("deleteFilesBody").replace("{n}", count.toString()), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelDeleteFiles() }
                DialogButton(ws.t("delete"), Palette.error, Palette.holeBg, filled = true) { ws.confirmDeleteFiles() }
            }
        }
    }
}

@Composable
private fun RenameDialog(ws: Workspace, current: String) {
    var text by remember(current) { mutableStateOf(current.removeSuffix(".flow")) }
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelRename() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("renameTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            DtxField(text, { text = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelRename() }
                DialogButton(ws.t("rename"), Palette.accent, Palette.holeBg, filled = true) { ws.doRename(text) }
            }
        }
    }
}

@Composable
private fun OverwriteDialog(ws: Workspace, id: String) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.cancelInstall() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt(ws.t("overwriteTitle"), 14.sp, Palette.text, weight = FontWeight.SemiBold)
            Txt(ws.t("overwriteBody").replace("{id}", id), 12.5.sp, Palette.subText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(ws.t("cancel"), Palette.buttonBorder, Palette.menuText) { ws.cancelInstall() }
                DialogButton(ws.t("overwrite"), Palette.accent, Palette.holeBg, filled = true) { ws.confirmInstall() }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    var m = Modifier.then(
        if (filled) Modifier.background(color, RoundedCornerShape(6.dp))
        else Modifier.border(1.dp, color, RoundedCornerShape(6.dp))
    )
    Box(m.plainClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Txt(label, 12.sp, textColor)
    }
}

@Composable
private fun EmptyEditor(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.canvasBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(ws.t("noOpenFile"), 13.sp, Palette.faintText)
            Box(
                Modifier
                    .border(1.dp, Palette.runFromBorder, RoundedCornerShape(6.dp))
                    .plainClick { ws.newComponent() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Txt(ws.t("newFlow"), 12.sp, Palette.accentHover) }
        }
    }
}

@Composable
private fun DragGhost(d: DragModule) {
    val comp = isComp(d.type)
    val def = findDef(d.type)
    val label = when {
        comp -> d.type.removePrefix("comp:").removeSuffix(".flow").removeSuffix(".json")
        def != null -> def.name["en"] ?: def.type
        else -> d.type
    }
    val cat = if (comp) "component" else def?.cat ?: "transform"
    Box(
        Modifier
            .offset { IntOffset(d.pos.x.roundToInt() + 8, d.pos.y.roundToInt() + 8) }
            .background(Palette.dropdownBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, Palette.accent, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(Palette.catColor(cat), RoundedCornerShape(3.dp)))
            Txt(label, 12.sp, Palette.text)
        }
    }
}

// component validation error (e.g. missing/unconnected in/out)
@Composable
private fun SaveErrorDialog(ws: Workspace, message: String) {
    Box(
        Modifier.fillMaxSize().background(Palette.appBg.copy(alpha = 0.55f)).plainClick { ws.saveError = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Palette.dropdownBg, RoundedCornerShape(10.dp))
                .border(1.dp, Palette.dropdownBorder, RoundedCornerShape(10.dp))
                .plainClick { }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = if (ws.saveWarn) ws.t("saveWarnTitle") else ws.t("saveErrorTitle")
            val titleColor = if (ws.saveWarn) Palette.warnSoft else Palette.errorSoft
            Txt(title, 14.sp, titleColor, weight = FontWeight.SemiBold)
            Txt(message, 12.5.sp, Palette.subText)
            Row(modifier = Modifier.align(Alignment.End)) {
                DialogButton(ws.t("ok"), Palette.accent, Palette.holeBg, filled = true) { ws.saveError = null }
            }
        }
    }
}

// Extensions: install and manage modules/components (hosted in its own window)
@Composable
fun ExtensionsScreen(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(Palette.panelBg).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(ws.t("manageTitle"), 15.sp, Palette.text, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.border(1.dp, Palette.buttonBorder, RoundedCornerShape(6.dp))
                        .plainClick { ws.showManage = false }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Txt(ws.t("done"), 12.sp, Palette.menuText) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // install actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(ws.t("installPlugin"), Palette.accent, Palette.holeBg, filled = true) {
                        Platform.pickJar()?.let { ws.installJarFlow(it) }
                    }
                    DialogButton(ws.t("installComponent"), Palette.runFromBorder, Palette.accentHover) {
                        ws.installActiveComponent()
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(ws.t("installedModules").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
                    if (ws.installedModules.isEmpty()) Txt(ws.t("noneInstalled"), 12.sp, Palette.faintText)
                    ws.installedModules.forEach { m ->
                        ManageRow(ws, title = m.name, id = m.id, io = "${m.inputs.size}→${m.outputs.size}",
                            onUninstall = if (m.builtin) null else { { ws.uninstallModule(m.id) } })
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(ws.t("installedComponents").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold, letterSpacing = 1.sp)
                    val installed = ws.components.filter { it.installed }
                    if (installed.isEmpty()) Txt(ws.t("noneInstalled"), 12.sp, Palette.faintText)
                    installed.forEach { c ->
                        ManageRow(ws, title = c.name, id = c.file.removeSuffix(".json"), io = "${c.ins.size}→${c.outs.size}") {
                            ws.uninstallComponent(c.file.removeSuffix(".json"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageRow(ws: Workspace, title: String, id: String, io: String, onUninstall: (() -> Unit)?) {
    Row(
        Modifier
            .width(520.dp)
            .background(Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(title, 12.5.sp, Palette.text, weight = FontWeight.Medium)
        Txt(id, 11.sp, Palette.dimText, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
        Txt(io, 10.5.sp, Palette.dimText, mono = true)
        if (onUninstall != null) {
            Box(
                Modifier.border(1.dp, Palette.dangerBorder, RoundedCornerShape(5.dp))
                    .plainClick(onUninstall)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) { Txt(ws.t("uninstall"), 11.sp, Palette.errorSoft) }
        } else {
            Box(
                Modifier.border(1.dp, Palette.buttonBorder, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) { Txt(ws.t("builtin"), 11.sp, Palette.faintText) }
        }
    }
}

// Dedicated settings screen (logo menu > Settings) — opens in its own window, same as ExtensionsScreen
@Composable
fun SettingsScreen(ws: Workspace) {
    Box(Modifier.fillMaxSize().background(Palette.appBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(Palette.panelBg).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(ws.t("settingsTitle"), 15.sp, Palette.text, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.border(1.dp, Palette.buttonBorder, RoundedCornerShape(6.dp))
                        .plainClick { ws.showSettings = false }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Txt(ws.t("done"), 12.sp, Palette.menuText) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.panelBorder))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(ws.t("language").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold)
                SettingChoice("한국어", ws.lang == "ko") { ws.lang = "ko" }
                SettingChoice("English", ws.lang == "en") { ws.lang = "en" }

                Spacer(Modifier.height(10.dp))
                Txt(ws.t("animTime").uppercase(), 11.sp, Palette.subText, weight = FontWeight.Bold)
                val unit = ws.t("secUnit")
                listOf(0.25f, 0.5f, 1f, 2f).forEach { v ->
                    val num = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
                    val label = "$num$unit" + if (v == 1f) " (${ws.t("default")})" else ""
                    SettingChoice(label, ws.animSeconds == v) { ws.animSeconds = v }
                }
            }
        }
    }
}

@Composable
private fun SettingChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .width(280.dp)
            .background(if (selected) Palette.langActiveBg else Palette.dropdownBg, RoundedCornerShape(7.dp))
            .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(7.dp))
            .plainClick(onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp)) {
            if (selected) Txt("●", 11.sp, Palette.accent) else Txt("○", 11.sp, Palette.dimText)
        }
        Txt(label, 12.5.sp, if (selected) Palette.text else Palette.menuText)
    }
}

fun handleKey(ws: Workspace, ev: KeyEvent): Boolean {
    val active = ws.active
    if (ev.type == KeyEventType.KeyUp) {
        if (ev.key == Key.Spacebar) { active?.spaceDown = false; return true }
        return false
    }
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.key == Key.Escape) {
        // only closes an Input/Output data-editor tab; the canvas (document) tab never closes on Esc
        val dataTab = ws.activeData
        if (dataTab != null) { ws.closeDataTab(dataTab); return true }
        active?.wire = null
        ws.menu = null
        return true
    }
    val ctrl = ev.isCtrlPressed || ev.isMetaPressed
    if (ctrl && ev.key == Key.N) { ws.newComponent(); return true }
    if (ctrl && ev.key == Key.S) { ws.saveActive(); return true }
    if (ctrl && ev.key == Key.W) {
        ws.activeData?.let { ws.closeDataTab(it) } ?: ws.requestClose(ws.activeIndex)
        return true
    }
    if (active == null) return false
    if (ws.activeData != null) return false // a data-editor tab handles its own keys
    if (active.textEditing) return false // ignore shortcuts while a text field is focused
    return when {
        // space toggles run/stop; with a node selected it runs from there
        // (like the "Start from here" button). Guard against key auto-repeat.
        ev.key == Key.Spacebar -> {
            if (!active.spaceDown) {
                active.spaceDown = true
                when {
                    active.running -> active.stopRun()
                    active.selNodes.isNotEmpty() -> active.runFromSelection()
                    else -> active.startRun()
                }
            }
            true
        }
        ev.key == Key.Delete || ev.key == Key.Backspace -> { active.deleteSelection(); true }
        ctrl && ev.key == Key.C -> { active.copySelection(); true }
        ctrl && ev.key == Key.V -> { active.paste(); true }
        ctrl && ev.key == Key.Z -> { if (ev.isShiftPressed) active.redo() else active.undo(); true }
        ctrl && ev.key == Key.Y -> { active.redo(); true }
        ctrl && ev.isShiftPressed && ev.key == Key.L -> { active.autoLayout(); true }
        else -> false
    }
}
