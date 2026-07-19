package dataflow

data class ModuleDef(
    val type: String,
    val name: Map<String, String>,
    val cat: String, // source | transform | sink
    val ins: List<String>,
    val outs: List<String>,
    val params: Map<String, String>,
    val plugin: Boolean = false,
)

// 모듈 정의 = 플러그인 레지스트리. 외부 플러그인은 이 목록에 등록되면 리스트에 나타난다.
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

fun findDef(type: String): ModuleDef? = REGISTRY.find { it.type == type }
