package flow.ui.data

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.DataTab
import flow.core.Workspace
import flow.model.Port
import flow.platform.Platform
import flow.platform.droppedFilePath
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette
import flow.util.bytesToHex
import flow.util.decodeUtf8Lossy
import flow.util.hexToBytes
import flow.util.spliceBytes

// Data editor tab for a component boundary (cin/cout) node.
//
// The port value is BYTES. Those bytes are canonical (stored as hex in params);
// the string view is just their UTF-8 decoding, and editing in either view keeps
// the underlying bytes. A cout (output) port is display-only.
private const val PREVIEW_BYTES = 4096 // for a loaded file we read/show only this leading window

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DataEditor(ws: Workspace, tab: DataTab) {
    val node = tab.node ?: run { ws.closeDataTab(tab); return }
    val isOut = node.type == "cout"
    val fmt = node.params["dataFmt"] ?: "hex"
    val isHex = fmt == "hex"
    // cin value = its editable output-port bytes; cout value = the transient run output
    val bytes = if (isOut) (tab.doc.runOutputs[tab.nodeId] ?: ByteArray(0))
    else (node.outputs.firstOrNull()?.data ?: ByteArray(0))

    // a cin can be backed by a file: we read/show only a leading window, never the whole file
    val filePath = if (isOut) null else node.params["dataFile"]
    val totalSize = remember(filePath) { if (filePath != null) Platform.fileSize(filePath) else -1L }
    val fileBacked = filePath != null && totalSize >= 0
    val previewBytes = remember(filePath, totalSize) {
        if (fileBacked) Platform.readFileRange(filePath!!, 0, PREVIEW_BYTES) else ByteArray(0)
    }
    val displayBytes = if (fileBacked) previewBytes else bytes
    val editable = !isOut && !fileBacked

    fun switchTo(target: String) {
        if (target != fmt) tab.doc.updateNode(node.id) { it.copy(params = it.params + ("dataFmt" to target)) }
    }
    fun setParam(key: String, value: String?) = tab.doc.updateNode(node.id) {
        it.copy(params = if (value == null) it.params - key else it.params + (key to value))
    }
    // external file drop -> back the cin by that file (uses stable tab refs, no stale capture)
    val dropTarget = remember(tab) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val path = droppedFilePath(event) ?: return false
                tab.doc.updateNode(tab.nodeId) { it.copy(params = it.params + ("dataFile" to path)) }
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.appBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(node.label, 15.sp, Palette.text, weight = FontWeight.SemiBold)
            val portKind = if (node.type == "cin") ws.t("labelInputs") else ws.t("labelOutputs")
            Box(Modifier.background(Palette.catIo.copy(alpha = 0.16f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Txt(portKind, 10.sp, Palette.catIo, weight = FontWeight.Medium)
            }
            if (isOut) Txt(ws.t("dataReadOnly"), 10.sp, Palette.dimText)
            Spacer(Modifier.weight(1f))
            if (isOut) {
                ToolButton(ws.t("dataSaveFile")) {
                    Platform.pickFileSave("${node.label}.bin")?.let { Platform.writeBytes(it, bytes) }
                }
            } else {
                ToolButton(ws.t("dataReadFile")) { Platform.pickFileRead()?.let { setParam("dataFile", it) } }
            }
            FormatToggle(isHex) { hex -> switchTo(if (hex) "hex" else "string") }
        }

        // loaded-file banner (name + total size + clear)
        if (fileBacked) {
            Row(
                Modifier.fillMaxWidth().background(Palette.holeBg, RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt("📄 ${Platform.fileName(filePath!!)}", 12.sp, Palette.text, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
                Txt(ws.t("dataBytes").replace("{n}", totalSize.toString()), 11.sp, Palette.dimText, mono = true)
                Txt("×", 14.sp, Palette.dimText, modifier = Modifier.plainClick { setParam("dataFile", null) }.padding(horizontal = 4.dp))
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.holeBg, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                .then(if (isOut) Modifier else Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget))
                .padding(12.dp),
        ) {
            if (editable) EditableField(bytes, isHex, tab, node) else ReadOnlyView(displayBytes, isHex)

            if (displayBytes.isEmpty()) {
                Txt(
                    when {
                        isOut -> ws.t("dataNoOutput")
                        isHex -> ws.t("dataHexHint")
                        else -> ws.t("dataStringHint")
                    },
                    13.sp, Palette.faintText, mono = true,
                )
            }
        }

        val shown = if (fileBacked) minOf(displayBytes.size.toLong(), totalSize) else displayBytes.size.toLong()
        val label = if (fileBacked && totalSize > shown)
            ws.t("dataPreviewOf").replace("{n}", shown.toString()).replace("{total}", totalSize.toString())
        else ws.t("dataBytes").replace("{n}", shown.toString())
        Txt(label, 11.sp, Palette.dimText, mono = true)
    }
}

// Editable inline byte field (cin, not file-backed).
@Composable
private fun EditableField(bytes: ByteArray, isHex: Boolean, tab: DataTab, node: flow.model.Node) {
    val canonicalKey = bytesToHex(bytes)
    var field by remember(tab, isHex) { mutableStateOf(atEnd(viewOf(bytes, isHex))) }
    var lastKey by remember(tab, isHex) { mutableStateOf(canonicalKey) }
    LaunchedEffect(canonicalKey) {
        if (canonicalKey != lastKey) { field = atEnd(viewOf(bytes, isHex)); lastKey = canonicalKey }
    }
    fun commit(newBytes: ByteArray) {
        lastKey = bytesToHex(newBytes)
        tab.doc.updateNode(node.id) { n ->
            val outs = n.outputs.toMutableList()
            if (outs.isEmpty()) outs.add(Port("out", newBytes)) else outs[0] = outs[0].withData(newBytes)
            n.copy(outputs = outs)
        }
    }
    val scroll = rememberScrollState()
    BasicTextField(
        value = field,
        onValueChange = { v ->
            if (isHex) {
                field = formatHex(v)
                commit(hexToBytes(field.text))
            } else {
                val old = field.text
                field = v
                commit(spliceBytes(bytes, old, v.text))
            }
        },
        textStyle = TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Palette.text),
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
    )
}

// Read-only view of bytes (cout output, or a file-backed preview window).
@Composable
private fun ReadOnlyView(bytes: ByteArray, isHex: Boolean) {
    val scroll = rememberScrollState()
    BasicTextField(
        value = viewOf(bytes, isHex),
        onValueChange = {},
        readOnly = true,
        textStyle = TextStyle(color = Palette.subText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Palette.text),
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
    )
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, Palette.border, RoundedCornerShape(6.dp)).plainClick(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Txt(label, 11.sp, Palette.menuText, weight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun FormatToggle(isHex: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.background(Palette.holeBg, RoundedCornerShape(6.dp)).border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Seg("HEX", active = isHex) { onChange(true) }
        Seg("STRING", active = !isHex) { onChange(false) }
    }
}

@Composable
private fun Seg(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Palette.accent else Color.Transparent, RoundedCornerShape(4.dp))
            .plainClick(onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Txt(label, 11.sp, if (active) Palette.holeBg else Palette.subText, weight = FontWeight.Medium, mono = true, maxLines = 1)
    }
}

/* ───────── byte <-> view conversion (UTF-8) ───────── */

private fun isHexDigit(c: Char) = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

// a TextFieldValue with the caret at the end
private fun atEnd(s: String) = TextFieldValue(s, TextRange(s.length))

// Reformat free hex typing into space-separated "XX XX" pairs while keeping the caret
// anchored to the same hex digit it was at (so it never drifts as spaces are inserted).
private fun formatHex(v: TextFieldValue): TextFieldValue {
    val caret = v.selection.end.coerceIn(0, v.text.length)
    val digitsBefore = v.text.take(caret).count { isHexDigit(it) }
    val grouped = v.text.filter { isHexDigit(it) }.uppercase().chunked(2).joinToString(" ")
    // each completed pair before the caret adds one space; caret sits after `digitsBefore` digits
    val pos = if (digitsBefore == 0) 0 else (digitsBefore + (digitsBefore - 1) / 2).coerceAtMost(grouped.length)
    return TextFieldValue(grouped, TextRange(pos))
}

// how the port bytes appear in the given view
private fun viewOf(bytes: ByteArray, hex: Boolean): String =
    if (hex) bytesToHex(bytes) else decodeUtf8Lossy(bytes).first
