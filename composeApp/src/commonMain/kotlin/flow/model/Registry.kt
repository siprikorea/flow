package flow.model

data class ModuleDef(
    val type: String,
    val name: Map<String, String>,
    val cat: String, // source | transform | sink | io
    val ins: List<String>,
    val outs: List<String>,
    val params: Map<String, String>,
    val plugin: Boolean = false,
)

// Module definitions = the built-in registry.
val REGISTRY = listOf(
    ModuleDef("csv", mapOf("ko" to "CSV 입력", "en" to "CSV Input"), "source", emptyList(), listOf("out"), mapOf("path" to "data.csv", "delimiter" to ",")),
    ModuleDef("filter", mapOf("ko" to "필터", "en" to "Filter"), "transform", listOf("in"), listOf("pass", "fail"), mapOf("expr" to "value > 0")),
    ModuleDef("map", mapOf("ko" to "매핑", "en" to "Map"), "transform", listOf("in"), listOf("out"), mapOf("expr" to "x * 2")),
    ModuleDef("merge", mapOf("ko" to "병합", "en" to "Merge"), "transform", listOf("a", "b"), listOf("out"), mapOf("mode" to "inner")),
    ModuleDef("split", mapOf("ko" to "분기", "en" to "Split"), "transform", listOf("in"), listOf("a", "b"), mapOf("ratio" to "0.5")),
    ModuleDef("agg", mapOf("ko" to "집계", "en" to "Aggregate"), "transform", listOf("in"), listOf("out"), mapOf("fn" to "sum", "key" to "value")),
    ModuleDef("log", mapOf("ko" to "로그 출력", "en" to "Log"), "sink", listOf("in"), emptyList(), mapOf("level" to "info")),
    ModuleDef("fout", mapOf("ko" to "파일 출력", "en" to "File Output"), "sink", listOf("in"), emptyList(), mapOf("path" to "out.json")),
    ModuleDef("jflat", mapOf("ko" to "JSON 평탄화", "en" to "JSON Flatten"), "transform", listOf("in"), listOf("out"), mapOf("depth" to "2"), plugin = true),
    ModuleDef("regex", mapOf("ko" to "정규식 추출", "en" to "Regex Extract"), "transform", listOf("in"), listOf("out"), mapOf("pattern" to "\\d+"), plugin = true),
)

// Component boundary nodes: cin = component input port, cout = output port. The label is the port name.
val IO_DEFS = listOf(
    ModuleDef("cin", mapOf("ko" to "입력", "en" to "Input"), "io", emptyList(), listOf("out"), emptyMap()),
    ModuleDef("cout", mapOf("ko" to "출력", "en" to "Output"), "io", listOf("in"), emptyList(), emptyMap()),
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
