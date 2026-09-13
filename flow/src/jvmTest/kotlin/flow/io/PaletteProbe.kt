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
import flow.ui.tools.ModulePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Draws the modules palette, to see the fold marks. Skipped unless RENDER_OUT is set. */
class PaletteProbe {
    @Test
    fun `draw the palette`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(620, 620, density = Density(2f)) {
            ApplyTheme(Theme.DARK)
            val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
            Box(Modifier.fillMaxSize().background(Palette.panelBg)) {
                Box(Modifier.width(280.dp).fillMaxSize()) { ModulePalette(ws) }
            }
        }
        File(dir, "palette.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
