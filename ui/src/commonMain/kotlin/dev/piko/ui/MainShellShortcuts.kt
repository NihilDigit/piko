package dev.piko.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.workbench.WorkspaceViewModel

internal fun Modifier.mainShellShortcuts(
    shortcutModifier: ShortcutModifier,
    workspaceModel: WorkspaceViewModel,
    workspaceSurface: Boolean,
    desktop: Boolean,
    sidebarMode: Boolean,
    drawerOpen: Boolean,
    onDismissDrawer: () -> Unit,
    onToggleSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectMainTab: (MainTab) -> Unit,
): Modifier = this
    .onPreviewKeyEvent { event ->
        if (drawerOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onDismissDrawer()
            return@onPreviewKeyEvent true
        }
        if (!workspaceSurface || drawerOpen || event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) false
        else when (event.key) {
            Key.Tab -> { workspaceModel.cycleContext(if (event.isShiftPressed) -1 else 1); true }
            Key.T -> { workspaceModel.newFileContext(); true }
            Key.W -> {
                val id = workspaceModel.contexts.activeContextId.value
                if (workspaceModel.canCloseContext(id)) workspaceModel.requestCloseContext(id)
                true
            }
            Key.PageUp -> if (event.isShiftPressed) { workspaceModel.moveActiveContext(-1); true } else false
            Key.PageDown -> if (event.isShiftPressed) { workspaceModel.moveActiveContext(1); true } else false
            else -> false
        }
    }
    .onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.F1 || (shortcutModifier.isPressed(event) && event.key == Key.Slash))) {
            workspaceModel.shortcutsOpen = true
            return@onKeyEvent true
        }
        if (event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) return@onKeyEvent false
        if (event.key == Key.K) {
            onDismissDrawer()
            workspaceModel.paletteOpen = true
            return@onKeyEvent true
        }
        if (event.key == Key.Comma) {
            onDismissDrawer()
            onOpenSettings()
            return@onKeyEvent true
        }
        if (event.key == Key.B && sidebarMode) {
            onToggleSidebar()
            return@onKeyEvent true
        }
        val tab = when (event.key) {
            Key.One -> MainTab.FILES
            Key.Two -> MainTab.TRANSFERS
            Key.Three -> if (desktop) return@onKeyEvent false else MainTab.SETTINGS
            else -> return@onKeyEvent false
        }
        onDismissDrawer()
        onSelectMainTab(tab)
        true
    }

@Composable
internal fun EscClosesWhenFocused(onClose: () -> Unit, content: @Composable () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = hasFocus, onBack = onClose)
    Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
}
