package flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import flow.core.Workspace
import androidx.compose.ui.window.WindowPlacement
import flow.ui.shell.App
import flow.ui.shell.handleKey
import java.awt.Taskbar
import java.awt.Toolkit

fun main() {
    // App name shown in the macOS menu bar / Dock (instead of the main class name)
    System.setProperty("apple.awt.application.name", "Flow")
    // macOS: dark title bar so the traffic lights match the dark background
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    // app icon — shown in the macOS Dock
    val iconImage = AppIcon.image()
    runCatching {
        if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().iconImage = iconImage
    }
    val iconPainter = BitmapPainter(iconImage.toComposeImageBitmap())

    application {
        val scope = rememberCoroutineScope()
        val ws = remember { Workspace(scope) }

        // clamp the initial size to the screen
        val screen = Toolkit.getDefaultToolkit().screenSize
        val initW = minOf(1480, screen.width - 120).coerceAtLeast(800)
        val initH = minOf(920, screen.height - 120).coerceAtLeast(560)

        val windowState = rememberWindowState(
            width = initW.dp,
            height = initH.dp,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = { ws.saveAll(); exitApplication() },
            title = "Flow",
            state = windowState,
            icon = iconPainter,
            onKeyEvent = { handleKey(ws, it) },
        ) {
            AppMenuBar(ws) // macOS top system menu bar (Flow / File / Edit / View)
            // Use the native window but make the title bar transparent/full-content so the app theme shows through.
            // (close/minimize/fullscreen buttons and resize keep working natively)
            LaunchedEffect(Unit) {
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                window.toFront()
                window.requestFocus()
            }
            // Reserve ~72px on the left for the native traffic lights so nothing overlaps them
            App(
                ws,
                leadingInset = if (isMac) 72.dp else 0.dp,
                onTitleDoubleClick = {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                        else WindowPlacement.Fullscreen
                },
            )
        }
    }
}

// Native (system) menu bar: Flow / File / Edit / View
@Composable
private fun FrameWindowScope.AppMenuBar(ws: Workspace) {
    MenuBar {
        Menu(ws.t("menuFile")) {
            Item(ws.t("newComponent")) { ws.newComponent() }
            Item(ws.t("newFlow")) { ws.newDoc() }
            Item(ws.t("closeTab")) { ws.requestClose(ws.activeIndex) }
            Separator()
            Item(ws.t("manageTitle")) { ws.showManage = true }
            Separator()
            Item(ws.t("menuSettings")) { ws.showSettings = true }
        }
        Menu(ws.t("menuEdit")) {
            Item(ws.t("undo")) { ws.active?.undo() }
            Item(ws.t("redo")) { ws.active?.redo() }
            Item(ws.t("deleteSel")) { ws.active?.deleteSelection() }
            Item(ws.t("autoLayout")) { ws.active?.autoLayout() }
        }
        Menu(ws.t("menuView")) {
            CheckboxItem(ws.t("toggleLeft"), checked = ws.showLeft, onCheckedChange = { ws.showLeft = it })
            CheckboxItem(ws.t("toggleProps"), checked = ws.showProps, onCheckedChange = { ws.showProps = it })
            CheckboxItem(ws.t("toggleMinimap"), checked = ws.showMinimap, onCheckedChange = { ws.showMinimap = it })
        }
    }
}
