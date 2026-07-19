package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo

expect object Platform {
    // ── 설치된 모듈/컴포넌트 (각 폴더 격리 클래스로더 = 샌드박스) ──
    fun installedModuleInfos(): List<ModuleInfo>
    fun moduleProcess(id: String, inputs: Map<String, String?>): Map<String, String?>
    fun listInstalledComponents(): List<String>          // components/<id>/ 의 id.json
    fun readInstalledComponent(name: String): String?
    // 컴포넌트를 자신의 폴더 샌드박스(번들된 의존 모듈)로 실행
    fun runComponent(id: String, inputs: Map<String, String?>): Map<String, String?>

    // 설치: 이미 존재하는 id 는 overwrite=false 면 conflicts 로 반환(설치 안 함)
    fun installJar(path: String, overwrite: Boolean): InstallResult
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult
    fun pickJar(): String? // JAR 파일 선택 다이얼로그

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
