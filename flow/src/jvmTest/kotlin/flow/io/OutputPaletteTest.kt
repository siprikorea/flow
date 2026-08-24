package flow.io

import flow.extension.OutputCanvas
import flow.model.OutputColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The theme colours are declared twice — once in the extension contract for views to name, once in
 * the app's model for the replay to recognise — because the two cannot depend on each other. If
 * they ever drift apart a view asking for "the text colour" gets a nearly-black shade of nothing
 * instead, in whichever theme it was not tested in. This is what stops that happening quietly.
 */
class OutputPaletteTest {

    @Test
    fun `both sides name the same colours`() {
        assertEquals(OutputCanvas.DEFAULT_TEXT, OutputColor.DEFAULT_TEXT)
        assertEquals(OutputCanvas.MUTED_TEXT, OutputColor.MUTED_TEXT)
        assertEquals(OutputCanvas.ACCENT, OutputColor.ACCENT)
        assertEquals(OutputCanvas.ERROR, OutputColor.ERROR)
        assertEquals(OutputCanvas.SURFACE, OutputColor.SURFACE)
        assertEquals(OutputCanvas.BORDER, OutputColor.BORDER)
        assertEquals(OutputCanvas.SELECTION, OutputColor.SELECTION)
    }

    @Test
    fun `every declared colour is recognised as a theme colour`() {
        listOf(
            OutputCanvas.DEFAULT_TEXT, OutputCanvas.MUTED_TEXT, OutputCanvas.ACCENT,
            OutputCanvas.ERROR, OutputCanvas.SURFACE, OutputCanvas.BORDER, OutputCanvas.SELECTION,
        ).forEach { assertTrue(OutputColor.isThemeColor(it), "$it was not recognised") }
    }

    @Test
    fun `an ordinary colour is left alone`() {
        // opaque black and white are the two a view is most likely to name outright
        assertFalse(OutputColor.isThemeColor(0xFF000000.toInt()))
        assertFalse(OutputColor.isThemeColor(0xFFFFFFFF.toInt()))
        assertFalse(OutputColor.isThemeColor(0))
    }
}
