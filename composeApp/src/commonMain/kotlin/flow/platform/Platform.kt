package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo

expect object Platform {
    // ── installed modules/components (each folder isolated by a classloader = sandbox) ──
    fun installedModuleInfos(): List<ModuleInfo>
    fun moduleProcess(id: String, inputs: Map<String, String?>): Map<String, String?>
    fun listInstalledComponents(): List<String>          // id.json under components/<id>/
    fun readInstalledComponent(name: String): String?
    // run a component in its own folder sandbox (bundled dependency modules)
    fun runComponent(id: String, inputs: Map<String, String?>): Map<String, String?>

    // install: an already-existing id is returned in conflicts when overwrite=false (not installed)
    fun installJar(path: String, overwrite: Boolean): InstallResult
    fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult
    fun uninstallModule(id: String)
    fun uninstallComponent(id: String)
    fun pickJar(): String? // JAR file picker dialog

    // flow files in the project folder (*.json)
    fun listFlows(): List<String>
    fun readFlow(name: String): String?
    fun writeFlow(name: String, json: String)
    fun deleteFlow(name: String)
    fun renameFlow(oldName: String, newName: String): Boolean
    fun flowsDirLabel(): String

    // session (open tabs + UI state) restore
    fun loadSession(): String?
    fun saveSession(json: String)

    fun currentTimeHms(): String
}
