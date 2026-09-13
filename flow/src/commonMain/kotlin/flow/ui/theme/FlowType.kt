package flow.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * One named text style from the Flow design guide's six-token type scale (§2).
 * `body` is the default for everything — list rows, buttons, field values, node titles; hierarchy
 * inside a screen comes from weight and colour (`Palette.textPrimary` vs `textTertiary`), not from
 * reaching for a different size. No size outside this file's six values belongs anywhere in the
 * app, and nothing outside this file should name a size at all: ask for the style.
 */
data class FlowTextStyle(
    val size: TextUnit,
    val lineHeight: TextUnit,
    val weight: FontWeight,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val mono: Boolean = false,
)

/**
 * Balanced against the IDE it sits beside, measured rather than guessed.
 *
 * A screenshot of IntelliJ's own window, read pixel by pixel: a tree row, a tab, a button and a
 * tool window's title all carry the same cap height, and the status bar is one notch under it. The
 * hierarchy is weight and colour; the size stays put. Flow had four sizes doing that work — a
 * palette header at 12 over rows at 14 beside hints at 13 — which is what read as uneven.
 *
 * So: one size for everything in the chrome, one notch down for the meta line at the bottom and
 * the labels over a group, and one step up for a dialog's own title. 12 / 13 / 15, where the middle
 * one is the IDE's 13.
 */
object FlowType {
    // group labels, status bar, field labels — always uppercased by the caller
    val caption = FlowTextStyle(12.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.07.em)
    // secondary descriptions, hints, versions — the same size, told apart by colour
    val small = FlowTextStyle(12.sp, 16.sp, FontWeight.Normal)
    // the default, and the IDE's own: list rows, buttons, input values, node titles, tabs
    val body = FlowTextStyle(13.sp, 18.sp, FontWeight.Normal)
    // a selected row, a panel's title, anything the eye should land on first
    val bodyStrong = FlowTextStyle(13.sp, 18.sp, FontWeight.SemiBold)
    // dialog titles and the settings page's own heading
    val title = FlowTextStyle(15.sp, 20.sp, FontWeight.SemiBold)
    // paths, hashes, value previews, port counts — always the mono face, sized with the body
    val mono = FlowTextStyle(13.sp, 18.sp, FontWeight.Normal, mono = true)
}
