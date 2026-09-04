package flow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Every colour the UI names, in one theme's worth of values.
 *
 * The names describe a role rather than a shade ("faintText", not "grey60"), so a light theme is a
 * matter of filling this in again — the call sites never learn which theme is showing.
 *
 * Values follow the Flow design guide's closed token set (`Flow 디자인 가이드`, Claude Design
 * project 5bbc7aa4). The guide's own field names — `canvas/base/panel/raised/overlay`,
 * `textPrimary/Secondary/Tertiary/Disabled`, `catInput/catProcessor/catOutput`, etc. — are added
 * here as the new source of truth; a handful of pre-existing fields with the same meaning (border,
 * text, accent, accentHover, success, error, warn, gridDot) are revalued in place rather than
 * duplicated. Fields with no equivalent in the guide yet (nodeBorder, dropdownBg, catSource, the
 * per-kind header tints, ...) stay as they were — every UI file still reads them — and get folded
 * into the new set as each is migrated.
 */
class Scheme(
    val appBg: Color,
    val panelBg: Color,
    val canvasBg: Color,
    val holeBg: Color,
    val nodeBg: Color,
    val nodeHeaderBg: Color,
    val dropdownBg: Color,
    val tabBarBg: Color,

    val panelBorder: Color,
    val border: Color,
    val nodeBorder: Color,
    val buttonBorder: Color,
    val dropdownBorder: Color,
    val gridDot: Color,

    val text: Color,
    val menuText: Color,
    val subText: Color,
    val dimText: Color,
    val faintText: Color,
    val faintestText: Color,

    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val success: Color,
    val successText: Color,
    val doneBorder: Color,
    val error: Color,
    val errorSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val catSource: Color,
    val catTransform: Color,
    val catSink: Color,
    val catIo: Color,
    val catOut: Color,
    val catComponent: Color,
    val catPlugin: Color,
    // Washes laid over a node header, one per node kind. Kept apart from the cat* colours because
    // the roles pull opposite ways in a light theme: a badge has to darken to stay legible, a wash
    // has to stay pale to stay a wash. Alpha is part of the value, so each theme sets its own
    // strength — including none at all, which is what the dark theme does for plain modules.
    val ioHeaderTint: Color,
    val compHeaderTint: Color,
    val moduleHeaderTint: Color,
    val pluginHeaderTint: Color,

    val edge: Color,
    val edgeSelected: Color,
    val portBorder: Color,
    val statusDotIdle: Color,
    val resizeHandle: Color,
    val minimapNode: Color,
    val langActiveBg: Color,
    val langActiveText: Color,
    val hoverBg: Color,
    val dropdownHover: Color,
    val idText: Color,
    val autosave: Color,
    val pluginBadgeBorder: Color,
    val dangerBorder: Color,
    val runFromBorder: Color,
    val tabActiveBg: Color,

    // --- Flow design guide tokens (§1) ---
    // Surface ladder: canvas < base < panel < raised < overlay (dark); canvas is lightest in light.
    val base: Color,
    val raised: Color,
    val overlay: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val accentPressed: Color,
    val accentSubtle: Color,
    val warning: Color,
    val danger: Color,
    // The three module-category colours the guide allows — everything else stays grey.
    val catInput: Color,
    val catProcessor: Color,
    val catOutput: Color,
    // Generic state washes: hover/pressed on any flat row or icon button.
    val hoverOverlay: Color,
    val pressedOverlay: Color,
)

val DarkScheme = Scheme(
    appBg = Color(0xFF14161B),
    panelBg = Color(0xFF202329),
    canvasBg = Color(0xFF17191D),
    holeBg = Color(0xFF12151C),
    nodeBg = Color(0xFF1D212B),
    nodeHeaderBg = Color(0xFF232836),
    dropdownBg = Color(0xFF20242F),
    tabBarBg = Color(0xFF171A21),

    panelBorder = Color(0xFF2A2E3A),
    border = Color(0xFF363C46),
    nodeBorder = Color(0xFF333A49),
    buttonBorder = Color(0xFF3A4152),
    dropdownBorder = Color(0xFF313747),
    gridDot = Color(0xFF242830),

    text = Color(0xFFDFE1E5),
    menuText = Color(0xFFC9CEDB),
    subText = Color(0xFF8A90A0),
    dimText = Color(0xFF6B7284),
    faintText = Color(0xFF5D6475),
    faintestText = Color(0xFF4A5162),

    accent = Color(0xFF3574F0),
    accentHover = Color(0xFF4680F5),
    accentSoft = Color(0xFF7AA1FF),
    success = Color(0xFF4E9E5F),
    successText = Color(0xFF59D6A4),
    doneBorder = Color(0xFF2B7A5C),
    error = Color(0xFFDB5C5C),
    errorSoft = Color(0xFFFF8A8A),
    warn = Color(0xFFD6A73B),
    warnSoft = Color(0xFFF0C85A),
    catSource = Color(0xFF22C3A6),
    catTransform = Color(0xFFB07BFF),
    catSink = Color(0xFFFF9D5C),
    catIo = Color(0xFFF2C94C),         // input (yellow)
    catOut = Color(0xFF4FA3FF),        // output (blue) — the far end reads as a different thing
    catComponent = Color(0xFF62C6FF),  // component instance node
    catPlugin = Color(0xFFEF7DBB),     // installed module plugin
    ioHeaderTint = Color(0xFFF2C94C).copy(alpha = 0.16f),
    compHeaderTint = Color(0xFF62C6FF).copy(alpha = 0.16f),
    moduleHeaderTint = Color.Transparent,
    pluginHeaderTint = Color.Transparent,

    edge = Color(0xFF4A5262),
    edgeSelected = Color(0xFFEAF1FF),
    portBorder = Color(0xFF4A5262),
    statusDotIdle = Color(0xFF3A4152),
    resizeHandle = Color(0xFF454D61),
    minimapNode = Color(0xFF59627A),
    langActiveBg = Color(0xFF33405A),
    langActiveText = Color(0xFFC9D6F5),
    hoverBg = Color(0xFF262B37),
    dropdownHover = Color(0xFF2C3342),
    idText = Color(0xFF9FE8D0),
    autosave = Color(0xFF3F6B58),
    pluginBadgeBorder = Color(0xFF1F5A4C),
    dangerBorder = Color(0xFF4A2F34),
    runFromBorder = Color(0xFF33405A),
    tabActiveBg = Color(0xFF1D212B),

    base = Color(0xFF1C1F24),
    raised = Color(0xFF272B33),
    overlay = Color(0xFF2E333C),
    borderSubtle = Color(0xFF2A2F37),
    borderStrong = Color(0xFF4A515D),
    textPrimary = Color(0xFFDFE1E5),
    textSecondary = Color(0xFF9DA0A8),
    textTertiary = Color(0xFF6F737A),
    textDisabled = Color(0xFF52565E),
    accentPressed = Color(0xFF2B62CE),
    accentSubtle = Color(0x2E3574F0),
    warning = Color(0xFFD6A73B),
    danger = Color(0xFFDB5C5C),
    catInput = Color(0xFFD6A73B),
    catProcessor = Color(0xFF8E7BEE),
    catOutput = Color(0xFF3FA6C9),
    hoverOverlay = Color(0x0FFFFFFF),
    pressedOverlay = Color(0x1AFFFFFF),
)

/**
 * The light theme is not the dark one inverted: the surfaces climb the other way (the canvas is the
 * *lightest* thing, panels sit below it, nodes are white cards that lift off it), and every colour
 * that carries meaning as text — the error, warning, success and accent shades — is darkened rather
 * than lightened, since on a pale ground contrast comes from going down, not up.
 */
val LightScheme = Scheme(
    // Chrome is grey and the canvas is white, the way a light IDE separates its editor from the
    // furniture around it. Nodes are white too, so what lifts them off the canvas is their border
    // and their tinted header rather than a difference in fill.
    appBg = Color(0xFFE7EAF0),
    panelBg = Color(0xFFF2F3F5),
    canvasBg = Color(0xFFFFFFFF),
    holeBg = Color(0xFFFFFFFF),
    nodeBg = Color(0xFFFFFFFF),
    // clearly grey against the white node body, or an untinted header stops reading as a header
    nodeHeaderBg = Color(0xFFE9EDF3),
    dropdownBg = Color(0xFFFFFFFF),
    tabBarBg = Color(0xFFE2E6EC),

    panelBorder = Color(0xFFD5DAE3),
    border = Color(0xFFD3D5DB),
    nodeBorder = Color(0xFFC3CBD8),
    buttonBorder = Color(0xFFB4BDCB),
    dropdownBorder = Color(0xFFCBD2DD),
    gridDot = Color(0xFFDCDFE4),

    text = Color(0xFF1E1F22),
    menuText = Color(0xFF343A45),
    subText = Color(0xFF5C6472),
    dimText = Color(0xFF737B8A),
    faintText = Color(0xFF8B93A1),
    faintestText = Color(0xFFA5ACB9),

    accent = Color(0xFF2E5FCC),
    accentHover = Color(0xFF3A6DD9),
    accentSoft = Color(0xFF4B7FFF),
    success = Color(0xFF3E8A4E),
    successText = Color(0xFF0C8257),
    doneBorder = Color(0xFF7FC9AD),
    error = Color(0xFFC0453F),
    errorSoft = Color(0xFFBE2F2F),
    warn = Color(0xFFA97C1B),
    warnSoft = Color(0xFF8F6410),
    catSource = Color(0xFF0E9A82),
    catTransform = Color(0xFF8446DE),
    catSink = Color(0xFFDD6E1F),
    catIo = Color(0xFFB88A08),
    catOut = Color(0xFF1668C7),
    catComponent = Color(0xFF1789CE),
    catPlugin = Color(0xFFD1428D),
    // Nearly opaque pastels rather than a thin wash: a low-alpha tint over a cool grey header
    // turns khaki, and the point of the tint is to tell the node kinds apart at a glance. Every
    // kind gets one here — on a white canvas an untinted header leaves the node all one colour.
    ioHeaderTint = Color(0xFFFFE9A8).copy(alpha = 0.85f),
    compHeaderTint = Color(0xFFBFE3FB).copy(alpha = 0.85f),
    moduleHeaderTint = Color(0xFFDED2FA).copy(alpha = 0.85f),
    pluginHeaderTint = Color(0xFFFBD3E7).copy(alpha = 0.85f),

    edge = Color(0xFFA3ACBA),
    edgeSelected = Color(0xFF1B3E7A),
    portBorder = Color(0xFFA3ACBA),
    statusDotIdle = Color(0xFFC5CCD8),
    resizeHandle = Color(0xFFAAB2C0),
    minimapNode = Color(0xFF98A1B1),
    langActiveBg = Color(0xFFD9E3FA),
    langActiveText = Color(0xFF1B3E7A),
    hoverBg = Color(0xFFE6EAF1),
    dropdownHover = Color(0xFFE9EDF4),
    idText = Color(0xFF0B7A5E),
    autosave = Color(0xFF6FAE93),
    pluginBadgeBorder = Color(0xFF8CCBB8),
    dangerBorder = Color(0xFFE3B0B0),
    runFromBorder = Color(0xFFB6C8EE),
    tabActiveBg = Color(0xFFFFFFFF),

    base = Color(0xFFF7F8FA),
    raised = Color(0xFFFFFFFF),
    overlay = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFE6E8EC),
    borderStrong = Color(0xFFB4B8C0),
    textPrimary = Color(0xFF1E1F22),
    textSecondary = Color(0xFF5A5D63),
    textTertiary = Color(0xFF818594),
    textDisabled = Color(0xFFA8ACB4),
    accentPressed = Color(0xFF254FAD),
    accentSubtle = Color(0x1F2E5FCC),
    warning = Color(0xFFA97C1B),
    danger = Color(0xFFC0453F),
    catInput = Color(0xFFA97C1B),
    catProcessor = Color(0xFF6A54C9),
    catOutput = Color(0xFF2A7E9B),
    hoverOverlay = Color(0x0D000000),
    pressedOverlay = Color(0x14000000),
)

/**
 * The colours the UI reads.
 *
 * [scheme] is Compose state, so swapping it repaints everything that read a colour from here — which
 * is why the call sites stay plain `Palette.appBg` instead of threading a theme through the tree.
 */
object Palette {
    var scheme by mutableStateOf(DarkScheme)

    val appBg get() = scheme.appBg
    val panelBg get() = scheme.panelBg
    val canvasBg get() = scheme.canvasBg
    val holeBg get() = scheme.holeBg
    val nodeBg get() = scheme.nodeBg
    val nodeHeaderBg get() = scheme.nodeHeaderBg
    val dropdownBg get() = scheme.dropdownBg
    val tabBarBg get() = scheme.tabBarBg

    val panelBorder get() = scheme.panelBorder
    val border get() = scheme.border
    val nodeBorder get() = scheme.nodeBorder
    val buttonBorder get() = scheme.buttonBorder
    val dropdownBorder get() = scheme.dropdownBorder
    val gridDot get() = scheme.gridDot

    val text get() = scheme.text
    val menuText get() = scheme.menuText
    val subText get() = scheme.subText
    val dimText get() = scheme.dimText
    val faintText get() = scheme.faintText
    val faintestText get() = scheme.faintestText

    val accent get() = scheme.accent
    val accentHover get() = scheme.accentHover
    val accentSoft get() = scheme.accentSoft
    val success get() = scheme.success
    val successText get() = scheme.successText
    val doneBorder get() = scheme.doneBorder
    val error get() = scheme.error
    val errorSoft get() = scheme.errorSoft
    val warn get() = scheme.warn
    val warnSoft get() = scheme.warnSoft
    val catSource get() = scheme.catSource
    val catTransform get() = scheme.catTransform
    val catSink get() = scheme.catSink
    val catIo get() = scheme.catIo
    val catOut get() = scheme.catOut
    val catComponent get() = scheme.catComponent
    val catPlugin get() = scheme.catPlugin
    val ioHeaderTint get() = scheme.ioHeaderTint
    val compHeaderTint get() = scheme.compHeaderTint
    val moduleHeaderTint get() = scheme.moduleHeaderTint
    val pluginHeaderTint get() = scheme.pluginHeaderTint

    val edge get() = scheme.edge
    val edgeSelected get() = scheme.edgeSelected
    val portBorder get() = scheme.portBorder
    val statusDotIdle get() = scheme.statusDotIdle
    val resizeHandle get() = scheme.resizeHandle
    val minimapNode get() = scheme.minimapNode
    val langActiveBg get() = scheme.langActiveBg
    val langActiveText get() = scheme.langActiveText
    val hoverBg get() = scheme.hoverBg
    val dropdownHover get() = scheme.dropdownHover
    val idText get() = scheme.idText
    val autosave get() = scheme.autosave
    val pluginBadgeBorder get() = scheme.pluginBadgeBorder
    val dangerBorder get() = scheme.dangerBorder
    val runFromBorder get() = scheme.runFromBorder
    val tabActiveBg get() = scheme.tabActiveBg

    // --- Flow design guide tokens ---
    // panelBg already carries the guide's exact "panel" surface value (revalued in phase 1); this
    // is just the guide's own name for it, so new call sites don't have to know the old one.
    val panel get() = scheme.panelBg
    val base get() = scheme.base
    val raised get() = scheme.raised
    val overlay get() = scheme.overlay
    val borderSubtle get() = scheme.borderSubtle
    val borderStrong get() = scheme.borderStrong
    val textPrimary get() = scheme.textPrimary
    val textSecondary get() = scheme.textSecondary
    val textTertiary get() = scheme.textTertiary
    val textDisabled get() = scheme.textDisabled
    val accentPressed get() = scheme.accentPressed
    val accentSubtle get() = scheme.accentSubtle
    val warning get() = scheme.warning
    val danger get() = scheme.danger
    val catInput get() = scheme.catInput
    val catProcessor get() = scheme.catProcessor
    val catOutput get() = scheme.catOutput
    val hoverOverlay get() = scheme.hoverOverlay
    val pressedOverlay get() = scheme.pressedOverlay

    fun catColor(cat: String) = when (cat) {
        "source" -> catSource
        "sink" -> catSink
        "io" -> catIo
        "out" -> catOut
        "component" -> catComponent
        "pluginmod" -> catPlugin
        else -> catTransform
    }

    /** The guide's 3-way category colour: input / output boundary nodes, everything else a processor. */
    fun catColor3(cat: String) = when (cat) {
        "io" -> catInput
        "out" -> catOutput
        else -> catProcessor // transform, component, pluginmod — the guide has no 4th slot
    }

    fun statusDot(status: String) = when (status) {
        "running" -> accent
        "done" -> success
        "error" -> error
        else -> statusDotIdle
    }
}

/** Theme setting values persisted in the session and offered in Settings. */
object Theme {
    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"
    val ALL = listOf(SYSTEM, DARK, LIGHT)

    /** The scheme a setting resolves to; [systemDark] is what the OS reports. */
    fun schemeFor(theme: String, systemDark: Boolean): Scheme = when (theme) {
        DARK -> DarkScheme
        LIGHT -> LightScheme
        else -> if (systemDark) DarkScheme else LightScheme
    }
}

/**
 * Keeps [Palette] in step with the chosen theme, and with the OS setting while that choice is
 * "system" — so switching the machine to dark repaints the app without a restart.
 *
 * Called from every window the app opens rather than once at the root: they are separate Compose
 * trees, and the palette has to be settled before any of them paints.
 */
@Composable
fun ApplyTheme(theme: String) {
    val systemDark = isSystemInDarkTheme()
    val scheme = Theme.schemeFor(theme, systemDark)
    // assign during composition, not in an effect: an effect runs after the first frame, which
    // would show one frame of the old theme
    if (Palette.scheme !== scheme) Palette.scheme = scheme
}
