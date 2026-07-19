package dataflow.plugin

/**
 * DataFlow Editor 플러그인 계약.
 *
 * 플러그인은 곧 하나 이상의 "컴포넌트"를 제공한다(플러그인 = 컴포넌트 형식).
 * 각 컴포넌트는 입력/출력 경계 노드(cin/cout)를 포함한 플로우 정의(JSON)이며,
 * 앱은 빌드된 JAR 을 ServiceLoader 로 로드해 컴포넌트를 팔레트에 등록한다.
 *
 * 구현 클래스는 인자 없는 생성자를 가지고,
 * `META-INF/services/dataflow.plugin.Plugin` 에 등록되어야 한다.
 */
interface Plugin {
    /** 플러그인 식별자 (예: "com.example.sample") */
    val id: String

    /** 이 플러그인이 제공하는 컴포넌트 목록 */
    fun components(): List<PluginComponent>
}

/**
 * 플러그인이 제공하는 컴포넌트.
 *
 * @param fileName 프로젝트 폴더에 저장될 파일명(반드시 순수 파일명, ".json" 으로 끝남)
 * @param flowJson FlowFile JSON. cin/cout 경계 노드를 포함해야 컴포넌트로 인식된다.
 */
data class PluginComponent(
    val fileName: String,
    val flowJson: String,
)
