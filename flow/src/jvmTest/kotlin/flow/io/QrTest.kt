package flow.io

import flow.qr.QrModule
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The QR encoder.
 *
 * What these cannot check is that a scanner reads the result — for that the codes were decoded with
 * the platform's own detector across every version from 1 to 40 and all four correction levels, and
 * these tests pin down the structure that has to hold for that to keep being true: the size formula,
 * the finder patterns a scanner locks on to, the quiet zone it needs to find the edges, and the
 * version chosen for a given amount of data.
 */
class QrTest {

    /** The decoded pixels of a code, at one pixel per module, with the quiet zone taken off. */
    private fun modules(text: String, ecc: String = "L", quiet: Int = 4): Array<BooleanArray> {
        val png = QrModule().process(
            mapOf("in" to text.encodeToByteArray()),
            mapOf("correction" to ecc, "moduleSize" to "1", "quietZone" to quiet.toString()),
        )["out"]!!
        val image = ImageIO.read(ByteArrayInputStream(png))
        val size = image.width - quiet * 2
        return Array(size) { y ->
            BooleanArray(size) { x -> (image.getRGB(x + quiet, y + quiet) and 0xFFFFFF) == 0 }
        }
    }

    @Test
    fun `the symbol is square and a legal size`() {
        val m = modules("flow")
        assertEquals(m.size, m[0].size, "the symbol is not square")
        // every version is 4n+17 modules across, for n from 1 to 40
        assertTrue((m.size - 17) % 4 == 0 && m.size in 21..177, "${m.size} is not a QR code size")
    }

    @Test
    fun `all three finder patterns are there`() {
        val m = modules("flow")
        val last = m.size - 7
        listOf(0 to 0, last to 0, 0 to last).forEach { (ox, oy) ->
            // 7x7: a dark ring, a light ring inside it, and a dark 3x3 core
            for (dy in 0..6) for (dx in 0..6) {
                val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                assertEquals(
                    ring != 2, m[oy + dy][ox + dx],
                    "the finder at ($ox,$oy) is wrong at ($dx,$dy)",
                )
            }
        }
    }

    @Test
    fun `the timing patterns alternate`() {
        val m = modules("flow")
        for (i in 8 until m.size - 8) {
            assertEquals(i % 2 == 0, m[6][i], "the horizontal timing pattern breaks at $i")
            assertEquals(i % 2 == 0, m[i][6], "the vertical timing pattern breaks at $i")
        }
    }

    @Test
    fun `the module that is always dark is dark`() {
        val m = modules("flow")
        assertTrue(m[m.size - 8][8], "the fixed dark module is light")
    }

    @Test
    fun `the quiet zone is left clear all the way round`() {
        val quiet = 4
        val png = QrModule().process(
            mapOf("in" to "flow".encodeToByteArray()),
            mapOf("correction" to "L", "moduleSize" to "1", "quietZone" to quiet.toString()),
        )["out"]!!
        val image = ImageIO.read(ByteArrayInputStream(png))
        for (i in 0 until image.width) {
            for (k in 0 until quiet) {
                listOf(i to k, i to image.height - 1 - k, k to i, image.width - 1 - k to i).forEach { (x, y) ->
                    assertTrue(
                        (image.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF,
                        "the quiet zone is not clear at ($x,$y)",
                    )
                }
            }
        }
    }

    @Test
    fun `more data means a larger symbol, and the same data always the same one`() {
        val small = modules("a")
        val large = modules("a".repeat(200))
        assertTrue(large.size > small.size, "200 bytes did not need a bigger symbol than 1")
        // the mask is chosen by score, so the same input has to come out identically every time
        assertTrue(modules("a").contentDeepEquals(small), "two runs produced different symbols")
    }

    @Test
    fun `a higher correction level costs capacity`() {
        // the same data at the strongest level needs at least as much room as at the weakest
        assertTrue(modules("a".repeat(100), ecc = "H").size >= modules("a".repeat(100), ecc = "L").size)
    }

    @Test
    fun `the module size scales the image and nothing else`() {
        fun width(scale: Int): Int {
            val png = QrModule().process(
                mapOf("in" to "flow".encodeToByteArray()),
                mapOf("correction" to "L", "moduleSize" to scale.toString(), "quietZone" to "4"),
            )["out"]!!
            return ImageIO.read(ByteArrayInputStream(png)).width
        }
        assertEquals(width(1) * 8, width(8))
    }

    @Test
    fun `empty input is refused rather than encoded as nothing`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QrModule().process(mapOf("in" to ByteArray(0)), emptyMap())
        }
        assertTrue(failure.message!!.contains("no data"), failure.message!!)
    }

    @Test
    fun `more data than a QR code holds is refused with the limit`() {
        val failure = assertFailsWith<IllegalStateException> {
            QrModule().process(mapOf("in" to ByteArray(4000) { 65 }), mapOf("correction" to "L"))
        }
        assertTrue(failure.message!!.contains("more than a QR code holds"), failure.message!!)
    }

    @Test
    fun `every correction level produces a readable structure`() {
        listOf("L", "M", "Q", "H").forEach { ecc ->
            val m = modules("flow extensions", ecc)
            assertTrue(m[0][0] && m[6][6], "level $ecc did not draw a finder pattern")
            assertTrue(m[m.size - 8][8], "level $ecc did not set the fixed dark module")
        }
    }
}
