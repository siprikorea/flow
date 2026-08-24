package flow.model

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every published version is the version the extension actually reports.
 *
 * These are two different files — the manifest a registry serves, and the Kotlin that declares
 * `version` — and only the second one reaches the machine an extension is installed on. Raising the
 * manifest without raising the source produces an extension that installs perfectly and then still
 * says it is the older one, so the update is offered again, and again: the button never goes away
 * and nothing the user does makes it. That happened twice before this test existed.
 */
class PublishedVersionTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Where the extension sources live, from wherever the test happens to be run. */
    private fun extensionRoot(): File =
        File("../flow-extensions").takeIf { it.isDirectory } ?: File("flow-extensions")

    /**
     * The version each extension declares, by id.
     *
     * An extension that does not declare one takes the interface's default, so a missing
     * declaration is 1.0.0 rather than an omission.
     */
    private fun declaredVersions(): Map<String, String> {
        val id = Regex("""override val id = "([^"]+)"""")
        val version = Regex("""override val version = "([^"]+)"""")
        return extensionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("src/main/") }
            .mapNotNull { file ->
                val text = file.readText()
                val found = id.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                found to (version.find(text)?.groupValues?.get(1) ?: "1.0.0")
            }
            .toMap()
    }

    private fun published(): List<RegistryEntry> {
        val raw = javaClass.getResourceAsStream("/extensions-registry.json")!!.readBytes().decodeToString()
        return json.decodeFromString<RegistryIndex>(raw).extensions
    }

    @Test
    fun `the sources this repository builds are where the published extensions come from`() {
        val declared = declaredVersions()
        assertTrue(declared.size >= 20, "only found ${declared.size} extensions in the sources")
        val missing = published().map { it.id }.filterNot { it in declared }
        assertTrue(missing.isEmpty(), "published but not built here: $missing")
    }

    @Test
    fun `what the manifest offers is what the extension will report once installed`() {
        val declared = declaredVersions()
        val wrong = published().mapNotNull { entry ->
            val source = declared[entry.id] ?: return@mapNotNull null
            if (source == entry.version) null else "${entry.id}: source $source, manifest ${entry.version}"
        }
        assertEquals(
            emptyList(), wrong,
            "the manifest offers a version the extension does not report, so the update never settles",
        )
    }
}
