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

// Data editor tab: edits the sample data of a component boundary (cin/cout) node.
// The value can be entered as a string or as hex; toggling converts between them.
@Composable
fun DataEditor(ws: Workspace, tab: DataTab) {
    val node = tab.node ?: run { ws.closeDataTab(tab); return }
    val fmt = node.params["dataFmt"] ?: "string"
    val data = node.params["data"] ?: ""
    val isHex = fmt == "hex"

    fun write(newData: String = data, newFmt: String = fmt) {
        tab.doc.updateNode(node.id) { it.copy(params = it.params + ("data" to newData) + ("dataFmt" to newFmt)) }
    }

    fun switchTo(target: String) {
        if (target == fmt) return
        val converted = if (target == "hex") stringToHex(data) else hexToString(data)
        write(converted, target)
    }

    val byteCount = if (isHex) hexBytes(data).size else data.encodeToByteArray().size

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
            Spacer(Modifier.weight(1f))
            FormatToggle(isHex) { hex -> switchTo(if (hex) "hex" else "string") }
        }

        // multiline data entry
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
                value = data,
                onValueChange = { v -> write(if (isHex) v.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it.isWhitespace() } else v) },
                textStyle = TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Palette.text),
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
            if (data.isEmpty()) {
                Txt(
                    if (isHex) ws.t("dataHexHint") else ws.t("dataStringHint"),
                    13.sp, Palette.faintText, mono = true,
                )
            }
        }

        Txt(ws.t("dataBytes").replace("{n}", byteCount.toString()), 11.sp, Palette.dimText, mono = true)
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

/* ───────── hex <-> string conversion (UTF-8) ───────── */

private fun stringToHex(s: String): String =
    s.encodeToByteArray().joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

private fun hexBytes(h: String): ByteArray =
    h.split(Regex("\\s+")).filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull(16)?.toByte() }
        .toByteArray()

private fun hexToString(h: String): String = hexBytes(h).decodeToString()
