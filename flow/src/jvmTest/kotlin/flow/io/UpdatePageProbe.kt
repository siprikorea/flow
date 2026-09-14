package flow.io

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import flow.core.Workspace
import flow.ui.shell.SettingsScreen
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The update page, with and without something on offer. Skipped unless RENDER_OUT is set. */
class UpdatePageProbe {
    @Test
    fun `draw the update page`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(1200, 700, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
            ws.theme = Theme.DARK
            ws.settingsCategory = "update"
            Box(Modifier.fillMaxSize()) { SettingsScreen(ws) }
        }
        File(dir, "update.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
