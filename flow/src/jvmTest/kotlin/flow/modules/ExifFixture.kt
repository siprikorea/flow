package flow.modules

import java.io.ByteArrayOutputStream

/**
 * An EXIF block built to the spec, so the reader is tested against something it did not write.
 *
 * EXIF is a TIFF file with no image in it: a byte-order header, then directories of twelve-byte
 * entries — tag, type, count, and either the value itself (when it fits in four bytes) or an offset
 * to it. Values that do not fit go in a heap after the directories, which is the part a reader
 * actually has to get right, so this fixture writes them that way even when it could inline them.
 *
 * Big-endian ("MM"), which is the other order from the one a Mac writes — a reader that only ever
 * sees little-endian files is a reader that has never been tested.
 */
class ExifFixture(
    private val image: List<Pair<Int, Entry>> = emptyList(),
    private val exif: List<Pair<Int, Entry>> = emptyList(),
    private val gps: List<Pair<Int, Entry>> = emptyList(),
) {
    /** One value: its TIFF type, how many of them, and the bytes. */
    class Entry(val type: Int, val count: Int, val bytes: ByteArray)

    companion object {
        private const val EXIF_IFD = 0x8769
        private const val GPS_IFD = 0x8825

        fun text(value: String) = Entry(2, value.length + 1, value.encodeToByteArray() + byteArrayOf(0))

        fun short(value: Int) = Entry(3, 1, byteArrayOf((value shr 8).toByte(), value.toByte(), 0, 0))

        fun rational(numerator: Int, denominator: Int) = Entry(5, 1, int(numerator) + int(denominator))

        fun rationals(vararg parts: Pair<Int, Int>) =
            Entry(5, parts.size, parts.fold(ByteArray(0)) { acc, (n, d) -> acc + int(n) + int(d) })

        private fun int(value: Int) =
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }

    fun build(): ByteArray {
        // the sub-directories go after the first one; their offsets are known only once its size is
        val imageCount = image.size + (if (exif.isNotEmpty()) 1 else 0) + (if (gps.isNotEmpty()) 1 else 0)
        val imageStart = 8
        val imageSize = 2 + imageCount * 12 + 4
        val imageHeap = heapSize(image)
        val exifStart = imageStart + imageSize + imageHeap
        val exifSize = if (exif.isEmpty()) 0 else 2 + exif.size * 12 + 4
        val gpsStart = exifStart + exifSize + heapSize(exif)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)) // MM, and the magic 42
        out.write(int(imageStart))

        val pointers = buildList {
            if (exif.isNotEmpty()) add(EXIF_IFD to Entry(4, 1, int(exifStart)))
            if (gps.isNotEmpty()) add(GPS_IFD to Entry(4, 1, int(gpsStart)))
        }
        out.write(directory(image + pointers, imageStart, nextDirectory = 0))
        if (exif.isNotEmpty()) out.write(directory(exif, exifStart, nextDirectory = 0))
        if (gps.isNotEmpty()) out.write(directory(gps, gpsStart, nextDirectory = 0))
        return out.toByteArray()
    }

    /** One directory and the heap that belongs to it, as a reader will find them. */
    private fun directory(entries: List<Pair<Int, Entry>>, start: Int, nextDirectory: Int): ByteArray {
        val body = ByteArrayOutputStream()
        val heap = ByteArrayOutputStream()
        var heapAt = start + 2 + entries.size * 12 + 4
        body.write(short(entries.size))
        entries.forEach { (tag, entry) ->
            body.write(short(tag))
            body.write(short(entry.type))
            body.write(int(entry.count))
            if (entry.bytes.size <= 4) {
                body.write(entry.bytes + ByteArray(4 - entry.bytes.size))
            } else {
                body.write(int(heapAt))
                heap.write(entry.bytes)
                heapAt += entry.bytes.size
            }
        }
        body.write(int(nextDirectory))
        return body.toByteArray() + heap.toByteArray()
    }

    private fun heapSize(entries: List<Pair<Int, Entry>>): Int =
        entries.sumOf { (_, entry) -> if (entry.bytes.size <= 4) 0 else entry.bytes.size }

    private fun short(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun int(value: Int) =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
}
