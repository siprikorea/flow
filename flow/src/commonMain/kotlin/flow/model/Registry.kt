package flow.model

// The kind of editor a module option uses in the property panel.
enum class OptType { TEXT, NUMBER, SELECT }

// A predefined module option: a named value the user can edit. `default` seeds
// the value on node creation; `choices` lists the allowed values for SELECT.
data class OptDef(
    val name: String,
    val type: OptType = OptType.TEXT,
    val default: String = "",
    val choices: List<String> = emptyList(),
)

data class ModuleDef(
    val type: String,
    val name: Map<String, String>,
    val cat: String, // source | transform | sink | io
    val ins: List<String>,
    val outs: List<String>,
    val options: List<OptDef> = emptyList(),
) {
    // default value map seeded onto a new node's params
    fun defaultParams(): Map<String, String> = options.associate { it.name to it.default }
}

// Built-in modules, beyond the io boundary nodes below, are provided entirely by extensions
// (flow-extensions/*) now — this stays declared (rather than removed) so palette/props code that
// iterates it keeps working unchanged if a true built-in is ever added again.
val REGISTRY = emptyList<ModuleDef>()

// Component boundary nodes: cin = component input port, cout = output port. The label is the port name.
val IO_DEFS = listOf(
    ModuleDef("cin", mapOf("ko" to "입력", "en" to "Input"), "io", emptyList(), listOf("out")),
    ModuleDef("cout", mapOf("ko" to "출력", "en" to "Output"), "io", listOf("in"), emptyList()),
)

fun findDef(type: String): ModuleDef? =
    REGISTRY.find { it.type == type } ?: IO_DEFS.find { it.type == type }

// Component instance node type = "comp:<file>", where file is the flow's project-relative path
fun isComp(type: String) = type.startsWith("comp:")
fun compFile(type: String) = type.removePrefix("comp:")

// Component definition (derived from a flow file): the cin/cout labels are the external ports.
data class CompDef(
    val file: String,
    val name: String,
    val ins: List<String>,
    val outs: List<String>,
    val installed: Boolean = false, // installed copy under components/ (read-only)
)

// Installed module extension info (palette / node creation / engine)
data class ModuleInfo(
    val id: String,
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val options: List<OptDef> = emptyList(),
    val version: String = "1.0.0",
    // what the extension asks to be configured once, rather than on every node — see
    // ProcessorExtension.settings
    val settings: List<OptDef> = emptyList(),
)

// Install result: installed ids and conflicting (already-existing) ids
data class InstallResult(
    val installed: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

// A flow is a component iff it has at least one boundary node
fun FlowFile.asComponent(file: String): CompDef? {
    val cin = nodes.filter { it.type == "cin" }.sortedWith(compareBy({ it.y }, { it.x })).map { it.label }
    val cout = nodes.filter { it.type == "cout" }.sortedWith(compareBy({ it.y }, { it.x })).map { it.label }
    if (cin.isEmpty() && cout.isEmpty()) return null
    // project files are ".flow"; installed components are always ".json" — strip whichever applies
    return CompDef(file, file.substringAfterLast('/').removeSuffix(".flow").removeSuffix(".json"), cin, cout)
}
