package flow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A release of the app itself, as the place it is published describes one.
 *
 * The same release the modules come from: the .dmg is an asset on it, beside the module jars and
 * the manifest. So "is there a new Flow" is the same question, asked of the same tag, and the app
 * needs no second place to look.
 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    // what the release says about itself; shown as-is, so whoever writes the tag writes this
    val body: String = "",
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

/** Where the app's own releases are listed. The newest tag, never a nightly. */
const val DEFAULT_UPDATE_URL = "https://api.github.com/repos/siprikorea/flow/releases/latest"

/** The version a tag names: `v1.8.3` is `1.8.3`, and anything else is taken as it stands. */
fun versionOfTag(tag: String): String = tag.trim().removePrefix("v").trim()

/**
 * Whether [release] is newer than the [current] build.
 *
 * A build that does not know its own version — one run from Gradle — is never out of date: there
 * is nothing to replace it with, and offering to would replace a developer's own build with a
 * download. A prerelease is not an update either; `latest` should never be one, but the answer
 * does not depend on the server behaving.
 */
fun isNewer(release: ReleaseInfo, current: String): Boolean {
    if (release.prerelease) return false
    val theirs = versionOfTag(release.tag)
    if (theirs.isBlank() || current.isBlank() || current == UNKNOWN_VERSION) return false
    return compareVersions(theirs, current) > 0
}

/** What a build calls itself when it was not packaged — a run from Gradle, or a test. */
const val UNKNOWN_VERSION = "dev"

/**
 * The asset to download for this platform, or null when the release carries none.
 *
 * By extension rather than by name: the file is named after the version it ships, which is exactly
 * the part that changes every time.
 */
fun installerFor(release: ReleaseInfo, os: String): ReleaseAsset? {
    val suffix = when {
        os.contains("mac", ignoreCase = true) -> ".dmg"
        os.contains("win", ignoreCase = true) -> ".msi"
        else -> ".deb"
    }
    return release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
}
