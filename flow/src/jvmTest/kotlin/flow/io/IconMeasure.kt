package flow.io

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test

/** Measures the Claude mark out of a PNG, so it can be drawn rather than guessed at. */
class IconMeasure {
    @Test
    fun measure() {
        val path = System.getenv("ICON_PNG") ?: return
        val img = ImageIO.read(File(path))
        val w = img.width
        val h = img.height

        // the mark is the light shape on the coloured square: light = high luminance
        fun light(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= w || y >= h) return false
            val p = img.getRGB(x, y)
            if ((p ushr 24 and 0xFF) < 128) return false
            val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            return (r * 30 + g * 59 + b * 11) / 100 > 205
        }

        // centre of mass of the light pixels
        var sx = 0L; var sy = 0L; var n = 0L
        for (y in 0 until h) for (x in 0 until w) if (light(x, y)) { sx += x; sy += y; n++ }
        val cx = sx.toDouble() / n
        val cy = sy.toDouble() / n
        val half = minOf(w, h) / 2.0
        println("size=${w}x$h centre=(${"%.1f".format(cx)}, ${"%.1f".format(cy)}) lit=$n")

        // how far the shape reaches at each degree, as a fraction of the half-size
        val reach = DoubleArray(360)
        for (deg in 0 until 360) {
            val a = Math.toRadians(deg.toDouble())
            var last = 0.0
            var r = 0.0
            while (r < half) {
                if (light((cx + cos(a) * r).toInt(), (cy + sin(a) * r).toInt())) last = r
                r += 0.5
            }
            reach[deg] = last / half
        }
        println("reach per degree (0 = right, clockwise):")
        println((0 until 360).joinToString(",") { "%.3f".format(reach[it]) })

        // a ray is a run of degrees that reach further than halfway; report its middle and extent
        val far = reach.map { it > 0.40 }
        val rays = mutableListOf<Triple<Int, Int, Double>>()
        var start = -1
        for (deg in 0 until 360 + 60) {
            val on = far[deg % 360]
            if (on && start < 0) start = deg
            if (!on && start >= 0) {
                if (deg - start in 1..80) {
                    val mid = ((start + deg - 1) / 2) % 360
                    val peak = (start until deg).maxOf { reach[it % 360] }
                    rays.add(Triple(mid, deg - start, peak))
                }
                start = -1
            }
            if (deg >= 360 && start < 0) break
        }
        println("rays: ${rays.size}")
        // widen from the ray's own middle until it goes dark, so a neighbour is never counted
        rays.forEach { (mid, _, peak) ->
            val profile = listOf(0.20, 0.40, 0.60, 0.80, 0.95).map { f ->
                val r = peak * half * f
                fun lit(d: Double): Boolean {
                    val a2 = Math.toRadians(mid + d)
                    return light((cx + cos(a2) * r).toInt(), (cy + sin(a2) * r).toInt())
                }
                if (!lit(0.0)) return@map 0.0
                var lo = 0.0
                while (lo > -45 && lit(lo - 0.5)) lo -= 0.5
                var hi = 0.0
                while (hi < 45 && lit(hi + 0.5)) hi += 0.5
                hi - lo
            }
            val widths = profile.joinToString(" ") { "%.0f".format(it) }
            println("  angle=%3d reach=%.3f widthDeg(20/40/60/80/95%%)= %s".format(mid, peak, widths))
        }
    }
}
