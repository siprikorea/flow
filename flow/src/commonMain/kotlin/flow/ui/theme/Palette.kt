package flow.ui.theme

import androidx.compose.ui.graphics.Color

object Palette {
    val appBg = Color(0xFF14161B)
    val panelBg = Color(0xFF1B1E26)
    val canvasBg = Color(0xFF101218)
    val holeBg = Color(0xFF12151C)
    val nodeBg = Color(0xFF1D212B)
    val nodeHeaderBg = Color(0xFF232836)
    val dropdownBg = Color(0xFF20242F)
    val tabBarBg = Color(0xFF171A21)

    val panelBorder = Color(0xFF2A2E3A)
    val border = Color(0xFF2C3140)
    val nodeBorder = Color(0xFF333A49)
    val buttonBorder = Color(0xFF3A4152)
    val dropdownBorder = Color(0xFF313747)
    val gridDot = Color(0xFF2B3040)

    val text = Color(0xFFE8EAF0)
    val menuText = Color(0xFFC9CEDB)
    val subText = Color(0xFF8A90A0)
    val dimText = Color(0xFF6B7284)
    val faintText = Color(0xFF5D6475)
    val faintestText = Color(0xFF4A5162)

    val accent = Color(0xFF5B8CFF)
    val accentHover = Color(0xFF8FB0FF)
    val accentSoft = Color(0xFF7AA1FF)
    val success = Color(0xFF34C98E)
    val successText = Color(0xFF59D6A4)
    val doneBorder = Color(0xFF2B7A5C)
    val error = Color(0xFFFF5C5C)
    val errorSoft = Color(0xFFFF8A8A)
    val warn = Color(0xFFE0A93B)
    val warnSoft = Color(0xFFF0C85A)
    val catSource = Color(0xFF22C3A6)
    val catTransform = Color(0xFFB07BFF)
    val catSink = Color(0xFFFF9D5C)
    val catIo = Color(0xFFF2C94C)         // input/output (yellow)
    val catComponent = Color(0xFF62C6FF)  // component instance node
    val catPlugin = Color(0xFFEF7DBB)     // installed module plugin

    val edge = Color(0xFF4A5262)
    val edgeSelected = Color(0xFFEAF1FF)
    val portBorder = Color(0xFF4A5262)
    val statusDotIdle = Color(0xFF3A4152)
    val resizeHandle = Color(0xFF454D61)
    val minimapNode = Color(0xFF59627A)
    val langActiveBg = Color(0xFF33405A)
    val langActiveText = Color(0xFFC9D6F5)
    val hoverBg = Color(0xFF262B37)
    val dropdownHover = Color(0xFF2C3342)
    val idText = Color(0xFF9FE8D0)
    val autosave = Color(0xFF3F6B58)
    val pluginBadgeBorder = Color(0xFF1F5A4C)
    val dangerBorder = Color(0xFF4A2F34)
    val runFromBorder = Color(0xFF33405A)
    val tabActiveBg = Color(0xFF1D212B)

    fun catColor(cat: String) = when (cat) {
        "source" -> catSource
        "sink" -> catSink
        "io" -> catIo
        "component" -> catComponent
        "pluginmod" -> catPlugin
        else -> catTransform
    }

    fun statusDot(status: String) = when (status) {
        "running" -> accent
        "done" -> success
        "error" -> error
        else -> statusDotIdle
    }
}
