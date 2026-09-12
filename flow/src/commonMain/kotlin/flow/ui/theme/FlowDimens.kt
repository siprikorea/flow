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
    /**
     * The strip of chrome along each window edge: the title bar, the two rails, the status bar.
     *
     * One value for all four, because the ground around the content is what a person reads as the
     * window's border and they are all part of it. At 40 for the title bar and 44 for the rails
     * that border came out 48 at the top, 52 at the sides and 32 at the bottom — four different
     * thicknesses that no amount of matching the *gaps* could even out, since the gap was never
     * the thing being seen.
     *
     * 40, and the strip is the whole band: there is no separate gap between the chrome and the
     * content any more. With a 32dp strip and an 8dp gap after it the numbers were right and the
     * picture was still wrong — the rail icons were centred in the 32, which put them 7dp from the
     * window's edge and 16dp from the sheet, visibly hard against the outside. What a person reads
     * as the border is the whole 40, so that is what the chrome has to be centred in, and the only
     * way to be sure of that is for the strip and the band to be the same thing.
     *
     * The air between the chrome and the sheet comes from the content instead: a 28dp control
     * centred in 40 leaves 6dp at each end, on every side of the window.
     */
    val chrome = 40.dp

    val toolbar = chrome
    val tabBar = 32.dp
    val statusBar = chrome

    val row = 28.dp
    val groupHeader = 24.dp
    val panelHeader = 32.dp

    val control = 28.dp
    val controlCompact = 24.dp
    val iconButton = 28.dp

    val icon = 16.dp
    val iconLarge = 20.dp
    val iconSmall = 12.dp

    val activityBar = chrome
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
    /** Between two sheets — a tool window and the content beside it. Not used at the window edge. */
    val inset = Space.s
    val border = 1.dp

    /** What a person sees as the window's border, and what the chrome in it is centred in. */
    val band = Size.chrome
}
