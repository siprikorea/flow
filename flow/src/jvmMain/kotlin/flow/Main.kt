package flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import flow.core.Workspace
import androidx.compose.ui.window.WindowPlacement
import flow.ui.shell.App
import flow.ui.shell.ExtensionsScreen
import flow.ui.shell.SettingsScreen
import flow.ui.shell.handleKey
import java.awt.Taskbar
import java.awt.Toolkit
import kotlinx.coroutines.flow.debounce

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

        val screen = Toolkit.getDefaultToolkit().screenSize
        val bounds = initialWindowBounds(
            screenWidth = screen.width, screenHeight = screen.height,
            savedX = ws.windowX, savedY = ws.windowY, savedWidth = ws.windowWidth, savedHeight = ws.windowHeight,
        )

        val windowState = rememberWindowState(
            width = bounds.width.dp,
            height = bounds.height.dp,
            position = if (bounds.x != null && bounds.y != null) WindowPosition.Absolute(bounds.x.dp, bounds.y.dp) else WindowPosition(Alignment.Center),
            placement = if (ws.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
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
            // Track the window's bounds into the workspace as it's moved/resized; App.kt's existing
            // session-save effect (debounced snapshotFlow of ws.sessionJson()) picks this up and
            // persists it, so the window reopens at the same place/size next launch.
            LaunchedEffect(Unit) {
                snapshotFlow { Triple(windowState.position, windowState.size, windowState.placement) }
                    .debounce(350)
                    .collect { (position, size, placement) ->
                        ws.windowMaximized = placement == WindowPlacement.Maximized
                        // only floating bounds are meaningful to restore as an exact size/position —
                        // a maximized window's reported size/position is the full-screen bounds, not
                        // what the user would want back after un-maximizing
                        if (placement != WindowPlacement.Maximized && position is WindowPosition.Absolute) {
                            ws.windowX = position.x.value
                            ws.windowY = position.y.value
                            ws.windowWidth = size.width.value
                            ws.windowHeight = size.height.value
                        }
                    }
            }
            // Reserve ~72px on the left for the native traffic lights so nothing overlaps them
            App(
                ws,
                leadingInset = if (isMac) 72.dp else 0.dp,
                // double-click zooms (maximize/restore) like the native title bar,
                // not the green-button fullscreen
                onTitleDoubleClick = {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                        else WindowPlacement.Maximized
                },
            )
        }

        // Extensions manager opens as its own window, centered over the main window
        if (ws.showManage) {
            Window(
                onCloseRequest = { ws.showManage = false },
                title = ws.t("manageTitle"),
                state = rememberWindowState(width = 780.dp, height = 560.dp, position = centeredOver(windowState, 780.dp, 560.dp)),
                icon = iconPainter,
            ) {
                ExtensionsScreen(ws)
            }
        }

        // Settings opens as its own window too, centered over the main window
        if (ws.showSettings) {
            Window(
                onCloseRequest = { ws.showSettings = false },
                title = ws.t("settingsTitle"),
                state = rememberWindowState(width = 480.dp, height = 460.dp, position = centeredOver(windowState, 480.dp, 460.dp)),
                icon = iconPainter,
            ) {
                SettingsScreen(ws)
            }
        }
    }
}

// Position a childWidth×childHeight window centered over the main window's current bounds.
// Falls back to centering on the screen if the main window's position isn't resolved to an
// absolute value yet (e.g. it was just created and hasn't been shown/moved).
private fun centeredOver(main: WindowState, childWidth: Dp, childHeight: Dp): WindowPosition {
    val pos = main.position
    return if (pos is WindowPosition.Absolute) {
        val (x, y) = centeredOverBounds(pos.x.value, pos.y.value, main.size.width.value, main.size.height.value, childWidth.value, childHeight.value)
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition(Alignment.Center)
    }
}

// pure math for centeredOver, split out so it can be verified without a Compose/AWT window
internal fun centeredOverBounds(mainX: Float, mainY: Float, mainWidth: Float, mainHeight: Float, childWidth: Float, childHeight: Float): Pair<Float, Float> =
    (mainX + (mainWidth - childWidth) / 2f) to (mainY + (mainHeight - childHeight) / 2f)

// Resolved size + position (dp) for the main window at launch: the saved bounds from the last
// session if present, clamped back onto the current screen (resolution may have changed, e.g. an
// external monitor got disconnected) — else the default centered/screen-clamped size with no
// fixed position (x/y null signals "let the caller center it").
internal data class WindowBounds(val width: Int, val height: Int, val x: Float?, val y: Float?)

internal fun initialWindowBounds(
    screenWidth: Int, screenHeight: Int,
    savedX: Float?, savedY: Float?, savedWidth: Float?, savedHeight: Float?,
): WindowBounds {
    val defaultW = minOf(1480, screenWidth - 120).coerceAtLeast(800)
    val defaultH = minOf(920, screenHeight - 120).coerceAtLeast(560)
    val w = savedWidth?.toInt()?.coerceIn(800, screenWidth) ?: defaultW
    val h = savedHeight?.toInt()?.coerceIn(560, screenHeight) ?: defaultH
    val x = savedX?.coerceIn(0f, (screenWidth - w).coerceAtLeast(0).toFloat())
    val y = savedY?.coerceIn(0f, (screenHeight - h).coerceAtLeast(0).toFloat())
    return WindowBounds(w, h, x, y)
}

// Native (system) menu bar: Flow / File / Edit / View
@Composable
private fun FrameWindowScope.AppMenuBar(ws: Workspace) {
    MenuBar {
        Menu(ws.t("menuFile")) {
            Item(ws.t("newComponent")) { ws.newComponent() }
            Item(ws.t("save")) { ws.saveActive() }
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
