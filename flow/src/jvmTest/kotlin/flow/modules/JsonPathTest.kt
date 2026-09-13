package flow.modules

import flow.extension.ModuleExtension
import flow.model.Edge
import flow.model.FlowFile
import flow.model.Node
import flow.model.Port
import flow.model.PortRef
import java.util.ServiceLoader
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Taking one field out of a JSON document.
 *
 * The failure worth guarding against is not a wrong answer — it is a quiet one. A path that finds
 * nothing must stop the flow, because a renamed field that produces an empty output produces a
 * flow that goes on to hash, sign or post that emptiness and reports success. So most of these
 * are about what happens when the path is wrong, and about the message being enough to fix it.
 */
class JsonPathTest {

    private val json: ModuleExtension =
        ServiceLoader.load(ModuleExtension::class.java).first { it.id == "flow.json" }

    private val document = """
        {
          "token": "abc.def",
          "count": 3,
          "ok": true,
          "user": { "name": "Ada", "roles": ["admin", "dev"] },
          "items": [ { "id": 1 }, { "id": 2 } ],
          "headers": { "content-type": "application/json", "x.trace": "t-9" },
          "nothing": null,
          "empty": {},
          "none": []
        }
    """.trimIndent()

    private fun at(path: String, doc: String = document): String {
        val options = json.optionsFor(mapOf("path" to path)).associate { it.name to it.default } + ("path" to path)
        return json.process(mapOf("in" to doc.encodeToByteArray()), options)["out"]!!.decodeToString()
    }

    private fun failure(path: String, doc: String = document): String =
        runCatching { at(path, doc) }.exceptionOrNull()?.message
            ?: error("'$path' was expected to fail, and did not")

    /**
     * A string comes out as its text, with no quotes.
     *
     * This is what makes the module chainable: the next node hashes the token, not the token
     * wrapped in quotation marks — a difference nothing downstream would ever notice.
     */
    @Test
    fun `a string comes out as itself, ready for the next node`() {
        assertEquals("abc.def", at("token"))
        assertEquals("Ada", at("user.name"))
    }

    @Test
    fun `numbers keep the digits they were written with`() {
        assertEquals("3", at("count"))
        assertEquals("true", at("ok"))
        assertEquals("null", at("nothing"))
        // a value's precision is not a thing to be helpful about
        assertEquals("1.10", at("v", """{"v":1.10}"""))
        assertEquals("10000000000000000001", at("v", """{"v":10000000000000000001}"""))
    }

    @Test
    fun `arrays are reached by index, through as many steps as it takes`() {
        assertEquals("2", at("items[1].id"))
        assertEquals("admin", at("user.roles[0]"))
        assertEquals("dev", at("""user["roles"][1]"""))
    }

    @Test
    fun `a key with a dot in it is reachable, because JSON allows one`() {
        assertEquals("application/json", at("""headers["content-type"]"""))
        assertEquals("t-9", at("""headers["x.trace"]"""))
    }

    @Test
    fun `an object or array comes out as JSON, indented`() {
        val out = at("user")
        assertTrue(out.startsWith("{") && out.contains("\n"), out)
        assertTrue(out.contains("\"name\": \"Ada\""), out)
    }

    /** Unchanged behaviour: no path is the whole document, which is what this module used to do. */
    @Test
    fun `no path means the whole document, as before`() {
        val out = at("")
        assertTrue(out.contains("\"token\"") && out.contains("\"items\""), out)
    }

    /* ───────── the paths that find nothing ───────── */

    @Test
    fun `a field that is not there fails, and says what was`() {
        val said = failure("user.email")
        assertTrue(said.contains("found nothing"), said)
        assertTrue(said.contains("'.user'"), "it does not say how far it got: $said")
        assertTrue(said.contains("name") && said.contains("roles"), "it does not say what was there: $said")
    }

    @Test
    fun `an index past the end says how many there were`() {
        val said = failure("items[5].id")
        assertTrue(said.contains("2 items"), said)
        assertEquals("2", at("items[1].id"), "the last real index still works")
    }

    @Test
    fun `stepping into something that cannot be stepped into says what it is`() {
        assertTrue(failure("token.length").contains("is a string"), failure("token.length"))
        assertTrue(failure("user[0]").contains("is an object"), failure("user[0]"))
        assertTrue(failure("items.id").contains("is an array"), failure("items.id"))
        assertTrue(failure("nothing.any").contains("is null"), failure("nothing.any"))
    }

    @Test
    fun `an empty object or array says it is empty rather than listing nothing`() {
        assertTrue(failure("empty.a").contains("empty"), failure("empty.a"))
        assertTrue(failure("none[0]").contains("empty array"), failure("none[0]"))
    }

    /**
     * What was there is described, not printed.
     *
     * A path error goes into a log and into an MCP client's transcript. The shape of a document is
     * what a caller needs to fix a path; the values in it are the flow's business.
     */
    @Test
    fun `a failure describes the document without quoting what is in it`() {
        val secret = """{"credentials":{"password":"hunter2"},"user":{"name":"Ada"}}"""
        val said = failure("credentials.token", secret)
        assertTrue(said.contains("password"), "the key it does have is what fixes the path: $said")
        assertTrue(!said.contains("hunter2"), "the failure quoted a value: $said")
    }

    @Test
    fun `a path that is not a path says so before looking at the document`() {
        assertTrue(failure(".leading").contains("not a usable path"), failure(".leading"))
        assertTrue(failure("a..b").contains("nothing after"), failure("a..b"))
        assertTrue(failure("a[").contains("never closed"), failure("a["))
        assertTrue(failure("a[x]").contains("neither an index nor a quoted key"), failure("a[x]"))
        assertTrue(failure("a[-1]").contains("neither an index"), failure("a[-1]"))
    }

    @Test
    fun `a document that does not parse still says where it stopped making sense`() {
        val said = failure("a", """{"a": }""")
        assertTrue(said.contains("invalid JSON at offset"), said)
    }

    /* ───────── round trips ───────── */

    /**
     * Re-formatting is idempotent, and does not change what the document says.
     *
     * Running a document through twice must not keep changing it — a formatter that is not stable
     * makes every comparison downstream of it depend on how many times it ran.
     */
    @Test
    fun `formatting a document twice gives the same text, and the same meaning`() {
        val once = at("")
        val twice = at("", once)
        assertEquals(once, twice, "re-formatting kept changing the document")
        // and the values are still reachable after the trip through the formatter
        assertEquals("abc.def", at("token", once))
        assertEquals("2", at("items[1].id", once))
        assertEquals("t-9", at("""headers["x.trace"]""", once))
    }

    /**
     * A path survives being saved and opened again.
     *
     * It is the whole configuration of the node — a file that dropped it would open as a node that
     * looks the same and hands on the entire document instead of the one field.
     */
    @Test
    fun `a saved path node comes back extracting the same field`() {
        val serializer = Json { ignoreUnknownKeys = true }
        val params = mapOf("path" to """headers["x.trace"]""", "indent" to "4", "sortKeys" to "true")
        val flow = FlowFile(
            nodes = listOf(
                Node("j", json.id, "JSON", 0f, 0f, 180f, 100f,
                    listOf(Port("in", ByteArray(0))), listOf(Port("out", ByteArray(0))), params, "idle"),
                Node("h", "flow.hash", "Hash", 0f, 0f, 180f, 100f,
                    listOf(Port("in", ByteArray(0))), listOf(Port("out", ByteArray(0))), emptyMap(), "idle"),
            ),
            edges = listOf(Edge("e1", PortRef("j", "out"), PortRef("h", "in"))),
            seq = 2,
        )
        val reopened = serializer.decodeFromString<FlowFile>(serializer.encodeToString(FlowFile.serializer(), flow))
        val node = reopened.nodes.single { it.id == "j" }
        assertEquals(params, node.params, "the path did not survive the file")
        assertEquals(
            "t-9",
            json.process(mapOf("in" to document.encodeToByteArray()), node.params)["out"]!!.decodeToString(),
        )
    }
}
