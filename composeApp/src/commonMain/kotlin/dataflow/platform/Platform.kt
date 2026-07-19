package dataflow.platform

expect object Platform {
    // 프로젝트 폴더의 플로우 파일(*.json)
    fun listFlows(): List<String>
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)
    fun deleteFlow(name: String)
    fun flowsDirLabel(): String

    // 세션(열린 탭 + UI 상태) 복원
    fun loadSession(): String?
    fun saveSession(json: String)

    // 파일 다이얼로그 기반 내보내기/불러오기
    fun exportJson(json: String)
    fun importJson(onLoaded: (String) -> Unit)

    fun currentTimeHms(): String
}
