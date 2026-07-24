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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flow.core.DataTab
import flow.core.Workspace
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
    val canonical = node.params["data"] ?: ""
    val bytes = hexBytes(canonical) // canonical bytes

    // local editing buffer; re-derived from the canonical bytes on a format change
    // (a toggle never mutates the bytes, only how they're shown)
    var text by remember(tab, fmt) { mutableStateOf(viewOf(bytes, isHex)) }
    // keep the buffer in sync when the canonical bytes change outside our own typing
    // (e.g. a cout port receiving fresh output); our own edits already match, so skip
    LaunchedEffect(canonical, fmt) {
        val bufBytes = if (isHex) hexBytes(text) else text.encodeToByteArray()
        if (!bufBytes.contentEquals(bytes)) text = viewOf(bytes, isHex)
    }

    fun commitBytes(newBytes: ByteArray) {
        tab.doc.updateNode(node.id) { it.copy(params = it.params + ("data" to hexOf(newBytes))) }
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
                value = text,
                onValueChange = { v ->
                    if (isHex) {
                        // auto-format: drop whitespace and regroup into "XX XX" byte pairs
                        val grouped = groupHex(v)
                        text = grouped
                        commitBytes(hexBytes(grouped))
                    } else {
                        text = v
                        commitBytes(v.encodeToByteArray())
                    }
                },
                readOnly = readOnly,
                textStyle = TextStyle(color = if (readOnly) Palette.subText else Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Palette.text),
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
            if (text.isEmpty()) {
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
        Seg("STRING", active = !isHex) { onChange(false) }
        Seg("HEX", active = isHex) { onChange(true) }
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

// canonical byte serialization: space-separated uppercase hex
private fun hexOf(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

// normalize free hex typing into space-separated uppercase byte pairs ("4865" -> "48 65")
private fun groupHex(raw: String): String =
    raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        .uppercase()
        .chunked(2)
        .joinToString(" ")

private fun hexBytes(h: String): ByteArray =
    h.split(Regex("\\s+")).filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull(16)?.toByte() }
        .toByteArray()

// how the canonical bytes appear in the given view
private fun viewOf(bytes: ByteArray, hex: Boolean): String =
    if (hex) hexOf(bytes) else bytes.decodeToString()
