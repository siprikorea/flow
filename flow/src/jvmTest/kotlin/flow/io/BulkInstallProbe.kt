package flow.io

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import flow.core.Workspace
import flow.model.RegistryEntry
import flow.ui.shell.SettingsScreen
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The modules screen with a registry in hand, to see the whole-list buttons. */
class BulkInstallProbe {
    @Test
    fun `draw the modules screen`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(1400, 1000, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
            ws.theme = Theme.DARK
            ws.settingsCategory = "modules"
            // a registry: two the machine has at an older version, two it does not have at all
            ws.registry = listOf(
                RegistryEntry("flow.hash", "Hash", "9.9.9", "Message digest.", "module", "crypto", "hash-module.jar"),
                RegistryEntry("flow.cipher", "Cipher", "9.9.9", "Symmetric and RSA.", "module", "crypto", "cipher-module.jar"),
                RegistryEntry("com.example.one", "Example One", "1.0.0", "Not installed here.", "module", "other", "one.jar"),
                RegistryEntry("com.example.two", "Example Two", "1.0.0", "Nor this one.", "module", "other", "two.jar"),
            )
            Box(Modifier.fillMaxSize()) { SettingsScreen(ws) }
        }
        File(dir, "modules-bulk.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
