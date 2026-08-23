package flow.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What build_flow does with a spec: work out the ports, the wiring and the coordinates, and refuse
 * a spec it cannot make sense of rather than writing a file that opens wrong.
 *
 * These use cin/cout only, so they hold whatever extensions happen to be installed on the machine.
 */
class FlowBuilderTest {

    private fun spec(id: String, type: String, label: String? = null, x: Float? = null, y: Float? = null) =
        NodeSpec(id, type, label, emptyMap(), x, y)

    @Test
    fun `a chain is laid out left to right`() {
        val flow = buildFlow(
            listOf(spec("a", "cin", "A"), spec("b", "cout", "B")),
            listOf(EdgeSpec("a", "b")),
        )
        val a = flow.nodes.first { it.id == "a" }
        val b = flow.nodes.first { it.id == "b" }
        assertTrue(b.x > a.x, "the consumer should sit to the right of what feeds it")
    }

    @Test
    fun `a node is placed against the nodes feeding it`() {
        // two inputs far apart: the node they feed belongs between them, not stacked at the top
        val flow = buildFlow(
            listOf(
                spec("top", "cin", "Top"),
                spec("bottom", "cin", "Bottom"),
                spec("out", "cout", "Out"),
            ),
            listOf(EdgeSpec("top", "out"), EdgeSpec("bottom", "out")),
        )
        val top = flow.nodes.first { it.id == "top" }.y
        val bottom = flow.nodes.first { it.id == "bottom" }.y
        val out = flow.nodes.first { it.id == "out" }.y
        assertTrue(out in top..bottom, "expected $out between $top and $bottom")
    }

    @Test
    fun `coordinates given by the caller are used as given`() {
        val flow = buildFlow(
            listOf(spec("a", "cin", "A", x = 120f, y = 340f), spec("b", "cout", "B")),
            listOf(EdgeSpec("a", "b")),
        )
        val a = flow.nodes.first { it.id == "a" }
        assertEquals(120f, a.x)
        assertEquals(340f, a.y)
    }

    @Test
    fun `an auto-placed node stays right of a pinned node feeding it`() {
        // pinning only some nodes used to leave a consumer left of its own source, wiring backwards
        val flow = buildFlow(
            listOf(spec("a", "cin", "A", x = 900f, y = 60f), spec("b", "cout", "B")),
            listOf(EdgeSpec("a", "b")),
        )
        val a = flow.nodes.first { it.id == "a" }
        val b = flow.nodes.first { it.id == "b" }
        assertTrue(b.x > a.x, "auto-placed ${b.x} should be right of pinned ${a.x}")
    }

    @Test
    fun `an endpoint may name just the node when that side has one port`() {
        val flow = buildFlow(
            listOf(spec("a", "cin", "A"), spec("b", "cout", "B")),
            listOf(EdgeSpec("a", "b")),
        )
        val edge = flow.edges.single()
        assertEquals("out", edge.from.port)
        assertEquals("in", edge.to.port)
    }

    @Test
    fun `an unknown node type is refused`() {
        val e = assertFailsWith<IllegalStateException> {
            buildFlow(listOf(spec("a", "no.such.module", "A")), emptyList())
        }
        assertContains(e.message!!, "unknown type")
    }

    @Test
    fun `an edge to a port the node does not have is refused`() {
        val e = assertFailsWith<IllegalArgumentException> {
            buildFlow(
                listOf(spec("a", "cin", "A"), spec("b", "cout", "B")),
                listOf(EdgeSpec("a", "b.nope")),
            )
        }
        assertContains(e.message!!, "nope")
    }

    @Test
    fun `two nodes with the same id are refused`() {
        val e = assertFailsWith<IllegalArgumentException> {
            buildFlow(listOf(spec("a", "cin", "A"), spec("a", "cout", "B")), emptyList())
        }
        assertContains(e.message!!, "duplicate")
    }

    @Test
    fun `wiring faults are reported rather than written out quietly`() {
        val flow = buildFlow(
            listOf(spec("a", "cin", "A"), spec("b", "cin", "B"), spec("out", "cout", "Out")),
            listOf(EdgeSpec("a", "out"), EdgeSpec("b", "out")),
        )
        val problems = flowProblems(flow)
        assertTrue(problems.any { it.contains("fed by 2 edges") }, "expected the double-fed input: $problems")
    }

    @Test
    fun `a flow with no boundary nodes says so`() {
        val flow = buildFlow(listOf(spec("a", "cin", "A")), emptyList())
        assertTrue(flowProblems(flow).any { it.contains("cout") })
    }
}
