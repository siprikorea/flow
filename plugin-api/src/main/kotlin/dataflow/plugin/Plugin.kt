package dataflow.plugin

/**
 * DataFlow 플러그인 공통 계약.
 * 플러그인은 모듈 플러그인([ModulePlugin]) 또는 컴포넌트 플러그인([ComponentPlugin]) 으로 나뉜다.
 * 구현 클래스는 인자 없는 생성자를 가지고 `META-INF/services/` 에 각 인터페이스로 등록되어야 한다.
 */
interface DataflowPlugin {
    /** 식별자. 패키지명 형식이어야 한다(예: "com.example.double"). */
    val id: String

    /** 팔레트에 표시될 이름 */
    val displayName: String

    /** 입력 포트 id 목록 (필수) */
    val inputs: List<String>

    /** 출력 포트 id 목록 (필수) */
    val outputs: List<String>
}

/**
 * 모듈 플러그인: 실제 입출력 처리 구현부를 가진다.
 * 입력 포트 id → 값 을 받아 출력 포트 id → 값 을 반환한다.
 */
interface ModulePlugin : DataflowPlugin {
    fun process(inputs: Map<String, String?>): Map<String, String?>
}

/**
 * 컴포넌트 플러그인: 모듈 간 "연결"만 정의한다(크기/좌표 없음).
 * 좌표가 없으므로 설치 시 연결 순서대로 자동 배치된다.
 */
interface ComponentPlugin : DataflowPlugin {
    /** 내부 모듈 노드 (cin/cout 경계는 inputs/outputs 로부터 자동 생성) */
    fun nodes(): List<PluginNode>

    /** 내부 노드 간 연결 */
    fun connections(): List<PluginConn>

    /** 외부 입력 id → "노드id.포트" (컴포넌트 입력이 어느 내부 포트로 들어가는지) */
    fun inputBindings(): Map<String, String>

    /** 외부 출력 id → "노드id.포트" (컴포넌트 출력이 어느 내부 포트에서 나오는지) */
    fun outputBindings(): Map<String, String>
}

/** 컴포넌트 내부 노드 정의 (좌표 없음) */
data class PluginNode(
    val id: String,
    val type: String, // 내장 모듈 타입 또는 설치된 모듈 id
    val inputs: List<String>,
    val outputs: List<String>,
    val params: Map<String, String> = emptyMap(),
)

/** 컴포넌트 내부 연결 */
data class PluginConn(
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
)
