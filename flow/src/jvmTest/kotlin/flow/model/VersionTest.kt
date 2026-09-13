package flow.model

import kotlin.test.Test
import kotlin.test.assertTrue

/** Version comparison decides whether the Modules page offers an update, so its edges matter. */
class VersionTest {
    private fun newer(a: String, b: String) = compareVersions(a, b) > 0
    private fun same(a: String, b: String) = compareVersions(a, b) == 0

    @Test fun `a higher segment is newer`() = assertTrue(newer("1.0.1", "1.0.0"))

    @Test
    fun `segments compare as numbers, not as text`() {
        // the classic trap: "1.10.0" sorts before "1.2.0" as a string
        assertTrue(newer("1.10.0", "1.2.0"))
    }

    @Test fun `a shorter version is not older for being shorter`() {
        // 1.0 and 1.0.0 are the same release; treating the missing segment as less than zero
        // offered an update for ever
        assertTrue(same("1.0", "1.0.0"))
        assertTrue(newer("2.0", "1.9.9"))
    }

    @Test fun `a pre-release does not outrank the release`() = assertTrue(newer("1.0.0", "1.0.0-beta"))
}
