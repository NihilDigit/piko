package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isHeightCompact

internal data class DetailPanelSpec(
    val title: String,
    val closeLabel: String,
    val minWidth: Dp = 320.dp,
    val maxWidth: Dp = 440.dp,
)

internal class DetailPanelKey<T : Any>(val spec: DetailPanelSpec)

/** Each owner and typed component key gets an independent instance, retained across layout changes. */
internal class DetailPanelStore {
    private val owners = mutableMapOf<Any, MutableMap<DetailPanelKey<*>, DetailPanelState<*>>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> stateFor(owner: Any, key: DetailPanelKey<T>): DetailPanelState<T> =
        owners.getOrPut(owner) { mutableMapOf() }.getOrPut(key) { DetailPanelState<T>(key.spec) } as DetailPanelState<T>

    fun forget(owner: Any) { owners.remove(owner)?.values?.forEach { it.close() } }
    fun forgetWhere(predicate: (Any) -> Boolean) { owners.keys.filter(predicate).forEach(::forget) }
    fun clear() { owners.keys.toList().forEach(::forget) }
}

/** UI-only ownership: inspecting another object never owns or restarts its business execution. */
internal class DetailPanelState<T : Any>(val spec: DetailPanelSpec) {
    var target by mutableStateOf<T?>(null)
        private set
    val panel = FloatingPanelState(spec.minWidth, followsEnd = false)
    val anchors = ItemPanelAnchors()

    fun show(target: T, anchor: PanelAnchor = PanelAnchor.Centered) {
        if (this.target == null) panel.placeAt(anchor)
        this.target = target
    }

    fun close() { target = null }

    fun handleKey(event: KeyEvent): Boolean {
        if (target == null || event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
        close()
        return true
    }

    companion object {
        fun keySaver(spec: DetailPanelSpec) = Saver<DetailPanelState<String>, String>(
            save = { it.target.orEmpty() },
            restore = { saved -> DetailPanelState<String>(spec).apply { if (saved.isNotEmpty()) show(saved) } },
        )
    }
}

@Composable
internal fun <T : Any> DetailHost(
    state: DetailPanelState<T>,
    modifier: Modifier = Modifier,
    avoid: () -> DpRect? = { null },
    onActivate: () -> Unit = {},
    content: @Composable (T, Modifier) -> Unit,
) {
    val target = state.target ?: return
    val spec = state.spec
    val focus = remember { FocusRequester() }
    val useBottomSheet = currentWidthClass() == WidthClass.Compact && !isHeightCompact()
    BackHandler { state.close() }
    if (useBottomSheet) {
        PikoSheet(
            onDismissRequest = state::close,
            sideSheetTitle = spec.title,
            sideSheetButtons = { TooltipIconButton(Icons.Outlined.Close, spec.closeLabel, state::close) },
        ) {
            val sideSheet = isSideSheet
            Column(Modifier.fillMaxWidth().onPreviewKeyEvent(state::handleKey).focusRequester(focus).focusable()) {
                LaunchedEffect(target) { focus.requestFocus() }
                if (!sideSheet) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TooltipIconButton(Icons.Outlined.Close, spec.closeLabel, state::close)
                    }
                }
                key(target) { content(target, Modifier.fillMaxWidth()) }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onPreviewKeyEvent(state::handleKey),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .pointerInput(state) {
                        detectTapGestures { state.close() }
                    },
            )
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(min = spec.minWidth, max = spec.maxWidth)
                    .pointerInput(Unit) {
                        detectTapGestures { onActivate() }
                    }
                    .focusRequester(focus)
                    .focusable()
                    .semantics { paneTitle = spec.title },
            ) {
                LaunchedEffect(target) { focus.requestFocus() }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TooltipIconButton(
                            icon = Icons.Outlined.Close,
                            label = spec.closeLabel,
                            onClick = state::close,
                            shortcut = "Esc",
                        )
                    }
                    key(target) {
                        content(target, Modifier.weight(1f, fill = false).fillMaxWidth())
                    }
                }
            }
        }
    }
}
