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
import flow.platform.Platform
import flow.ui.io.Builtin
import flow.ui.io.EndPicker
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
import kotlinx.coroutines.delay
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
// how long typing settles before it is converted to bytes; one round trip per keystroke would show
private const val COMMIT_DELAY_MS = 120L
// the most clipboard text a paste will assemble before being converted
private const val PASTE_TEXT_LIMIT = 32 * 1024 * 1024

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

/** How the value is written: one of Builtin.ALL. Saved with the flow, like any other node param. */
private const val FORMAT_PARAM = "format"

/** Which encoding, when it is written as text. */
private const val CHARSET_PARAM = "charset"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DataEditor(ws: Workspace, tab: DataTab) {
    val node = tab.node ?: run { ws.closeDataWindow(tab); return }
    val isOut = node.type == "cout"
    // cin value = its editable output-port bytes; cout value = the transient run output
    val bytes = if (isOut) (tab.doc.runOutputs[tab.nodeId] ?: ByteArray(0))
    else (node.outputs.firstOrNull()?.data ?: ByteArray(0))

    // a cin can be backed by a file: read windows straight off disk, never the whole file.
    // cinFileSize is the same rule the run uses, so what is shown here is what gets processed.
    val filePath = if (isOut) null else node.params["dataFile"]
    val totalFileSize = remember(filePath, node) { if (isOut) -1L else cinFileSize(node) ?: -1L }
    val fileBacked = totalFileSize >= 0
    val editable = !isOut && !fileBacked

    // Text or hex, and which encoding when it is text. Both ends read and write the same two ways
    // — an output is the same bytes, only not editable — and anything richer than this is a view
    // extension, which opens a window of its own rather than living in this panel.
    val format = node.params[FORMAT_PARAM]?.takeIf { it in Builtin.ALL } ?: Builtin.STRING
    val charset = node.params[CHARSET_PARAM] ?: "UTF-8"
    val writing = remember(format, charset) { Writing(format, charset) }
    var problem by remember(tab) { mutableStateOf<String?>(null) }

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
            val portKind = if (isOut) ws.t("labelOutputs") else ws.t("labelInputs")
            val endColor = if (isOut) Palette.catOut else Palette.catIo
            Box(Modifier.background(endColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Txt(portKind, 10.sp, endColor, weight = FontWeight.Medium)
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
            // A view extension is not one of these: it is a viewer of its own, opened beside the
            // editor rather than swapped into it. Output only — a viewer reads what a run produced,
            // while an input is bytes being authored, which the editor itself is for.
            if (isOut && ws.installedViews.isNotEmpty()) {
                EndPicker(
                    ws, ws.t("viewIn"),
                    selected = null,
                    choices = ws.installedViews.map { it.id to it.name },
                    none = null,
                    placeholder = ws.t("viewInPick"),
                ) { id -> id?.let { ws.openView(it, bytes, node.params) } }
            }
            if (format == Builtin.STRING) {
                EndPicker(
                    ws, ws.t("encodingLabel"),
                    selected = charset,
                    choices = Platform.charsetNames().map { it to it },
                    none = null,
                ) { setParam(CHARSET_PARAM, it) }
            }
            EndPicker(
                ws, ws.t("formatLabel"),
                selected = format,
                choices = Builtin.ALL.map { it to Builtin.name(it) },
                none = null,
            ) { setParam(FORMAT_PARAM, it) }
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

        var windowStart by remember(tab, writing.format, writing.charset, filePath) { mutableStateOf(0L) }
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
                EditableField(bytes, writing, tab, node, windowStart, { windowStart = it }) { problem = it }
            } else {
                val source = if (fileBacked) DataSource.FileRange(filePath!!, totalFileSize) else DataSource.Memory(bytes)
                ReadOnlyView(source, writing, windowStart) { windowStart = it }
            }

            if (totalSize == 0L) {
                Txt(
                    if (isOut) ws.t("dataNoOutput") else ws.t("dataTypeHint"),
                    13.sp, Palette.faintText, mono = true,
                )
            }
        }

        // what the input made of what is typed, said beside the value rather than in place of it
        problem?.let { Txt(it, 11.sp, Palette.errorSoft) }

        val windowEnd = minOf(windowStart + WINDOW_BYTES, totalSize)
        val label = if (totalSize > WINDOW_BYTES)
            ws.t("dataWindowOf").replace("{start}", windowStart.toString())
                .replace("{end}", windowEnd.toString()).replace("{total}", totalSize.toString())
        else ws.t("dataBytes").replace("{n}", totalSize.toString())
        Txt(label, 11.sp, Palette.dimText, mono = true)
    }
}

// How a value is written: text in some encoding, or hex.
//
// Both are the app's own, so this is a plain call — no process, no round trip, nothing to wait for.
// Typing used to go out to an extension and come back, which is why the editor had a settle delay;
// it does not any more.
private class Writing(val format: String, val charset: String) {
    val charsPerByte: Int get() = Builtin.charsPerByte(format)

    fun format(bytes: ByteArray): String = Builtin.format(format, bytes, charset)

    /** The bytes, and what is wrong with the text — a note beside the value, not a failure. */
    fun parse(text: String): Pair<ByteArray, String?> = Builtin.parse(format, text, charset)

    /** Which byte a caret position falls on. */
    fun byteAt(text: String, charPos: Int): Int {
        val clamped = charPos.coerceIn(0, text.length)
        // fixed-width writing divides out exactly; text is counted a character to a byte, which is
        // right for the ASCII it mostly is and close enough elsewhere
        return if (charsPerByte > 0) clamped / charsPerByte else clamped
    }
}

// Editable inline byte field (cin, not file-backed). Only ever holds up to WINDOW_BYTES of the
// underlying value in the text field itself; edits splice back into the full byte array at the
// window's absolute offset, and an oversized paste is committed in full but the displayed field
// snaps back to a bounded slice (starting where the paste landed) rather than staying huge.
//
// Reading and writing the text both cross into the input extension's process, so they happen off
// the keystroke: what is typed appears at once and is turned into bytes a moment later. Without
// that, every character would wait on a round trip.
@Composable
private fun EditableField(
    bytes: ByteArray,
    writing: Writing,
    tab: DataTab,
    node: flow.model.Node,
    windowStart: Long,
    onWindowStart: (Long) -> Unit,
    onProblem: (String?) -> Unit,
) {
    val total = bytes.size
    val winStart = windowStart.toInt().coerceIn(0, total)
    val winSize = minOf(WINDOW_BYTES, total - winStart)
    val windowBytes = remember(bytes, winStart, winSize) {
        if (winSize <= 0) ByteArray(0) else bytes.copyOfRange(winStart, winStart + winSize)
    }

    var field by remember(tab, writing.format, writing.charset) { mutableStateOf(TextFieldValue("")) }
    // the bytes the text in the field stands for. Re-formatting only when the value arrived from
    // somewhere else is what stops the caret jumping to the end on every keystroke.
    var shownKey by remember(tab, writing.format, writing.charset) { mutableStateOf<String?>(null) }
    // exactly what was put in the field, so that displaying a value is never mistaken for editing
    // it. Not every way of writing bytes can carry every byte — text cannot hold what is not text —
    // and without this, opening a binary value under a text input and touching nothing would write
    // the replacement characters back over it.
    var shownText by remember(tab, writing.format, writing.charset) { mutableStateOf<String?>(null) }

    val canonicalKey = bytesToHex(windowBytes)
    LaunchedEffect(canonicalKey, writing.format, writing.charset) {
        if (canonicalKey != shownKey) {
            val text = writing.format(windowBytes)
            field = atEnd(text)
            shownKey = canonicalKey
            shownText = text
        }
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

    // splice the edited window back into the full array at its absolute offset
    fun commit(newWindowBytes: ByteArray) {
        val newFull = spliceWindow(bytes, winStart, winSize, newWindowBytes)
        if (newFull.size > SPILL_THRESHOLD_BYTES) {
            spillToFile(newFull)
            return
        }
        // the field already shows this value, so record it before the node changes — otherwise the
        // effect above would see new bytes arrive and re-format the text under the caret
        shownKey = bytesToHex(
            if (newWindowBytes.size <= WINDOW_BYTES) newWindowBytes else newWindowBytes.copyOfRange(0, WINDOW_BYTES),
        )
        shownText = field.text
        tab.doc.updateNode(node.id) { n ->
            val outs = n.outputs.toMutableList()
            if (outs.isEmpty()) outs.add(Port("out", newFull)) else outs[0] = outs[0].withData(newFull)
            n.copy(outputs = outs)
        }
    }

    // Typing settles into bytes rather than converting on every keystroke: the conversion is a
    // round trip to another process, and one per character would be felt.
    LaunchedEffect(field.text, writing.format, writing.charset) {
        // the field is showing the value, not being edited — leave it alone
        if (field.text == shownText) return@LaunchedEffect
        delay(COMMIT_DELAY_MS)
        val (parsed, problem) = writing.parse(field.text)
        onProblem(problem)
        if (!parsed.contentEquals(windowBytes)) commit(parsed)
    }

    // land the window on `focusByteOffset` after a paste, whether the result stayed in memory or
    // ended up file-backed
    suspend fun showAround(newTotal: Int, focusByteOffset: Int, source: DataSource) {
        val newWinStart = (focusByteOffset - WINDOW_BYTES / 2).coerceIn(0, (newTotal - WINDOW_BYTES).coerceAtLeast(0))
        val newWinSize = minOf(WINDOW_BYTES, newTotal - newWinStart)
        val boundedWin = if (newWinSize <= 0) ByteArray(0) else source.read(newWinStart.toLong(), newWinSize)
        onWindowStart(newWinStart.toLong())
        val text = writing.format(boundedWin)
        field = atEnd(text)
        shownKey = bytesToHex(boundedWin)
        shownText = text
    }

    val scope = rememberCoroutineScope()
    var pasting by remember(tab) { mutableStateOf(false) }

    // Reads the clipboard in bounded chunks so it is never asked for as one string, but the text
    // is assembled before being converted: only the input extension knows how its writing decodes,
    // and a chunk boundary could fall in the middle of a byte. PASTE_TEXT_LIMIT is what keeps that
    // assembly bounded — past it the paste is cut short rather than allowed to grow without end.
    fun handlePaste() {
        if (pasting) return
        val selStart = minOf(field.selection.start, field.selection.end)
        val selEnd = maxOf(field.selection.start, field.selection.end)
        val localByteStart = writing.byteAt(field.text, selStart)
        val localByteEnd = writing.byteAt(field.text, selEnd)
        val absStart = (winStart + localByteStart).coerceIn(0, total)
        val absEnd = (winStart + localByteEnd).coerceIn(absStart, total)
        val prefix = bytes.copyOfRange(0, absStart)
        val suffix = bytes.copyOfRange(absEnd, total)
        pasting = true
        scope.launch {
            val pasted = StringBuilder()
            val got = withContext(Dispatchers.Default) {
                Platform.pasteClipboardChunks(65536) { chunk ->
                    if (pasted.length < PASTE_TEXT_LIMIT) pasted.append(chunk)
                }
            }
            if (!got) { pasting = false; return@launch }
            val (pastedBytes, problem) = writing.parse(pasted.toString())
            onProblem(problem)

            val newFull = ByteArray(prefix.size + pastedBytes.size + suffix.size)
            prefix.copyInto(newFull)
            pastedBytes.copyInto(newFull, prefix.size)
            suffix.copyInto(newFull, prefix.size + pastedBytes.size)
            val pastedEnd = prefix.size + pastedBytes.size

            pasting = false
            if (newFull.size > SPILL_THRESHOLD_BYTES) {
                val path = spillToFile(newFull)
                showAround(newFull.size, pastedEnd, DataSource.FileRange(path, newFull.size.toLong()))
            } else {
                shownKey = null // the window is about to move; let it be re-read for where it lands
                tab.doc.updateNode(node.id) { n ->
                    val outs = n.outputs.toMutableList()
                    if (outs.isEmpty()) outs.add(Port("out", newFull)) else outs[0] = outs[0].withData(newFull)
                    n.copy(outputs = outs)
                }
                showAround(newFull.size, pastedEnd, DataSource.Memory(newFull))
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        BasicTextField(
            value = field,
            // what was typed shows straight away; turning it into bytes happens a moment later
            onValueChange = { field = it },
            textStyle = TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Palette.text),
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)
                .onPreviewKeyEvent { ev ->
                    // intercept Cmd+V ourselves so a paste goes through the chunked read below
                    // instead of Compose's native paste, which would first materialize the whole
                    // clipboard as one in-memory String
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
private fun ReadOnlyView(source: DataSource, writing: Writing, windowStart: Long, onWindowStart: (Long) -> Unit) {
    val total = source.totalSize
    val winSize = minOf(WINDOW_BYTES.toLong(), total - windowStart).coerceAtLeast(0)
    val windowBytes = remember(source, windowStart, winSize) { source.read(windowStart, winSize.toInt()) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(windowBytes, writing.format, writing.charset) { text = writing.format(windowBytes) }

    Row(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        BasicTextField(
            value = text,
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

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> 0
}

/** Splices an edited window back into the whole value at its absolute offset. */
internal fun spliceWindow(full: ByteArray, winStart: Int, winSize: Int, newWindowBytes: ByteArray): ByteArray {
    val out = ByteArray(winStart + newWindowBytes.size + (full.size - winStart - winSize))
    full.copyInto(out, 0, 0, winStart)
    newWindowBytes.copyInto(out, winStart)
    full.copyInto(out, winStart + newWindowBytes.size, winStart + winSize, full.size)
    return out
}

private fun atEnd(s: String) = TextFieldValue(s, TextRange(s.length))
