package flow.model

import kotlinx.serialization.Serializable

/**
 * One extension as a registry advertises it. The registry is a plain JSON file (extensions.json)
 * served over HTTPS, so publishing an module is committing a jar and a line of metadata — no
 * server to run.
 */
@Serializable
data class RegistryEntry(
    val id: String,
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    // which kind this is, and so which list it belongs in. Anything unrecognised is treated as a
    // module, so an older build still offers one a newer registry has categorised in a way it has
    // never heard of — and so the older word, "module", still reads as what it always meant.
    val kind: String = KIND_MODULE,
    // What the module is for, chosen when it is registered. Grouping in the Modules screen is the
    // whole of it — a module works the same whichever category it is in. One this build does not
    // know, or none at all, is listed under "Other" rather than dropped, so a registry that has
    // grown a category since is still fully usable here.
    val category: String = "",
    // where the jar sits, relative to the manifest's own URL, or an absolute https URL
    val file: String = "",
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
)

const val KIND_MODULE = "module"
const val KIND_VIEW = "view"

/**
 * What a module's kind was called before the word was settled.
 *
 * Every manifest published so far says this, and so does every build that reads one — so it is
 * read as "module" rather than as something unknown, and a manifest may say either.
 */
const val KIND_PROCESSOR = "module"

/** Which list an entry belongs in: a view if it says so, a module otherwise. */
fun kindOf(entry: RegistryEntry): String =
    if (entry.kind.lowercase() == KIND_VIEW) KIND_VIEW else KIND_MODULE

// The categories a registry may put an module in. Kept as a list rather than an enum because
// the manifest is shared with builds other than this one: a name from a newer registry has to
// survive being read here, and does — as CATEGORY_OTHER.
const val CATEGORY_CRYPTO = "crypto"
const val CATEGORY_AI = "ai"
// the ones that carry a result out to a person — Slack, Telegram, whatever comes next
const val CATEGORY_MESSAGING = "messaging"
const val CATEGORY_OTHER = "other"

/** The order they are shown in, which is also the order they are listed to whoever registers one. */
val CATEGORIES: List<String> = listOf(CATEGORY_CRYPTO, CATEGORY_AI, CATEGORY_MESSAGING, CATEGORY_OTHER)

/** Which section an entry belongs in: its own category, or Other for none and for one from the future. */
fun categoryOf(entry: RegistryEntry): String =
    entry.category.lowercase().takeIf { it in CATEGORIES } ?: CATEGORY_OTHER

/**
 * The same for something installed, which says what it is itself.
 *
 * An module declares its category, so the palette can group one installed from a file and can
 * group at all without the registry having been fetched. One built before the contract had a
 * category says nothing, and is listed under Other rather than left out.
 */
fun categoryOf(module: ModuleInfo): String =
    module.category.lowercase().takeIf { it in CATEGORIES } ?: CATEGORY_OTHER

/**
 * The entries of each category, in [CATEGORIES] order, leaving out the ones with nothing in them.
 *
 * Order within a category is the order the registry gave, so whoever maintains the manifest decides
 * what comes first rather than it being alphabetised out from under them.
 */
fun byCategory(entries: List<RegistryEntry>): List<Pair<String, List<RegistryEntry>>> {
    val grouped = entries.groupBy(::categoryOf)
    return CATEGORIES.mapNotNull { category ->
        grouped[category]?.takeIf { it.isNotEmpty() }?.let { category to it }
    }
}

/**
 * The installed modules of each category, in [CATEGORIES] order, leaving out the empty ones.
 *
 * The palette's grouping. Order within a category is the order it was given — the workspace sorts
 * installed modules by name — and a category nothing is installed under is left out rather than
 * heading a list of nothing.
 */
fun modulesByCategory(modules: List<ModuleInfo>): List<Pair<String, List<ModuleInfo>>> {
    val grouped = modules.groupBy(::categoryOf)
    return CATEGORIES.mapNotNull { category ->
        grouped[category]?.takeIf { it.isNotEmpty() }?.let { category to it }
    }
}

@Serializable
data class RegistryIndex(
    val extensions: List<RegistryEntry> = emptyList(),
)

/** What the Modules screen shows for one registry entry, given what is installed locally. */
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
