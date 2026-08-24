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
    fun `an entry with no kind is a processor`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.base64","name":"Base64","file":"extensions/flow.base64.flowext"}]}""",
        )
        assertEquals(KIND_PROCESSOR, index.extensions.single().kind)
    }

    @Test
    fun `a view says so`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.view.asn1","name":"ASN.1","kind":"view","file":"x.flowext"}]}""",
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
    fun `a kind this build does not know is treated as a processor`() {
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
        val index = json.decodeFromString<RegistryIndex>(manifest())
        assertTrue(index.extensions.size >= 20, "only ${index.extensions.size} entries")
        index.extensions.forEach { e ->
            assertTrue(e.id.isNotBlank(), "an entry has no id")
            assertTrue(e.file.isNotBlank(), "${e.id} names no file")
            assertTrue(e.version.isNotBlank(), "${e.id} has no version")
            assertTrue(e.kind in listOf(KIND_PROCESSOR, KIND_VIEW), "${e.id} has kind '${e.kind}'")
        }
        assertEquals(
            index.extensions.map { it.id }.distinct().size, index.extensions.size,
            "the manifest lists the same id twice",
        )
        assertEquals(2, index.extensions.count { it.kind == KIND_VIEW })
    }

    @Test
    fun `no two entries are the same jar`() {
        // The install store keeps a folder per extension a jar provides, so installing a jar that
        // provides three installs all three. That is right for a jar meant as one extension in
        // three parts — and wrong for a manifest that lists them as three rows, because then
        // clicking Install on one row silently installs the other two. Two rows, two jars.
        val index = json.decodeFromString<RegistryIndex>(manifest())
        val shared = index.extensions.groupBy { it.file }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "these entries share a jar, so installing one would install the others: " +
                shared.map { (file, entries) -> "$file <- ${entries.map { it.id }}" },
        )
    }

    @Test
    fun `an entry's jar is named after the entry`() {
        // not required by anything, but it is what makes the one-to-one above visible at a glance
        val index = json.decodeFromString<RegistryIndex>(manifest())
        index.extensions.forEach { e ->
            assertEquals(
                "extensions/${e.id}.flowext", e.file,
                "${e.id} is published as ${e.file}",
            )
        }
    }

    private fun manifest(): String =
        javaClass.getResourceAsStream("/extensions-registry.json")!!.readBytes().decodeToString()
}
