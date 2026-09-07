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
import flow.model.AI_CLAUDE
import flow.model.AI_GEMINI
import flow.model.AI_OLLAMA
import flow.model.AI_OPENAI
import flow.model.AiMessage
import flow.ui.shell.SettingsScreen
import flow.ui.theme.ApplyTheme
import flow.ui.theme.Palette
import flow.ui.theme.Theme
import flow.ui.tools.AiPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Draws the assistant panel and its settings to files. Skipped unless RENDER_OUT names a directory. */
class AiPanelProbe {

    private fun workspace(): Workspace {
        val ws = Workspace(CoroutineScope(Dispatchers.Unconfined))
        // a folder, so the panel shows the conversation rather than asking for one
        val folder = File(System.getProperty("java.io.tmpdir"), "flow-ai-probe").apply { mkdirs() }
        ws.openProject(folder.absolutePath)
        return ws
    }

    /** A conversation and the composer under it — the model picker lives there. */
    @Test
    fun `draw a conversation`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(380, 1000, density = Density(2f)) {
            ApplyTheme(Theme.DARK)
            val ws = workspace()
            // two providers in one transcript: the label belongs to the answer, not to the setting
            ws.aiProvider = AI_OLLAMA
            ws.aiMessages = listOf(
                AiMessage(true, "sha256 flow 를 만들어줘"),
                AiMessage(false, "**sha256-hash.flow** 를 저장했습니다.\n\n- `text` → `SHA-256` → `hash`", AI_CLAUDE),
                AiMessage(true, "실행해줘"),
                AiMessage(false, "실행했습니다. 출력은 `2cf24db8…` 입니다.", AI_OLLAMA),
            )
            Box(Modifier.fillMaxSize().background(Palette.panelBg)) { AiPanel(ws) }
        }
        File(dir, "ai-conversation.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /**
     * Settings ▸ AI with Ollama picked: the server address and the models it reports having.
     *
     * Rendered a few times over a second because that list is a request to the server, so the first
     * frame has nothing in it — the same reason the real screen fills in a moment after it opens.
     */
    @Test
    fun `draw the ai settings`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        listOf(AI_CLAUDE, AI_OLLAMA, AI_OPENAI, AI_GEMINI).forEach { provider ->
            val scene = ImageComposeScene(1400, 900, density = Density(1.4f), coroutineContext = Dispatchers.Unconfined) {
                ApplyTheme(Theme.DARK)
                val ws = workspace()
                ws.aiProvider = provider
                ws.settingsCategory = "ai"
                SettingsScreen(ws)
            }
            repeat(20) { scene.render(); Thread.sleep(50) }
            File(dir, "ai-settings-$provider.png").writeBytes(
                scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes,
            )
            scene.close()
        }
    }

    /**
     * The detail pane with an extension's own settings in it.
     *
     * They only appear for an installed extension that declares some, and what is installed on this
     * machine is whatever jar happens to be there — so the module list is seeded here rather than
     * left to it, which is also the only way this is the same picture twice.
     */
    @Test
    fun `draw an extension's settings`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(1400, 1000, density = Density(1.4f), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            val ws = workspace()
            ws.settingsCategory = "processors"
            ws.installedModules = listOf(
                flow.model.ModuleInfo(
                    id = "flow.ai", name = "AI", inputs = listOf("in"), outputs = listOf("out"),
                    version = "1.0.0",
                    settings = listOf(
                        flow.model.OptDef("provider", flow.model.OptType.SELECT, "", listOf("", "claude", "openai", "gemini", "ollama")),
                        flow.model.OptDef("transport", flow.model.OptType.SELECT, "api", listOf("api", "cli")),
                        flow.model.OptDef("apiKey", flow.model.OptType.TEXT, "", secret = true),
                        flow.model.OptDef("model", flow.model.OptType.SELECT, "", listOf("", "claude-opus-5", "claude-sonnet-5")),
                    ),
                ),
            )
            SettingsScreen(ws)
        }
        repeat(30) { scene.render(); Thread.sleep(50) }
        File(dir, "ext-detail.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** The Extensions page, where the registry's categories become headings. */
    @Test
    fun `draw the extensions settings`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(1400, 1000, density = Density(1.4f), coroutineContext = Dispatchers.Unconfined) {
            ApplyTheme(Theme.DARK)
            val ws = workspace()
            ws.settingsCategory = "processors"
            SettingsScreen(ws)
        }
        repeat(30) { scene.render(); Thread.sleep(50) }
        File(dir, "ext-settings.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun `draw the ai panel`() {
        val dir = System.getenv("RENDER_OUT")?.let { File(it) } ?: return
        dir.mkdirs()
        listOf(Theme.DARK, Theme.LIGHT).forEach { theme ->
            val scene = ImageComposeScene(760, 900, density = Density(2f)) {
                ApplyTheme(theme)
                val ws = workspace()
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
