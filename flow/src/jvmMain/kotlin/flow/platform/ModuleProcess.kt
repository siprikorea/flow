package flow.platform

import flow.extension.ModuleExtension
import flow.module.host.ModuleWorker
import flow.module.host.Wire
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One module, running in its own JVM.
 *
 * Started on first use and kept for later calls — a JVM per call would cost more than the work.
 * Requests carry an id so several can be in flight at once, which the engine needs: it runs
 * independent nodes together and a module that blocks must not hold up the rest.
 *
 * [kill] is the point of all this. A module stuck in a loop that never checks its interrupt flag
 * cannot be stopped inside a shared JVM by any means the platform offers; here it is a process, and
 * a process can be ended. The next call starts a fresh one.
 */
internal class ModuleProcess(private val dir: File, private val jars: List<File>) {

    private class Pending(val future: CompletableFuture<Reply>)
    class Reply(val ok: Boolean, val payload: ByteArray)

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    private var process: Process? = null
    private var out: DataOutputStream? = null
    private val startLock = Any()

    private fun ensureStarted() {
        synchronized(startLock) {
            process?.takeIf { it.isAlive }?.let { return }
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            // exactly three things, and the app is not among them: the worker itself, the contract
            // both sides have to agree on, and the Kotlin runtime the shipped modules use
            val classpath = (
                listOfNotNull(
                    jarOf(ModuleWorker::class.java),
                    jarOf(ModuleExtension::class.java),
                    jarOf(Unit::class.java),
                ) + uiJars()
                ).distinct().joinToString(File.pathSeparator)
            // A view opens a window from here, so this process has to be able to. UIElement is
            // what keeps it from looking like a second program: the window appears and can be used,
            // but there is no dock icon and no menu bar of its own — a module is part of Flow,
            // not something the user started. A worker that only computes never touches any of it.
            val command = workerCommand(java, classpath)
            val p = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT) // the worker's own logs stay visible
                .start()
            process = p
            out = DataOutputStream(BufferedOutputStream(p.outputStream))
            readerThread(p).start()
        }
    }

    /**
     * The command that starts one worker.
     *
     * Its own function because what is in it is the whole of what a worker can do: the three jars
     * it is given, the window it is allowed to open, and where the native library for drawing one
     * is. A test can read it; a running process cannot be asked.
     */
    internal fun workerCommand(java: String, classpath: String): List<String> = listOf(
        java,
        "-cp", classpath,
        "-Djava.awt.headless=false",
        "-Dapple.awt.UIElement=true",
        "-Dapple.awt.application.name=Flow",
    ) + nativeLibraryOptions() + listOf(
        ModuleWorker::class.java.name,
    ) + jars.map { it.absolutePath }

    // one thread drains the pipe and hands each reply to whoever asked for it
    private fun readerThread(p: Process) = Thread({
        val input = DataInputStream(BufferedInputStream(p.inputStream))
        runCatching {
            while (true) {
                val id = input.readInt()
                val status = input.readInt()
                val payload = ByteArray(input.readInt())
                input.readFully(payload)
                pending.remove(id)?.future?.complete(Reply(status == Wire.OK, payload))
            }
        }
        // the process ended — anything still waiting will never be answered
        failAllPending("module process for '${dir.name}' stopped")
    }, "extension-reader-${dir.name}").apply { isDaemon = true }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.future?.complete(Reply(false, reason.toByteArray()))
        }
    }

    /**
     * Sends one request and waits for its reply. Blocking on purpose: the caller runs this under
     * runInterruptible, so cancelling the run interrupts this wait and [kill] ends the process.
     */
    fun request(op: Int, write: (DataOutputStream) -> Unit): Reply {
        ensureStarted()
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<Reply>()
        pending[id] = Pending(future)
        val stream = out ?: return Reply(false, "module process is not running".toByteArray())
        try {
            synchronized(stream) {
                stream.writeInt(id)
                stream.writeInt(op)
                write(stream)
                stream.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            return Reply(false, (e.message ?: "could not reach the module process").toByteArray())
        }
        return try {
            future.get()
        } catch (e: InterruptedException) {
            // the run was cancelled: end the process so a module that ignores interruption stops too
            pending.remove(id)
            kill()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    fun kill() {
        synchronized(startLock) {
            process?.destroyForcibly()
            process = null
            out = null
        }
        failAllPending("module process for '${dir.name}' was stopped")
    }

    private fun jarOf(c: Class<*>): String? =
        runCatching { File(c.protectionDomain.codeSource.location.toURI()).absolutePath }.getOrNull()

    /**
     * The app's own libraries, handed to the worker so a view can open a window.
     *
     * A view module could carry its own Compose, but Compose's rendering half ships a native
     * library per platform and a jar that bundled them all would be a hundred megabytes — for every
     * view, in a registry served out of a git repository. So the app lends its copy.
     *
     * What that costs is that a view is built against the app's Compose rather than one of its
     * choosing. What it does not cost is the isolation that matters: this is still another process,
     * so a view that hangs or crashes takes only itself, and everything else an module depends on
     * is still its own.
     *
     * This used to name the jars to lend by prefix — "compose-", "ui-", "skiko" and so on — and the
     * list fell behind the first time Compose grew a module: 1.12 added androidx.navigationevent,
     * which nothing matched, and a window that Compose could no longer construct simply never
     * appeared. So the rule is now the one that was actually meant: lend the libraries the app runs
     * on. Lending one a view never touches costs nothing, since a classpath entry is not read until
     * something asks for it, whereas leaving one out costs a window.
     *
     * Two things are held back. Flow's own code, which an module has no business reaching into;
     * and anything that declares an module of its own, because a worker finds its modules by
     * service declaration and would otherwise serve every module that happened to be on the
     * app's classpath as though it were the one installed in this folder.
     */
    private fun uiJars(): List<String> {
        // In a dev run Flow's own classes are the directories on the classpath (build/classes/…,
        // build/resources/…); in a packaged app they are one jar, the one this class came from.
        val own = jarOf(ModuleProcess::class.java)
        return (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { it == own || File(it).isDirectory || declaresExtensions(File(it)) }
    }

    /**
     * Where Skia's native library is, when the app was told.
     *
     * A view draws with Compose, which draws with Skia, which loads a dylib. Run from Gradle, the
     * dylib is inside the skiko jar and Skia unpacks it itself — which is why every test passed.
     * In the packaged app jpackage has already unpacked it, next to the jars, and the app is
     * launched with `-Dskiko.library.path` pointing there; the jar it would otherwise unpack from
     * no longer carries it. The worker is a JVM of its own and inherits none of that, so a view in
     * an installed Flow could not draw at all:
     *
     *   LibraryLoadException: Cannot find libskiko-macos-arm64.dylib.sha256
     *
     * which surfaced as "the view did not open a window". Passing on what the app was told costs
     * nothing when there is nothing to pass on.
     */
    private fun nativeLibraryOptions(): List<String> =
        listOfNotNull(System.getProperty("skiko.library.path")?.takeIf { it.isNotBlank() })
            .map { "-Dskiko.library.path=$it" }

    /** Whether a jar announces a module — the one thing a lent library must not do. */
    private fun declaresExtensions(jar: File): Boolean = runCatching {
        java.util.zip.ZipFile(jar).use { zip ->
            zip.stream().anyMatch { it.name.startsWith("META-INF/services/flow.extension.") }
        }
    }.getOrDefault(false)
}
