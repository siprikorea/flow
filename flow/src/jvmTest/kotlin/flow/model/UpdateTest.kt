package flow.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app decides about its own version before it downloads anything.
 *
 * Every branch here ends in replacing the program someone is using, so the answer to "is this
 * newer" has to be wrong in the safe direction: a build that does not know what it is, a release
 * that is a prerelease, a tag that says nothing — none of those is an update.
 */
class UpdateTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Trimmed to what the app reads; the real thing carries fifty fields more. */
    private val published = """
        {
          "tag_name": "v1.8.3",
          "name": "v1.8.3",
          "body": "Install or update every module in one press.",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "extensions.json", "browser_download_url": "https://example.test/extensions.json", "size": 8000},
            {"name": "hash-module.jar", "browser_download_url": "https://example.test/hash-module.jar", "size": 4200},
            {"name": "Flow-1.8.3.dmg", "browser_download_url": "https://example.test/Flow-1.8.3.dmg", "size": 84568064}
          ]
        }
    """.trimIndent()

    private fun release(): ReleaseInfo = json.decodeFromString(published)

    @Test
    fun `a published release reads, whatever else it carries`() {
        val release = release()
        assertEquals("v1.8.3", release.tag)
        assertEquals(3, release.assets.size)
        assertFalse(release.prerelease)
        assertTrue(release.body.isNotBlank())
    }

    @Test
    fun `newer, same and older`() {
        assertTrue(isNewer(release(), "1.8.2"))
        assertFalse(isNewer(release(), "1.8.3"))
        assertFalse(isNewer(release(), "1.9.0"))
    }

    /** A build run from Gradle has nothing to compare and nothing to replace. */
    @Test
    fun `a development build is never out of date`() {
        assertFalse(isNewer(release(), UNKNOWN_VERSION))
        assertFalse(isNewer(release(), ""))
    }

    /** `latest` should never be one, but the answer does not depend on that. */
    @Test
    fun `a prerelease is not an update`() {
        val pre = json.decodeFromString<ReleaseInfo>(published.replace("\"prerelease\": false", "\"prerelease\": true"))
        assertFalse(isNewer(pre, "1.0.0"))
    }

    @Test
    fun `a release with no tag says nothing`() {
        assertFalse(isNewer(ReleaseInfo(), "1.0.0"))
    }

    /**
     * The installer is picked by what it is, not by what it is called: the name carries the
     * version, which is the part that is different every single time.
     */
    @Test
    fun `the installer for this platform is the one for this platform`() {
        val release = release()
        assertEquals("Flow-1.8.3.dmg", installerFor(release, "Mac OS X")?.name)
        assertNull(installerFor(release, "Windows 11"), "a .dmg was offered to Windows")
        assertNull(installerFor(release, "Linux"))
    }

    @Test
    fun `a tag is a version with the v taken off`() {
        assertEquals("1.8.3", versionOfTag("v1.8.3"))
        assertEquals("1.8.3", versionOfTag(" v1.8.3 "))
        assertEquals("1.8.3", versionOfTag("1.8.3"))
    }
}
