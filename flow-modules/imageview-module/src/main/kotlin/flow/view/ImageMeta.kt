package flow.view

import java.util.zip.Inflater

/**
 * What an image says about itself, read straight out of the file.
 *
 * Every picture carries more than pixels — where it was taken, with what, when, at what exposure,
 * which way up it is meant to be — and a viewer that shows only the pixels throws that away. A
 * module hands over bytes and nothing else, so the file itself is the only place it can come from.
 *
 * Read by hand, without a library: ImageIO's metadata tree differs per plugin and still leaves EXIF
 * as an opaque blob, and reading a TIFF directory is a few dozen lines. Nothing here trusts the
 * file — they are bytes from a module, possibly not an image at all — so every read is bounded and
 * a malformed section ends at itself rather than taking the window down.
 *
 * Public for the same reason the panel is: the tests live in the app's source set, and what a file
 * says about itself is worth checking against files something else wrote.
 */
object ImageMeta {

    /** A group of rows under a heading, as the panel shows them. */
    data class Section(val title: String, val rows: List<Pair<String, String>>)

    fun read(data: ByteArray): List<Section> {
        val sections = ArrayList<Section>()
        val format = format(data)
        val file = buildList {
            add("format" to (format ?: "unknown"))
            // "bytes" rather than "size", which the pixel dimensions below have a better claim to
            add("bytes" to size(data.size.toLong()))
        }
        when (format) {
            "PNG" -> {
                add(sections, "file", file + runCatching { png(data) }.getOrDefault(emptyList()))
                add(sections, "text", runCatching { pngText(data) }.getOrDefault(emptyList()))
            }
            "JPEG" -> {
                add(sections, "file", file + runCatching { jpeg(data) }.getOrDefault(emptyList()))
                val exif = exif(data)
                add(sections, "camera", exif["image"].orEmpty())
                add(sections, "exposure", exif["exif"].orEmpty())
                add(sections, "location", exif["gps"].orEmpty())
            }
            "GIF" -> add(sections, "file", file + runCatching { gif(data) }.getOrDefault(emptyList()))
            "BMP" -> add(sections, "file", file + runCatching { bmp(data) }.getOrDefault(emptyList()))
            "WEBP" -> add(sections, "file", file + runCatching { webp(data) }.getOrDefault(emptyList()))
            else -> add(sections, "file", file)
        }
        return sections
    }

    private fun add(into: MutableList<Section>, title: String, rows: List<Pair<String, String>>) {
        if (rows.isNotEmpty()) into.add(Section(title, rows))
    }

    /** By what the bytes begin with, never by a file name — there isn't one. */
    fun format(data: ByteArray): String? = when {
        data.size < 12 -> null
        data.startsWith(0x89, 0x50, 0x4E, 0x47) -> "PNG"
        data.startsWith(0xFF, 0xD8, 0xFF) -> "JPEG"
        data.startsWith(0x47, 0x49, 0x46, 0x38) -> "GIF"
        data.startsWith(0x42, 0x4D) -> "BMP"
        data.startsWith(0x52, 0x49, 0x46, 0x46) &&
            runCatching { data.decodeToString(8, 12) }.getOrNull() == "WEBP" -> "WEBP"
        data.startsWith(0x49, 0x49, 0x2A, 0x00) || data.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> "TIFF"
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == (prefix[it] and 0xFF) }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes * 10 / 1024) / 10.0} KB"
        else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    }

    /* ───────── PNG ───────── */

    private fun png(data: ByteArray): List<Pair<String, String>> = buildList {
        chunks(data).forEach { (type, body) ->
            when (type) {
                "IHDR" -> if (body.size >= 13) {
                    add("pixels" to "${int(body, 0)} x ${int(body, 4)}")
                    add("depth" to "${body[8].toInt() and 0xFF} bit")
                    add("colour" to (PNG_COLOUR[body[9].toInt() and 0xFF] ?: "type ${body[9].toInt()}"))
                    if (body[12].toInt() != 0) add("interlaced" to "yes")
                }
                // pixels per metre, which is how PNG says DPI
                "pHYs" -> if (body.size >= 9 && body[8].toInt() == 1) {
                    add("density" to "${Math.round(int(body, 0) * 0.0254)} dpi")
                }
                "tIME" -> if (body.size >= 7) add(
                    "modified" to "%04d-%02d-%02d %02d:%02d:%02d".format(
                        ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF),
                        body[2].toInt() and 0xFF, body[3].toInt() and 0xFF,
                        body[4].toInt() and 0xFF, body[5].toInt() and 0xFF, body[6].toInt() and 0xFF,
                    ),
                )
            }
        }
    }

    /** tEXt/zTXt/iTXt — where a PNG keeps its author, its software, its comment. */
    private fun pngText(data: ByteArray): List<Pair<String, String>> = buildList {
        chunks(data).forEach { (type, body) ->
            runCatching {
                val split = body.indexOf(0)
                when {
                    type == "tEXt" && split > 0 ->
                        add(body.decodeToString(0, split) to body.decodeToString(split + 1, body.size).trim())
                    type == "zTXt" && split > 0 && body.size > split + 2 ->
                        add(body.decodeToString(0, split) to inflate(body.copyOfRange(split + 2, body.size)).decodeToString().trim())
                    type == "iTXt" && split > 0 && body.size > split + 3 -> {
                        // keyword 0 compressed 0 method 0 language 0 translated 0 text
                        val compressed = body[split + 1].toInt() == 1
                        var at = split + 3
                        repeat(2) { at = body.indexOf(0, at) + 1 }
                        if (at in 1 until body.size) {
                            val rest = body.copyOfRange(at, body.size)
                            add(body.decodeToString(0, split) to (if (compressed) inflate(rest) else rest).decodeToString().trim())
                        }
                    }
                }
            }
        }
    }

    /** Type and content of each chunk, stopping at the first that does not fit. */
    private fun chunks(data: ByteArray): List<Pair<String, ByteArray>> = buildList {
        var at = 8 // past the signature
        while (at + 8 <= data.size) {
            val length = int(data, at)
            val type = runCatching { data.decodeToString(at + 4, at + 8) }.getOrNull() ?: return@buildList
            if (length < 0 || at + 12 + length > data.size) return@buildList
            add(type to data.copyOfRange(at + 8, at + 8 + length))
            if (type == "IEND") return@buildList
            at += 12 + length
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished() && out.size() < 64 * 1024) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private val PNG_COLOUR = mapOf(
        0 to "greyscale", 2 to "RGB", 3 to "palette", 4 to "greyscale + alpha", 6 to "RGBA",
    )

    /* ───────── JPEG ───────── */

    private fun jpeg(data: ByteArray): List<Pair<String, String>> = buildList {
        segments(data).forEach { (marker, body) ->
            when {
                // SOF0..SOF15, minus the three that are not frame headers
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                    if (body.size >= 6 && none { it.first == "pixels" }) {
                        add("pixels" to "${short(body, 3)} x ${short(body, 1)}")
                        add("depth" to "${body[0].toInt() and 0xFF} bit")
                        add("components" to "${body[5].toInt() and 0xFF}")
                        add("encoding" to if (marker == 0xC2) "progressive" else "baseline")
                    }
                marker == 0xE0 && body.size >= 12 && runCatching { body.decodeToString(0, 4) }.getOrNull() == "JFIF" ->
                    if (body[7].toInt() == 1) add("density" to "${short(body, 8)} dpi")
            }
        }
    }

    /** Marker and payload of each segment, up to the start of the compressed data. */
    private fun segments(data: ByteArray): List<Pair<Int, ByteArray>> = buildList {
        var at = 2
        while (at + 4 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) return@buildList
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return@buildList // end, or the image data itself
            val length = short(data, at + 2)
            if (length < 2 || at + 2 + length > data.size) return@buildList
            add(marker to data.copyOfRange(at + 4, at + 2 + length))
            at += 2 + length
        }
    }

    /* ───────── EXIF ───────── */

    /** The three directories worth showing, each as label to value. */
    fun exif(data: ByteArray): Map<String, List<Pair<String, String>>> {
        if (format(data) != "JPEG") return emptyMap()
        val app1 = segments(data).firstOrNull { (marker, body) ->
            marker == 0xE1 && body.size > 6 && runCatching { body.decodeToString(0, 4) }.getOrNull() == "Exif"
        }?.second ?: return emptyMap()
        val tiff = app1.copyOfRange(6, app1.size) // past "Exif" and two zero bytes
        return runCatching { Tiff(tiff).read() }.getOrDefault(emptyMap())
    }

    /**
     * A TIFF directory, which is what EXIF is.
     *
     * Byte order first (II or MM), then a chain of directories: a count, then twelve bytes per
     * entry — tag, type, count, and either the value itself or an offset to it. Two of the entries
     * point at further directories: the EXIF one and the GPS one.
     */
    private class Tiff(private val bytes: ByteArray) {
        private val little = bytes.size >= 2 && bytes[0].toInt() == 0x49

        fun read(): Map<String, List<Pair<String, String>>> {
            require(bytes.size >= 8) { "not a TIFF header" }
            val image = entries(int(4))
            val out = LinkedHashMap<String, List<Pair<String, String>>>()
            out["image"] = IMAGE_TAGS.mapNotNull { (tag, label) -> image[tag]?.let { label to format(tag, it) } }
                .filter { it.second.isNotBlank() }
            image[EXIF_IFD]?.let { pointer ->
                val exif = entries(pointer.asLong())
                out["exif"] = EXIF_TAGS.mapNotNull { (tag, label) -> exif[tag]?.let { label to format(tag, it) } }
                    .filter { it.second.isNotBlank() }
            }
            image[GPS_IFD]?.let { pointer -> out["gps"] = gps(entries(pointer.asLong())) }
            return out
        }

        /** Tag to value for one directory, ignoring any entry that does not fit the bytes we have. */
        private fun entries(offset: Long): Map<Int, Value> {
            val at = offset.toInt()
            if (at < 0 || at + 2 > bytes.size) return emptyMap()
            val count = short(at)
            val out = LinkedHashMap<Int, Value>()
            for (i in 0 until minOf(count, 512)) {
                val entry = at + 2 + i * 12
                if (entry + 12 > bytes.size) break
                val tag = short(entry)
                val type = short(entry + 2)
                val length = int(entry + 4).toInt()
                val unit = TYPE_SIZES[type] ?: continue
                val total = unit.toLong() * length
                if (length < 0 || total > 1L shl 20) continue
                val from = if (total <= 4) entry + 8 else int(entry + 8).toInt()
                if (from < 0 || from + total > bytes.size) continue
                out[tag] = Value(type, length, bytes.copyOfRange(from, (from + total).toInt()))
            }
            return out
        }

        private fun gps(entries: Map<Int, Value>): List<Pair<String, String>> = buildList {
            val latitude = entries[2]?.asDegrees()
            val longitude = entries[4]?.asDegrees()
            if (latitude != null && longitude != null) {
                val northSouth = entries[1]?.asText()?.trim()?.uppercase() ?: "N"
                val eastWest = entries[3]?.asText()?.trim()?.uppercase() ?: "E"
                add("coordinates" to "%.6f %s, %.6f %s".format(latitude, northSouth, longitude, eastWest))
            }
            entries[6]?.asRationals()?.firstOrNull()?.let { add("altitude" to "%.1f m".format(it)) }
            entries[29]?.asText()?.trim()?.takeIf { it.isNotEmpty() }?.let { add("date" to it) }
        }

        private fun format(tag: Int, value: Value): String = when (tag) {
            0x0112 -> ORIENTATIONS[value.asLong().toInt()] ?: value.asLong().toString()
            0x829A -> value.asRationals().firstOrNull()?.let { exposure(it) } ?: value.asText()
            0x829D -> value.asRationals().firstOrNull()?.let { "f/%.1f".format(it) } ?: value.asText()
            0x920A -> value.asRationals().firstOrNull()?.let { "%.0f mm".format(it) } ?: value.asText()
            0x8827 -> value.asLong().toString()
            0x9209 -> if (value.asLong().toInt() and 0x01 == 1) "fired" else "did not fire"
            else -> value.asText()
        }

        private fun exposure(seconds: Double): String =
            if (seconds >= 1) "%.1f s".format(seconds) else "1/${Math.round(1 / seconds)} s"

        private fun short(at: Int): Int =
            if (little) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private fun int(at: Int): Long {
            val b = (0..3).map { bytes[at + it].toLong() and 0xFF }
            return if (little) b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
            else (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        }

        /** One entry's bytes, read as whatever its type says they are. */
        private inner class Value(val type: Int, val length: Int, val raw: ByteArray) {
            fun asLong(): Long = when (type) {
                3 -> number(0, 2)
                4 -> number(0, 4)
                else -> number(0, minOf(4, raw.size))
            }

            fun asText(): String = when (type) {
                2 -> raw.decodeToString().trim { it <= ' ' }
                5, 10 -> asRationals().joinToString(", ") { "%.4g".format(it) }
                else -> (0 until minOf(length, 8)).joinToString(" ") { i ->
                    val unit = TYPE_SIZES[type] ?: 1
                    number(i * unit, unit).toString()
                }
            }

            fun asRationals(): List<Double> = (0 until minOf(length, 8)).mapNotNull { i ->
                val at = i * 8
                if (at + 8 > raw.size) return@mapNotNull null
                val denominator = number(at + 4, 4)
                if (denominator == 0L) null else number(at, 4).toDouble() / denominator
            }

            /** Degrees, minutes and seconds as one number, which is how a GPS tag is written. */
            fun asDegrees(): Double? {
                val parts = asRationals()
                if (parts.size < 3) return null
                return parts[0] + parts[1] / 60 + parts[2] / 3600
            }

            private fun number(at: Int, size: Int): Long {
                if (at + size > raw.size) return 0
                var value = 0L
                for (i in 0 until size) {
                    val b = raw[at + i].toLong() and 0xFF
                    value = if (little) value or (b shl (8 * i)) else (value shl 8) or b
                }
                return value
            }
        }
    }

    /* ───────── the rest, by their headers ───────── */

    private fun gif(data: ByteArray): List<Pair<String, String>> = buildList {
        if (data.size < 13) return@buildList
        add("pixels" to "${le16(data, 6)} x ${le16(data, 8)}")
        add("version" to data.decodeToString(3, 6))
        add("colours" to "${1 shl ((data[10].toInt() and 0x07) + 1)}")
    }

    private fun bmp(data: ByteArray): List<Pair<String, String>> = buildList {
        if (data.size < 30) return@buildList
        add("pixels" to "${le32(data, 18)} x ${le32(data, 22)}")
        add("depth" to "${le16(data, 28)} bit")
    }

    private fun webp(data: ByteArray): List<Pair<String, String>> = buildList {
        if (data.size < 30) return@buildList
        when (runCatching { data.decodeToString(12, 16) }.getOrNull()) {
            "VP8X" -> add("pixels" to "${le24(data, 24) + 1} x ${le24(data, 27) + 1}")
            "VP8L" -> {
                val bits = le32(data, 21)
                add("pixels" to "${(bits and 0x3FFF) + 1} x ${((bits shr 14) and 0x3FFF) + 1}")
            }
            "VP8 " -> add("pixels" to "${le16(data, 26) and 0x3FFF} x ${le16(data, 28) and 0x3FFF}")
        }
    }

    /* ───────── reading numbers ───────── */

    private fun int(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun short(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun le16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun le24(data: ByteArray, at: Int): Int =
        le16(data, at) or ((data[at + 2].toInt() and 0xFF) shl 16)

    private fun le32(data: ByteArray, at: Int): Int =
        le24(data, at) or ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.indexOf(value: Int, from: Int = 0): Int {
        for (i in from until size) if ((this[i].toInt() and 0xFF) == value) return i
        return -1
    }

    /* ───────── the tags worth a line ───────── */

    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825

    private val TYPE_SIZES = mapOf(
        1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
    )

    /** In the order they are shown, which is the order somebody reads them in. */
    private val IMAGE_TAGS = linkedMapOf(
        0x010F to "make",
        0x0110 to "model",
        0x0112 to "orientation",
        0x0131 to "software",
        0x0132 to "modified",
        0x013B to "artist",
        0x8298 to "copyright",
    )

    private val EXIF_TAGS = linkedMapOf(
        0x9003 to "taken",
        0x829A to "exposure",
        0x829D to "aperture",
        0x8827 to "ISO",
        0x920A to "focal length",
        0x9209 to "flash",
        0xA002 to "pixels across",
        0xA003 to "pixels down",
        0xA434 to "lens",
    )

    private val ORIENTATIONS = mapOf(
        1 to "upright", 2 to "mirrored", 3 to "180 degrees", 4 to "mirrored, 180 degrees",
        5 to "mirrored, 90 CW", 6 to "90 CW", 7 to "mirrored, 90 CCW", 8 to "90 CCW",
    )
}
