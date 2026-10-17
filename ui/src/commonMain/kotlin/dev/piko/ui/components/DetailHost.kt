package dev.piko.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp.Companion.Unspecified
import dev.piko.ui.adaptive.isDesktopLayout

internal data class DetailPanelSpec(
    val title: String,
    val closeLabel: String,
    val minWidth: Dp = 280.dp,
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
    BackHandler { state.close() }
    if (isDesktopLayout()) {
        FloatingPanel(
            state = state.panel, title = spec.title, closeLabel = spec.closeLabel,
            onClose = state::close, minWidth = spec.minWidth, maxWidth = spec.maxWidth,
            autoMaxHeight = Unspecified, modifier = modifier, resizable = false,
            avoid = avoid, onActivate = onActivate,
        ) {
            key(target) { content(target, Modifier.weight(1f, fill = false).widthIn(min = spec.minWidth)) }
        }
    } else {
        val focus = remember { FocusRequester() }
        PikoSheet(onDismissRequest = state::close, sideSheetTitle = spec.title,
            sideSheetButtons = { TooltipIconButton(Icons.Outlined.Close, spec.closeLabel, state::close) }) {
            val sideSheet = isSideSheet
            Column(Modifier.fillMaxWidth().onPreviewKeyEvent(state::handleKey).focusRequester(focus).focusable()) {
                LaunchedEffect(target) { focus.requestFocus() }
                if (!sideSheet) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = state::close, modifier = Modifier.semantics { contentDescription = spec.closeLabel }) { Text("关闭") }
                }
                key(target) { content(target, Modifier.fillMaxWidth()) }
            }
        }
    }
}
