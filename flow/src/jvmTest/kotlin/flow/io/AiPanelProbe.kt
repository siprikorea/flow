package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import flow.core.Workspace
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import flow.ui.tools.AiPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Draws the assistant panel to a file. Skipped unless RENDER_OUT names a directory. */
class AiPanelProbe {
    @Test
    fun `draw the ai panel`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val scene = ImageComposeScene(760, 900, density = Density(2f)) {
                ApplyTheme(theme)
                val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
                Box(Modifier.fillMaxSize().background(Palette.panelBg)) {
                    Box(Modifier.width(340.dp).fillMaxSize()) { AiPanel(ws) }
                }
            }
            File(dir, "ai-$theme.png").writeBytes(
                scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes,
            )
            scene.close()
        }
    }
}
