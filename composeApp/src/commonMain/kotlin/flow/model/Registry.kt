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

// convenience builders
fun optText(name: String, default: String = "") = OptDef(name, OptType.TEXT, default)
fun optNum(name: String, default: String = "0") = OptDef(name, OptType.NUMBER, default)
fun optSelect(name: String, default: String, vararg choices: String) =
    OptDef(name, OptType.SELECT, default, choices.toList())

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

// Module definitions = the built-in registry.
val REGISTRY = listOf(
    ModuleDef("csv", mapOf("ko" to "CSV 입력", "en" to "CSV Input"), "source", emptyList(), listOf("out"),
        listOf(optText("path", "data.csv"), optSelect("delimiter", ",", ",", ";", "\\t", "|"))),
    ModuleDef("filter", mapOf("ko" to "필터", "en" to "Filter"), "transform", listOf("in"), listOf("pass", "fail"),
        listOf(optText("expr", "value > 0"))),
    ModuleDef("map", mapOf("ko" to "매핑", "en" to "Map"), "transform", listOf("in"), listOf("out"),
        listOf(optText("expr", "x * 2"))),
    ModuleDef("merge", mapOf("ko" to "병합", "en" to "Merge"), "transform", listOf("a", "b"), listOf("out"),
        listOf(optSelect("mode", "inner", "inner", "outer", "left", "right"))),
    ModuleDef("split", mapOf("ko" to "분기", "en" to "Split"), "transform", listOf("in"), listOf("a", "b"),
        listOf(optNum("ratio", "0.5"))),
    ModuleDef("agg", mapOf("ko" to "집계", "en" to "Aggregate"), "transform", listOf("in"), listOf("out"),
        listOf(optSelect("fn", "sum", "sum", "avg", "min", "max", "count"), optText("key", "value"))),
    ModuleDef("log", mapOf("ko" to "로그 출력", "en" to "Log"), "sink", listOf("in"), emptyList(),
        listOf(optSelect("level", "info", "debug", "info", "warn", "error"))),
    ModuleDef("fout", mapOf("ko" to "파일 출력", "en" to "File Output"), "sink", listOf("in"), emptyList(),
        listOf(optText("path", "out.json"))),
    ModuleDef("jflat", mapOf("ko" to "JSON 평탄화", "en" to "JSON Flatten"), "transform", listOf("in"), listOf("out"),
        listOf(optNum("depth", "2"))),
    ModuleDef("regex", mapOf("ko" to "정규식 추출", "en" to "Regex Extract"), "transform", listOf("in"), listOf("out"),
        listOf(optText("pattern", "\\d+"))),
    ModuleDef("timeout", mapOf("ko" to "지연", "en" to "Timeout"), "transform", listOf("in"), listOf("out"),
        listOf(optNum("ms", "1000"))),
)

// Component boundary nodes: cin = component input port, cout = output port. The label is the port name.
val IO_DEFS = listOf(
    ModuleDef("cin", mapOf("ko" to "입력", "en" to "Input"), "io", emptyList(), listOf("out")),
    ModuleDef("cout", mapOf("ko" to "출력", "en" to "Output"), "io", listOf("in"), emptyList()),
)

fun findDef(type: String): ModuleDef? =
    REGISTRY.find { it.type == type } ?: IO_DEFS.find { it.type == type }

// Component instance node type = "comp:<fileName>"
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

// Installed module plugin info (palette / node creation / engine)
data class ModuleInfo(
    val id: String,
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
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
    return CompDef(file, file.removeSuffix(".json"), cin, cout)
}
