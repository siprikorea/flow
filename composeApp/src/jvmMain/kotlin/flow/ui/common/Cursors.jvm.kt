package flow.ui.common

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual fun resizeCursorIcon(): PointerIcon = PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR))
actual fun moveCursorIcon(): PointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))
