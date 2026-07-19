package dataflow.platform

expect object Platform {
    // 플러그인(JAR) 로드 — 제공된 컴포넌트를 프로젝트 폴더에 반영 (없을 때만)
    fun loadPlugins()

    // 프로젝트 폴더의 플로우 파일(*.json)
    fun listFlows(): List<String>
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)
    fun deleteFlow(name: String)
    fun flowsDirLabel(): String

    // 세션(열린 탭 + UI 상태) 복원
    fun loadSession(): String?
    fun saveSession(json: String)

    fun currentTimeHms(): String
}
