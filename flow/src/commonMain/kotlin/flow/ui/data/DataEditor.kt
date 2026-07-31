package flow.ui.data

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt

// Data editor tab for a component boundary (cin/cout) node.
//
// The port value is BYTES. Those bytes are canonical (stored as hex in params);
// the string view is just their UTF-8 decoding, and editing in either view keeps
// the underlying bytes. A cout (output) port is display-only.
//
// The displayed/edited text is never more than WINDOW_BYTES worth of hex/string at a time — only
// the slice currently in view gets converted, so a multi-hundred-MB value stays scrollable and
// responsive instead of freezing the field. An edit that pushes the *underlying* value's size past
// SPILL_THRESHOLD_BYTES is written out to a temp file and the node switches to file-backed (the
// same on-disk-windowed path "Read file" already uses), so it isn't kept as one live in-memory
// buffer either — only held transiently while being spliced and written.
private const val WINDOW_BYTES = 262_144 // 256 KiB shown/edited at once, scroll for the rest
private const val SPILL_THRESHOLD_BYTES = 8 * 1024 * 1024 // 8 MiB — beyond this, don't keep it as one in-memory node value

// Where windowed bytes come from: an in-memory value, or a range read straight off disk.
private sealed class DataSource {
    abstract val totalSize: Long
    data class Memory(val bytes: ByteArray) : DataSource() {
        override val totalSize get() = bytes.size.toLong()
    }
    data class FileRange(val path: String, override val totalSize: Long) : DataSource()
}

private fun DataSource.read(offset: Long, length: Int): ByteArray = when (this) {
    is DataSource.Memory -> {
        val end = minOf(offset + length, bytes.size.toLong()).toInt()
        if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset.toInt(), end)
    }
    is DataSource.FileRange -> Platform.readFileRange(path, offset, length)
}

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

    // a cin can be backed by a file: read windows straight off disk, never the whole file
    val filePath = if (isOut) null else node.params["dataFile"]
    val totalFileSize = remember(filePath) { if (filePath != null) Platform.fileSize(filePath) else -1L }
    val fileBacked = filePath != null && totalFileSize >= 0
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

    val totalSize = if (fileBacked) totalFileSize else bytes.size.toLong()

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
                Txt(ws.t("dataBytes").replace("{n}", totalFileSize.toString()), 11.sp, Palette.dimText, mono = true)
                Txt("×", 14.sp, Palette.dimText, modifier = Modifier.plainClick { setParam("dataFile", null) }.padding(horizontal = 4.dp))
            }
        }

        var windowStart by remember(tab, isHex, filePath) { mutableStateOf(0L) }
        val maxStart = (totalSize - WINDOW_BYTES).coerceAtLeast(0)
        if (windowStart > maxStart) windowStart = maxStart

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.holeBg, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                .then(if (isOut) Modifier else Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget))
                .padding(12.dp),
        ) {
            if (editable) {
                EditableField(bytes, isHex, tab, node, windowStart) { windowStart = it }
            } else {
                val source = if (fileBacked) DataSource.FileRange(filePath!!, totalFileSize) else DataSource.Memory(bytes)
                ReadOnlyView(source, isHex, windowStart) { windowStart = it }
            }

            if (totalSize == 0L) {
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

        val windowEnd = minOf(windowStart + WINDOW_BYTES, totalSize)
        val label = if (totalSize > WINDOW_BYTES)
            ws.t("dataWindowOf").replace("{start}", windowStart.toString())
                .replace("{end}", windowEnd.toString()).replace("{total}", totalSize.toString())
        else ws.t("dataBytes").replace("{n}", totalSize.toString())
        Txt(label, 11.sp, Palette.dimText, mono = true)
    }
}

// Editable inline byte field (cin, not file-backed). Only ever holds up to WINDOW_BYTES of the
// underlying value in the text field itself; edits splice back into the full byte array at the
// window's absolute offset, and an oversized paste is committed in full but the displayed field
// snaps back to a bounded slice (starting where the paste landed) rather than staying huge.
@Composable
private fun EditableField(
    bytes: ByteArray,
    isHex: Boolean,
    tab: DataTab,
    node: flow.model.Node,
    windowStart: Long,
    onWindowStart: (Long) -> Unit,
) {
    val total = bytes.size
    val winStart = windowStart.toInt().coerceIn(0, total)
    val winSize = minOf(WINDOW_BYTES, total - winStart)
    val windowBytes = remember(bytes, winStart, winSize) {
        if (winSize <= 0) ByteArray(0) else bytes.copyOfRange(winStart, winStart + winSize)
    }

    val canonicalKey = bytesToHex(windowBytes)
    var field by remember(tab, isHex) { mutableStateOf(atEnd(viewOf(windowBytes, isHex))) }
    var lastKey by remember(tab, isHex) { mutableStateOf(canonicalKey) }
    LaunchedEffect(canonicalKey) {
        if (canonicalKey != lastKey) { field = atEnd(viewOf(windowBytes, isHex)); lastKey = canonicalKey }
    }

    // splice the edited window back into the full array at its absolute offset, then re-clamp
    // what's displayed to WINDOW_BYTES — a huge paste is fully committed but not fully shown
    fun commit(newWindowBytes: ByteArray, caretInNewWindow: Int) {
        val newFull = spliceWindow(bytes, winStart, winSize, newWindowBytes)
        if (newFull.size > SPILL_THRESHOLD_BYTES) {
            // don't keep this as one live in-memory buffer on the node — spill it to a temp file
            // and switch to file-backed, reusing the same on-disk windowed reading "Read file" uses.
            // newFull is still momentarily whole here (spliceWindow/the paste itself already built
            // it that large), but nothing keeps a reference to it after this write returns.
            val path = Platform.createTempFile("flow-cin")
            Platform.writeBytes(path, newFull)
            tab.doc.updateNode(node.id) { n ->
                n.copy(
                    params = n.params + ("dataFile" to path),
                    outputs = listOf((n.outputs.firstOrNull() ?: Port("out")).withData(ByteArray(0))),
                )
            }
            return
        }
        tab.doc.updateNode(node.id) { n ->
            val outs = n.outputs.toMutableList()
            if (outs.isEmpty()) outs.add(Port("out", newFull)) else outs[0] = outs[0].withData(newFull)
            n.copy(outputs = outs)
        }
        // below the spill threshold but still possibly above WINDOW_BYTES (a few-MB paste): commit
        // it in full above, but keep what's actually live in the field bounded
        val bounded = if (newWindowBytes.size <= WINDOW_BYTES) newWindowBytes else newWindowBytes.copyOfRange(0, WINDOW_BYTES)
        val text = viewOf(bounded, isHex)
        field = if (newWindowBytes.size <= WINDOW_BYTES) TextFieldValue(text, TextRange(caretInNewWindow.coerceIn(0, text.length)))
        else atEnd(text) // paste overflowed the window: show its start, caret at the end of what's shown
        lastKey = bytesToHex(bounded)
    }

    Row(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        BasicTextField(
            value = field,
            onValueChange = { v ->
                if (isHex) {
                    val formatted = formatHex(v)
                    commit(hexToBytes(formatted.text), formatted.selection.end)
                } else {
                    val old = field.text
                    val newWindowBytes = spliceBytes(windowBytes, old, v.text)
                    commit(newWindowBytes, v.selection.end)
                }
            },
            textStyle = TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Palette.text),
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
        )
        if (total > WINDOW_BYTES) {
            WindowScrollbar(total.toLong(), windowStart, winSize.toLong(), onWindowStart)
        }
    }
}

// Read-only windowed view: cout output (in-memory) or a file-backed cin (read straight off disk).
@Composable
private fun ReadOnlyView(source: DataSource, isHex: Boolean, windowStart: Long, onWindowStart: (Long) -> Unit) {
    val total = source.totalSize
    val winSize = minOf(WINDOW_BYTES.toLong(), total - windowStart).coerceAtLeast(0)
    val windowBytes = remember(source, windowStart, winSize) { source.read(windowStart, winSize.toInt()) }

    Row(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        BasicTextField(
            value = viewOf(windowBytes, isHex),
            onValueChange = {},
            readOnly = true,
            textStyle = TextStyle(color = Palette.subText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Palette.text),
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
        )
        if (total > WINDOW_BYTES) {
            WindowScrollbar(total, windowStart, winSize, onWindowStart)
        }
    }
}

// A thin draggable position indicator for jumping the window through data far larger than
// WINDOW_BYTES — click or drag anywhere on the track to seek there, thumb sized to the fraction
// of the total currently in view.
@Composable
private fun WindowScrollbar(total: Long, windowStart: Long, windowSize: Long, onSeek: (Long) -> Unit) {
    val maxStart = (total - windowSize).coerceAtLeast(0)
    val thumbFrac = (windowSize.toFloat() / total.toFloat()).coerceIn(0.04f, 1f)
    val posFrac = if (maxStart > 0) windowStart.toFloat() / maxStart.toFloat() else 0f
    var trackHeightPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    fun seekTo(y: Float) {
        if (trackHeightPx <= 0f) return
        val thumbPx = (thumbFrac * trackHeightPx).coerceAtLeast(20f)
        val usable = (trackHeightPx - thumbPx).coerceAtLeast(1f)
        val topY = (y - thumbPx / 2).coerceIn(0f, usable)
        onSeek(((topY / usable) * maxStart).roundToInt().toLong().coerceIn(0, maxStart))
    }

    Box(
        Modifier
            .fillMaxHeight()
            .width(10.dp)
            .padding(start = 6.dp)
            .background(Palette.holeBg, RoundedCornerShape(4.dp))
            .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
            .pointerInput(total, windowSize) {
                detectDragGestures(onDragStart = { seekTo(it.y) }) { change, _ ->
                    change.consume()
                    seekTo(change.position.y)
                }
            },
    ) {
        val thumbHeightPx = (thumbFrac * trackHeightPx).coerceAtLeast(20f)
        val thumbOffsetPx = posFrac * (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(10.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(Palette.accent.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        )
    }
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

// Replace the [winStart, winStart+winSize) slice of `full` with `newWindowBytes` — the window's
// edit result — leaving everything before/after the window untouched. Internal visibility (not
// private) so a headless check can verify it directly.
internal fun spliceWindow(full: ByteArray, winStart: Int, winSize: Int, newWindowBytes: ByteArray): ByteArray {
    val windowEndAbs = winStart + winSize
    return full.copyOfRange(0, winStart) + newWindowBytes + full.copyOfRange(windowEndAbs, full.size)
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
