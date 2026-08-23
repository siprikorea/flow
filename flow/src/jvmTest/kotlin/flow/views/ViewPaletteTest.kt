package flow.views

import flow.extension.ViewCanvas
import flow.model.ViewColor
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
class ViewPaletteTest {

    @Test
    fun `both sides name the same colours`() {
        assertEquals(ViewCanvas.DEFAULT_TEXT, ViewColor.DEFAULT_TEXT)
        assertEquals(ViewCanvas.MUTED_TEXT, ViewColor.MUTED_TEXT)
        assertEquals(ViewCanvas.ACCENT, ViewColor.ACCENT)
        assertEquals(ViewCanvas.ERROR, ViewColor.ERROR)
        assertEquals(ViewCanvas.SURFACE, ViewColor.SURFACE)
        assertEquals(ViewCanvas.BORDER, ViewColor.BORDER)
    }

    @Test
    fun `every declared colour is recognised as a theme colour`() {
        listOf(
            ViewCanvas.DEFAULT_TEXT, ViewCanvas.MUTED_TEXT, ViewCanvas.ACCENT,
            ViewCanvas.ERROR, ViewCanvas.SURFACE, ViewCanvas.BORDER,
        ).forEach { assertTrue(ViewColor.isThemeColor(it), "$it was not recognised") }
    }

    @Test
    fun `an ordinary colour is left alone`() {
        // opaque black and white are the two a view is most likely to name outright
        assertFalse(ViewColor.isThemeColor(0xFF000000.toInt()))
        assertFalse(ViewColor.isThemeColor(0xFFFFFFFF.toInt()))
        assertFalse(ViewColor.isThemeColor(0))
    }
}
