package flow.output

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.OutputCanvas
import flow.extension.OutputEvent
import flow.extension.OutputExtension

/**
 * DER-encoded ASN.1 as a tree.
 *
 * This is what certificates, keys, PKCS structures and signed data are made of, and reading one as
 * hex tells you almost nothing. Parsing stops at the first byte that does not make sense and shows
 * what was understood up to there, with the problem in place — half a certificate plus the offset
 * where it went wrong is far more use than one line saying the input was invalid.
 *
 * Anything with children can be clicked shut. A certificate is a few hundred lines and most of any
 * given look at one is spent on a handful of them, so which branches are open is worth keeping —
 * and it is kept in the options, which travel with the flow.
 */
class Asn1Output : OutputExtension {
    override val id = "flow.output.asn1"
    override val displayName = "ASN.1 Output"
    override val version = "1.1.0"
    override val options = listOf(
        ExtensionOption("showOffsets", OptionType.SELECT, "true", listOf("true", "false")),
    )

    override fun draw(canvas: OutputCanvas, data: ByteArray, options: Map<String, String>) {
        val grid = Grid(canvas)
        val collapsed = collapsedOf(options)
        val rows = ArrayList<Row>()
        runCatching { Der(data, rows, collapsed).readSequenceOfValues(0, data.size, depth = 0) }
            .onFailure { rows.add(Row(0, it.message ?: "could not be parsed", OutputCanvas.ERROR, -1, false)) }

        if (rows.isEmpty()) rows.add(Row(0, "(no data)", OutputCanvas.MUTED_TEXT, -1, false))
        val showOffsets = options["showOffsets"] != "false"
        val offsetColumns = if (showOffsets) 8 else 0

        rows.forEachIndexed { index, row ->
            val y = grid.y(index)
            if (showOffsets && row.offset >= 0) {
                canvas.text(
                    grid.x(0), y, row.offset.toString().padStart(6),
                    grid.fontSize, OutputCanvas.MUTED_TEXT, mono = true,
                )
            }
            val marker = when {
                !row.hasChildren -> "  "
                row.offset in collapsed -> "▶ "
                else -> "▼ "
            }
            canvas.text(
                grid.x(offsetColumns + row.depth * 2), y, marker + row.text,
                grid.fontSize, row.color, mono = true,
            )
            // the whole line is the target, not just the marker: a two-character hit area is a
            // thing to aim at, and there is nothing else on the line to click
            if (row.hasChildren) {
                canvas.region(0f, y, canvas.width, grid.lineHeight, "n${row.offset}")
            }
        }
        canvas.contentHeight(grid.height(rows.size))
    }

    override fun onEvent(event: OutputEvent, options: Map<String, String>): Map<String, String> {
        if (event.kind != OutputEvent.CLICK) return options
        val offset = event.region?.removePrefix("n")?.toIntOrNull() ?: return options
        val collapsed = collapsedOf(options).toMutableSet()
        // one click closes what is open and opens what is closed
        if (!collapsed.add(offset)) collapsed.remove(offset)
        return options + (COLLAPSED to collapsed.sorted().joinToString(","))
    }

    private fun collapsedOf(options: Map<String, String>): Set<Int> =
        options[COLLAPSED]?.split(',').orEmpty().mapNotNull { it.trim().toIntOrNull() }.toSet()

    internal class Row(
        val depth: Int,
        val text: String,
        val color: Int,
        val offset: Int,
        val hasChildren: Boolean,
    )

    /**
     * Just enough DER to name what is there and show its value. Not a validator: it reads the
     * structure, and anything it cannot read it says so about rather than rejecting the whole input.
     */
    private class Der(
        private val bytes: ByteArray,
        private val rows: MutableList<Row>,
        private val collapsed: Set<Int>,
    ) {

        fun readSequenceOfValues(from: Int, to: Int, depth: Int) {
            var i = from
            while (i < to) {
                if (rows.size > MAX_ROWS) {
                    rows.add(Row(depth, "… too many elements to show", OutputCanvas.MUTED_TEXT, -1, false))
                    return
                }
                i = readValue(i, to, depth)
            }
        }

        /** Reads one TLV starting at [start] and returns where the next one begins. */
        fun readValue(start: Int, limit: Int, depth: Int): Int {
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

            val name = nameOf(tag, number)
            if (constructed) {
                rows.add(Row(depth, "$name ($length bytes)", OutputCanvas.ACCENT, offset, true))
                when {
                    offset in collapsed -> Unit // shut: its children are not drawn at all
                    depth < MAX_DEPTH -> readSequenceOfValues(i, end, depth + 1)
                    else -> rows.add(Row(depth + 1, "… nested too deeply to show", OutputCanvas.MUTED_TEXT, -1, false))
                }
            } else {
                rows.add(
                    Row(depth, "$name  ${primitive(number, tag, i, end)}", OutputCanvas.DEFAULT_TEXT, offset, false),
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

        private fun nameOf(tag: Int, number: Int): String = when {
            tag and 0xC0 == 0x80 -> "[$number]"                       // context-specific
            tag and 0xC0 == 0x40 -> "APPLICATION $number"
            tag and 0xC0 == 0xC0 -> "PRIVATE $number"
            else -> UNIVERSAL[number] ?: "UNIVERSAL $number"
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
                12, 19, 20, 22, 26, 27 -> "\"" + printable(String(slice, Charsets.UTF_8)) + "\""
                23, 24 -> printable(String(slice, Charsets.US_ASCII))
                30 -> "\"" + printable(String(slice, Charsets.UTF_16BE)) + "\""
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
            return OIDS[text]?.let { "$text  ($it)" } ?: text
        }

        private fun hex(v: ByteArray): String {
            val shown = v.copyOfRange(0, v.size.coerceAtMost(MAX_HEX))
            val text = shown.joinToString("") { "%02X".format(it) }
            return if (v.size > shown.size) "$text…" else text
        }

        private fun printable(s: String) = s.map { if (it.code in 0x20..0x7E || it.code > 0xA0) it else '.' }
            .joinToString("").take(120)
    }

    private companion object {
        /** Offsets of the branches the user has clicked shut, comma-separated. */
        const val COLLAPSED = "asn1.collapsed"

        const val MAX_DEPTH = 24
        const val MAX_ROWS = 5000
        const val MAX_HEX = 32

        val UNIVERSAL = mapOf(
            1 to "BOOLEAN", 2 to "INTEGER", 3 to "BIT STRING", 4 to "OCTET STRING", 5 to "NULL",
            6 to "OBJECT IDENTIFIER", 10 to "ENUMERATED", 12 to "UTF8String", 16 to "SEQUENCE",
            17 to "SET", 19 to "PrintableString", 20 to "T61String", 22 to "IA5String",
            23 to "UTCTime", 24 to "GeneralizedTime", 26 to "VisibleString", 27 to "GeneralString",
            30 to "BMPString",
        )

        // the handful that turn an unreadable dotted number into the thing it names
        val OIDS = mapOf(
            "1.2.840.113549.1.1.1" to "rsaEncryption",
            "1.2.840.113549.1.1.11" to "sha256WithRSAEncryption",
            "1.2.840.113549.1.1.5" to "sha1WithRSAEncryption",
            "1.2.840.10045.2.1" to "ecPublicKey",
            "1.2.840.10045.4.3.2" to "ecdsaWithSHA256",
            "2.5.4.3" to "commonName",
            "2.5.4.6" to "countryName",
            "2.5.4.7" to "localityName",
            "2.5.4.8" to "stateOrProvinceName",
            "2.5.4.10" to "organizationName",
            "2.5.4.11" to "organizationalUnitName",
            "2.5.29.14" to "subjectKeyIdentifier",
            "2.5.29.15" to "keyUsage",
            "2.5.29.17" to "subjectAltName",
            "2.5.29.19" to "basicConstraints",
            "2.5.29.31" to "cRLDistributionPoints",
            "2.5.29.35" to "authorityKeyIdentifier",
            "2.16.840.1.101.3.4.2.1" to "sha-256",
        )
    }
}
