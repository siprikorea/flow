package dataflow.platform

import dataflow.model.InstallResult
import dataflow.model.ModuleInfo

expect object Platform {
    // ── 설치된 모듈/컴포넌트 ──
    fun installedModuleInfos(): List<ModuleInfo>
    fun moduleProcess(id: String, inputs: Map<String, String?>): Map<String, String?>
    fun listInstalledComponents(): List<String>          // components/ 의 *.json
    fun readInstalledComponent(name: String): String?

    // 설치: 이미 존재하는 id 는 overwrite=false 면 conflicts 로 반환(설치 안 함)
    fun installJar(path: String, overwrite: Boolean): InstallResult
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult

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
