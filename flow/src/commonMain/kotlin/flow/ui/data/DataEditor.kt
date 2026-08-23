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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import flow.core.cinFileSize
import flow.model.VIEW_PARAM
import flow.platform.Platform
import flow.ui.views.ViewPicker
import flow.ui.views.ViewSurface
import flow.platform.droppedFilePath
import flow.ui.common.Txt
import flow.ui.common.plainClick
import flow.ui.theme.Palette
import flow.util.bytesToHex
import flow.util.decodeUtf8Lossy
import flow.util.hexToBytes
import flow.util.spliceBytes
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

// Node params the editor owns. A view is handed the whole map — it is where its own state lives —
// but these come back from the app's copy whatever it returns.
private val EDITOR_PARAMS = setOf(VIEW_PARAM, "dataFmt", "dataFile")

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

    // a cin can be backed by a file: read windows straight off disk, never the whole file.
    // cinFileSize is the same rule the run uses, so what is shown here is what gets processed.
    val filePath = if (isOut) null else node.params["dataFile"]
    val totalFileSize = remember(filePath, node) { if (isOut) -1L else cinFileSize(node) ?: -1L }
    val fileBacked = totalFileSize >= 0
    val editable = !isOut && !fileBacked

    // an output can be read through a view; a cin is the user's own bytes and stays plain
    val activeView = if (isOut) ws.viewFor(node) else null

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
            if (isOut && ws.enabledViews.isNotEmpty()) {
                ViewPicker(ws, node.params[VIEW_PARAM]?.takeIf { it.isNotBlank() }) { setParam(VIEW_PARAM, it) }
            }
            // hex and string are how the raw bytes are read; a view decides that for itself
            if (activeView == null) FormatToggle(isHex) { hex -> switchTo(if (hex) "hex" else "string") }
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
            if (activeView != null) {
                // A view keeps what it needs to remember in the node's params, so a tree left
                // half-open stays that way — but the params the editor itself owns are not its to
                // change, or a view could switch itself off or repoint the node at another file.
                ViewSurface(ws, activeView.id, bytes, node.params, Modifier.fillMaxSize()) { next ->
                    tab.doc.updateNode(node.id) { n ->
                        n.copy(params = next - EDITOR_PARAMS + n.params.filterKeys { it in EDITOR_PARAMS })
                    }
                }
            } else if (editable) {
                EditableField(bytes, isHex, tab, node, windowStart) { windowStart = it }
            } else {
                val source = if (fileBacked) DataSource.FileRange(filePath!!, totalFileSize) else DataSource.Memory(bytes)
                ReadOnlyView(source, isHex, windowStart) { windowStart = it }
            }

            if (totalSize == 0L && activeView == null) {
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

    // spill this exact byte array to a temp file and switch the node to file-backed, reusing the
    // same on-disk windowed reading "Read file" uses — nothing keeps a live reference afterward
    fun spillToFile(full: ByteArray): String {
        val path = Platform.createTempFile("flow-cin")
        Platform.writeBytes(path, full)
        tab.doc.updateNode(node.id) { n ->
            n.copy(
                params = n.params + ("dataFile" to path),
                outputs = listOf((n.outputs.firstOrNull() ?: Port("out")).withData(ByteArray(0))),
            )
        }
        return path
    }

    // splice the edited window back into the full array at its absolute offset, then re-clamp
    // what's displayed to WINDOW_BYTES — a huge paste is fully committed but not fully shown
    fun commit(newWindowBytes: ByteArray, caretInNewWindow: Int) {
        val newFull = spliceWindow(bytes, winStart, winSize, newWindowBytes)
        if (newFull.size > SPILL_THRESHOLD_BYTES) {
            // newFull is still momentarily whole here (spliceWindow/the paste itself already built
            // it that large — this path is for typing/small in-field pastes only; see handlePaste
            // below for the genuinely streamed path that never builds this in the first place)
            spillToFile(newFull)
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

    // land the window on `focusByteOffset` after a paste that went through the temp-file path,
    // whether it ended up staying in memory (bytes read back) or file-backed (path only)
    fun showAround(newTotal: Int, focusByteOffset: Int, source: DataSource) {
        val newWinStart = (focusByteOffset - WINDOW_BYTES / 2).coerceIn(0, (newTotal - WINDOW_BYTES).coerceAtLeast(0))
        val newWinSize = minOf(WINDOW_BYTES, newTotal - newWinStart)
        val boundedWin = if (newWinSize <= 0) ByteArray(0) else source.read(newWinStart.toLong(), newWinSize)
        onWindowStart(newWinStart.toLong())
        val text = viewOf(boundedWin, isHex)
        field = atEnd(text)
        lastKey = bytesToHex(boundedWin)
    }

    val scope = rememberCoroutineScope()
    var pasting by remember(tab) { mutableStateOf(false) }

    // Reads the clipboard in bounded chunks (Platform.pasteClipboardChunks) and appends each,
    // converted to bytes, straight to a temp file that already has the pre-selection prefix
    // written — the clipboard's own content is never held as one block, however large the paste
    // is. If the final result turns out small after all, it's collapsed back into the simple
    // in-memory case; only a genuinely large paste stays file-backed.
    fun handlePaste() {
        if (pasting) return
        val selStart = minOf(field.selection.start, field.selection.end)
        val selEnd = maxOf(field.selection.start, field.selection.end)
        val localByteStart = charPosToByteOffset(field.text, selStart, isHex)
        val localByteEnd = charPosToByteOffset(field.text, selEnd, isHex)
        val absStart = (winStart + localByteStart).coerceIn(0, total)
        val absEnd = (winStart + localByteEnd).coerceIn(absStart, total)
        val prefix = bytes.copyOfRange(0, absStart)
        val suffix = bytes.copyOfRange(absEnd, total)
        pasting = true
        scope.launch(Dispatchers.IO) {
            val path = Platform.createTempFile("flow-paste")
            Platform.writeBytes(path, prefix)
            val hexDecoder = HexStreamDecoder()
            val strEncoder = Utf8StreamEncoder()
            var written = prefix.size.toLong()
            val got = Platform.pasteClipboardChunks(65536) { chunk ->
                val chunkBytes = if (isHex) hexDecoder.decode(chunk) else strEncoder.encode(chunk)
                if (chunkBytes.isNotEmpty()) { Platform.appendBytes(path, chunkBytes); written += chunkBytes.size }
            }
            val tail = if (isHex) hexDecoder.finish() else strEncoder.finish()
            if (tail.isNotEmpty()) { Platform.appendBytes(path, tail); written += tail.size }
            val pastedEnd = written
            if (suffix.isNotEmpty()) { Platform.appendBytes(path, suffix); written += suffix.size }
            val finalSize = if (got) written else -1L

            withContext(Dispatchers.Main) {
                pasting = false
                if (finalSize >= 0) {
                    if (finalSize <= SPILL_THRESHOLD_BYTES) {
                        val newFull = Platform.readFileRange(path, 0, finalSize.toInt())
                        tab.doc.updateNode(node.id) { n ->
                            val outs = n.outputs.toMutableList()
                            if (outs.isEmpty()) outs.add(Port("out", newFull)) else outs[0] = outs[0].withData(newFull)
                            n.copy(outputs = outs)
                        }
                        showAround(newFull.size, pastedEnd.toInt(), DataSource.Memory(newFull))
                    } else {
                        // file at `path` already holds the full spliced result — just point the node at it
                        tab.doc.updateNode(node.id) { n ->
                            n.copy(
                                params = n.params + ("dataFile" to path),
                                outputs = listOf((n.outputs.firstOrNull() ?: Port("out")).withData(ByteArray(0))),
                            )
                        }
                        showAround(finalSize.toInt(), pastedEnd.toInt(), DataSource.FileRange(path, finalSize))
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        BasicTextField(
            value = field,
            onValueChange = { v ->
                if (isHex) {
                    val formatted = formatHex(field, v)
                    commit(hexToBytes(formatted.text), formatted.selection.end)
                } else {
                    val old = field.text
                    val newWindowBytes = spliceBytes(windowBytes, old, v.text)
                    commit(newWindowBytes, v.selection.end)
                }
            },
            textStyle = TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Palette.text),
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)
                .onPreviewKeyEvent { ev ->
                    // intercept Cmd+V ourselves so a paste always goes through the streaming path
                    // below (handlePaste) instead of Compose's native paste, which would first
                    // materialize the whole clipboard as one in-memory String
                    if (ev.type == KeyEventType.KeyDown && ev.isMetaPressed && ev.key == Key.V) {
                        handlePaste()
                        true
                    } else false
                },
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

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> 0
}

// Converts a stream of hex-digit text chunks (arbitrary spacing, e.g. clipboard content read in
// bounded pieces) into bytes incrementally, carrying a leftover odd digit across chunk boundaries
// so a byte pair split across two chunks still decodes correctly.
internal class HexStreamDecoder {
    private var pendingHi: Char? = null
    fun decode(chunk: String): ByteArray {
        val out = ArrayList<Byte>(chunk.length / 2 + 1)
        var hi = pendingHi
        for (c in chunk) {
            if (!isHexDigit(c)) continue
            if (hi == null) hi = c
            else { out.add(((hexVal(hi) shl 4) or hexVal(c)).toByte()); hi = null }
        }
        pendingHi = hi
        return out.toByteArray()
    }
    // a trailing lone digit (odd total count) becomes its own byte — matches hexToBytes's existing
    // behavior for an odd-length token (e.g. "5" -> 0x05), rather than being silently dropped
    fun finish(): ByteArray = pendingHi?.let { byteArrayOf(hexVal(it).toByte()) } ?: ByteArray(0)
}

// Converts a stream of text chunks to UTF-8 bytes incrementally, holding back a trailing high
// surrogate so a character outside the BMP (e.g. an emoji) split across two chunks encodes
// correctly instead of each half turning into a replacement character.
internal class Utf8StreamEncoder {
    private var pendingHighSurrogate: Char? = null
    fun encode(chunk: String): ByteArray {
        var s = chunk
        pendingHighSurrogate?.let { s = it + s; pendingHighSurrogate = null }
        if (s.isNotEmpty() && s.last().isHighSurrogate()) {
            pendingHighSurrogate = s.last()
            s = s.dropLast(1)
        }
        return s.encodeToByteArray()
    }
    fun finish(): ByteArray = pendingHighSurrogate?.let { it.toString().encodeToByteArray() } ?: ByteArray(0)
}

// How many bytes precede this character position in the given view (rounds down mid-pair for
// hex). Used to translate the field's text-selection into an absolute byte offset for pasting.
// Internal visibility (not private) so a headless check can verify it directly.
internal fun charPosToByteOffset(text: String, charPos: Int, isHex: Boolean): Int {
    val clamped = charPos.coerceIn(0, text.length)
    return if (isHex) text.take(clamped).count { isHexDigit(it) } / 2
    else text.take(clamped).encodeToByteArray().size
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
//
// `old` is the field's value just before this edit. Without it, deleting a single formatting
// space (the char right at a pair boundary) is invisible: no digit was removed, so re-grouping
// puts the identical space right back and Backspace looks like it did nothing. Detecting that
// case and dropping the adjacent digit too makes every delete visibly remove something.
internal fun formatHex(old: TextFieldValue, v: TextFieldValue): TextFieldValue {
    val caret = v.selection.end.coerceIn(0, v.text.length)
    var digits = v.text.filter { isHexDigit(it) }
    var digitsBefore = v.text.take(caret).count { isHexDigit(it) }

    val oldDigitCount = old.text.count { isHexDigit(it) }
    // both selections collapsed (plain cursor) rules out "replace a selection" (paste or typing
    // over a selection), which can shrink the text too but must never lose a pasted character here
    val shrankWithNoDigitLoss = old.selection.collapsed && v.selection.collapsed &&
        v.text.length < old.text.length && digits.length == oldDigitCount
    if (shrankWithNoDigitLoss && digitsBefore > 0) {
        digits = digits.removeRange(digitsBefore - 1, digitsBefore)
        digitsBefore -= 1
    }

    val grouped = digits.uppercase().chunked(2).joinToString(" ")
    // each completed pair before the caret adds one space; caret sits after `digitsBefore` digits
    val pos = if (digitsBefore <= 0) 0 else (digitsBefore + (digitsBefore - 1) / 2).coerceAtMost(grouped.length)
    return TextFieldValue(grouped, TextRange(pos))
}

// how the port bytes appear in the given view
private fun viewOf(bytes: ByteArray, hex: Boolean): String =
    if (hex) bytesToHex(bytes) else decodeUtf8Lossy(bytes).first
