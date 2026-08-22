package flow.model

import kotlinx.serialization.Serializable

/**
 * One extension as a registry advertises it. The registry is a plain JSON file (extensions.json)
 * served over HTTPS, so publishing an extension is committing a jar and a line of metadata — no
 * server to run.
 */
@Serializable
data class RegistryEntry(
    val id: String,
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    // where the jar sits, relative to the manifest's own URL, or an absolute https URL
    val file: String = "",
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
)

@Serializable
data class RegistryIndex(
    val extensions: List<RegistryEntry> = emptyList(),
)

/** What the Extensions screen shows for one registry entry, given what is installed locally. */
enum class RegistryState { AVAILABLE, INSTALLED, UPDATABLE }

/**
 * Compare dot-separated numeric versions.
 *
 * A segment that is simply not there counts as 0, so "1.0" and "1.0.0" are the same version and
 * neither offers the other an update. A segment that is there but is not a number counts as less
 * than 0, so "1.0.0-beta" stays behind "1.0.0" instead of jumping ahead of it.
 */
fun compareVersions(a: String, b: String): Int {
    val x = a.trim().split('.')
    val y = b.trim().split('.')
    fun segment(parts: List<String>, i: Int): Int =
        parts.getOrNull(i)?.let { it.toIntOrNull() ?: -1 } ?: 0
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = segment(x, i)
        val q = segment(y, i)
        if (p != q) return p.compareTo(q)
    }
    return 0
}
