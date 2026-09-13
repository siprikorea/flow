package flow.qr

/**
 * A QR Code symbol, per ISO/IEC 18004.
 *
 * Written out here rather than pulled in so the module stays a single jar with nothing to
 * collide with — the same rule the other shipped modules follow. Byte mode only, which is what
 * arbitrary input data is: the alphanumeric and kanji modes pack denser but only for the
 * restricted character sets they are for, and this module is handed bytes.
 */
internal class QrCode private constructor(
    val version: Int,
    val ecl: Ecc,
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {
    val size: Int get() = version * 4 + 17

    /** True where the module is dark. */
    fun isDark(x: Int, y: Int): Boolean = modules[y][x]

    /** The four error-correction levels, in the order the format bits number them. */
    enum class Ecc(val formatBits: Int) {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);

        companion object {
            fun of(name: String?): Ecc = when (name?.uppercase()) {
                "M", "MEDIUM" -> MEDIUM
                "Q", "QUARTILE" -> QUARTILE
                "H", "HIGH" -> HIGH
                else -> LOW
            }
        }
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        /**
         * Encodes [data] at the smallest version that holds it.
         *
         * A higher correction level does not make a symbol more likely to be read — it makes it
         * survive damage — and it costs capacity, so the version is chosen after the level, not
         * traded against it.
         */
        fun encode(data: ByteArray, ecl: Ecc): QrCode {
            val version = (MIN_VERSION..MAX_VERSION).firstOrNull { v ->
                dataCodewords(v, ecl) * 8 >= 4 + charCountBits(v) + data.size * 8
            } ?: error(
                "${data.size} bytes is more than a QR code holds " +
                    "(at most ${dataCodewords(MAX_VERSION, ecl) - 3} at this correction level)",
            )

            val bits = BitBuffer()
            bits.append(0b0100, 4) // byte mode
            bits.append(data.size, charCountBits(version))
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }

            val capacity = dataCodewords(version, ecl) * 8
            bits.append(0, minOf(4, capacity - bits.size))       // terminator
            bits.append(0, (8 - bits.size % 8) % 8)              // up to the byte boundary
            // the two pad codewords the spec names, alternating, for the rest
            var pad = 0xEC
            while (bits.size < capacity) {
                bits.append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }

            val codewords = addEcc(bits.toBytes(), version, ecl)
            return draw(version, ecl, codewords)
        }

        /* ───────── capacity ───────── */

        private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

        /** Every module a symbol has that is not part of a function pattern. */
        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val numAlign = version / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (version >= 7) result -= 36 // the two version-information blocks
            }
            return result
        }

        private fun dataCodewords(version: Int, ecl: Ecc): Int =
            rawDataModules(version) / 8 -
                ECC_PER_BLOCK[ecl.ordinal][version] * NUM_BLOCKS[ecl.ordinal][version]

        /* ───────── error correction ───────── */

        /**
         * Splits the data into blocks, appends each block's Reed-Solomon remainder, and interleaves
         * them. Interleaving is what makes the correction work against a scratch: damage that would
         * wipe out one block whole is spread across all of them instead.
         */
        private fun addEcc(data: ByteArray, version: Int, ecl: Ecc): ByteArray {
            val numBlocks = NUM_BLOCKS[ecl.ordinal][version]
            val eccLen = ECC_PER_BLOCK[ecl.ordinal][version]
            val rawCodewords = rawDataModules(version) / 8
            val shortBlocks = numBlocks - rawCodewords % numBlocks
            val shortLen = rawCodewords / numBlocks - eccLen

            val generator = rsGenerator(eccLen)
            val dataBlocks = ArrayList<ByteArray>(numBlocks)
            val eccBlocks = ArrayList<ByteArray>(numBlocks)
            var at = 0
            for (i in 0 until numBlocks) {
                val len = shortLen + if (i < shortBlocks) 0 else 1
                val block = data.copyOfRange(at, at + len)
                at += len
                dataBlocks.add(block)
                eccBlocks.add(rsRemainder(block, generator))
            }

            val result = ByteArray(rawCodewords)
            var k = 0
            for (i in 0..shortLen) {
                dataBlocks.forEach { block -> if (i < block.size) result[k++] = block[i] }
            }
            for (i in 0 until eccLen) {
                eccBlocks.forEach { block -> result[k++] = block[i] }
            }
            return result
        }

        /** The generator polynomial's coefficients, for a remainder of [degree] bytes. */
        private fun rsGenerator(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1 // the polynomial x^0, built up to (x-r^0)(x-r^1)…
            var root = 1
            repeat(degree) {
                for (i in 0 until degree) {
                    result[i] = gfMul(result[i].toInt() and 0xFF, root).toByte()
                    if (i + 1 < degree) result[i] = (result[i].toInt() xor result[i + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, generator: ByteArray): ByteArray {
            val result = ByteArray(generator.size)
            data.forEach { b ->
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in generator.indices) {
                    result[i] = (result[i].toInt() xor gfMul(generator[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo the QR code's primitive polynomial, x^8+x^4+x^3+x^2+1. */
        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        /* ───────── the symbol itself ───────── */

        private fun draw(version: Int, ecl: Ecc, codewords: ByteArray): QrCode {
            val size = version * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            // function patterns are fixed; the data has to be laid around them, so where they are
            // is tracked as it is drawn rather than worked out again afterwards
            val reserved = Array(size) { BooleanArray(size) }
            val symbol = Symbol(size, modules, reserved)

            symbol.drawFunctionPatterns(version)
            symbol.drawCodewords(codewords)

            // the mask is chosen for how well the result scans: the penalty rules score runs of one
            // colour, blocks, finder-like sequences and an unbalanced light/dark ratio
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                symbol.applyMask(mask)
                symbol.drawFormatBits(ecl, mask)
                val penalty = symbol.penalty()
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                }
                symbol.applyMask(mask) // XOR again to undo it
            }
            symbol.applyMask(bestMask)
            symbol.drawFormatBits(ecl, bestMask)
            return QrCode(version, ecl, bestMask, modules)
        }

        /* ───────── tables (ISO/IEC 18004 annex; index 0 is unused) ───────── */

        private val ECC_PER_BLOCK = arrayOf(
            // version:  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31  32  33  34  35  36  37  38  39  40
            intArrayOf(-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // L
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28), // M
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // Q
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // H
        )

        private val NUM_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25), // L
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49), // M
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68), // Q
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 5, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81), // H
        )
    }
}

/** A run of bits being built up, most-significant first, as everything in a QR code is. */
private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size

    fun append(value: Int, length: Int) {
        require(length in 0..31) { "cannot append $length bits at once" }
        for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
    }

    fun toBytes(): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, bit -> if (bit) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        return out
    }
}
