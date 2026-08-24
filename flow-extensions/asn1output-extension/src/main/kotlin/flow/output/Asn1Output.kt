package flow.output

import flow.extension.OutputCanvas
import flow.extension.OutputEvent
import flow.extension.OutputExtension

/**
 * DER-encoded ASN.1, as a tree beside the bytes it is made of.
 *
 * This is what certificates, keys, PKCS structures and signed data are, and reading one as hex
 * tells you almost nothing — while reading it as a tree loses the bytes, which is what you came for
 * when something does not parse the way it should. So: the structure on the left, and for whatever
 * is picked there, its own bytes and what its header says on the right.
 *
 * Parsing stops at the first byte that does not make sense and shows what was understood up to
 * there. Half a certificate plus the offset where it went wrong is far more use than one line
 * saying the input was invalid.
 */
class Asn1Output : OutputExtension {
    override val id = "flow.output.asn1"
    override val displayName = "ASN.1 Output"
    override val version = "2.2.0"

    override fun draw(canvas: OutputCanvas, data: ByteArray, options: Map<String, String>) {
        val grid = Grid(canvas)
        val items = Der(data).parse()
        if (items.isEmpty()) {
            canvas.text(grid.x(0), grid.y(0), "(no data)", grid.fontSize, OutputCanvas.MUTED_TEXT, mono = true)
            canvas.contentHeight(grid.height(1))
            return
        }

        val collapsed = numbersIn(options[COLLAPSED])
        val shown = visible(items, collapsed)
        val selected = items.find { it.offset == options[SELECTED]?.toIntOrNull() } ?: shown.first()

        // the tree needs to be wide enough to read and the hex needs whole rows; below that the two
        // together do not fit at all, and the tree is the half you steer from
        // Sixteen bytes a row, always: an offset only reads at a glance when the low nibble is the
        // column, and a dump that is eight wide one moment and sixteen the next cannot be scanned
        // at all. When that leaves the tree too narrow to read, the characters column goes first —
        // it is the one part of the dump the hex beside it already says.
        val showText = canvas.width - COLUMNS_WITH_TEXT * grid.charWidth - grid.pad * 4 >= MIN_TREE_WIDTH
        val detailWidth = (if (showText) COLUMNS_WITH_TEXT else COLUMNS_BARE) * grid.charWidth
        val treeWidth = (canvas.width - detailWidth - grid.pad * 4).coerceIn(180f, canvas.width * 0.55f)
        val rows = drawTree(canvas, grid, shown, collapsed, selected, treeWidth, items)

        val dividerX = treeWidth + grid.pad
        val rightRows = drawDetail(canvas, grid, data, selected, dividerX + grid.pad * 2, showText)

        // drawn last, once both sides have said how tall they are, so it runs the whole way down
        val height = grid.height(maxOf(rows, rightRows))
        canvas.line(dividerX, grid.pad, dividerX, height - grid.pad, OutputCanvas.BORDER, 1f)
        canvas.contentHeight(height)
    }

    /* ───────── the tree ───────── */

    private fun drawTree(
        canvas: OutputCanvas,
        grid: Grid,
        shown: List<Item>,
        collapsed: Set<Int>,
        selected: Item,
        width: Float,
        all: List<Item>,
    ): Int {
        // Open and close everything, which is how you find your way around a certificate: shut it
        // all and open the one branch you want. Closing everything needs to know what the branches
        // are, and this is called again from scratch each time — so the offsets travel in the
        // button's own name, the same way a hex row carries the layout that reads a click back.
        val branches = all.filter { it.hasChildren }.joinToString(",") { it.offset.toString() }
        var at = 0f
        at = button(canvas, grid, at, "expand all", EXPAND_ALL)
        button(canvas, grid, at + grid.charWidth, "collapse all", "$COLLAPSE_ALL$branches")

        shown.forEachIndexed { index, item ->
            val row = index + HEADER_ROWS
            val y = grid.y(row)
            if (item === selected) {
                canvas.rect(0f, y - 2f, width, grid.lineHeight, OutputCanvas.SELECTION, filled = true)
            }
            // the whole row picks it; the marker toggles it. The marker is declared second so it
            // wins where the two overlap — the last region declared is the one on top.
            canvas.region(0f, y - 2f, width, grid.lineHeight, "$SELECT${item.offset}")

            val indent = grid.x(item.depth * 2)
            if (item.hasChildren) {
                val marker = if (item.offset in collapsed) "▶" else "▼"
                canvas.text(indent, y, marker, grid.fontSize, OutputCanvas.MUTED_TEXT, mono = true)
                canvas.region(indent - 2f, y - 2f, grid.charWidth * 2, grid.lineHeight, "$TOGGLE${item.offset}")
            }
            // Nothing clips what is drawn, so a label that would run past the divider has to be
            // cut here — otherwise a long BIT STRING preview lands on top of the detail panel.
            val labelX = indent + grid.charWidth * 2
            val room = ((width - labelX) / grid.charWidth).toInt() - 1
            canvas.text(
                labelX, y, fit(item.label, room), grid.fontSize,
                when {
                    item.error != null -> OutputCanvas.ERROR
                    item.hasChildren -> OutputCanvas.ACCENT
                    else -> OutputCanvas.DEFAULT_TEXT
                },
                mono = true,
            )
        }
        return shown.size + HEADER_ROWS
    }

    /**
     * A button, drawn as one: a filled box with a border and a label, and a region over it.
     *
     * Text alone reads as a line of the tree rather than as something to press, which is what it
     * was mistaken for. Returns where the next one starts.
     */
    private fun button(canvas: OutputCanvas, grid: Grid, x: Float, label: String, region: String): Float {
        val pad = grid.charWidth
        val width = label.length * grid.charWidth + pad * 2
        val height = grid.lineHeight
        val y = grid.y(0) - 3f
        canvas.rect(x, y, width, height, OutputCanvas.SELECTION, filled = true)
        canvas.rect(x, y, width, height, OutputCanvas.BORDER, filled = false)
        canvas.text(x + pad, grid.y(0), label, grid.fontSize, OutputCanvas.ACCENT, mono = true)
        canvas.region(x, y, width, height, region)
        return x + width
    }

    private fun fit(text: String, columns: Int): String =
        if (columns < 2 || text.length <= columns) text else text.take(columns - 1) + "…"

    /* ───────── the bytes, and what the header says about them ───────── */

    private fun drawDetail(
        canvas: OutputCanvas,
        grid: Grid,
        data: ByteArray,
        item: Item,
        left: Float,
        showText: Boolean,
    ): Int {
        fun x(column: Int) = left + column * grid.charWidth
        var row = 0
        fun line(text: String, color: Int, column: Int = 0) {
            canvas.text(x(column), grid.y(row), text, grid.fontSize, color, mono = true)
        }

        val perRow = 16

        line("Offset", OutputCanvas.MUTED_TEXT)
        (0 until perRow).forEach { i ->
            line("%2X".format(i), OutputCanvas.MUTED_TEXT, OFFSET_COLUMNS + i * 3)
        }
        if (showText) line("Text", OutputCanvas.MUTED_TEXT, OFFSET_COLUMNS + perRow * 3 + 1)
        row += 2

        val end = minOf(item.end, data.size)
        val shownEnd = minOf(end, item.offset + MAX_DETAIL_BYTES)
        var at = item.offset
        while (at < shownEnd) {
            val stop = minOf(at + perRow, shownEnd)
            line("%08X".format(at), OutputCanvas.MUTED_TEXT)
            val text = StringBuilder(perRow)
            for (i in at until stop) {
                val b = data[i].toInt() and 0xFF
                // the header — the tag and the length — reads differently from the value it wraps
                val header = i < item.contentStart
                line(
                    "%02X".format(b),
                    if (header) OutputCanvas.ACCENT else OutputCanvas.DEFAULT_TEXT,
                    OFFSET_COLUMNS + (i - at) * 3,
                )
                text.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            if (showText) line(text.toString(), OutputCanvas.MUTED_TEXT, OFFSET_COLUMNS + perRow * 3 + 1)
            at = stop
            row++
        }
        if (shownEnd < end) {
            line("… ${end - shownEnd} more bytes", OutputCanvas.MUTED_TEXT)
            row++
        }
        row++

        // Only what the bytes above do not already say. The offset is the first column of the
        // dump, the header is its first bytes in the accent colour, and the class and P/C bit are
        // readable off the tag — so what is left is the tag's name and how much it wraps.
        val facts = buildList {
            add("Tag" to "0x%02X  %s".format(item.tag, item.typeName))
            add("Length" to "${item.end - item.contentStart} bytes")
            item.oid?.let { add("OID" to it) }
            item.error?.let { add("Problem" to it) }
        }
        val width = facts.maxOf { it.first.length } + 2
        facts.forEach { (name, value) ->
            line(name.padEnd(width) + ": ", OutputCanvas.MUTED_TEXT)
            line(value, if (name == "Problem") OutputCanvas.ERROR else OutputCanvas.DEFAULT_TEXT, width + 2)
            row++
        }
        return row
    }

    /* ───────── being clicked on ───────── */

    override fun onEvent(event: OutputEvent, options: Map<String, String>): Map<String, String> {
        if (event.kind != OutputEvent.CLICK) return options
        val region = event.region ?: return options
        return when {
            region == EXPAND_ALL -> options - COLLAPSED
            region.startsWith(COLLAPSE_ALL) -> {
                // everything, the outermost included: one closed row is what "collapse all" means,
                // and opening it again is one click
                val all = numbersIn(region.removePrefix(COLLAPSE_ALL))
                options + (COLLAPSED to all.sorted().joinToString(","))
            }
            region.startsWith(TOGGLE) -> {
                val offset = region.removePrefix(TOGGLE).toIntOrNull() ?: return options
                val collapsed = numbersIn(options[COLLAPSED]).toMutableSet()
                // one click closes what is open and opens what is closed
                if (!collapsed.add(offset)) collapsed.remove(offset)
                options + (COLLAPSED to collapsed.sorted().joinToString(","))
            }
            region.startsWith(SELECT) -> {
                val offset = region.removePrefix(SELECT).toIntOrNull() ?: return options
                options + (SELECTED to offset.toString())
            }
            else -> options
        }
    }

    /** The items to draw: everything except what is inside a branch the user has closed. */
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

    private fun numbersIn(text: String?): Set<Int> =
        text?.split(',').orEmpty().mapNotNull { it.trim().toIntOrNull() }.toSet()

    private companion object {
        /** Offsets of the branches the user has clicked shut, comma-separated. */
        const val COLLAPSED = "asn1.collapsed"

        /** The offset of the row on show in the detail panel. */
        const val SELECTED = "asn1.selected"

        const val TOGGLE = "t"
        const val SELECT = "s"
        const val EXPAND_ALL = "xa"
        const val COLLAPSE_ALL = "ca"

        /** The buttons, and the blank line under them. */
        const val HEADER_ROWS = 2



        /** Eight offset digits and the two spaces after them. */
        const val OFFSET_COLUMNS = 10

        /** Eight offset digits, two spaces, then three characters a byte. */
        const val COLUMNS_BARE = OFFSET_COLUMNS + 16 * 3

        /** The same, plus a gap and one character a byte for the characters column. */
        const val COLUMNS_WITH_TEXT = COLUMNS_BARE + 1 + 16

        /** Below this the tree stops being a tree and starts being a column of ellipses. */
        const val MIN_TREE_WIDTH = 320f

        /** A key's modulus is thousands of bytes and nobody reads them all here. */
        const val MAX_DETAIL_BYTES = 2048
    }
}

/** One TLV, flattened in document order with the depth it sits at. */
internal class Item(
    val offset: Int,
    val depth: Int,
    val contentStart: Int,
    val end: Int,
    val tag: Int,
    val constructed: Boolean,
    val className: String,
    val typeName: String,
    val label: String,
    val hasChildren: Boolean,
    val oid: String? = null,
    val value: String? = null,
    val error: String? = null,
) {
    fun headerHex(data: ByteArray): String =
        (offset until minOf(contentStart, data.size)).joinToString(" ") { "%02X".format(data[it]) }
}

/**
 * Just enough DER to name what is there and show its value. Not a validator: it reads the
 * structure, and anything it cannot read it says so about rather than rejecting the whole input.
 */
internal class Der(private val bytes: ByteArray) {

    private val items = ArrayList<Item>()

    fun parse(): List<Item> {
        runCatching { readValues(0, bytes.size, depth = 0) }
            .onFailure {
                items.add(
                    Item(
                        offset = 0, depth = 0, contentStart = 0, end = bytes.size, tag = 0,
                        constructed = false, className = "?", typeName = "?",
                        label = it.message ?: "could not be parsed", hasChildren = false,
                        error = it.message ?: "could not be parsed",
                    ),
                )
            }
        return items
    }

    private fun readValues(from: Int, to: Int, depth: Int) {
        var i = from
        while (i < to) {
            if (items.size > MAX_ITEMS) return
            i = readValue(i, to, depth)
        }
    }

    private fun readValue(start: Int, limit: Int, depth: Int): Int {
        val offset = start
        var i = start
        val tag = bytes[i].toInt() and 0xFF
        i++
        val constructed = tag and 0x20 != 0
        val number = if (tag and 0x1F == 0x1F) readHighTagNumber(i, limit).also { i = it.second }.first
        else tag and 0x1F
        val (length, afterLength) = readLength(i, limit)
        i = afterLength
        val end = i + length
        require(end in i..limit) { "a length at offset $offset runs past the end of the data" }

        val className = when (tag and 0xC0) {
            0x80 -> "Context-specific"
            0x40 -> "Application"
            0xC0 -> "Private"
            else -> "Universal"
        }
        val typeName = when (tag and 0xC0) {
            0x80 -> "[$number]"
            0x40 -> "APPLICATION $number"
            0xC0 -> "PRIVATE $number"
            else -> UNIVERSAL[number] ?: "UNIVERSAL $number"
        }

        if (constructed) {
            items.add(
                Item(
                    offset, depth, i, end, tag, true, className, typeName,
                    label = "$typeName ($length bytes)", hasChildren = true,
                ),
            )
            if (depth < MAX_DEPTH) readValues(i, end, depth + 1)
        } else {
            val oid = if (tag and 0xC0 == 0 && number == 6) oid(bytes.copyOfRange(i, end)) else null
            val value = primitive(number, tag, i, end)
            items.add(
                Item(
                    offset, depth, i, end, tag, false, className, typeName,
                    label = if (value.isBlank()) typeName else "$typeName  $value",
                    hasChildren = false, oid = oid, value = value,
                ),
            )
        }
        return end
    }

    private fun readHighTagNumber(from: Int, limit: Int): Pair<Int, Int> {
        var i = from
        var value = 0
        while (true) {
            require(i < limit) { "a tag at offset $from is never finished" }
            val b = bytes[i].toInt() and 0xFF
            i++
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return value to i
            require(value < 1 shl 21) { "a tag at offset $from is unreasonably large" }
        }
    }

    private fun readLength(from: Int, limit: Int): Pair<Int, Int> {
        require(from < limit) { "a value at offset $from has no length" }
        val first = bytes[from].toInt() and 0xFF
        if (first < 0x80) return first to from + 1
        val count = first and 0x7F
        // 0x80 is BER's indefinite length, which DER does not allow and this cannot follow
        require(count in 1..4) { "the length at offset $from is not one this can read" }
        require(from + 1 + count <= limit) { "the length at offset $from runs past the end" }
        var value = 0
        for (k in 1..count) value = (value shl 8) or (bytes[from + k].toInt() and 0xFF)
        require(value >= 0) { "the length at offset $from is not a size" }
        return value to from + 1 + count
    }

    private fun primitive(number: Int, tag: Int, from: Int, to: Int): String {
        val slice = bytes.copyOfRange(from, to)
        if (tag and 0xC0 != 0) return hex(slice) // not universal: the tag says nothing about the value
        return when (number) {
            1 -> if (slice.isEmpty()) "?" else if (slice[0].toInt() == 0) "false" else "true"
            2 -> integer(slice)
            3 -> bitString(slice)
            5 -> ""
            6 -> oid(slice)
            12, 19, 20, 22, 26, 27 -> "'" + printable(String(slice, Charsets.UTF_8)) + "'"
            23, 24 -> "'" + printable(String(slice, Charsets.US_ASCII)) + "'"
            30 -> "'" + printable(String(slice, Charsets.UTF_16BE)) + "'"
            else -> hex(slice)
        }
    }

    private fun integer(v: ByteArray): String =
        // small ones read as numbers; a 2048-bit modulus is only ever useful as hex
        if (v.size <= 8) java.math.BigInteger(v).toString() else "${v.size} bytes  ${hex(v)}"

    private fun bitString(v: ByteArray): String {
        if (v.isEmpty()) return "(empty)"
        val unused = v[0].toInt() and 0xFF
        val bits = (v.size - 1) * 8 - unused
        return "$bits bits  ${hex(v.copyOfRange(1, v.size))}"
    }

    /** The first byte packs the first two arcs; the rest are base-128 with a continuation bit. */
    private fun oid(v: ByteArray): String {
        if (v.isEmpty()) return "(empty)"
        val out = StringBuilder()
        val first = v[0].toInt() and 0xFF
        out.append(first / 40).append('.').append(first % 40)
        var value = 0L
        for (k in 1 until v.size) {
            val b = v[k].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                out.append('.').append(value)
                value = 0
            } else if (value > 1L shl 56) {
                return hex(v) // not an OID this can read; the bytes are still worth showing
            }
        }
        val text = out.toString()
        // the name first: in the tree a long label is cut to fit, and the name is the half worth
        // keeping — the number is in the detail panel either way
        return Oids.name(text)?.let { "$it  ($text)" } ?: text
    }

    private fun hex(v: ByteArray): String {
        val shown = v.copyOfRange(0, v.size.coerceAtMost(MAX_HEX))
        val text = shown.joinToString("") { "%02X".format(it) }
        return if (v.size > shown.size) "$text…" else text
    }

    private fun printable(s: String) = s.map { if (it.code in 0x20..0x7E || it.code > 0xA0) it else '.' }
        .joinToString("").take(120)

    private companion object {
        const val MAX_DEPTH = 24
        const val MAX_ITEMS = 5000
        const val MAX_HEX = 24

        val UNIVERSAL = mapOf(
            1 to "BOOLEAN", 2 to "INTEGER", 3 to "BIT STRING", 4 to "OCTET STRING", 5 to "NULL",
            6 to "OBJECT IDENTIFIER", 10 to "ENUMERATED", 12 to "UTF8String", 16 to "SEQUENCE",
            17 to "SET", 19 to "PrintableString", 20 to "T61String", 22 to "IA5String",
            23 to "UTCTime", 24 to "GeneralizedTime", 26 to "VisibleString", 27 to "GeneralString",
            30 to "BMPString",
        )

    }
}
