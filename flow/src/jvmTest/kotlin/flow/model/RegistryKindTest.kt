package flow.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry manifest, as the app reads it.
 *
 * `kind` was added after extensions were already published, so both directions have to keep
 * working: an entry without one is a module, and an entry naming a kind this build has never heard
 * of is a module too rather than disappearing from the list.
 */
class RegistryKindTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an entry with no kind is a module`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.base64","name":"Base64","file":"extensions/flow.base64.flowext"}]}""",
        )
        assertEquals(KIND_MODULE, index.extensions.single().kind)
    }

    @Test
    fun `a view says so`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.view.hex","name":"Hex View","kind":"view","file":"x.flowext"}]}""",
        )
        assertEquals(KIND_VIEW, index.extensions.single().kind)
    }

    @Test
    fun `an unknown field does not lose the entry`() {
        // the registry is shared with builds newer than this one, which may add fields
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.qr","name":"QR","file":"x","somethingNew":42}]}""",
        )
        assertEquals("flow.qr", index.extensions.single().id)
    }

    @Test
    fun `a kind this build does not know is treated as a module`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.future","name":"F","kind":"gadget","file":"x"}]}""",
        )
        // only KIND_VIEW moves an entry out of the modules list, so anything else stays visible
        assertTrue(index.extensions.single().kind != KIND_VIEW)
    }

    @Test
    fun `the published manifest parses, and every entry points somewhere`() {
        // a copy of what the app actually downloads: a typo here breaks the Extensions screen for
        // everyone at once, and nothing else in the build would notice
        val raw = javaClass.getResourceAsStream("/extensions-registry.json")!!.readBytes().decodeToString()
        val index = json.decodeFromString<RegistryIndex>(raw)
        assertTrue(index.extensions.size >= 24, "only ${index.extensions.size} entries")
        index.extensions.forEach { e ->
            assertTrue(e.id.isNotBlank(), "an entry has no id")
            assertTrue(e.file.isNotBlank(), "${e.id} names no file")
            assertTrue(e.version.isNotBlank(), "${e.id} has no version")
            assertTrue(e.kind in listOf(KIND_MODULE, KIND_VIEW), "${e.id} has kind '${e.kind}'")
        }
        assertEquals(
            index.extensions.map { it.id }.distinct().size, index.extensions.size,
            "the manifest lists the same id twice",
        )
        assertEquals(4, index.extensions.count { it.kind == KIND_VIEW })
    }
}
