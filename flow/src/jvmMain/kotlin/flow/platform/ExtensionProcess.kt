package flow.platform

import flow.extension.ProcessorExtension
import flow.extension.host.ExtensionWorker
import flow.extension.host.Wire
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One extension, running in its own JVM.
 *
 * Started on first use and kept for later calls — a JVM per call would cost more than the work.
 * Requests carry an id so several can be in flight at once, which the engine needs: it runs
 * independent nodes together and a module that blocks must not hold up the rest.
 *
 * [kill] is the point of all this. A module stuck in a loop that never checks its interrupt flag
 * cannot be stopped inside a shared JVM by any means the platform offers; here it is a process, and
 * a process can be ended. The next call starts a fresh one.
 */
internal class ExtensionProcess(private val dir: File, private val jars: List<File>) {

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
            // both sides have to agree on, and the Kotlin runtime the shipped extensions use
            val classpath = (
                listOfNotNull(
                    jarOf(ExtensionWorker::class.java),
                    jarOf(ProcessorExtension::class.java),
                    jarOf(Unit::class.java),
                ) + uiJars()
                ).distinct().joinToString(File.pathSeparator)
            // A view opens a window from here, so this process has to be able to. UIElement is
            // what keeps it from looking like a second program: the window appears and can be used,
            // but there is no dock icon and no menu bar of its own — an extension is part of Flow,
            // not something the user started. A worker that only computes never touches any of it.
            val command = listOf(
                java,
                "-cp", classpath,
                "-Djava.awt.headless=false",
                "-Dapple.awt.UIElement=true",
                "-Dapple.awt.application.name=Flow",
                ExtensionWorker::class.java.name,
            ) + jars.map { it.absolutePath }
            val p = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT) // the worker's own logs stay visible
                .start()
            process = p
            out = DataOutputStream(BufferedOutputStream(p.outputStream))
            readerThread(p).start()
        }
    }

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
        failAllPending("extension process for '${dir.name}' stopped")
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
        val stream = out ?: return Reply(false, "extension process is not running".toByteArray())
        try {
            synchronized(stream) {
                stream.writeInt(id)
                stream.writeInt(op)
                write(stream)
                stream.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            return Reply(false, (e.message ?: "could not reach the extension process").toByteArray())
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
        failAllPending("extension process for '${dir.name}' was stopped")
    }

    private fun jarOf(c: Class<*>): String? =
        runCatching { File(c.protectionDomain.codeSource.location.toURI()).absolutePath }.getOrNull()

    /**
     * The app's own Compose, handed to the worker so a view can open a window.
     *
     * A view extension could carry its own, but Compose's rendering half ships a native library per
     * platform and a jar that bundled them all would be a hundred megabytes — for every view, in a
     * registry served out of a git repository. So the app lends its copy.
     *
     * What that costs is that a view is built against the app's Compose rather than one of its
     * choosing. What it does not cost is the isolation that matters: this is still another process,
     * so a view that hangs or crashes takes only itself, and everything else an extension depends on
     * is still its own.
     *
     * Named by prefix because the artifacts change between Compose versions — and getting the list
     * wrong is quiet: a missing jar is a NoClassDefFoundError on the thread that was going to open
     * the window, so nothing appears and nothing says why. UiClasspathTest is what checks it.
     */
    private fun uiJars(): List<String> =
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { path ->
                val name = File(path).name.lowercase()
                UI_PREFIXES.any { name.startsWith(it) }
            }

    private companion object {
        // Compose and what it stands on. Named by prefix because the exact artifacts change between
        // Compose versions, and a view needs whichever ones this build happens to have.
        val UI_PREFIXES = listOf(
            "compose-", "desktop-jvm", "ui-", "foundation-", "runtime-", "animation-", "material-",
            "skiko", "annotation-", "annotations-", "collection-", "lifecycle-", "savedstate-",
            "kotlinx-coroutines-", "atomicfu", "core-common", "jbr-api", "kotlin-stdlib",
        )
    }
}
