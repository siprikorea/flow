package flow.io

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import flow.core.EditorState
import flow.core.Workspace
import flow.model.FlowFile
import flow.ui.shell.App
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Frame
import flow.ui.theme.Palette
import flow.ui.theme.Size
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window is one sheet on one ground, and the margin around it is the same everywhere.
 *
 * This is a thing about pixels, so it is measured in pixels: the program is rendered and then read
 * back, and the four margins are counted from the outside in. Written by eye it drifts — a padding
 * added on one side for a panel that needed room, a divider left behind by a region that used to
 * draw its own edge — and none of that fails anything, it just stops looking like one window.
 */
class WindowFrameTest {

    private val density = 2f
    private val w = 1200
    private val h = 800

    private fun px(dp: Float) = (dp * density).roundToInt()

    /** The whole program, drawn. */
    private fun render(showLeft: Boolean, theme: String = Theme.DARK): Bitmap {
        val scene = ImageComposeScene(w, h, density = Density(density), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(theme)
            Box(Modifier.fillMaxSize()) {
                val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                ws.theme = theme
                ws.docs.add(EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "a.flow").also { it.load(FlowFile()) })
                ws.activeIndex = 0
                ws.showLeft = showLeft
                // The margin round the window is what is being measured, so it is measured with
                // one sheet in it. A second sheet beside the first puts its own rounded corners in
                // the middle of every scan line, which is a different question — and the one
                // `a tool window has the frame's own corner` asks.
                ws.showProps = false
                App(ws)
            }
        }
        val image = scene.render()
        return Bitmap().also {
            it.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL))
            image.readPixels(it)
            scene.close()
        }
    }

    /** Skia hands colours back as ARGB ints; the palette speaks in Compose colours. */
    private fun isFrameLine(argb: Int): Boolean {
        val want = Palette.frameBorder
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return abs(r - (want.red * 255).roundToInt()) <= 6 &&
            abs(g - (want.green * 255).roundToInt()) <= 6 &&
            abs(b - (want.blue * 255).roundToInt()) <= 6
    }

    /**
     * How far from an edge the frame's line is, insisting on a line and not a pixel.
     *
     * A single matching pixel is not enough: text is antialiased, and a letter in the status bar
     * blended against the ground lands on the border's own colour often enough that the first
     * version of this measured the border as 30px instead of 80. So each candidate is checked
     * across the perpendicular — a border runs the width of the sheet, a letter does not.
     */
    private fun stepsToEdge(b: Bitmap, from: Int, dir: Int, vertical: Boolean, limit: Int = 400): Int {
        val across = if (vertical) listOf(w * 35 / 100, w / 2, w * 65 / 100) else listOf(h * 35 / 100, h / 2, h * 65 / 100)
        var i = 0
        while (i < limit) {
            val at = from + dir * i
            if (at !in 0 until (if (vertical) h else w)) return -1
            val onTheLine = across.all { other ->
                val px = if (vertical) other else at
                val py = if (vertical) at else other
                isFrameLine(b.getColor(px, py))
            }
            if (onTheLine) return i
            i++
        }
        return -1
    }

    /** How many steps from [x],[y] towards ([dx],[dy]) before the frame's line is reached. */
    private fun stepsToLine(b: Bitmap, x: Int, y: Int, dx: Int, dy: Int, limit: Int = 400): Int {
        var i = 0
        while (i < limit) {
            val px = x + dx * i
            val py = y + dy * i
            if (px !in 0 until w || py !in 0 until h) return -1
            if (isFrameLine(b.getColor(px, py))) return i
            i++
        }
        return -1
    }

    /**
     * The border is the same thickness on all four sides — measured from the window's own edge.
     *
     * Not from the chrome inwards, which is the mistake this test was written wrong the first
     * time: the gaps after the rails and the title bar were all 8dp and equal, and the border a
     * person actually saw was 48 at the top, 52 at the sides and 32 at the bottom, because the
     * chrome strips themselves are part of what reads as the border. A rail with three icons in it
     * and forty empty dp below them is not furniture, it is margin. So all four strips are one
     * size and the band is measured the whole way from the edge.
     */
    @Test
    fun `the border is the same thickness on all four sides of the window`() {
        val b = render(showLeft = false)
        val band = px(Frame.band.value)

        val left = stepsToEdge(b, 0, 1, vertical = false)
        val right = stepsToEdge(b, w - 1, -1, vertical = false)
        val top = stepsToEdge(b, 0, 1, vertical = true)
        val bottom = stepsToEdge(b, h - 1, -1, vertical = true)

        assertTrue(left >= 0 && right >= 0 && top >= 0 && bottom >= 0, "no frame found: $left/$right/$top/$bottom")
        assertEquals(band, left, "left border")
        assertEquals(band, right, "right border")
        assertEquals(band, top, "top border")
        assertEquals(band, bottom, "bottom border")
    }

    /** And the chrome that lives in that border is one size, which is what makes the above hold. */
    @Test
    fun `the title bar, the rails and the status bar are the same size`() {
        assertEquals(Size.chrome, Size.toolbar, "the title bar is not one chrome strip")
        assertEquals(Size.chrome, Size.activityBar, "the rails are not one chrome strip")
        assertEquals(Size.chrome, Size.statusBar, "the status bar is not one chrome strip")
    }

    /**
     * And the four corners are the same corner.
     *
     * Measured along the diagonal from where the frame's own rectangle would begin: on a rounded
     * corner the line is further away than it is along a straight edge, and by exactly as much on
     * each of the four. A square corner reads as zero here, which is the failure this catches.
     */
    @Test
    fun `all four corners are rounded, and rounded the same`() {
        val b = render(showLeft = false)
        val band = px(Frame.band.value)
        val x0 = band
        val x1 = w - 1 - band
        val y0 = band
        val y1 = h - 1 - band

        val corners = listOf(
            "top-left" to stepsToLine(b, x0, y0, 1, 1),
            "top-right" to stepsToLine(b, x1, y0, -1, 1),
            "bottom-left" to stepsToLine(b, x0, y1, 1, -1),
            "bottom-right" to stepsToLine(b, x1, y1, -1, -1),
        )
        corners.forEach { (name, steps) ->
            assertTrue(steps > 1, "$name is not rounded (the line is $steps steps along the diagonal)")
        }
        val distinct = corners.map { it.second }.distinct()
        assertEquals(1, distinct.size, "the corners are not all the same: $corners")
    }

    /**
     * The properties panel is a sheet of its own, with the window showing between it and the editor.
     *
     * It used to be docked inside the editor's frame, one surface with a rule down it. What tells
     * the two apart now is the ground: scanning in from the right there are two borders with the
     * window's own gradient between them, exactly the seam that separates the project panel from
     * the editor on the other side.
     */
    @Test
    fun `the properties panel is a sheet beside the editor, not part of it`() {
        val scene = ImageComposeScene(w, h, density = Density(density), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            Box(Modifier.fillMaxSize()) {
                val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                ws.theme = Theme.DARK
                ws.docs.add(EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "a.flow").also { it.load(FlowFile()) })
                ws.activeIndex = 0
                ws.showLeft = false
                ws.showProps = true
                App(ws)
            }
        }
        val image = scene.render()
        val b = Bitmap().also {
            it.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL))
            image.readPixels(it)
            scene.close()
        }

        // in from the right: the properties sheet's own two borders, then the editor's
        val outer = nextLineLeft(b, w - 1)
        val propsLeft = nextLineLeft(b, runLeftEnd(b, outer) - 1)
        val editorRight = nextLineLeft(b, runLeftEnd(b, propsLeft) - 1)
        assertEquals(px(Frame.band.value), w - 1 - outer, "the sheet does not start where the chrome ends")
        assertTrue(propsLeft > 0 && editorRight > 0, "only one border was found: $propsLeft / $editorRight")
        assertEquals(
            px(Frame.inset.value), runLeftEnd(b, propsLeft) - editorRight - 1,
            "the gap between the two sheets is not the window's own seam",
        )
    }

    /** The next column to the left holding the frame's line, checked across three rows so text is not one. */
    private fun nextLineLeft(b: Bitmap, from: Int): Int {
        val rows = listOf(h * 45 / 100, h / 2, h * 55 / 100)
        var x = from
        while (x > 0) {
            if (rows.all { isFrameLine(b.getColor(x, it)) }) return x
            x--
        }
        return -1
    }

    /** The leftmost column of the line [x] is part of — a 1dp line is more than one pixel. */
    private fun runLeftEnd(b: Bitmap, x: Int): Int {
        val rows = listOf(h * 45 / 100, h / 2, h * 55 / 100)
        var at = x
        while (at > 0 && rows.all { isFrameLine(b.getColor(at - 1, it)) }) at--
        return at
    }

    /**
     * A tool window opened beside the content is the same kind of sheet.
     *
     * Same line, same radius — it is the same composable, and this is what says so from the
     * outside. Two sheets with different corners are the thing this whole arrangement is meant to
     * stop looking like.
     */
    @Test
    fun `a tool window has the frame's own corner`() {
        val folded = render(showLeft = false)
        val open = render(showLeft = true)
        val band = px(Frame.band.value)
        val frameCorner = stepsToLine(folded, band, band, 1, 1)
        val cardCorner = stepsToLine(open, band, band, 1, 1)
        assertTrue(frameCorner > 1, "the frame has no rounded corner to compare against")
        assertEquals(frameCorner, cardCorner, "the tool window's corner is not the frame's corner")
    }

    /**
     * The ground under it all is a gradient, not a flat fill.
     *
     * Read at two opposite corners of the window, where only the ground is showing.
     */
    @Test
    fun `the window's ground changes from one corner to the other`() {
        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val b = render(showLeft = false, theme = theme)
            val topLeft = b.getColor(2, px(Frame.band.value) - 4)
            val bottomRight = b.getColor(w - 3, h - 1 - px(Frame.band.value) + 4)
            val drop = ((topLeft shr 16) and 0xFF) - ((bottomRight shr 16) and 0xFF)
            // 4 would pass for a wash nobody can see; this is about what the eye picks up on a
            // strip of ground a few pixels wide
            assertTrue(drop > 20, "$theme: the ground is too flat to read (top ${"%06X".format(topLeft and 0xFFFFFF)}, " +
                "bottom ${"%06X".format(bottomRight and 0xFFFFFF)})")
        }
    }

    /**
     * The settings button in the title bar stands on the rail's own line.
     *
     * They are drawn by different files — one is the last control in a Row, the other is a button
     * in a Column — so nothing makes them agree except arithmetic, and being two dp apart is
     * exactly the kind of wrong that is felt before it is seen. Measured as ink: the columns each
     * icon actually paints, and where the middle of that span falls.
     */
    @Test
    fun `the settings button and the properties button stand on the same line`() {
        val b = render(showLeft = false)
        val band = px(Frame.band.value)
        // the right-hand end of the chrome, wide enough for one button and nothing beside it
        val from = w - band
        val until = w - 1

        val gear = inkCentre(b, from, until, px(4f), band - px(4f))
        val props = inkCentre(b, from, until, band + px(6f), band * 2 - px(6f))
        assertTrue(gear > 0 && props > 0, "nothing was drawn to measure: $gear / $props")
        assertTrue(abs(gear - props) <= 1, "the settings icon is at $gear and the properties icon at $props")
    }

    /**
     * The middle of whatever is painted in a box, by column.
     *
     * The ground is a gradient, so "not the background" is decided against the row's own leftmost
     * pixel rather than against one colour for the whole window.
     */
    private fun inkCentre(b: Bitmap, fromX: Int, untilX: Int, fromY: Int, untilY: Int): Double {
        var first = -1
        var last = -1
        for (x in fromX..untilX) {
            for (y in fromY..untilY) {
                val ground = b.getColor(fromX, y)
                if (!sameColour(b.getColor(x, y), ground)) {
                    if (first < 0) first = x
                    last = x
                    break
                }
            }
        }
        return if (first < 0) -1.0 else (first + last) / 2.0
    }

    private fun sameColour(a: Int, other: Int): Boolean = listOf(16, 8, 0).all { shift ->
        abs(((a shr shift) and 0xFF) - ((other shr shift) and 0xFF)) <= 4
    }
}
