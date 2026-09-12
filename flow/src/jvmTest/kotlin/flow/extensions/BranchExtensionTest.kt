package flow.extensions

import flow.branch.BranchExtension
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one node that decides which way a value goes.
 *
 * Two things matter here and nothing else really does. The first is that the side not taken
 * carries *nothing* rather than an empty value: the engine reads nothing as "this branch did not
 * happen" and skips what hangs off it, while an empty value is a value and would go on to be
 * hashed, posted or encrypted as one. The second is that a comparison that cannot be made honestly
 * — "10" against "9" as numbers when one of them is a word — fails loudly instead of falling back
 * to comparing them as text and being wrong on a case nobody tries.
 */
class BranchExtensionTest {

    private val branch = BranchExtension()

    private fun run(input: String?, vararg options: Pair<String, String>): Map<String, ByteArray?> =
        branch.process(
            mapOf("in" to input?.encodeToByteArray()),
            branch.optionsFor(options.toMap()).associate { it.name to it.default } + options,
        )

    private fun taken(out: Map<String, ByteArray?>): String? = when {
        out["then"] != null -> "then"
        out["else"] != null -> "else"
        else -> null
    }

    @Test
    fun `the value goes out one side, and the other carries nothing at all`() {
        val out = run("abc", "test" to "equals", "value" to "abc")
        assertEquals("abc", out["then"]!!.decodeToString(), "the value was not passed through untouched")
        assertNull(out["else"], "the untaken side carried something, so the flow would continue down it")
        assertTrue(out.containsKey("else"), "the port is still there, it is just empty")
    }

    @Test
    fun `the tests decide the way a reader would expect`() {
        assertEquals("then", taken(run("hello world", "test" to "contains", "value" to "lo w")))
        assertEquals("else", taken(run("hello", "test" to "contains", "value" to "bye")))
        assertEquals("then", taken(run("report.pdf", "test" to "endsWith", "value" to ".pdf")))
        assertEquals("then", taken(run("v1.2.3", "test" to "startsWith", "value" to "v")))
        assertEquals("then", taken(run("abc123", "test" to "matches", "value" to """^[a-z]+\d+$""")))
        assertEquals("else", taken(run("abc", "test" to "matches", "value" to """^\d+$""")))
    }

    /**
     * An empty input is a decision too, not a missing one.
     *
     * A node that got nothing at all is skipped by the engine before it reaches here, so an empty
     * value arriving means something upstream really did produce nothing — which is the case
     * isEmpty exists to catch.
     */
    @Test
    fun `isEmpty asks only about the input`() {
        assertEquals("then", taken(run("", "test" to "isEmpty")))
        assertEquals("else", taken(run("x", "test" to "isEmpty")))
        // and it does not need a value to compare against, so it is not offered one
        assertTrue(branch.optionsFor(mapOf("test" to "isEmpty")).none { it.name == "value" })
    }

    /**
     * Case folding is off unless asked for.
     *
     * This node's most likely job is comparing a digest, a token or a base64 value against a
     * stored one, and a comparison that ignores case passes on values that are not equal.
     */
    @Test
    fun `case matters by default, and can be told not to`() {
        assertEquals("else", taken(run("ABC", "test" to "equals", "value" to "abc")))
        assertEquals("then", taken(run("ABC", "test" to "equals", "value" to "abc", "caseSensitive" to "false")))
    }

    @Test
    fun `numbers are compared as numbers`() {
        // as text, "10" is less than "9" — which is the bug this test exists for
        assertEquals("then", taken(run("10", "test" to "greaterThan", "value" to "9")))
        assertEquals("then", taken(run("2.5", "test" to "lessThan", "value" to "10")))
    }

    @Test
    fun `a comparison that cannot be made as numbers fails instead of guessing`() {
        val failure = runCatching { run("many", "test" to "greaterThan", "value" to "9") }.exceptionOrNull()
        assertTrue(failure != null, "'many' was silently compared to 9")
        assertTrue(failure.message!!.contains("number"), "${failure.message}")
        // and it says which side was the problem, since either one can be
        assertTrue(failure.message!!.contains("in"), "${failure.message}")
    }

    @Test
    fun `a regular expression that does not parse says so`() {
        val failure = runCatching { run("x", "test" to "matches", "value" to "([unclosed") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("regular expression") == true, "${failure?.message}")
    }

    /**
     * The port wins over the option.
     *
     * Comparing two values the flow computed — a fresh digest against a stored one — is the case
     * the option cannot express, and it is the one worth having.
     */
    @Test
    fun `a connected compare port replaces the typed-in value`() {
        val out = branch.process(
            mapOf("in" to "9f86d0".encodeToByteArray(), "compare" to "9f86d0".encodeToByteArray()),
            mapOf("test" to "equals", "value" to "something else entirely", "caseSensitive" to "true"),
        )
        assertEquals("9f86d0", out["then"]?.decodeToString(), "the option was used instead of the port")
    }

    @Test
    fun `compare is the input that may be left unconnected`() {
        assertEquals(listOf("compare"), branch.optionalInputsFor(emptyMap()))
        assertTrue("in" !in branch.optionalInputsFor(emptyMap()), "the value itself cannot be optional")
    }

    /**
     * A branch survives being saved and opened again, still deciding the same way.
     *
     * Its two outputs and the option that chooses between them are the whole node; a file that
     * kept only one of the ports, or dropped 'caseSensitive', would open as a flow that looks
     * right and takes the other path.
     */
    @Test
    fun `a saved branch node comes back deciding the same way`() {
        val json = Json { ignoreUnknownKeys = true }
        val params = mapOf("test" to "endsWith", "value" to ".PDF", "caseSensitive" to "false")
        val flow = FlowFile(
            nodes = listOf(
                Node("b", branch.id, "Branch", 0f, 0f, 180f, 100f,
                    branch.inputs.map { Port(it, ByteArray(0)) },
                    branch.outputs.map { Port(it, ByteArray(0)) },
                    params, "idle"),
                Node("k", "flow.hash", "Keep", 0f, 0f, 180f, 100f,
                    listOf(Port("in", ByteArray(0))), listOf(Port("out", ByteArray(0))), emptyMap(), "idle"),
            ),
            edges = listOf(Edge("e1", PortRef("b", "then"), PortRef("k", "in"))),
            seq = 2,
        )

        val reopened = json.decodeFromString<FlowFile>(json.encodeToString(FlowFile.serializer(), flow))
        val node = reopened.nodes.single { it.id == "b" }
        assertEquals(branch.id, node.type)
        assertEquals(listOf("in", "compare"), node.inputs.map { it.name })
        assertEquals(listOf("then", "else"), node.outputs.map { it.name }, "an output port was lost")
        assertEquals(params, node.params, "the options did not survive the file")
        // the edge is anchored to a named port, so it has to still name one that exists
        assertEquals(PortRef("b", "then"), reopened.edges.single().from)
        assertTrue(reopened.edges.single().from.port in node.outputs.map { it.name })

        // and the options mean what they meant: case-insensitively, this ends with .PDF
        assertEquals("then", taken(branch.process(
            mapOf("in" to "report.pdf".encodeToByteArray()),
            node.params,
        )))
    }
}
