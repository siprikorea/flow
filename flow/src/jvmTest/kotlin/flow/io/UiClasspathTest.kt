package flow.io

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The classpath a view's process gets is enough to open a window.
 *
 * A view is written in Compose but does not carry it — the app lends its own, chosen by matching
 * jar names. Getting that list wrong fails silently: the class that opens the window is simply not
 * there, the thread dies with a NoClassDefFoundError nobody reads, and no window appears. That is
 * exactly what happened, so this loads the classes in a JVM with that very classpath.
 */
class UiClasspathTest {

    /** Mirrors ExtensionProcess.uiJars, which is private to it. */
    private fun uiJars(): List<String> {
        val prefixes = listOf(
            "compose-", "desktop-jvm", "ui-", "foundation-", "runtime-", "animation-", "material-",
            "skiko", "annotation-", "annotations-", "collection-", "lifecycle-", "savedstate-",
            "kotlinx-coroutines-", "atomicfu", "core-common", "jbr-api", "kotlin-stdlib",
        )
        return (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { path -> prefixes.any { File(path).name.lowercase().startsWith(it) } }
    }

    @Test
    fun `a view's process can load what it takes to open a window`() {
        val classpath = uiJars().joinToString(File.pathSeparator)
        // the classes a windowed view actually reaches for, each from a different artifact
        val needed = listOf(
            "androidx.compose.ui.window.Window_desktopKt",
            "androidx.compose.ui.window.Application_desktopKt",
            "androidx.compose.runtime.Composer",
            "androidx.compose.foundation.BackgroundKt",
            "androidx.compose.foundation.lazy.LazyDslKt",
            "androidx.compose.material.TextKt",
            "androidx.compose.ui.graphics.Color",
            "org.jetbrains.skia.Image",
            "kotlinx.coroutines.Job",
        )
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val script = needed.joinToString(";") { "Class.forName(\"$it\")" }
        val probe = File.createTempFile("uiprobe", ".java").apply {
            writeText(
                """
                public class ${nameWithoutExtension} {
                    public static void main(String[] a) throws Exception {
                        ${needed.joinToString("\n") { "Class.forName(\"$it\");" }}
                        System.out.println("OK");
                    }
                }
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val process = ProcessBuilder(java, "-cp", classpath, probe.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        assertEquals("OK", output.lines().last(), "the window classes are not all there:\n$output")
        assertTrue(script.isNotEmpty())
    }
}
