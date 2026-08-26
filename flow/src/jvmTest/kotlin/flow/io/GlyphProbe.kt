package flow.io

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import flow.ui.common.BlocksGlyph
import flow.ui.common.FolderGlyph
import flow.ui.common.ClaudeGlyph
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Draws the rail icons side by side, to see the new one against the ones it sits with. */
class GlyphProbe {
    @Test
    fun `draw the rail icons`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        // large, so the shape can be judged against the icon it was measured from
        val big = ImageComposeScene(420, 420, density = Density(1f)) {
            ApplyTheme(Theme.DARK)
            Box(Modifier.fillMaxSize().background(Palette.panelBg), contentAlignment = Alignment.Center) {
                ClaudeGlyph(Palette.text, size = 380.dp)
            }
        }
        File(dir, "claude-big.png").writeBytes(big.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        big.close()

        val scene = ImageComposeScene(560, 200, density = Density(4f)) {
            ApplyTheme(Theme.DARK)
            Box(Modifier.fillMaxSize().background(Palette.panelBg), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(10.dp)) {
                    FolderGlyph(Palette.menuText)
                    BlocksGlyph(Palette.menuText)
                    ClaudeGlyph(Palette.menuText)
                    ClaudeGlyph(Palette.accent)
                }
            }
        }
        File(dir, "glyphs.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
