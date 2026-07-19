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

// 모듈 정의 = 내장 레지스트리.
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

// 컴포넌트 경계 노드: cin = 컴포넌트의 입력 포트, cout = 출력 포트. label 이 곧 포트 이름.
val IO_DEFS = listOf(
    ModuleDef("cin", mapOf("ko" to "입력", "en" to "Input"), "io", emptyList(), listOf("out"), emptyMap()),
    ModuleDef("cout", mapOf("ko" to "출력", "en" to "Output"), "io", listOf("in"), emptyList(), emptyMap()),
)

fun findDef(type: String): ModuleDef? =
    REGISTRY.find { it.type == type } ?: IO_DEFS.find { it.type == type }

// 컴포넌트 인스턴스 노드 타입 = "comp:<파일명>"
fun isComp(type: String) = type.startsWith("comp:")
fun compFile(type: String) = type.removePrefix("comp:")

// 컴포넌트 정의(다른 플로우 파일에서 파생): 경계 노드(cin/cout)의 라벨이 곧 외부 포트.
data class CompDef(
    val file: String,
    val name: String,
    val ins: List<String>,
    val outs: List<String>,
    val installed: Boolean = false, // components/ 폴더의 설치본(읽기 전용)
)

// 설치된 모듈 플러그인 정보 (팔레트/노드 생성/엔진용)
data class ModuleInfo(
    val id: String,
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
)

// 설치 결과: 설치된 id 와 충돌(이미 존재) id
data class InstallResult(
    val installed: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

// 플로우가 컴포넌트인지 = 경계 노드를 하나라도 가지는가
fun FlowFile.asComponent(file: String): CompDef? {
    val cin = nodes.filter { it.type == "cin" }.sortedWith(compareBy({ it.y }, { it.x })).map { it.label }
    val cout = nodes.filter { it.type == "cout" }.sortedWith(compareBy({ it.y }, { it.x })).map { it.label }
    if (cin.isEmpty() && cout.isEmpty()) return null
    return CompDef(file, file.removeSuffix(".json"), cin, cout)
}
