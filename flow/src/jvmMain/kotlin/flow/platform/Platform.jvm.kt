package flow.platform

import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual object Platform {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    private val flowsDir = File(baseDir, "flows")
    private val sessionFile = File(baseDir, "session.json")
    private val settingsFile = File(baseDir, "settings.json")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    // 순회 최대 깊이 (심볼릭 링크 순환 방지)
    private const val MAX_TREE_DEPTH = 12

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
    actual fun moduleInputsFor(id: String, options: Map<String, String>): List<String>? = ExtensionLoader.inputsFor(id, options)
    actual fun moduleOutputsFor(id: String, options: Map<String, String>): List<String>? = ExtensionLoader.outputsFor(id, options)
    actual fun moduleOptionsFor(id: String, values: Map<String, String>): List<OptDef>? = ExtensionLoader.optionsFor(id, values)
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

    actual fun pickFlowFile(): String? {
        val dlg = FileDialog(null as Frame?, "Open Flow File", FileDialog.LOAD)
        dlg.setFilenameFilter { _, name -> name.endsWith(".flow") }
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name).absolutePath
    }

    actual fun readExternalFlow(path: String): String? {
        if (!path.endsWith(".flow")) return null
        return runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    // flows 루트 기준 상대 경로('/' 구분)만 허용: 빈 세그먼트·상위 이동·심볼릭 링크 탈출 차단
    private fun resolveRel(rel: String, requireFlow: Boolean): File? {
        if (rel.isEmpty() || rel.contains('\\')) return null
        if (requireFlow && !rel.endsWith(".flow")) return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return runCatching {
            val file = File(flowsDir, rel)
            val base = flowsDir.canonicalFile.path + File.separator
            if (file.canonicalFile.path.startsWith(base)) file else null
        }.getOrNull()
    }

    private fun relPath(file: File): String =
        file.toRelativeString(flowsDir).replace(File.separatorChar, '/')

    // 깊이 제한 트리 순회, 숨김(.) 항목 제외
    private fun walk(): Sequence<File> {
        ensureDirs()
        return flowsDir.walkTopDown().maxDepth(MAX_TREE_DEPTH)
            .onEnter { !it.name.startsWith(".") }
            .filter { it != flowsDir && !it.name.startsWith(".") }
    }

    actual fun listFlows(): List<String> =
        walk().filter { it.isFile && it.name.endsWith(".flow") }.map { relPath(it) }.sorted().toList()

    actual fun listProjectFiles(): List<String> =
        walk().filter { it.isFile }.map { relPath(it) }.sorted().toList()

    actual fun listFlowDirs(): List<String> =
        walk().filter { it.isDirectory }.map { relPath(it) }.sorted().toList()

    actual fun readFlow(name: String): String? {
        val file = resolveRel(name, requireFlow = true) ?: return null
        return runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    actual fun writeFlow(name: String, json: String) {
        val file = resolveRel(name, requireFlow = true) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
    }

    actual fun createFlowDir(path: String): Boolean {
        val dir = resolveRel(path, requireFlow = false) ?: return false
        if (dir.exists()) return false
        return runCatching { dir.mkdirs() }.getOrDefault(false)
    }

    actual fun deleteFlowPath(path: String) {
        val file = resolveRel(path, requireFlow = false) ?: return
        runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
    }

    actual fun renameFlowPath(oldPath: String, newPath: String): Boolean {
        val o = resolveRel(oldPath, requireFlow = false) ?: return false
        val n = resolveRel(newPath, requireFlow = false) ?: return false
        if (!o.exists() || n.exists()) return false
        return runCatching { n.parentFile?.mkdirs(); o.renameTo(n) }.getOrDefault(false)
    }

    actual fun flowsDirLabel(): String = flowsDir.absolutePath
    actual fun flowsDirName(): String = flowsDir.name

    // ── "Open In" ──
    // The project root resolves to the flows dir itself; anything else goes through resolveRel, so
    // a crafted path still cannot point the file manager outside the project.
    private fun openTarget(rel: String): File? =
        if (rel.isEmpty()) flowsDir else resolveRel(rel, requireFlow = false)?.takeIf { it.exists() }

    private fun run(vararg command: String) {
        runCatching { ProcessBuilder(*command).start() }
    }

    actual fun revealInFileManager(rel: String) {
        val file = openTarget(rel) ?: return
        when {
            isMac -> run("open", "-R", file.absolutePath)
            isWindows -> run("explorer.exe", "/select,${file.absolutePath}")
            // no universal "reveal" on Linux, so open the containing folder
            else -> run("xdg-open", (if (file.isDirectory) file else file.parentFile).absolutePath)
        }
    }

    actual fun openInTerminal(rel: String) {
        val file = openTarget(rel) ?: return
        val dir = (if (file.isDirectory) file else file.parentFile) ?: return
        when {
            isMac -> run("open", "-a", "Terminal", dir.absolutePath)
            isWindows -> run("cmd.exe", "/c", "start", "cmd.exe", "/K", "cd /d ${dir.absolutePath}")
            // the Debian alternative most desktops register; nothing to fall back to if absent
            else -> run("x-terminal-emulator", "--working-directory=${dir.absolutePath}")
        }
    }

    actual fun fileManagerName(): String = when {
        isMac -> "Finder"
        isWindows -> "Explorer"
        else -> "Files"
    }

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

    actual fun loadSettings(): String? =
        runCatching { settingsFile.takeIf { it.exists() }?.readText() }.getOrNull()

    actual fun saveSettings(json: String) {
        runCatching {
            baseDir.mkdirs()
            settingsFile.writeText(json)
        }
    }

    actual fun currentTimeHms(): String = LocalTime.now().format(hms)

    private val osName = System.getProperty("os.name").lowercase()
    private val isMac = osName.contains("mac")
    private val isWindows = osName.contains("win")
    actual fun metaKeyLabel(): String = if (isMac) "\u2318" else "Win+"
}
