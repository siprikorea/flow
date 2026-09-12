package flow.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * One named text style from the Flow design guide's six-token type scale (§2 / CLAUDE.md §2).
 * `body` (13/18/Normal) is the default for everything — list rows, buttons, field values, node
 * titles; hierarchy inside a screen comes from colour (`Palette.textPrimary` vs `textTertiary`),
 * not from reaching for a different size. No size outside this file's six values belongs anywhere
 * in the app.
 */
data class FlowTextStyle(
    val size: TextUnit,
    val lineHeight: TextUnit,
    val weight: FontWeight,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val mono: Boolean = false,
)

// Raised one step in 2026-09: the app read small beside the IDEs it sits next to. The scale moved
// as a whole rather than at the call sites, so the relationships between the six are untouched and
// no screen has to be re-tuned against another.
object FlowType {
    // group labels, status bar, field labels — always uppercased by the caller
    val caption = FlowTextStyle(12.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.07.em)
    // secondary descriptions, tooltips
    val small = FlowTextStyle(13.sp, 17.sp, FontWeight.Normal)
    // the default: list rows, buttons, input values, node titles
    val body = FlowTextStyle(14.sp, 19.sp, FontWeight.Normal)
    // selected node title, inspector header
    val bodyStrong = FlowTextStyle(14.sp, 19.sp, FontWeight.SemiBold)
    // dialog titles, empty-state copy
    val title = FlowTextStyle(16.sp, 21.sp, FontWeight.SemiBold)
    // paths, hashes, value previews, port counts — always the mono face
    val mono = FlowTextStyle(13.sp, 19.sp, FontWeight.Normal, mono = true)
}
