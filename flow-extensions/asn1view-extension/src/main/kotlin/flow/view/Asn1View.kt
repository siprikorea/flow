package flow.view

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import flow.extension.ViewExtension
import flow.extension.ViewWindow
import kotlin.concurrent.thread

/**
 * A viewer for DER: the structure on the left, and the bytes of whatever is picked on the right.
 *
 * Reading a certificate as hex tells you almost nothing, and reading it as a tree loses the bytes —
 * which are what you came for when something does not parse the way it should. So both, side by
 * side, each scrolling on its own.
 *
 * It opens a window of its own, in its own process. That is what lets it be a real program: the
 * tree is a LazyColumn that will take a ten-thousand-node certificate, the hex can be selected and
 * copied, and if any of it goes wrong the window is all that is affected.
 */
class Asn1View : ViewExtension {
    override val id = "flow.view.asn1"
    override val displayName = "ASN.1"
    override val version = "3.4.0"
    override val description = "Read DER — certificates, keys, PKCS — as a tree beside its bytes."

    override fun open(data: ByteArray, options: Map<String, String>) {
        // The title bar is the system's, and its colour comes from the appearance the process runs
        // in — so a viewer opened from a dark Flow on a light Mac would wear a light title bar over
        // dark contents. Set before any window exists, which is when it is read.
        val dark = options["theme"] != "light"
        System.setProperty(
            "apple.awt.application.appearance",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
        Windows.open(
            dark = dark,
            corner = ViewWindow.centeredOn(options, Windows.WIDTH, Windows.HEIGHT),
        ) { Asn1Window(data) }
    }
}

/* ───────── the window ───────── */

@Composable
private fun Asn1Window(data: ByteArray) {
    val items = remember(data) { Der(data).parse() }
    var collapsed by remember(data) { mutableStateOf(emptySet<Int>()) }
    var selected by remember(data) { mutableStateOf(items.firstOrNull()) }
    val shown = remember(items, collapsed) { visible(items, collapsed) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // The keys a tree is read with: up and down walk the rows on show, right opens a node and
    // left closes it — and once there is nothing left to open or close, they step into the first
    // child and back out to the parent, so one hand can walk a certificate from end to end.
    fun select(index: Int) {
        shown.getOrNull(index.coerceIn(0, shown.lastIndex))?.let { selected = it }
    }

    fun move(delta: Int) {
        val here = shown.indexOfFirst { it === selected }
        select(if (here < 0) 0 else here + delta)
    }

    fun openOrIn() {
        val item = selected ?: return move(1)
        if (item.hasChildren && item.offset in collapsed) collapsed = collapsed - item.offset else move(1)
    }

    fun closeOrOut() {
        val item = selected ?: return move(-1)
        if (item.hasChildren && item.offset !in collapsed) {
            collapsed = collapsed + item.offset
            return
        }
        val here = shown.indexOfFirst { it === selected }
        val parent = (here - 1 downTo 0).firstOrNull { shown[it].depth < item.depth }
        if (parent != null) select(parent)
    }

    // a window opens ready to be walked, without a click first
    LaunchedEffect(Unit) { focus.requestFocus() }
    // keep the selected row on screen when the keys move it past the edge
    LaunchedEffect(selected) {
        val index = shown.indexOfFirst { it === selected }
        if (index < 0) return@LaunchedEffect
        val visibleRows = listState.layoutInfo.visibleItemsInfo
        val first = visibleRows.firstOrNull()?.index ?: 0
        val last = visibleRows.lastOrNull()?.index ?: 0
        if (visibleRows.isEmpty() || index <= first) listState.scrollToItem(index)
        else if (index >= last) listState.scrollToItem((index - (last - first)).coerceAtLeast(0))
    }

    Column(
        Modifier.fillMaxSize().background(Theme.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.DirectionRight -> { openOrIn(); true }
                    Key.DirectionLeft -> { closeOrOut(); true }
                    else -> false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button("Expand all") { collapsed = emptySet() }
            Button("Collapse all") { collapsed = items.filter { it.hasChildren }.map { it.offset }.toSet() }
            Spacer(Modifier.weight(1f))
            Mono("${data.size} bytes  ·  ${items.size} elements", Theme.muted)
        }

        Row(Modifier.fillMaxSize()) {
            // the two halves scroll separately, which is the whole reason this is a window: a tree
            // of two hundred rows and a dump of two hundred lines have nothing to say to each other
            // about where they are scrolled to
            Box(Modifier.weight(0.45f).fillMaxHeight()) {
                Tree(shown, collapsed, selected, listState, onToggle = { offset ->
                    collapsed = if (offset in collapsed) collapsed - offset else collapsed + offset
                }, onSelect = {
                    selected = it
                    // clicking a row hands the keys back to the tree, after the detail pane's
                    // selectable text has taken focus
                    focus.requestFocus()
                })
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.border))
            Box(Modifier.weight(0.55f).fillMaxHeight()) {
                selected?.let { Detail(data, it) }
            }
        }
    }
}

@Composable
private fun Tree(
    shown: List<Item>,
    collapsed: Set<Int>,
    selected: Item?,
    state: LazyListState,
    onToggle: (Int) -> Unit,
    onSelect: (Item) -> Unit,
) {
    // Lazy, because a certificate chain is thousands of rows and only a screenful is ever on show
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp), state = state) {
        // keyed by where it is in the list, not by offset: an offset is the natural identity but
        // it is data, and data that repeats is a thrown exception rather than a bad drawing
        itemsIndexed(shown) { _, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (item === selected) Theme.selection else Color.Transparent)
                    .plainClick { onSelect(item) }
                    .padding(start = (6 + item.depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(16.dp)) {
                    if (item.hasChildren) {
                        Mono(
                            if (item.offset in collapsed) "▶" else "▼",
                            Theme.muted,
                            modifier = Modifier.plainClick { onToggle(item.offset) },
                        )
                    }
                }
                Mono(
                    item.label,
                    when {
                        item.error != null -> Theme.error
                        item.hasChildren -> Theme.accent
                        else -> Theme.text
                    },
                )
            }
        }
    }
}

@Composable
private fun Detail(data: ByteArray, item: Item) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Facts(item)
        Spacer(Modifier.padding(top = 8.dp))
        // selectable, because the reason to look at these bytes is usually to put them somewhere
        SelectionContainer {
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Mono(HEADER_ROW, Theme.muted)
                    Spacer(Modifier.padding(top = 4.dp))
                    hexRows(data, item).forEach { (offset, bytes) ->
                        Row {
                            Mono("%08X  ".format(offset), Theme.muted)
                            Mono(bytes.hex(), Theme.text)
                            Mono("  ${bytes.printable()}", Theme.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(item: Item) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, RoundedCornerShape(6.dp))
            .border(1.dp, Theme.border, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Fact("Tag", "0x%02X  %s".format(item.tag, item.typeName))
        Fact("Length", "${item.end - item.contentStart} bytes")
        item.oid?.let { Fact("OID", it) }
        item.error?.let { Fact("Problem", it, Theme.error) }
    }
}

@Composable
private fun Fact(name: String, value: String, color: Color = Theme.text) {
    Row {
        Mono(name.padEnd(8) + ": ", Theme.muted)
        SelectionContainer { Mono(value, color) }
    }
}

/* ───────── the bytes of one element ───────── */

private const val PER_ROW = 16
private val HEADER_ROW = "Offset    " + (0 until PER_ROW).joinToString(" ") { "%2X".format(it) } + "  Text"

/** At most this much of one element; a modulus is thousands of bytes and nobody reads them all. */
private const val MAX_BYTES = 4096

private fun hexRows(data: ByteArray, item: Item): List<Pair<Int, ByteArray>> {
    val end = minOf(item.end, data.size, item.offset + MAX_BYTES)
    return (item.offset until end step PER_ROW).map { at ->
        at to data.copyOfRange(at, minOf(at + PER_ROW, end))
    }
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }.padEnd(PER_ROW * 3 - 1)

private fun ByteArray.printable() =
    map { val v = it.toInt() and 0xFF; if (v in 0x20..0x7E) v.toChar() else '.' }.joinToString("")

/** The rows to show: everything except what is inside a branch the user has closed. */
private fun visible(items: List<Item>, collapsed: Set<Int>): List<Item> {
    val out = ArrayList<Item>(items.size)
    var hiddenBelow = Int.MAX_VALUE
    items.forEach { item ->
        if (item.depth > hiddenBelow) return@forEach
        hiddenBelow = if (item.hasChildren && item.offset in collapsed) item.depth else Int.MAX_VALUE
        out.add(item)
    }
    return out
}

/* ───────── small pieces ───────── */
/**
 * JetBrains Mono, so a column of hex is a column.
 *
 * The file is in the extension contract's jar, which this process has on its classpath along with
 * the worker and the Compose the app lends it — the app's own jar is not there, so naming the same
 * resource path is how both sides end up with the same face.
 */
private val JetBrainsMono = FontFamily(
    Font("flow/fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
    Font("flow/fonts/JetBrainsMono-Medium.ttf", FontWeight.Medium),
    Font("flow/fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold),
)

@Composable
private fun Mono(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontSize = 12.sp, fontFamily = JetBrainsMono, maxLines = 1)
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Theme.surface, RoundedCornerShape(5.dp))
            .border(1.dp, Theme.border, RoundedCornerShape(5.dp))
            .plainClick(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = Theme.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun Modifier.plainClick(onClick: () -> Unit): Modifier =
    // no ripple: this is a data viewer, and a splash on every row of a tree is noise
    clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

/* ───────── colours, and the windows themselves ───────── */

private object Theme {
    var dark = true
    val background get() = if (dark) Color(0xFF14171F) else Color(0xFFF7F8FA)
    val surface get() = if (dark) Color(0xFF1D212B) else Color(0xFFFFFFFF)
    val border get() = if (dark) Color(0xFF333A49) else Color(0xFFC3CBD8)
    val text get() = if (dark) Color(0xFFE6E9EF) else Color(0xFF1B2030)
    val muted get() = if (dark) Color(0xFF8A93A6) else Color(0xFF6B7484)
    val accent get() = if (dark) Color(0xFF5B8CFF) else Color(0xFF2F6BFF)
    val error get() = if (dark) Color(0xFFFF7B72) else Color(0xFFD1242F)
    val selection get() = accent.copy(alpha = 0.22f)
}

/**
 * Every window this extension has open.
 *
 * Compose Desktop's `application` runs one event loop and returns when the last window closes, so
 * opening a second viewer cannot simply start another — they share the loop, and the loop starts
 * with the first one and ends with the last.
 */
private object Windows {
    const val WIDTH = 1100f
    const val HEIGHT = 760f

    private class Pane(val corner: Pair<Float, Float>?, val content: @Composable () -> Unit)

    private val open = mutableStateListOf<Pane>()
    private var running = false

    fun open(dark: Boolean, corner: Pair<Float, Float>?, content: @Composable () -> Unit) {
        synchronized(this) {
            Theme.dark = dark
            open.add(Pane(corner, content))
            if (running) return
            running = true
        }
        thread(name = "asn1-view", isDaemon = false) {
            // Whatever happens in here, this has to be put back. It was not, and one window that
            // failed to open left the flag set — after which every later request was added to the
            // list and quietly never shown, because something was already believed to be running.
            try {
                    application {
                    open.forEachIndexed { index, pane ->
                        Window(
                            onCloseRequest = { open.removeAt(index) },
                            state = rememberWindowState(
                                width = WIDTH.dp, height = HEIGHT.dp,
                                // over the window that opened it; centred on screen when it said nothing
                                position = pane.corner?.let { (x, y) -> WindowPosition.Absolute(x.dp, y.dp) }
                                    ?: WindowPosition(Alignment.Center),
                            ),
                            title = "ASN.1",
                        ) {
                            // Asked for a moment ago, so it belongs in front. A process with no dock
                            // icon is a background one as far as macOS is concerned, and toFront alone
                            // does not lift a background app's window above the app that is in front —
                            // raising it on top and letting go immediately does.
                            LaunchedEffect(Unit) { window.bringForward() }
                            pane.content()
                        }
                    }
                }
            } catch (t: Throwable) {
                System.err.println("asn1 view: $t")
            } finally {
                synchronized(this) {
                    running = false
                    // a pane that never opened would otherwise be shown by the next request
                    open.clear()
                }
            }
        }
    }
}

/** Brings a window to the front from a process that has no dock icon. */
private fun java.awt.Window.bringForward() {
    // A process with no dock icon is a background app to macOS, and a background app's window does
    // not come out in front of the one that is — not by toFront, and not by being briefly pinned on
    // top either. Asking to be brought to the foreground is the request that actually means it.
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }
    toFront()
    requestFocus()
}
