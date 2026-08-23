package flow.core

import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a .flow file keeps. It describes the flow — the modules, their options and how they are
 * wired — and nothing from having run it: an input is the user's to supply each time, an output
 * only exists once produced.
 */
class SavedContentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun doc(flow: FlowFile): EditorState {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        return EditorState(CoroutineScope(Dispatchers.Unconfined), ws, "a.flow").also { it.load(flow) }
    }

    private fun node(id: String, type: String, ins: List<Port>, outs: List<Port>) =
        Node(id, type, id, 0f, 0f, 180f, 100f, ins, outs, mapOf("algo" to "SHA-256"), "idle")

    private fun withData() = FlowFile(
        nodes = listOf(
            node("in", "cin", emptyList(), listOf(Port("out", "SECRET".encodeToByteArray()))),
            node("m", "test.mod", listOf(Port("in", "MID".encodeToByteArray())), listOf(Port("out", "RESULT".encodeToByteArray()))),
        ),
        edges = listOf(Edge("e1", PortRef("in", "out"), PortRef("m", "in"))),
        seq = 3,
    )

    @Test
    fun `port values are left out of the saved file`() {
        val saved = json.decodeFromString<FlowFile>(doc(withData()).flowJson())
        saved.nodes.forEach { n ->
            (n.inputs + n.outputs).forEach { port ->
                assertTrue(
                    port.data.isEmpty(),
                    "${n.id}.${port.name} was saved carrying ${port.data.size} bytes",
                )
            }
        }
    }

    @Test
    fun `the saved file still describes the flow itself`() {
        val saved = json.decodeFromString<FlowFile>(doc(withData()).flowJson())
        assertEquals(listOf("in", "m"), saved.nodes.map { it.id })
        // ports keep their names, options and wiring — only the values go
        assertEquals(listOf("in"), saved.nodes.first { it.id == "m" }.inputs.map { it.name })
        assertEquals("SHA-256", saved.nodes.first { it.id == "m" }.params["algo"])
        assertEquals(1, saved.edges.size)
        assertEquals(PortRef("in", "out"), saved.edges.single().from)
    }

    @Test
    fun `typing into an input is not an unsaved change`() {
        // it is not written to the file, so there is nothing about it left to save
        // load() takes the saved signature, so this starts clean
        val d = doc(withData())
        d.persisted = true
        d.updateNode("in") { n -> n.copy(outputs = listOf(Port("out", "SOMETHING ELSE".encodeToByteArray()))) }
        assertFalse(d.dirty)
    }

    @Test
    fun `an empty canvas is never dirty`() {
        // closing a blank tab that was never drawn on should not stop to ask about saving it
        val d = doc(FlowFile())
        d.persisted = false
        assertTrue(d.isEmpty)
        assertFalse(d.dirty, "a blank document asked to be saved")
    }

    @Test
    fun `a canvas with something on it is still dirty when unsaved`() {
        val d = doc(withData())
        d.persisted = false
        assertFalse(d.isEmpty)
        assertTrue(d.dirty)
    }
}
