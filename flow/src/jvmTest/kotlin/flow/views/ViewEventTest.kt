package flow.views

import flow.extension.ViewEvent
import flow.view.Asn1View
import flow.view.HexView
import flow.view.StringView
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Interaction: a view names the parts of its drawing that can be acted on, the app works out which
 * one was clicked, and the view answers with the options to draw it with next.
 *
 * The point of the round trip is that a view keeps nothing between calls — so everything it needs
 * to remember has to survive in those options, and everything it needs to interpret a click has to
 * survive in the region's name. These check both, because a view that quietly kept state in a field
 * would pass a single-click test and then behave unpredictably under two panels at once.
 */
class ViewEventTest {

    private fun rsaKey() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

    // the offset column is drawn as its own text, so counting rows means turning it off
    private val treeOnly = mapOf("showOffsets" to "false")

    /* ───────── ASN.1: collapsing a branch ───────── */

    @Test
    fun `every branch with children is clickable, and nothing else is`() {
        val canvas = FakeCanvas()
        Asn1View().draw(canvas, rsaKey(), emptyMap())
        assertTrue(canvas.regions.isNotEmpty(), "no branch was made clickable")
        // a leaf has nothing to open, so it claims nothing
        val branchRows = canvas.texts.count { it.text.startsWith("▼") || it.text.startsWith("▶") }
        assertEquals(branchRows, canvas.regions.size)
    }

    @Test
    fun `clicking a branch closes it, and its children go with it`() {
        val view = Asn1View()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)

        // the outermost SEQUENCE is the first branch drawn
        val region = open.regions.first()
        val collapsed = view.onEvent(ViewEvent(ViewEvent.CLICK, region.id, region.x, region.y), treeOnly)
        assertTrue(collapsed != treeOnly, "the click changed nothing")

        val shut = FakeCanvas()
        view.draw(shut, key, collapsed)
        assertEquals(1, shut.lines.size, "closing the outermost branch left ${shut.lines.size} rows")
        assertTrue(shut.lines.single().startsWith("▶"), shut.lines.single())
    }

    @Test
    fun `clicking a closed branch opens it again`() {
        val view = Asn1View()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        val region = open.regions.first()
        val event = ViewEvent(ViewEvent.CLICK, region.id, region.x, region.y)

        val closed = view.onEvent(event, treeOnly)
        val reopened = view.onEvent(event, closed)

        val again = FakeCanvas()
        view.draw(again, key, reopened)
        assertEquals(open.lines, again.lines, "opening it again did not restore the tree")
    }

    @Test
    fun `an inner branch closes without touching the ones around it`() {
        val view = Asn1View()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        // the AlgorithmIdentifier sequence, inside the outer one
        val inner = open.regions[1]
        val next = view.onEvent(ViewEvent(ViewEvent.CLICK, inner.id, inner.x, inner.y), treeOnly)

        val shut = FakeCanvas()
        view.draw(shut, key, next)
        assertTrue(shut.lines.size in 2 until open.lines.size, "got ${shut.lines.size} of ${open.lines.size} rows")
        assertTrue(shut.lines.none { it.contains("rsaEncryption") }, "the closed branch still shows its children")
        assertTrue(shut.lines.any { it.contains("BIT STRING") }, "a sibling disappeared too")
    }

    @Test
    fun `what is closed survives being written out and read back`() {
        val view = Asn1View()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        val region = open.regions[1]
        val saved = view.onEvent(ViewEvent(ViewEvent.CLICK, region.id, region.x, region.y), treeOnly)

        // the options are a plain string map because they are saved with the flow; nothing in here
        // may depend on an object that only exists while the app is running
        saved.forEach { (k, v) -> assertTrue(k.isNotBlank() && v.isNotBlank(), "$k=$v") }
        val restored = FakeCanvas()
        view.draw(restored, key, saved.toMap())
        val direct = FakeCanvas()
        view.draw(direct, key, saved)
        assertEquals(direct.lines, restored.lines)
    }

    /* ───────── Hex: picking out a byte ───────── */

    @Test
    fun `clicking a byte in the hex column selects that byte`() {
        val view = HexView()
        val data = ByteArray(64) { it.toByte() }
        val canvas = FakeCanvas()
        view.draw(canvas, data, mapOf("bytesPerRow" to "16"))

        // the third byte of the second row, which is byte 18
        val row = canvas.regions[1]
        val event = ViewEvent(ViewEvent.CLICK, row.id, row.x + 2 * 3 * canvas.monoCharWidth * 12f + 1f, row.y)
        val next = view.onEvent(event, mapOf("bytesPerRow" to "16"))
        assertEquals("18", next["hex.selected"])
    }

    @Test
    fun `the selected byte is reported with its offset and value`() {
        val canvas = FakeCanvas()
        HexView().draw(
            canvas, ByteArray(64) { it.toByte() },
            mapOf("bytesPerRow" to "16", "hex.selected" to "18"),
        )
        val note = canvas.lines.firstOrNull { it.startsWith("offset 18") }
        assertNotNull(note, "no note about the selection: ${canvas.lines.takeLast(3)}")
        assertTrue(note.contains("0x12"), note) // both the offset in hex and the value 18 = 0x12
    }

    @Test
    fun `the selection is drawn where the byte is`() {
        val canvas = FakeCanvas()
        HexView().draw(
            canvas, ByteArray(64) { it.toByte() },
            mapOf("bytesPerRow" to "16", "hex.selected" to "18"),
        )
        val marker = canvas.ops.filterIsInstance<FakeCanvas.Rect>().singleOrNull()
        assertNotNull(marker, "the selected byte was not marked")
        // on the second row, and past the offset column
        val rows = canvas.texts.map { it.y }.distinct().sorted()
        assertEquals(rows[1], marker.y + 1f, 0.01f)
    }

    @Test
    fun `clicking the selected byte again clears it`() {
        val view = HexView()
        val canvas = FakeCanvas()
        val options = mapOf("bytesPerRow" to "16", "hex.selected" to "0")
        view.draw(canvas, ByteArray(64) { it.toByte() }, options)
        val row = canvas.regions.first()
        val next = view.onEvent(ViewEvent(ViewEvent.CLICK, row.id, row.x + 1f, row.y), options)
        assertNull(next["hex.selected"], "clicking it a second time kept it selected")
    }

    @Test
    fun `two panels of the same view do not interfere`() {
        // the worker answers requests on a pool, so the same view instance can be drawing one panel
        // while answering a click on another; nothing may be carried in a field between the two
        val view = HexView()
        val wide = FakeCanvas(width = 900f)
        val narrow = FakeCanvas(width = 260f)
        view.draw(wide, ByteArray(64) { it.toByte() }, emptyMap())
        view.draw(narrow, ByteArray(64) { it.toByte() }, emptyMap())

        // a click on the wide panel, resolved after the narrow one was drawn over it
        val row = wide.regions.first()
        val clicked = view.onEvent(
            ViewEvent(ViewEvent.CLICK, row.id, row.x + 3 * wide.monoCharWidth * 12f + 1f, row.y),
            emptyMap(),
        )
        assertEquals("1", clicked["hex.selected"], "the click was read against the wrong layout")
    }

    /* ───────── the default ───────── */

    @Test
    fun `a view that does not care about clicks is left as it was`() {
        val options = mapOf("encoding" to "UTF-8")
        val next = StringView().onEvent(ViewEvent(ViewEvent.CLICK, "anything", 0f, 0f), options)
        assertSame(options, next, "the default onEvent should hand back exactly what it was given")
    }

    @Test
    fun `a click on nothing changes nothing`() {
        val view = Asn1View()
        val unchanged = view.onEvent(ViewEvent(ViewEvent.CLICK, null, 5f, 5f), treeOnly)
        assertEquals(treeOnly, unchanged)
    }

    @Test
    fun `a hover is not a click`() {
        val view = Asn1View()
        val canvas = FakeCanvas()
        view.draw(canvas, rsaKey(), emptyMap())
        val region = canvas.regions.first()
        val hovered = view.onEvent(ViewEvent(ViewEvent.HOVER, region.id, region.x, region.y), treeOnly)
        assertEquals(treeOnly, hovered, "hovering closed a branch")
    }
}
