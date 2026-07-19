package flow.ui.common

import androidx.compose.ui.input.pointer.PointerIcon

// desktop cursors depend on the platform (AWT), so provide them via expect/actual
expect fun resizeCursorIcon(): PointerIcon
expect fun moveCursorIcon(): PointerIcon
