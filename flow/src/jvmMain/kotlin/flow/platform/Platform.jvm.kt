package flow.platform

import flow.model.AI_VIA_CLI
import flow.model.AiReply
import flow.model.AiSetup
import flow.model.InstallResult
import flow.model.ModuleInfo
import flow.model.OptDef
import flow.model.ViewInfo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object Platform {
    private val baseDir = File(System.getProperty("user.home"), ".flow")
    // No folder is open until one is chosen, so this starts null and every path operation below
    // reads as empty. ~/.flow keeps only what belongs to the app itself — installed modules,
    // the session and the settings — under the user's home on every platform.
    @Volatile
    private var projectDir: File? = null
    private val sessionFile = File(baseDir, "session.json")
    private val settingsFile = File(baseDir, "settings.json")
    private val openRequestFile = File(baseDir, "open-request.txt")
    private val inputRequestFile = File(baseDir, "input-request.json")
    private val runRequestFile = File(baseDir, "run-request.json")
    private val pidFile = File(baseDir, "app.pid")
    private val requestJson = Json { ignoreUnknownKeys = true }
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

    actual fun projectRoot(): String? = projectDir?.absolutePath
    actual fun projectName(): String? = projectDir?.name

    actual fun openProject(path: String?) {
        projectDir = path?.let { File(it) }?.takeIf { it.isDirectory }
    }

    actual fun pickFolder(): String? {
        // AWT's FileDialog opens folders only with this flag set, and it has to be put back or
        // every later file dialog would select directories too
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        if (isMac) System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dlg = FileDialog(null as Frame?, "Open Folder", FileDialog.LOAD)
            dlg.isVisible = true
            val dir = dlg.directory ?: return null
            val name = dlg.file ?: return null
            val picked = File(dir, name)
            return if (picked.isDirectory) picked.absolutePath else picked.parentFile?.absolutePath
        } finally {
            if (isMac) {
                if (previous == null) System.clearProperty("apple.awt.fileDialogForDirectories")
                else System.setProperty("apple.awt.fileDialogForDirectories", previous)
            }
        }
    }

    actual fun writeExternalFlow(path: String, json: String): Boolean {
        if (!path.endsWith(".flow")) return false
        return runCatching { File(path).writeText(json); true }.getOrDefault(false)
    }

    // ── installed modules/components (delegated to ModuleLoader) ──
    actual fun installedModuleInfos(): List<ModuleInfo> = ModuleLoader.moduleInfos()
    // runInterruptible so cancelling the run interrupts the thread the module is on. That reaches
    // a module parked in sleep, wait or interruptible IO — most of what a module blocks on —
    // though nothing can reach one spinning in a loop that never checks.
    actual suspend fun moduleProcess(id: String, inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> =
        runInterruptible(Dispatchers.Default) { ModuleLoader.process(id, inputs, options) }
    actual fun moduleInputsFor(id: String, options: Map<String, String>): List<String>? = ModuleLoader.inputsFor(id, options)
    actual fun moduleOutputsFor(id: String, options: Map<String, String>): List<String>? = ModuleLoader.outputsFor(id, options)
    actual fun moduleOptionsFor(id: String, values: Map<String, String>): List<OptDef>? = ModuleLoader.optionsFor(id, values)
    actual fun installedViewInfos(): List<ViewInfo> = ModuleLoader.viewInfos()
    actual suspend fun openView(id: String, data: ByteArray, options: Map<String, String>) =
        runInterruptible(Dispatchers.Default) { ModuleLoader.openView(id, data, options) }
    actual suspend fun focusView(id: String) =
        runInterruptible(Dispatchers.Default) { ModuleLoader.focusView(id) }
    actual fun cliPath(provider: String): String? = flow.ai.CliAgent.path(provider)

    actual suspend fun askAi(prompt: String, sessionId: String?, ai: AiSetup, onText: (String) -> Unit): AiReply =
        runInterruptible(Dispatchers.IO) {
            // The two get the same system prompt and the same tools; what differs is who runs the
            // agent loop around them — Claude Code does it in its own process, Ollama.ask does it
            // here, because a model on its own does not.
            // The same assistant is reachable two ways and the setting says which: as its own
            // command, already signed in and running the agent loop itself, or as a model this
            // process drives (see HttpAgent). The system prompt and the tools are the same either
            // way — what differs is who is holding the conversation.
            val systemPrompt = flow.ai.FlowPrompt.systemPrompt(projectRoot())
            if (ai.transport == AI_VIA_CLI && flow.ai.CliAgent.handles(ai.provider)) {
                flow.ai.CliAgent.ask(
                    provider = ai.provider,
                    prompt = prompt,
                    sessionId = sessionId,
                    model = ai.model,
                    workingDir = projectDir,
                    projectRoot = projectRoot(),
                    systemPrompt = systemPrompt,
                    onText = onText,
                )
            } else {
                flow.ai.Agents.ask(
                    prompt = prompt,
                    sessionId = sessionId,
                    setup = ai,
                    systemPrompt = systemPrompt,
                    onText = onText,
                )
            }
        }

    // All of them, without asking which is running: only one can be, and stopping the rest is a
    // no-op.
    actual fun stopAi() {
        flow.ai.CliAgent.stop()
        flow.ai.Agents.stop()
    }

    actual fun forgetAi(sessionId: String?) = flow.ai.Agents.forget(sessionId)

    actual suspend fun aiModels(ai: AiSetup): List<String> =
        runInterruptible(Dispatchers.IO) { flow.ai.Agents.models(ai) }

    /**
     * API keys, in ~/.flow/credentials.json, readable only by the user who owns it.
     *
     * Apart from settings.json because these are not preferences: that file is rewritten whenever
     * anything in Settings changes, is meant to be looked at, and gets copied around with the rest
     * of a home directory. The permissions are set on the file itself rather than trusted to the
     * umask, and set before anything is written into it.
     */
    actual fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val secretsFile = File(baseDir, "credentials.json")

    actual fun loadSecret(name: String): String? = runCatching {
        if (!secretsFile.isFile) return null
        val root = Json.parseToJsonElement(secretsFile.readText()) as? JsonObject ?: return null
        (root[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    actual fun saveSecret(name: String, value: String) {
        runCatching {
            baseDir.mkdirs()
            val root = (
                runCatching { Json.parseToJsonElement(secretsFile.readText()) as? JsonObject }
                    .getOrNull() ?: JsonObject(emptyMap())
                ).toMutableMap()
            if (value.isBlank()) root.remove(name) else root[name] = JsonPrimitive(value)
            if (!secretsFile.exists()) secretsFile.createNewFile()
            ownerOnly(secretsFile)
            secretsFile.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(root)))
        }
    }

    /**
     * Owner read/write, nothing else.
     *
     * POSIX only. On a filesystem without it (Windows) this does nothing and the file is left as
     * the user's home directory made it — File.setReadable would report success there while
     * changing no ACL, which is worse than plainly not applying.
     */
    private fun ownerOnly(file: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    actual fun encodeText(text: String, charset: String): ByteArray =
        text.toByteArray(charsetOf(charset))
    actual fun decodeText(bytes: ByteArray, charset: String): String =
        String(bytes, charsetOf(charset))
    actual fun charsetNames(): List<String> = CHARSETS

    // an encoding this build does not know falls back rather than failing: the value is still bytes
    private fun charsetOf(name: String) =
        runCatching { java.nio.charset.Charset.forName(name) }
            .getOrDefault(java.nio.charset.StandardCharsets.UTF_8)

    private val CHARSETS = listOf("UTF-8", "US-ASCII", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE", "EUC-KR")

    actual fun setExtensionSettings(values: Map<String, Map<String, String>>) =
        ModuleLoader.setExtensionSettings(values)

    actual suspend fun extensionSettingsFor(id: String, values: Map<String, String>): List<OptDef> =
        runInterruptible(Dispatchers.IO) { ModuleLoader.settingsFor(id, values).orEmpty() }

    actual fun listInstalledComponents(): List<String> = ModuleLoader.listComponents()
    actual fun readInstalledComponent(name: String): String? = ModuleLoader.readComponent(name)
    actual fun runComponent(id: String, inputs: Map<String, ByteArray?>): Map<String, ByteArray?> = ModuleLoader.runComponent(id, inputs)
    actual fun installJar(path: String, overwrite: Boolean): InstallResult = ModuleLoader.installJar(path, overwrite)
    actual fun installComponent(id: String, flowJson: String, overwrite: Boolean): InstallResult = ModuleLoader.installComponent(id, flowJson, overwrite)
    actual fun uninstallModule(id: String) = ModuleLoader.uninstallModule(id)
    actual fun uninstallComponent(id: String) = ModuleLoader.uninstallComponent(id)

    actual fun pickJar(): String? {
        val dlg = FileDialog(null as Frame?, "Install Flow Module", FileDialog.LOAD)
        // the older suffix and a plain .jar too: a module built elsewhere may carry either
        dlg.setFilenameFilter { _, name ->
            name.endsWith(ModuleLoader.MODULE_SUFFIX) || name.endsWith(".flowext") || name.endsWith(".jar")
        }
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name).absolutePath
    }

    // ── module registry ──
    // java.net.http is in the JDK, so fetching costs the MCP/CLI bundle no extra jar.
    private val http: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()
    }

    private fun httpsOnly(url: String): java.net.URI? =
        runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.scheme == "https" }

    actual fun fetchText(url: String): String? {
        val uri = httpsOnly(url) ?: return null
        return runCatching {
            val req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(20)).GET().build()
            val res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() in 200..299) res.body() else null
        }.getOrNull()
    }

    actual fun installFromUrl(url: String, overwrite: Boolean): InstallResult {
        val uri = httpsOnly(url) ?: return InstallResult()
        return runCatching {
            val req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(60)).GET().build()
            val res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() !in 200..299) return InstallResult()
            // installJar reads from a path, so the download lands in the app's own scratch dir
            tmpDir.mkdirs()
            val tmp = File.createTempFile("download", ".jar", tmpDir).apply { deleteOnExit() }
            res.body().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val result = ModuleLoader.installJar(tmp.absolutePath, overwrite)
            tmp.delete()
            result
        }.getOrDefault(InstallResult())
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
        val root = projectDir ?: return null
        if (rel.isEmpty() || rel.contains('\\')) return null
        if (requireFlow && !rel.endsWith(".flow")) return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return runCatching {
            val file = File(root, rel)
            val base = root.canonicalFile.path + File.separator
            if (file.canonicalFile.path.startsWith(base)) file else null
        }.getOrNull()
    }

    private fun relPath(root: File, file: File): String =
        file.toRelativeString(root).replace(File.separatorChar, '/')

    // 깊이 제한 트리 순회, 숨김(.) 항목 제외
    private fun walk(): Sequence<Pair<File, File>> {
        val root = projectDir ?: return emptySequence()
        return root.walkTopDown().maxDepth(MAX_TREE_DEPTH)
            .onEnter { !it.name.startsWith(".") }
            .filter { it != root && !it.name.startsWith(".") }
            .map { root to it }
    }

    actual fun listFlows(): List<String> =
        walk().filter { it.second.isFile && it.second.name.endsWith(".flow") }
            .map { relPath(it.first, it.second) }.sorted().toList()

    actual fun listProjectFiles(): List<String> =
        walk().filter { it.second.isFile }.map { relPath(it.first, it.second) }.sorted().toList()

    actual fun listFlowDirs(): List<String> =
        walk().filter { it.second.isDirectory }.map { relPath(it.first, it.second) }.sorted().toList()

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


    // ── "Open In" ──
    // The project root resolves to the flows dir itself; anything else goes through resolveRel, so
    // a crafted path still cannot point the file manager outside the project.
    private fun openTarget(rel: String): File? =
        if (rel.isEmpty()) projectDir else resolveRel(rel, requireFlow = false)?.takeIf { it.exists() }

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

    // Writes [text] to [file] via a temp-file-then-rename so a poll tick reading concurrently
    // never sees a half-written file — plain writeText() truncates in place first, which a read
    // landing mid-write could catch as truncated/corrupt JSON.
    private fun writeAtomically(file: File, text: String) {
        baseDir.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { tmp.delete(); file.writeText(text) } // same-volume rename should always land; this is only a fallback
    }

    // "Takes" [file]'s content by first renaming it away (atomic on the same volume) and only then
    // reading and deleting that renamed copy. A plain read-then-delete has a real failure mode: if
    // the delete silently fails for any reason, the file is still sitting there under its original
    // name, and the *next* poll tick reads and re-applies the exact same request — repeating
    // whatever it was (a run, most visibly) for as long as delete keeps failing. Once the rename
    // lands, nothing can ever see the original name again, so a later delete failure on the
    // renamed copy just leaves an orphaned temp file instead of a request that keeps re-firing.
    /**
     * Claims a request file, if it is still about now.
     *
     * The claim is the rename: two readers racing for the same request both try it and only one
     * can win, so a request is acted on once.
     *
     * The age check is the other half, and it was missing. These files say "open this", "run this"
     * — all of them meaning now — and nothing expired them, so one that no one happened to claim
     * sat on disk until something did. A request written while the app was closing, or by an
     * external MCP client against an app that quit a moment later, was picked up by the next
     * launch: a flow that ran by itself, hours after anyone asked for anything, which is what this
     * looked like from the outside. Past the cutoff it is deleted rather than returned — nobody was
     * listening when it was asked for, and acting on it later is worse than dropping it.
     */
    private fun takeFile(file: File, maxAge: Long = REQUEST_MAX_AGE_MS): String? {
        if (!file.isFile) return null
        val claimed = File(file.parentFile, "${file.name}.claimed-${System.nanoTime()}")
        if (!runCatching { file.renameTo(claimed) }.getOrDefault(false)) return null
        val age = System.currentTimeMillis() - runCatching { claimed.lastModified() }.getOrDefault(0L)
        val text = if (age in 0..maxAge) runCatching { claimed.readText() }.getOrNull() else null
        runCatching { claimed.delete() }
        return text
    }

    /**
     * How long a pending request stays worth acting on.
     *
     * The app polls for these every 750ms and applies them at the end of every AI turn besides, so
     * anything still sitting here after this long was written when nothing was listening. Generous
     * against a machine that stalls; far short of "next time you open Flow".
     */
    private const val REQUEST_MAX_AGE_MS = 30_000L

    actual fun requestOpenFlow(name: String) {
        runCatching { writeAtomically(openRequestFile, name) }
    }

    actual fun takePendingOpenFlow(): String? = takeFile(openRequestFile)?.trim()?.takeIf { it.isNotEmpty() }

    actual fun requestFlowInput(path: String, inputs: Map<String, String>) {
        runCatching {
            val obj = buildJsonObject {
                put("path", path)
                putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
            }
            writeAtomically(inputRequestFile, requestJson.encodeToString(JsonObject.serializer(), obj))
        }
    }

    actual fun takePendingFlowInput(): Pair<String, Map<String, String>>? {
        val obj = takeFile(inputRequestFile)
            ?.let { runCatching { requestJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val path = obj["path"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return null
        val inputs = (obj["inputs"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        return path to inputs
    }

    actual fun requestFlowRun(path: String, start: Boolean, inputs: Map<String, String>) {
        runCatching {
            val obj = buildJsonObject {
                put("path", path)
                put("start", start)
                putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
            }
            writeAtomically(runRequestFile, requestJson.encodeToString(JsonObject.serializer(), obj))
        }
    }

    actual fun takePendingFlowRun(): FlowRunRequest? {
        val obj = takeFile(runRequestFile)
            ?.let { runCatching { requestJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val path = obj["path"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return null
        val start = obj["start"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return null
        val inputs = (obj["inputs"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        return FlowRunRequest(path, start, inputs)
    }

    // A pid a dead process can eventually reuse would misreport as "running" forever; in practice
    // that requires the exact pid to come back around before anyone calls this, on a desktop app
    // restarted by a human — the actual failure mode this exists to catch (the app not running at
    // all) is what matters, not that vanishingly unlikely one.
    actual fun isAppRunning(): Boolean {
        val pid = runCatching { pidFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
            ?: return false
        return runCatching { ProcessHandle.of(pid).isPresent }.getOrDefault(false)
    }

    actual fun markAppRunning() {
        runCatching { baseDir.mkdirs(); pidFile.writeText(ProcessHandle.current().pid().toString()) }
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
