package flow.core

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

// Actions a shortcut can be bound to (project tool window).
object Action {
    const val NEW_FLOW = "newFlow"
    const val NEW_FOLDER = "newFolder"
    const val RENAME = "rename"
    const val DELETE = "delete"

    val ALL = listOf(NEW_FLOW, NEW_FOLDER, RENAME, DELETE)
}

// A key plus its modifiers. `key` is one of KEY_NAMES' values; "meta" is Command on macOS and
// the Windows key elsewhere.
data class Shortcut(
    val key: String,
    val meta: Boolean = false,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    // stable form used for storage and comparison, e.g. "meta+n"
    fun id(): String = buildString {
        if (meta) append("meta+")
        if (ctrl) append("ctrl+")
        if (alt) append("alt+")
        if (shift) append("shift+")
        append(key)
    }

    // what the menus show, e.g. "⌘N" / "Win+N" / "F2"
    fun label(metaLabel: String): String = buildString {
        if (meta) append(metaLabel)
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(displayKey(key))
    }

    companion object {
        fun parse(id: String): Shortcut? {
            val parts = id.split('+')
            val key = parts.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return Shortcut(
                key = key,
                meta = "meta" in parts,
                ctrl = "ctrl" in parts,
                shift = "shift" in parts,
                alt = "alt" in parts,
            )
        }

        // null when the pressed key is a bare modifier or one we don't name
        fun of(ev: KeyEvent): Shortcut? {
            val name = KEY_NAMES[ev.key] ?: return null
            return Shortcut(name, ev.isMetaPressed, ev.isCtrlPressed, ev.isShiftPressed, ev.isAltPressed)
        }

        private fun displayKey(key: String) = when (key) {
            "delete" -> "Delete"
            "backspace" -> "Backspace"
            "enter" -> "Enter"
            "escape" -> "Esc"
            "space" -> "Space"
            else -> if (key.length == 1) key.uppercase() else key.replaceFirstChar { it.uppercase() }
        }
    }
}

val DEFAULT_KEYMAP = mapOf(
    Action.NEW_FLOW to Shortcut("n", meta = true),
    Action.NEW_FOLDER to Shortcut("n", meta = true, shift = true),
    Action.RENAME to Shortcut("f2"),
    Action.DELETE to Shortcut("delete"),
)

// The keys a shortcut can use: letters, digits, function keys and the few named ones.
private val KEY_NAMES: Map<Key, String> = buildMap {
    val letters = listOf(
        Key.A to "a", Key.B to "b", Key.C to "c", Key.D to "d", Key.E to "e", Key.F to "f",
        Key.G to "g", Key.H to "h", Key.I to "i", Key.J to "j", Key.K to "k", Key.L to "l",
        Key.M to "m", Key.N to "n", Key.O to "o", Key.P to "p", Key.Q to "q", Key.R to "r",
        Key.S to "s", Key.T to "t", Key.U to "u", Key.V to "v", Key.W to "w", Key.X to "x",
        Key.Y to "y", Key.Z to "z",
    )
    putAll(letters)
    putAll(
        listOf(
            Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
            Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
        )
    )
    putAll(
        listOf(
            Key.F1 to "f1", Key.F2 to "f2", Key.F3 to "f3", Key.F4 to "f4", Key.F5 to "f5",
            Key.F6 to "f6", Key.F7 to "f7", Key.F8 to "f8", Key.F9 to "f9", Key.F10 to "f10",
            Key.F11 to "f11", Key.F12 to "f12",
        )
    )
    putAll(
        listOf(
            Key.Delete to "delete", Key.Backspace to "backspace", Key.Enter to "enter",
            Key.Escape to "escape", Key.Spacebar to "space",
        )
    )
}
