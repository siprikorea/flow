package flow.ui.theme

import androidx.compose.ui.unit.dp

// The Flow design guide's spacing scale: every gap and margin is one of these six, no in-between
// values. See `Flow 디자인 가이드.dc.html` §5 / CLAUDE.md §3.
object Space {
    val xs = 4.dp   // icon-to-text micro adjustment
    val s = 8.dp    // icon-to-label, button interior gap
    val m = 12.dp   // panel left/right margin, field interior
    val l = 16.dp   // section interior
    val xl = 24.dp  // between sections
    val xxl = 32.dp // empty states, dialogs
}

// Fixed control/region sizes. Control heights are only ever 24, 28 or 32 — no other height is a
// control height in this app.
object Size {
    val toolbar = 40.dp
    val tabBar = 32.dp
    val statusBar = 24.dp

    val row = 28.dp
    val groupHeader = 24.dp
    val panelHeader = 32.dp

    val control = 28.dp
    val controlCompact = 24.dp
    val iconButton = 28.dp

    val icon = 16.dp
    val iconLarge = 20.dp
    val iconSmall = 12.dp

    val activityBar = 44.dp
    val panelLeft = 260.dp
    val panelRight = 280.dp

    val node = 220.dp
    val nodeMax = 320.dp
    val nodeHeader = 32.dp
    val portRow = 24.dp
    val port = 10.dp
}

object Radius {
    val control = 4.dp
    val surface = 6.dp
    val dialog = 8.dp

    // The content frame and any tool window shown as its own card. Larger than a dialog's, because
    // it is a much larger rectangle: the same radius on a full-height panel barely reads as round.
    val window = 10.dp
}

/**
 * The frame the whole program sits in.
 *
 * One inset, used on all four sides and between the content and any tool window beside it, so the
 * margin around everything is the same thickness in every direction and the corners are all the
 * same corner. Anything that wants a different gap here is a mistake, not a special case.
 */
object Frame {
    val inset = Space.s      // window edge to content, on every side
    val border = 1.dp
}
