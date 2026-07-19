package dataflow

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual object Platform {
    private val stateFile = File(System.getProperty("user.home"), ".dataflow-editor/flow.json")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    actual fun loadState(): String? =
        runCatching { stateFile.takeIf { it.exists() }?.readText() }.getOrNull()

    actual fun saveState(json: String) {
        runCatching {
            stateFile.parentFile.mkdirs()
            stateFile.writeText(json)
        }
    }

    actual fun exportJson(json: String) {
        val dlg = FileDialog(null as Frame?, "Export JSON", FileDialog.SAVE)
        dlg.file = "dataflow.json"
        dlg.isVisible = true
        val dir = dlg.directory ?: return
        val name = dlg.file ?: return
        runCatching { File(dir, name).writeText(json) }
    }

    actual fun importJson(onLoaded: (String) -> Unit) {
        val dlg = FileDialog(null as Frame?, "Import JSON", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory ?: return
        val name = dlg.file ?: return
        runCatching { File(dir, name).readText() }.getOrNull()?.let(onLoaded)
    }

    actual fun currentTimeHms(): String = LocalTime.now().format(hms)
}
