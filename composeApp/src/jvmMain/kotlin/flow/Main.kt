package flow

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import flow.core.Workspace
import flow.ui.shell.App
import flow.ui.shell.handleKey
import java.awt.Toolkit

fun main() {
    // macOS: 타이틀바를 어둡게(신호등이 다크 배경에 맞도록)
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    application {
        val scope = rememberCoroutineScope()
        val ws = remember { Workspace(scope) }

        // 초기 크기를 화면에 맞게 제한
        val screen = Toolkit.getDefaultToolkit().screenSize
        val initW = minOf(1480, screen.width - 120).coerceAtLeast(800)
        val initH = minOf(920, screen.height - 120).coerceAtLeast(560)

        val windowState = rememberWindowState(
            width = initW.dp,
            height = initH.dp,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = { ws.saveAll(); exitApplication() },
            title = "Flow",
            state = windowState,
            onKeyEvent = { handleKey(ws, it) },
        ) {
            // 시스템 창을 그대로 쓰되 타이틀바를 투명·풀콘텐츠로 만들어 앱 테마가 비치게 함.
            // (닫기/최소화/전체화면 버튼·리사이즈는 네이티브 그대로 동작)
            LaunchedEffect(Unit) {
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                window.toFront()
                window.requestFocus()
            }
            // 좌측에 네이티브 신호등(약 72px) 자리를 비워 메뉴가 겹치지 않게 함
            App(ws, leadingInset = if (isMac) 72.dp else 0.dp)
        }
    }
}
