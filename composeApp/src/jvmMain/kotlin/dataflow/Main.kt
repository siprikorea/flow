package dataflow

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { EditorState(scope) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "DataFlow Editor",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
        onKeyEvent = { handleKey(state, it) },
    ) {
        App(state)
    }
}
