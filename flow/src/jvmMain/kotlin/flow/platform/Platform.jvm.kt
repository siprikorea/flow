package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual object Platform {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    private val flowsDir = File(baseDir, "flows")
    private val sessionFile = File(baseDir, "session.json")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        // 기존 ~/.dataflow-editor 데이터를 ~/.flow 로 1회 이전(복사)
        runCatching {
            if (!baseDir.exists()) {
                val old = File(System.getProperty("user.home"), ".dataflow-editor")
                if (old.isDirectory) old.copyRecursively(baseDir, overwrite = false)
            }
        }
    }

    private fun ensureDirs() {
        flowsDir.mkdirs()
    }

    // ── installed modules/components (delegated to ExtensionLoader) ──
    actual fun installedModuleInfos(): List<ModuleInfo> = ExtensionLoader.moduleInfos()
    actual fun moduleProcess(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        ExtensionLoader.process(id, inputs, options)
    actual fun listInstalledComponents(): List<String> = ExtensionLoader.listComponents()
    actual fun readInstalledComponent(name: String): String? = ExtensionLoader.readComponent(name)
    actual fun runComponent(id: String, inputs: Map<String, ByteArray?>): Map<String, ByteArray?> = ExtensionLoader.runComponent(id, inputs)
    actual fun installJar(path: String, overwrite: Boolean): InstallResult = ExtensionLoader.installJar(path, overwrite)
    actual fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult = ExtensionLoader.installComponent(id, flowJson, overwrite)
    actual fun uninstallModule(id: String) = ExtensionLoader.uninstallModule(id)
    actual fun uninstallComponent(id: String) = ExtensionLoader.uninstallComponent(id)

    actual fun pickJar(): String? {
        val dlg = FileDialog(null as Frame?, "Install Plugin (JAR)", FileDialog.LOAD)
        dlg.setFilenameFilter { _, name -> name.endsWith(".jar") }
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name).absolutePath
    }

    // 플로우 파일명 검증: 순수 파일명(.flow)만 허용하고 flowsDir 하위로 한정.
    // 경로 분리자·상위 이동(..)·심볼릭 링크를 통한 디렉터리 탈출을 차단한다.
    private fun resolveFlow(name: String): File? {
        if (name.isEmpty() || !name.endsWith(".flow")) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        return runCatching {
            val file = File(flowsDir, name)
            val base = flowsDir.canonicalFile
            if (file.canonicalFile.parentFile == base) file else null
        }.getOrNull()
    }

    actual fun listFlows(): List<String> {
        ensureDirs()
        return flowsDir.listFiles { f -> f.isFile && f.name.endsWith(".flow") }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    actual fun readFlow(name: String): String? {
        val file = resolveFlow(name) ?: return null
        return runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    actual fun writeFlow(name: String, json: String) {
        val file = resolveFlow(name) ?: return
        runCatching {
            ensureDirs()
            file.writeText(json)
        }
    }

    actual fun deleteFlow(name: String) {
        val file = resolveFlow(name) ?: return
        runCatching { file.delete() }
    }

    actual fun renameFlow(oldName: String, newName: String): Boolean {
        val o = resolveFlow(oldName) ?: return false
        val n = resolveFlow(newName) ?: return false
        if (!o.exists() || n.exists()) return false
        return runCatching { o.renameTo(n) }.getOrDefault(false)
    }

    actual fun flowsDirLabel(): String = flowsDir.absolutePath

    // ── arbitrary file access for the data editor ──
    actual fun pickFileRead(): String? {
        val dlg = FileDialog(null as Frame?, "Open File", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name).absolutePath
    }

    actual fun pickFileSave(defaultName: String): String? {
        val dlg = FileDialog(null as Frame?, "Save Output", FileDialog.SAVE)
        dlg.file = defaultName
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name).absolutePath
    }

    actual fun fileSize(path: String): Long =
        runCatching { File(path).takeIf { it.isFile }?.length() ?: -1L }.getOrDefault(-1L)

    actual fun readFileRange(path: String, offset: Long, length: Int): ByteArray = runCatching {
        java.io.RandomAccessFile(path, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            raf.seek(offset)
            val n = minOf(length.toLong(), raf.length() - offset).toInt().coerceAtLeast(0)
            val buf = ByteArray(n)
            raf.readFully(buf)
            buf
        }
    }.getOrDefault(ByteArray(0))

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean =
        runCatching { File(path).writeBytes(bytes); true }.getOrDefault(false)

    actual fun appendBytes(path: String, bytes: ByteArray): Boolean =
        runCatching { java.io.FileOutputStream(path, true).use { it.write(bytes) } }.isSuccess

    actual fun fileName(path: String): String = File(path).name

    // scratch space for data the editor spills out of memory (e.g. an oversized paste) — an
    // app-owned dir rather than the shared OS temp dir, so it's easy to find and doesn't compete
    // with unrelated cleanup policies; each file is marked deleteOnExit as a backstop.
    private val tmpDir = File(baseDir, "tmp")
    actual fun createTempFile(prefix: String): String {
        tmpDir.mkdirs()
        val f = File.createTempFile(prefix, ".bin", tmpDir)
        f.deleteOnExit()
        return f.absolutePath
    }

    // Reads the clipboard's text via a java.io.Reader (java.awt.datatransfer.DataFlavor's own
    // text-reader support), so the content is pulled through in bounded chunks rather than
    // materialized as one String up front — the point for a very large clipboard paste.
    actual fun pasteClipboardChunks(maxChunkChars: Int, onChunk: (String) -> Unit): Boolean {
        val contents = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull() ?: return false
        if (!contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) return false
        return runCatching {
            java.awt.datatransfer.DataFlavor.stringFlavor.getReaderForText(contents).use { reader ->
                val buf = CharArray(maxChunkChars)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (n > 0) onChunk(String(buf, 0, n))
                }
            }
        }.isSuccess
    }

    actual fun loadSession(): String? =
        runCatching { sessionFile.takeIf { it.exists() }?.readText() }.getOrNull()

    actual fun saveSession(json: String) {
        runCatching {
            baseDir.mkdirs()
            sessionFile.writeText(json)
        }
    }

    actual fun currentTimeHms(): String = LocalTime.now().format(hms)
}
