package flow.io

import flow.extension.OutputCanvas
import flow.extension.OutputEvent
import flow.output.Asn1Output
import flow.output.HexOutput
import flow.output.StringOutput
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
class OutputEventTest {

    private fun rsaKey() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

    private val treeOnly = emptyMap<String, String>()

    /* ───────── ASN.1: the tree, and what picking a row shows ───────── */

    /** The rows of the tree, as the regions that make each one clickable. */
    private fun rows(canvas: FakeCanvas) = canvas.regions.filter { it.id.startsWith("s") }

    private fun toggles(canvas: FakeCanvas) = canvas.regions.filter { it.id.startsWith("t") }

    @Test
    fun `every row can be picked, and only the branches can be opened`() {
        val canvas = FakeCanvas()
        Asn1Output().draw(canvas, rsaKey(), treeOnly)
        assertTrue(rows(canvas).isNotEmpty(), "no row was made clickable")
        // a leaf has nothing to open, so it claims no toggle
        assertTrue(toggles(canvas).size < rows(canvas).size, "every row claimed a toggle")
        assertTrue(toggles(canvas).isNotEmpty(), "no branch could be opened")
    }

    @Test
    fun `clicking a branch closes it, and its children go with it`() {
        val view = Asn1Output()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)

        // the outermost SEQUENCE is the first branch drawn
        val marker = toggles(open).first()
        val collapsed = view.onEvent(OutputEvent(OutputEvent.CLICK, marker.id, marker.x, marker.y), treeOnly)
        assertTrue(collapsed.containsKey("asn1.collapsed"), "the click changed nothing: $collapsed")

        val shut = FakeCanvas()
        view.draw(shut, key, collapsed)
        assertEquals(1, rows(shut).size, "closing the outermost branch left ${rows(shut).size} rows")
    }

    @Test
    fun `clicking a closed branch opens it again`() {
        val view = Asn1Output()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        val marker = toggles(open).first()
        val event = OutputEvent(OutputEvent.CLICK, marker.id, marker.x, marker.y)

        val reopened = view.onEvent(event, view.onEvent(event, treeOnly))
        val again = FakeCanvas()
        view.draw(again, key, reopened)
        assertEquals(rows(open).map { it.id }, rows(again).map { it.id }, "the tree did not come back")
    }

    @Test
    fun `an inner branch closes without touching the ones around it`() {
        val view = Asn1Output()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        // the AlgorithmIdentifier sequence, inside the outer one
        val inner = toggles(open)[1]
        val next = view.onEvent(OutputEvent(OutputEvent.CLICK, inner.id, inner.x, inner.y), treeOnly)

        val shut = FakeCanvas()
        view.draw(shut, key, next)
        assertTrue(rows(shut).size in 2 until rows(open).size, "got ${rows(shut).size} of ${rows(open).size}")
        assertTrue(shut.lines.none { it.contains("rsaEncryption") }, "the closed branch still shows its children")
        assertTrue(shut.lines.any { it.contains("BIT STRING") }, "a sibling disappeared too")
    }

    @Test
    fun `picking a row shows that row's bytes and what its header says`() {
        val view = Asn1Output()
        val key = rsaKey()
        val first = FakeCanvas(width = 1600f)
        view.draw(first, key, treeOnly)

        // the algorithm OID, which is a leaf with a name worth showing
        val oidRow = first.texts.first { it.text.contains("rsaEncryption") }
        val row = rows(first).first { it.y <= oidRow.y && oidRow.y < it.y + it.h }
        val picked = view.onEvent(OutputEvent(OutputEvent.CLICK, row.id, row.x, row.y), treeOnly)
        assertTrue(picked.containsKey("asn1.selected"), "picking a row kept nothing: $picked")

        val shown = FakeCanvas(width = 1600f)
        view.draw(shown, key, picked)
        val detail = shown.lines
        assertTrue(detail.any { it.startsWith("OID") }, "no OID fact: ${detail.takeLast(12)}")
        assertTrue(detail.any { it.contains("rsaEncryption") }, "the OID was not named")
        assertTrue(detail.any { it.startsWith("Tag") }, "no tag fact")
        assertTrue(detail.any { it.startsWith("Length") }, "no length fact")
        // and the bytes themselves, which is the other half of picking a row
        assertTrue(detail.any { it.startsWith("Offset") }, "no hex table")
        assertTrue(detail.any { it == "06 09".substringBefore(' ') } || detail.any { it == "06" }, "no header byte")
    }

    @Test
    fun `the detail is drawn beside the tree, not under it`() {
        val view = Asn1Output()
        val canvas = FakeCanvas(width = 1200f)
        view.draw(canvas, rsaKey(), treeOnly)
        val treeRight = rows(canvas).maxOf { it.x + it.w }
        val facts = canvas.texts.filter { it.text.startsWith("Offset") || it.text.startsWith("Tag") }
        assertTrue(facts.isNotEmpty(), "the detail panel drew nothing")
        assertTrue(facts.all { it.x > treeRight }, "the detail overlaps the tree")
    }

    @Test
    fun `the picked row is marked`() {
        val view = Asn1Output()
        val canvas = FakeCanvas()
        view.draw(canvas, rsaKey(), treeOnly)
        val marks = canvas.ops.filterIsInstance<FakeCanvas.Rect>()
        assertEquals(1, marks.size, "expected exactly one row marked, got ${marks.size}")
        assertEquals(OutputCanvas.SELECTION, marks.single().color)
    }

    @Test
    fun `what is closed and what is picked survive being written out and read back`() {
        val view = Asn1Output()
        val key = rsaKey()
        val open = FakeCanvas()
        view.draw(open, key, treeOnly)
        val marker = toggles(open)[1]
        val saved = view.onEvent(OutputEvent(OutputEvent.CLICK, marker.id, marker.x, marker.y), treeOnly)

        // the options are a plain string map because they outlive any one drawing
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
        val view = HexOutput()
        val data = ByteArray(64) { it.toByte() }
        val canvas = FakeCanvas()
        view.draw(canvas, data, mapOf("bytesPerRow" to "16"))

        // the third byte of the second row, which is byte 18
        val row = canvas.regions[1]
        val event = OutputEvent(OutputEvent.CLICK, row.id, row.x + 2 * 3 * canvas.monoCharWidth * 12f + 1f, row.y)
        val next = view.onEvent(event, mapOf("bytesPerRow" to "16"))
        assertEquals("18", next["hex.selected"])
    }

    @Test
    fun `the selected byte is reported with its offset and value`() {
        val canvas = FakeCanvas()
        HexOutput().draw(
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
        HexOutput().draw(
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
        val view = HexOutput()
        val canvas = FakeCanvas()
        val options = mapOf("bytesPerRow" to "16", "hex.selected" to "0")
        view.draw(canvas, ByteArray(64) { it.toByte() }, options)
        val row = canvas.regions.first()
        val next = view.onEvent(OutputEvent(OutputEvent.CLICK, row.id, row.x + 1f, row.y), options)
        assertNull(next["hex.selected"], "clicking it a second time kept it selected")
    }

    @Test
    fun `two panels of the same view do not interfere`() {
        // the worker answers requests on a pool, so the same view instance can be drawing one panel
        // while answering a click on another; nothing may be carried in a field between the two
        val view = HexOutput()
        val wide = FakeCanvas(width = 900f)
        val narrow = FakeCanvas(width = 260f)
        view.draw(wide, ByteArray(64) { it.toByte() }, emptyMap())
        view.draw(narrow, ByteArray(64) { it.toByte() }, emptyMap())

        // a click on the wide panel, resolved after the narrow one was drawn over it
        val row = wide.regions.first()
        val clicked = view.onEvent(
            OutputEvent(OutputEvent.CLICK, row.id, row.x + 3 * wide.monoCharWidth * 12f + 1f, row.y),
            emptyMap(),
        )
        assertEquals("1", clicked["hex.selected"], "the click was read against the wrong layout")
    }

    /* ───────── the default ───────── */

    @Test
    fun `a view that does not care about clicks is left as it was`() {
        val options = mapOf("encoding" to "UTF-8")
        val next = StringOutput().onEvent(OutputEvent(OutputEvent.CLICK, "anything", 0f, 0f), options)
        assertSame(options, next, "the default onEvent should hand back exactly what it was given")
    }

    @Test
    fun `a click on nothing changes nothing`() {
        val view = Asn1Output()
        val unchanged = view.onEvent(OutputEvent(OutputEvent.CLICK, null, 5f, 5f), treeOnly)
        assertEquals(treeOnly, unchanged)
    }

    @Test
    fun `a hover is not a click`() {
        val view = Asn1Output()
        val canvas = FakeCanvas()
        view.draw(canvas, rsaKey(), emptyMap())
        val region = canvas.regions.first()
        val hovered = view.onEvent(OutputEvent(OutputEvent.HOVER, region.id, region.x, region.y), treeOnly)
        assertEquals(treeOnly, hovered, "hovering closed a branch")
    }
}
