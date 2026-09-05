package flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.runtime.key
import flow.ui.data.DataEditor
import flow.ui.shell.App
import flow.ui.shell.SettingsScreen
import flow.ui.shell.closeOnEscape
import flow.ui.shell.handleKey
import flow.ui.theme.Size
import flow.ui.theme.Theme
import flow.model.Session
import flow.platform.Platform
import kotlinx.serialization.json.Json
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame
import java.awt.Taskbar
import java.awt.Toolkit
import kotlinx.coroutines.flow.debounce

// The theme from the last session, for the one decision that has to be made before the Workspace
// exists. Any trouble reading it just means "system", which is the default anyway.
private fun savedTheme(): String = runCatching {
    val raw = Platform.loadSession() ?: return@runCatching Theme.SYSTEM
    Json { ignoreUnknownKeys = true }.decodeFromString<Session>(raw).theme
}.getOrNull()?.takeIf { it in Theme.ALL } ?: Theme.SYSTEM

// Tells the OS the title bar is exactly [heightDp] tall, so the native traffic lights it overlays
// on the (transparent, full-window-content) title strip centre on that height instead of on
// whatever default height AWT assumes — the same JBR window-decoration API IntelliJ's own title
// bar uses. This is layered on top of the fullWindowContent/transparentTitleBar setup below, not a
// replacement for it — that trio is what makes the window full-content with overlaid native
// controls in the first place; this just corrects where they land vertically.
//
// The returned CustomTitleBar is also how dragging works: per JBR's own test suite
// (HitTestClientArea/HitTestNonClientArea in JetBrainsRuntime), a custom title bar's plain
// background is chrome (draggable) by default, and `forceHitTest(true)` is what marks a point as
// real content instead — a Compose Desktop window has no separate native child components for JBR
// to tell those apart on its own (unlike the AWT/Swing test's real Button/Panel children), so
// MenuBar's onTitleBarPress calls forceHitTest(false) to confirm "let this drag" only for presses
// nothing else already consumed (Kotlin `false` here is the polarity that keeps dragging enabled;
// see Main.kt's caller).
private fun installCustomTitleBar(window: Frame, heightDp: Float): WindowDecorations.CustomTitleBar? {
    if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) return null
    return runCatching {
        val bar = JBR.getWindowDecorations().createCustomTitleBar()
        bar.height = heightDp
        JBR.getWindowDecorations().setCustomTitleBar(window, bar)
        bar
    }.getOrNull()
}

fun main() {
    // Lets the MCP server (a separate process — flow.cli.CliKt --mcp, which loads this same
    // Platform object but is not this app) tell whether the app itself is actually running, before
    // leaving it a request (open_flow etc.) nothing would ever pick up.
    Platform.markAppRunning()
    // App name shown in the macOS menu bar / Dock (instead of the main class name)
    System.setProperty("apple.awt.application.name", "Flow")
    // macOS: the title bar (and so the traffic lights) has to match the theme the app is about to
    // paint, and AWT reads this before any window exists — hence reading the saved theme here
    // rather than off the Workspace. "system" is left to AWT's own OS tracking.
    when (savedTheme()) {
        Theme.DARK -> System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
        Theme.LIGHT -> System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua")
        else -> System.setProperty("apple.awt.application.appearance", "system")
    }
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
            var leadingInset by remember { mutableStateOf(if (isMac) 72.dp else 0.dp) }
            var titleBar by remember { mutableStateOf<WindowDecorations.CustomTitleBar?>(null) }
            LaunchedEffect(Unit) {
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    // On top of the trio above: tells the OS the bar is exactly Size.toolbar tall,
                    // so the traffic lights it already overlays centre on it correctly. leadingInset
                    // becomes the exact width they occupy instead of the 72dp guess, when this
                    // succeeds. Keeping the CustomTitleBar itself is what lets MenuBar's
                    // onTitleBarPress make the plain part of the bar draggable (see installCustomTitleBar).
                    installCustomTitleBar(window, Size.toolbar.value)?.let {
                        titleBar = it
                        leadingInset = it.leftInset.dp
                    }
                }
                window.toFront()
                window.requestFocus()
            }
            // Where the window is right now, maximized or not, so a view can open centred on it.
            // Undebounced: a viewer opened moments after a drag should land on the window, not
            // where it used to be.
            LaunchedEffect(Unit) {
                snapshotFlow { windowState.position to windowState.size }
                    .collect { (position, size) ->
                        if (position is WindowPosition.Absolute) {
                            ws.liveWindowX = position.x.value
                            ws.liveWindowY = position.y.value
                        }
                        ws.liveWindowWidth = size.width.value
                        ws.liveWindowHeight = size.height.value
                    }
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
                leadingInset = leadingInset,
                // double-click zooms (maximize/restore) like the native title bar,
                // not the green-button fullscreen
                onTitleDoubleClick = {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                        else WindowPlacement.Maximized
                },
                // forceHitTest(false): "don't force this point to read as real content" — per JBR's
                // own CustomTitleBar tests, that's what leaves it as draggable chrome. Compose's own
                // input handling would otherwise claim every press itself before any native
                // chrome/drag detection gets a look at it.
                onTitleBarPress = { titleBar?.forceHitTest(false) },
            )
        }

        // Settings opens as its own window too, centered over the main window
        // A data editor per boundary node, each in its own window beside the flow it belongs to.
        // Esc deliberately does nothing here: the window is where data is typed, and losing a long
        // paste to a stray keystroke is not a thing an editor should do — it closes from its own
        // close button, or with the node, or with the document.
        ws.dataWindows.forEach { tab ->
            key(tab) {
                Window(
                    onCloseRequest = { ws.closeDataWindow(tab) },
                    title = tab.title,
                    state = rememberWindowState(
                        width = 980.dp, height = 700.dp,
                        position = centeredOver(windowState, 980.dp, 700.dp),
                    ),
                    icon = iconPainter,
                ) {
                    DataEditor(ws, tab)
                }
            }
        }

        if (ws.showSettings) {
            Window(
                onCloseRequest = { ws.showSettings = false },
                title = ws.t("settingsTitle"),
                state = rememberWindowState(width = 900.dp, height = 640.dp, position = centeredOver(windowState, 900.dp, 640.dp)),
                icon = iconPainter,
                onKeyEvent = { closeOnEscape(it) { ws.showSettings = false } },
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
            Item(ws.t("openFolder")) { Platform.pickFolder()?.let { ws.openProject(it) } }
            Item(ws.t("openFile")) { Platform.pickFlowFile()?.let { ws.importFlow(it) } }
            Item(ws.t("closeFolder")) { ws.openProject(null) }
            Item(ws.t("save")) { ws.saveActive() }
            Item(ws.t("closeTab")) { ws.requestClose(ws.activeIndex) }
            Separator()
            Item(ws.t("manageTitle")) { ws.openSettings("extensions") }
            Separator()
            Item(ws.t("menuSettings")) { ws.openSettings() }
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
        // Compose's MenuBar replaces the whole native bar rather than adding to it, so the
        // standard macOS Window menu (Minimize/Zoom) doesn't show up on its own — it has to be
        // declared like every other menu here.
        Menu(ws.t("menuWindow")) {
            Item(ws.t("minimize")) { window.extendedState = window.extendedState or Frame.ICONIFIED }
            Item(ws.t("zoom")) {
                window.extendedState =
                    if (window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) Frame.NORMAL
                    else window.extendedState or Frame.MAXIMIZED_BOTH
            }
        }
    }
}
