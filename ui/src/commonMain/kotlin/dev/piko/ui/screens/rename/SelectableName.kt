package dev.piko.ui.screens.rename

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.rename.SelectionEdit
import dev.piko.shared.rename.SelectionProposal
import dev.piko.shared.rename.tokenRangeAt
import dev.piko.ui.components.PikoDropdownMenu

/**
 * 预览里选中原名一段后能做的事。[propose] 算出会生成的规则（null 表示这一段不在查找范围内），
 * [apply] 把它放进积木条。[replacesRule] 为 true 时当前已有查找或替换，生成的规则会取代它们，菜单里要说一声。
 */
internal class SelectionActions(
    val propose: (IntRange, SelectionEdit, String) -> SelectionProposal?,
    val apply: (SelectionProposal) -> Unit,
    val replacesRule: Boolean,
)

/**
 * 可以选中一段的原名。鼠标按住拖选，双击选一个词；触屏没有拖选，长按选中手指下的一个词（连续的数字、字母、文字，
 * 标点单独成词），选多了少了在生成的积木上再改。松手后弹出菜单：删除、替换为、选中的是数字时改为编号。
 *
 * 触屏不做拖动手柄：手柄要自己画、还要与列表的滚动抢手势，而长按选词已经覆盖了集数、标签这类最常见的目标。
 */
@Composable
internal fun SelectableName(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    actions: SelectionActions?,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selection by remember { mutableStateOf<IntRange?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastClick by remember { mutableLongStateOf(0L) }
    val selectionColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val shown = selection?.let { range ->
        AnnotatedString.Builder(text).apply { addStyle(SpanStyle(background = selectionColor), range.first, range.last + 1) }.toAnnotatedString()
    } ?: text
    val name = text.text

    Box(modifier = modifier) {
        Text(
            text = shown,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = if (actions == null) Modifier else Modifier.pointerInput(name) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val result = layout ?: return@awaitEachGesture
                    val anchor = result.getOffsetForPosition(down.position).coerceIn(0, name.length)
                    if (down.type == PointerType.Mouse) {
                        val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (dragStart == null) {
                            // 双击选词，照文本编辑器的习惯
                            val now = down.uptimeMillis
                            if (now - lastClick < DOUBLE_CLICK_MS) {
                                selection = tokenRangeAt(name, anchor).takeIf { !it.isEmpty() }
                                menuOpen = selection != null
                            }
                            lastClick = now
                            return@awaitEachGesture
                        }
                        drag(dragStart.id) { change ->
                            val offset = result.getOffsetForPosition(change.position).coerceIn(0, name.length)
                            selection = (minOf(anchor, offset) until maxOf(anchor, offset)).takeIf { !it.isEmpty() }
                            change.consume()
                        }
                        menuOpen = selection != null
                    } else {
                        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        selection = tokenRangeAt(name, anchor.coerceAtMost(name.length - 1)).takeIf { !it.isEmpty() }
                        menuOpen = selection != null
                    }
                }
            },
        )
        val range = selection
        if (actions != null && range != null) {
            PikoDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = {
                    menuOpen = false
                    selection = null
                },
            ) {
                SelectionMenu(name.substring(range.first, range.last + 1), range, actions) {
                    menuOpen = false
                    selection = null
                }
            }
        }
    }
}

private const val DOUBLE_CLICK_MS = 400L

@Composable
private fun SelectionMenu(selected: String, range: IntRange, actions: SelectionActions, close: () -> Unit) {
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }
    val preview = remember(range) { actions.propose(range, SelectionEdit.DELETE, "") }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("「$selected」", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = when {
                preview == null -> "这一段不在查找范围内，调整「应用于」后再选"
                preview.generalized -> "按位置匹配，${preview.total} 项中 ${preview.matched} 项的同一位置"
                else -> "按文字匹配，${preview.total} 项中 ${preview.matched} 项含有它"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (preview == null) colors.error else colors.onSurfaceVariant,
        )
        if (preview != null && actions.replacesRule) {
            Text("将取代当前的查找与替换", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (preview == null) return
    fun apply(edit: SelectionEdit, text: String = "") {
        actions.propose(range, edit, text)?.let(actions.apply)
        close()
    }
    if (replacing) {
        Row(modifier = Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                label = { Text("替换为") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply(SelectionEdit.REPLACE, replacement) }),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { apply(SelectionEdit.REPLACE, replacement) }) { Text("确定") }
        }
        return
    }
    DropdownMenuItem(text = { Text("删除") }, onClick = { apply(SelectionEdit.DELETE) })
    DropdownMenuItem(text = { Text("替换为…") }, onClick = { replacing = true })
    if (selected.all { it in '0'..'9' }) {
        DropdownMenuItem(text = { Text("改为编号") }, onClick = { apply(SelectionEdit.NUMBER) })
    }
}
