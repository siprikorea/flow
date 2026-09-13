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

    /**
     * Every entry names a jar this build produces, and every jar has an entry.
     *
     * This is the release's own check, run here instead of ten minutes into CI: the workflow
     * compares the manifest's file names against the built jars and fails the deploy when they
     * disagree. An entry naming a jar that is not published is a row whose Install button 404s.
     */
    @Test
    fun `every entry names a jar this build produces, and every jar has an entry`() {
        val built = java.io.File(EXTENSIONS).listFiles().orEmpty()
            .flatMap { java.io.File(it, "build/libs").listFiles().orEmpty().toList() }
            .filter { it.extension == "jar" }
            .map { it.name }
            .sorted()
        assertTrue(built.size >= 20, "only ${built.size} jars were built to check against")
        val listed = json.decodeFromString<RegistryIndex>(manifest()).extensions.map { it.file }.sorted()
        assertEquals(built, listed, "the manifest and the built jars disagree")
    }

    /**
     * The manifest itself, not a copy of it.
     *
     * CI publishes this file verbatim as `extensions.json`, which is what every installed app
     * downloads. A fixture beside the test would be a second copy to keep in step — and the one
     * that used to be here had gone stale enough to be missing two extensions entirely, which is
     * exactly the drift these tests exist to catch.
     */
    private fun manifest(): String = java.io.File("$EXTENSIONS/registry.json").readText()

    private companion object {
        /** From wherever the test happens to be run: Gradle starts it in the module directory. */
        val EXTENSIONS: String =
            if (java.io.File("../flow-extensions").isDirectory) "../flow-extensions" else "flow-extensions"
    }
    /* ───────── categories ───────── */

    @Test
    fun `an entry with no category is listed under Other`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.base64","name":"Base64","file":"x"}]}""",
        )
        assertEquals(CATEGORY_OTHER, categoryOf(index.extensions.single()))
    }

    @Test
    fun `a category this build does not know is listed under Other, not dropped`() {
        // the manifest is shared with builds newer than this one, which may add categories
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[{"id":"flow.x","name":"X","file":"x","category":"quantum"}]}""",
        )
        assertEquals(CATEGORY_OTHER, categoryOf(index.extensions.single()))
        assertEquals(1, byCategory(index.extensions).sumOf { it.second.size })
    }

    @Test
    fun `groups come out in a fixed order, and empty ones do not appear`() {
        val index = json.decodeFromString<RegistryIndex>(
            """{"extensions":[
                {"id":"a","file":"x","category":"ai"},
                {"id":"b","file":"x","category":"crypto"},
                {"id":"c","file":"x","category":"crypto"}
            ]}""",
        )
        val groups = byCategory(index.extensions)
        assertEquals(listOf(CATEGORY_CRYPTO, CATEGORY_AI), groups.map { it.first })
        // within a category, the registry's own order stands
        assertEquals(listOf("b", "c"), groups.first().second.map { it.id })
    }

    /**
     * The shipped manifest says what each extension is for.
     *
     * A typo would not fail anything — the entry would quietly appear under Other — so the check
     * that every category is one the app knows belongs here rather than being left to notice.
     */
    @Test
    fun `every extension in the shipped registry has a category this build knows`() {
        val file = java.io.File("../flow-extensions/registry.json")
            .let { if (it.isFile) it else java.io.File("flow-extensions/registry.json") }
        assertTrue(file.isFile, "registry.json not found at ${file.absolutePath}")
        val index = json.decodeFromString<RegistryIndex>(file.readText())
        assertTrue(index.extensions.isNotEmpty())
        val unknown = index.extensions.filter { it.category !in CATEGORIES }
        assertTrue(unknown.isEmpty(), "not a category this build knows: ${unknown.map { it.id to it.category }}")
    }

    /* ───────── the palette's grouping ───────── */

    private fun module(id: String, category: String) =
        ModuleInfo(id, id, listOf("in"), listOf("out"), category = category)

    @Test
    fun `installed processors group by what the extension says it is`() {
        val groups = modulesByCategory(
            listOf(module("a", "other"), module("b", "crypto"), module("c", "ai"), module("d", "crypto")),
        )
        assertEquals(listOf(CATEGORY_CRYPTO, CATEGORY_AI, CATEGORY_OTHER), groups.map { it.first })
        // the order they were given in stands within a group — the workspace sorts them by name
        assertEquals(listOf("b", "d"), groups.first().second.map { it.id })
    }

    /**
     * An extension installed before categories existed, or from a newer build than this one, still
     * has a row in the palette — under Other, which is the whole of what an unknown category costs.
     */
    @Test
    fun `an extension that says nothing, or something unknown, is listed under Other`() {
        assertEquals(CATEGORY_OTHER, categoryOf(module("old", "")))
        assertEquals(CATEGORY_OTHER, categoryOf(module("future", "quantum")))
        val groups = modulesByCategory(listOf(module("old", ""), module("future", "quantum")))
        assertEquals(listOf(CATEGORY_OTHER), groups.map { it.first })
        assertEquals(2, groups.single().second.size)
    }
}
