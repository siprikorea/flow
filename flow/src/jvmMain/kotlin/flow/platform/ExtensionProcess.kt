package flow.platform

import flow.extension.ModuleExtension
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
            val classpath = listOfNotNull(jarOf(ModuleExtension::class.java), jarOf(Unit::class.java))
                .joinToString(File.pathSeparator)
            val command = listOf(java, "-cp", classpath, ExtensionWorker::class.java.name) +
                jars.map { it.absolutePath }
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
}
