package flow.model

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every published version is the version the module actually reports.
 *
 * These are two different files — the manifest a registry serves, and the Kotlin that declares
 * `version` — and only the second one reaches the machine an module is installed on. Raising the
 * manifest without raising the source produces an module that installs perfectly and then still
 * says it is the older one, so the update is offered again, and again: the button never goes away
 * and nothing the user does makes it. That happened twice before this test existed.
 */
class PublishedVersionTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Where the module sources live, from wherever the test happens to be run. */
    private fun extensionRoot(): File =
        File("../flow-modules").takeIf { it.isDirectory } ?: File("flow-modules")

    /**
     * The version each module declares, by id.
     *
     * An module that does not declare one takes the interface's default, so a missing
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
        // the file CI publishes verbatim as extensions.json, read directly — a fixture copy of it
        // is one more thing to keep in step, and the copy that used to be here had fallen behind
        val raw = File(extensionRoot(), "registry.json").readText()
        return json.decodeFromString<RegistryIndex>(raw).extensions
    }

    @Test
    fun `the sources this repository builds are where the published extensions come from`() {
        val declared = declaredVersions()
        assertTrue(declared.size >= 20, "only found ${declared.size} extensions in the sources")
        val missing = published().map { it.id }.filterNot { it in declared }
        assertTrue(missing.isEmpty(), "published but not built here: $missing")
    }

    /**
     * The version each built jar reports, by id, read by loading it the way the app does.
     *
     * The source saying 2.2.0 is no help if the jar beside it was built before the edit — and it is
     * the jar that gets published. Only the ones this module already builds are here, which is
     * every module whose behaviour is under test.
     */
    private fun builtVersions(): Map<String, String> {
        val jars = extensionRoot().listFiles().orEmpty()
            .mapNotNull { File(it, "build/libs").listFiles()?.firstOrNull { f -> f.extension == "jar" } }
        return buildMap {
            jars.forEach { jar ->
                val loader = java.net.URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader)
                java.util.ServiceLoader.load(flow.extension.ModuleExtension::class.java, loader)
                    .forEach { put(it.id, it.version) }
                java.util.ServiceLoader.load(flow.extension.ViewExtension::class.java, loader)
                    .forEach { put(it.id, it.version) }
            }
        }
    }

    @Test
    fun `a jar that was built before the version was raised is not publishable`() {
        val declared = declaredVersions()
        val built = builtVersions()
        assertTrue(built.size >= 6, "only ${built.size} extension jars were built to check")
        val stale = built.mapNotNull { (id, version) ->
            val source = declared[id] ?: return@mapNotNull null
            if (source == version) null else "$id: source $source, jar $version"
        }
        assertEquals(emptyList(), stale, "these jars are older than the source they were built from")
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

    /**
     * The category each module declares, by id. None declared is the interface's default of "",
     * which reads as Other.
     */
    private fun declaredCategories(): Map<String, String> {
        val id = Regex("""override val id = "([^"]+)"""")
        val category = Regex("""override val category = "([^"]+)"""")
        return extensionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("src/main/") }
            .mapNotNull { file ->
                val text = file.readText()
                val found = id.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                found to (category.find(text)?.groupValues?.get(1) ?: "")
            }
            .toMap()
    }

    /**
     * The group an module is listed under is the same in both places it is written down.
     *
     * The manifest's category sorts the Modules screen; the source's sorts the palette, because
     * the palette groups what is installed and has no manifest to consult. They are the same piece
     * of information written twice, and a category raised in one and not the other puts an
     * module in two different groups depending on which screen you are looking at.
     */
    @Test
    fun `an extension is in the same category wherever it is listed`() {
        val declared = declaredCategories()
        val wrong = published().mapNotNull { entry ->
            val source = declared[entry.id] ?: return@mapNotNull null
            // a view has no palette row to group, so only modules have to agree
            if (kindOf(entry) != KIND_MODULE || source == categoryOf(entry)) null
            else "${entry.id}: source '$source', manifest '${entry.category}'"
        }
        assertEquals(emptyList(), wrong, "the palette and the Extensions screen would group these differently")
    }
}
