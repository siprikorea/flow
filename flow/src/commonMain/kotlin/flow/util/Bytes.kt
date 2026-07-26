package flow.util

// Byte / hex / UTF-8 helpers shared across the app.

// bytes <-> hex string (space-separated uppercase byte pairs, e.g. "48 65 6C")
fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

fun hexToBytes(hex: String): ByteArray =
    hex.split(Regex("\\s+")).filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull(16)?.toByte() }
        .toByteArray()

// Lossless byte <-> string bridge (1 byte <-> 1 char, Latin-1-style) used to carry raw bytes
// through the engine's string-typed ports without loss. Unlike UTF-8 (decodeToString/
// encodeToByteArray), every byte value round-trips exactly — required for binary module output
// (e.g. Crypto ciphertext) flowing into another module or a cout: UTF-8's lossy U+FFFD
// substitution for invalid sequences would otherwise silently corrupt/resize the data.
fun bytesToLatin1(bytes: ByteArray): String = buildString(bytes.size) {
    for (b in bytes) append((b.toInt() and 0xFF).toChar())
}

fun latin1ToBytes(s: String): ByteArray = ByteArray(s.length) { i -> (s[i].code and 0xFF).toByte() }

// Decode bytes as UTF-8, emitting one U+FFFD per invalid byte, and record the byte
// offset where each output char (UTF-16 unit) starts. offsets.size == text.length + 1.
fun decodeUtf8Lossy(bytes: ByteArray): Pair<String, IntArray> {
    val sb = StringBuilder()
    val offs = ArrayList<Int>(bytes.size + 1)
    val n = bytes.size
    fun at(k: Int) = bytes[k].toInt() and 0xFF
    fun cont(k: Int) = k < n && at(k) in 0x80..0xBF
    var i = 0
    while (i < n) {
        val b0 = at(i)
        var cp = -1
        var len = 1
        when {
            b0 < 0x80 -> { cp = b0; len = 1 }
            b0 in 0xC2..0xDF -> if (cont(i + 1)) { cp = ((b0 and 0x1F) shl 6) or (at(i + 1) and 0x3F); len = 2 }
            b0 in 0xE0..0xEF -> if (cont(i + 1) && cont(i + 2)) {
                val c = ((b0 and 0x0F) shl 12) or ((at(i + 1) and 0x3F) shl 6) or (at(i + 2) and 0x3F)
                if (c >= 0x800 && (c < 0xD800 || c > 0xDFFF)) { cp = c; len = 3 }
            }
            b0 in 0xF0..0xF4 -> if (cont(i + 1) && cont(i + 2) && cont(i + 3)) {
                val c = ((b0 and 0x07) shl 18) or ((at(i + 1) and 0x3F) shl 12) or
                    ((at(i + 2) and 0x3F) shl 6) or (at(i + 3) and 0x3F)
                if (c in 0x10000..0x10FFFF) { cp = c; len = 4 }
            }
        }
        if (cp < 0) {
            sb.append('�'); offs.add(i); i += 1
        } else if (cp <= 0xFFFF) {
            sb.append(cp.toChar()); offs.add(i); i += len
        } else {
            val u = cp - 0x10000
            sb.append((0xD800 + (u shr 10)).toChar()); offs.add(i)
            sb.append((0xDC00 + (u and 0x3FF)).toChar()); offs.add(i)
            i += len
        }
    }
    offs.add(n)
    return sb.toString() to offs.toIntArray()
}

// Replace only the changed byte span. Diffs old vs new display text by common
// prefix/suffix (in chars), maps those char boundaries to byte offsets via the
// same lossy decode used for display, and splices the re-encoded middle into the
// bytes — so editing/deleting one (possibly broken) char never rewrites the rest.
fun spliceBytes(bytes: ByteArray, old: String, new: String): ByteArray {
    val (decoded, offsets) = decodeUtf8Lossy(bytes)
    if (decoded != old) return new.encodeToByteArray() // fall back if out of sync
    var p = 0
    val minLen = minOf(old.length, new.length)
    while (p < minLen && old[p] == new[p]) p++
    var s = 0
    while (s < minLen - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val mid = new.substring(p, new.length - s).encodeToByteArray()
    return bytes.copyOfRange(0, offsets[p]) + mid + bytes.copyOfRange(offsets[old.length - s], bytes.size)
}
