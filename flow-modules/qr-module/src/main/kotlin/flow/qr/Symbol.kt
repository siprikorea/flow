package flow.qr

/**
 * The grid being drawn, and the rules for where things go.
 *
 * Kept apart from [QrCode] because this is all mutation — patterns laid down, data threaded around
 * them, masks applied and undone — while a finished QrCode is just the result.
 */
internal class Symbol(
    private val size: Int,
    private val modules: Array<BooleanArray>,
    private val reserved: Array<BooleanArray>,
) {

    private fun set(x: Int, y: Int, dark: Boolean, isFunction: Boolean) {
        modules[y][x] = dark
        if (isFunction) reserved[y][x] = true
    }

    fun drawFunctionPatterns(version: Int) {
        // timing patterns: the alternating row and column a scanner measures the module size from
        for (i in 0 until size) {
            set(6, i, i % 2 == 0, true)
            set(i, 6, i % 2 == 0, true)
        }

        // the three finders, with the light separator around each
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // alignment patterns, wherever two of their coordinates meet — except the three corners
        // already occupied by the finders
        val positions = alignmentPositions(version)
        positions.forEachIndexed { i, x ->
            positions.forEachIndexed { j, y ->
                val corner = (i == 0 && j == 0) ||
                    (i == 0 && j == positions.lastIndex) ||
                    (i == positions.lastIndex && j == 0)
                if (!corner) drawAlignment(x, y)
            }
        }

        // format information is drawn for real once the mask is known; the space is claimed now so
        // the data placement steps over it
        drawFormatBits(QrCode.Ecc.LOW, 0)
        drawVersionBits(version)
    }

    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                // concentric rings: dark 3×3 core, light ring, dark ring, then the separator
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, distance != 2 && distance != 4, true)
            }
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1, true)
            }
        }
    }

    /**
     * Where alignment patterns sit: the first at 6, the last seven from the edge, and the rest
     * spread evenly between at an even spacing.
     */
    private fun alignmentPositions(version: Int): List<Int> {
        if (version == 1) return emptyList()
        val count = version / 7 + 2
        // version 32 is the one the general formula gets wrong, and the spec tabulates it directly
        val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val result = ArrayList<Int>(count)
        var pos = size - 7
        repeat(count - 1) {
            result.add(0, pos)
            pos -= step
        }
        result.add(0, 6)
        return result
    }

    /** The 15 format bits, BCH-protected and masked, written twice so either copy can be read. */
    fun drawFormatBits(ecl: QrCode.Ecc, mask: Int) {
        val data = ecl.formatBits shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412

        // first copy: down the left of the top-left finder, then right along the top
        for (i in 0..5) set(8, i, bit(bits, i), true)
        set(8, 7, bit(bits, 6), true)
        set(8, 8, bit(bits, 7), true)
        set(7, 8, bit(bits, 8), true)
        for (i in 9..14) set(14 - i, 8, bit(bits, i), true)

        // second copy: along the bottom of the top-right finder and up the right of the bottom-left
        for (i in 0..7) set(size - 1 - i, 8, bit(bits, i), true)
        for (i in 8..14) set(8, size - 15 + i, bit(bits, i), true)
        set(8, size - 8, true, true) // the module that is always dark
    }

    /** The 18 version bits, on symbols large enough to need them (version 7 and up). */
    private fun drawVersionBits(version: Int) {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = version shl 12 or rem
        for (i in 0 until 18) {
            val dark = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark, true)
            set(b, a, dark, true)
        }
    }

    private fun bit(value: Int, i: Int) = (value ushr i) and 1 != 0

    /**
     * Threads the codewords through every module not taken by a function pattern: upward and
     * downward in two-column strips from the bottom right, skipping the vertical timing column.
     */
    fun drawCodewords(codewords: ByteArray) {
        var i = 0 // bit index into the codewords
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // the timing column is not part of a strip
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (reserved[y][x] || i >= codewords.size * 8) continue
                    modules[y][x] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                    i++
                }
            }
            right -= 2
        }
    }

    /** XOR-applies a mask over the data modules. Applying it a second time takes it off again. */
    fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (reserved[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> error("mask $mask is not one of the eight")
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    /**
     * How badly this masking scans, by the spec's four rules: long runs of one colour, solid
     * blocks, sequences that look like a finder pattern, and an unbalanced light/dark ratio. Lower
     * is better; the mask with the lowest score is the one kept.
     */
    fun penalty(): Int {
        var result = 0
        var dark = 0

        // rule 1: a run of five or more of one colour, in either direction
        for (i in 0 until size) {
            result += runPenalty { j -> modules[i][j] }
            result += runPenalty { j -> modules[j][i] }
        }

        // rule 2: every 2×2 block of a single colour
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    result += PENALTY_N2
                }
            }
        }

        // rule 3: the 1:1:3:1:1 sequence a scanner reads as a finder, with four light modules on
        // one side of it — the thing that makes a scanner lock on to the wrong place
        for (i in 0 until size) {
            result += finderLikePenalty { j -> modules[i][j] }
            result += finderLikePenalty { j -> modules[j][i] }
        }

        for (y in 0 until size) for (x in 0 until size) if (modules[y][x]) dark++

        // rule 4: how far the dark proportion strays from half, in steps of five percent
        val total = size * size
        var k = 0
        while (dark * 20 < (9 - k) * total || dark * 20 > (11 + k) * total) k++
        return result + k * PENALTY_N4
    }

    private inline fun runPenalty(at: (Int) -> Boolean): Int {
        var result = 0
        var runColor = at(0)
        var runLength = 0
        for (i in 0 until size) {
            if (at(i) == runColor) {
                runLength++
            } else {
                runColor = at(i)
                runLength = 1
            }
            if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
        }
        return result
    }

    private inline fun finderLikePenalty(at: (Int) -> Boolean): Int {
        var result = 0
        for (i in 0..size - FINDER_LIKE.size) {
            // the four light modules may be off the edge, where the quiet zone supplies them
            if (matches(i, at, leading = true) || matches(i, at, leading = false)) result += PENALTY_N3
        }
        return result
    }

    private inline fun matches(from: Int, at: (Int) -> Boolean, leading: Boolean): Boolean {
        val pattern = if (leading) FINDER_LIKE else FINDER_LIKE.reversedArray()
        for (k in pattern.indices) {
            val index = from + k
            // beyond the symbol is the quiet zone, which is light
            val module = if (index in 0 until size) at(index) else false
            if (module != pattern[k]) return false
        }
        return true
    }

    private companion object {
        const val PENALTY_N1 = 3
        const val PENALTY_N2 = 3
        const val PENALTY_N3 = 40
        const val PENALTY_N4 = 10

        /** dark light dark dark dark light dark, then four light: 1:1:3:1:1 with its quiet side. */
        val FINDER_LIKE = booleanArrayOf(
            true, false, true, true, true, false, true, false, false, false, false,
        )
    }
}
