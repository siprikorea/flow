package flow.io

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a window can be opened, closed, and opened again in one process.
 *
 * The viewer runs `application` and lets it return when the last window closes, so a second viewer
 * is a second `application` in the same JVM. If that cannot be done, a viewer works once and never
 * again — which is what was reported. Off unless VIEW_WINDOW is set: it puts a window on screen.
 */
class AppTwiceProbe {
    @Test
    fun `application can be run twice`() {
        if (System.getenv("VIEW_WINDOW") == null) return
        val outcome = (1..2).map { n ->
            runCatching {
                application {
                    Window(onCloseRequest = ::exitApplication, title = "probe$n") {
                        LaunchedEffect(Unit) { exitApplication() }
                    }
                }
            }
        }
        outcome.forEachIndexed { i, r -> println("run ${i + 1}: ${r.exceptionOrNull() ?: "ok"}") }
        assertTrue(outcome.all { it.isSuccess }, "a second application run failed")
    }
}
