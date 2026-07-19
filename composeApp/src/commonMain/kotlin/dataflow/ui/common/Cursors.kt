package dataflow.ui.common

import androidx.compose.ui.input.pointer.PointerIcon

// 데스크톱 커서는 플랫폼(AWT)에 의존하므로 expect/actual 로 제공
expect fun resizeCursorIcon(): PointerIcon
expect fun moveCursorIcon(): PointerIcon
