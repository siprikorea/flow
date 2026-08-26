package flow.view

/**
 * Just enough DER to say what is in a certificate, a key or a piece of signed data.
 *
 * Reads the structure and names what it finds; it is not a validator. Parsing stops at the first
 * byte that does not make sense and keeps what was understood up to there, because half a
 * certificate plus the offset where it went wrong is far more use than "invalid".
 */
/** One TLV, flattened in document order with the depth it sits at. */
class Item(
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
class Der(private val bytes: ByteArray) {

    private val items = ArrayList<Item>()

    fun parse(): List<Item> {
        runCatching { readValues(0, bytes.size, depth = 0) }
            .onFailure {
                items.add(
                    Item(
                        // past everything that was read, so it is last in the list and cannot
                        // collide with a real element — the tree keys its rows by offset, and a
                        // repeated key throws rather than drawing oddly
                        offset = bytes.size, depth = 0, contentStart = bytes.size, end = bytes.size, tag = 0,
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
