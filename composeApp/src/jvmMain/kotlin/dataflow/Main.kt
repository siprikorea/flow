package dataflow

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dataflow.core.Workspace
import dataflow.ui.shell.App
import dataflow.ui.shell.WindowChrome
import dataflow.ui.shell.handleKey
import kotlin.math.roundToInt

fun main() = application {
    val scope = rememberCoroutineScope()
    val ws = remember { Workspace(scope) }
    val windowState = rememberWindowState(
        width = 1480.dp,
        height = 920.dp,
        position = WindowPosition(Alignment.Center), // 화면 중앙에 새 창으로 배치
    )
    Window(
        onCloseRequest = { ws.saveAll(); exitApplication() },
        title = "DataFlow Editor",
        state = windowState,
        undecorated = true, // OS 타이틀바 대신 앱 테마 타이틀바 사용
        onKeyEvent = { handleKey(ws, it) },
    ) {
        val maximized by androidx.compose.runtime.derivedStateOf { windowState.placement == WindowPlacement.Maximized }
        val chrome = WindowChrome(
            isMaximized = maximized,
            onMinimize = { windowState.isMinimized = true },
            onToggleMaximize = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                    else WindowPlacement.Maximized
            },
            onClose = { ws.saveAll(); exitApplication() },
            onDragBy = { d ->
                if (windowState.placement == WindowPlacement.Floating) {
                    window.setLocation((window.x + d.x).roundToInt(), (window.y + d.y).roundToInt())
                }
            },
        )
        // 시작 시 창을 앞으로 가져와 포커스 확보 (macOS에서 뒤에 뜨는 문제 방지)
        LaunchedEffect(Unit) {
            window.isAlwaysOnTop = true
            window.toFront()
            window.requestFocus()
            window.isAlwaysOnTop = false
        }
        App(ws, chrome)
    }
}
