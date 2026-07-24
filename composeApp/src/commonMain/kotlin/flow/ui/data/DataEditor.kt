package flow.ui.data

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
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
import flow.model.bytesToHex
import flow.model.hexToBytes
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette

// Data editor tab for a component boundary (cin/cout) node.
//
// The port value is BYTES. Those bytes are canonical (stored as hex in params);
// the string view is just their UTF-8 decoding, and editing in either view keeps
// the underlying bytes. A cout (output) port is display-only.
@Composable
fun DataEditor(ws: Workspace, tab: DataTab) {
    val node = tab.node ?: run { ws.closeDataTab(tab); return }
    val readOnly = node.type == "cout"
    val fmt = node.params["dataFmt"] ?: "string"
    val isHex = fmt == "hex"
    // the port carrying the value: a cin injects on its output, a cout receives on its input
    val port = if (node.type == "cin") node.outputs.firstOrNull() else node.inputs.firstOrNull()
    val bytes = port?.data ?: ByteArray(0)
    val canonicalKey = bytesToHex(bytes)

    // local editing buffer (TextFieldValue so we control the caret through reformatting);
    // re-derived from the port bytes on a format change (a toggle never mutates the bytes)
    var field by remember(tab, fmt) { mutableStateOf(atEnd(viewOf(bytes, isHex))) }
    // keep the buffer in sync when the port bytes change outside our own typing
    // (e.g. a cout port receiving fresh output); our own edits already match, so skip
    LaunchedEffect(canonicalKey, fmt) {
        val bufBytes = if (isHex) hexToBytes(field.text) else field.text.encodeToByteArray()
        if (!bufBytes.contentEquals(bytes)) field = atEnd(viewOf(bytes, isHex))
    }

    fun commitBytes(newBytes: ByteArray) {
        tab.doc.updateNode(node.id) { n ->
            if (n.type == "cin") {
                val outs = n.outputs.toMutableList()
                if (outs.isEmpty()) outs.add(Port("out", newBytes)) else outs[0] = outs[0].withData(newBytes)
                n.copy(outputs = outs)
            } else {
                val ins = n.inputs.toMutableList()
                if (ins.isEmpty()) ins.add(Port("in", newBytes)) else ins[0] = ins[0].withData(newBytes)
                n.copy(inputs = ins)
            }
        }
    }
    fun switchTo(target: String) {
        if (target != fmt) tab.doc.updateNode(node.id) { it.copy(params = it.params + ("dataFmt" to target)) }
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
            if (readOnly) Txt(ws.t("dataReadOnly"), 10.sp, Palette.dimText)
            Spacer(Modifier.weight(1f))
            FormatToggle(isHex) { hex -> switchTo(if (hex) "hex" else "string") }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.holeBg, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            val scroll = rememberScrollState()
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    if (isHex) {
                        // auto-format into "XX XX" byte pairs while keeping the caret in place
                        field = formatHex(v)
                        commitBytes(hexToBytes(field.text))
                    } else {
                        field = v
                        commitBytes(v.text.encodeToByteArray())
                    }
                },
                readOnly = readOnly,
                textStyle = TextStyle(color = if (readOnly) Palette.subText else Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Palette.text),
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
            if (field.text.isEmpty()) {
                Txt(
                    when {
                        readOnly -> ws.t("dataNoOutput")
                        isHex -> ws.t("dataHexHint")
                        else -> ws.t("dataStringHint")
                    },
                    13.sp, Palette.faintText, mono = true,
                )
            }
        }

        Txt(ws.t("dataBytes").replace("{n}", bytes.size.toString()), 11.sp, Palette.dimText, mono = true)
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
        Txt(label, 11.sp, if (active) Palette.holeBg else Palette.subText, weight = FontWeight.Medium, mono = true)
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
    if (hex) bytesToHex(bytes) else bytes.decodeToString()
