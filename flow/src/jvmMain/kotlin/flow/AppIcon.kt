package flow

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

// App icon (logo): shown in the macOS Dock/Taskbar and every window's title bar. Loaded from the
// bundled PNG (flow/src/jvmMain/resources/appicon.png, kept in sync with icons/appicon.svg — the
// icon's actual source of truth) rather than hand-drawn, so there's only one place the artwork
// lives; a hand-drawn Java2D copy previously here quietly went stale the moment the real icon
// changed, since nothing regenerated it.
object AppIcon {
    fun image(): BufferedImage =
        AppIcon::class.java.classLoader.getResourceAsStream("appicon.png").use { stream ->
            requireNotNull(stream) { "appicon.png missing from the classpath — check flow/src/jvmMain/resources" }
            ImageIO.read(stream)
        }
}
