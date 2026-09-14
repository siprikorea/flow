package flow.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A view's worker is told where Skia's native library is.
 *
 * Run from Gradle the library sits inside the skiko jar and Skia unpacks it itself, so every test
 * passed and every view opened. In an installed Flow jpackage has already unpacked it beside the
 * jars and the app is launched with `-Dskiko.library.path` pointing there — the jar it would
 * unpack from no longer carries it. The worker is a JVM of its own and inherits nothing, so a view
 * in the shipped app died with
 *
 *   LibraryLoadException: Cannot find libskiko-macos-arm64.dylib.sha256
 *
 * which reached the user as "the view did not open a window". The difference between the two runs
 * is a system property, which is why this reads the command rather than opening a window: the run
 * that would have caught it is the one that only happens after a release.
 */
class ViewLibraryPathTest {

    private val property = "skiko.library.path"

    private fun command(): List<String> =
        ModuleProcess(File("/tmp"), emptyList()).workerCommand("java", "cp")

    private fun <T> withProperty(value: String?, body: () -> T): T {
        val previous = System.getProperty(property)
        if (value == null) System.clearProperty(property) else System.setProperty(property, value)
        try {
            return body()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `the worker is told where the library is when the app was told`() {
        val command = withProperty("/Applications/Flow.app/Contents/app") { command() }
        assertTrue(
            "-D$property=/Applications/Flow.app/Contents/app" in command,
            "a view's worker cannot find Skia's library:\n${command.joinToString("\n")}",
        )
    }

    /** And says nothing when there is nothing to say — a dev run unpacks it from the jar. */
    @Test
    fun `nothing is passed when the app was told nothing`() {
        val command = withProperty(null) { command() }
        assertEquals(emptyList(), command.filter { it.startsWith("-D$property") })
    }
}
