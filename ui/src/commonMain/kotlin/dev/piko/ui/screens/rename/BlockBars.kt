package dev.piko.ui.screens.rename

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.BracketKind
import dev.piko.shared.rename.DATE_TOKEN_CHOICES
import dev.piko.shared.rename.FindBlock
import dev.piko.shared.rename.RandomKind
import dev.piko.shared.rename.ReplaceBlock
import dev.piko.shared.rename.captureNumbers
import dev.piko.shared.rename.circled
import dev.piko.shared.rename.describe
import dev.piko.shared.rename.describeFind
import dev.piko.shared.rename.describeReplace
import dev.piko.shared.rename.isExpressibleText
import dev.piko.shared.rename.regexToFindBlocks
import dev.piko.shared.rename.templateToReplaceBlocks
import androidx.compose.material.icons.outlined.History
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.TooltipIconButton

/**
 * 积木的颜色，按积木在查找条上的位置轮换。预览里匹配到的片段、替换条里引用它的 ①② 用同一种颜色，
 * 一眼对得上哪段来自哪块。取主题的几种容器色，深浅主题下都与文字有足够对比。
 */
@Composable
internal fun blockColor(index: Int): Color {
    val colors = MaterialTheme.colorScheme
    // 第五种不用 surfaceContainerHighest：浮动对话框的行就是这个颜色，积木与预览里的底色会看不出来
    val palette = listOf(
        colors.primaryContainer,
        colors.tertiaryContainer,
        colors.secondaryContainer,
        colors.inversePrimary,
        colors.tertiary.copy(alpha = 0.35f).compositeOver(colors.surface),
    )
    return palette[index.mod(palette.size)]
}

/** 文本模式下整个匹配的颜色。 */
@Composable
internal fun wholeMatchColor(): Color = MaterialTheme.colorScheme.primaryContainer

/** 查找的积木条：上方一句话说明，下面是积木、末尾的输入框与添加按钮。 */
@Composable
internal fun FindBlockBar(state: BatchRenameState, enabled: Boolean, focusRequester: FocusRequester, onSubmit: () -> Unit) {
    val blocks = state.findBlocks
    val numbers = captureNumbers(state.effectiveFindBlocks)
    val sentence = state.effectiveFindBlocks.takeIf { it.isNotEmpty() }?.let(::describeFind)
    BlockBar(
        caption = if (sentence == null) "查找：未添加，留空则仅调整大小写" else "查找：$sentence",
        count = blocks.size,
        note = state.selectionNote,
        inputModifier = Modifier.focusRequester(focusRequester),
        enabled = enabled,
        placeholder = "输入要查找的文字",
        addLabel = "添加查找积木",
        pendingText = state.pendingFindText,
        onPendingTextChange = { state.pendingFindText = it },
        onCommit = state::commitPendingText,
        onSubmit = onSubmit,
        onMove = { from, to -> state.updateFindBlocks(blocks.toMutableList().apply { add(to, removeAt(from)) }) },
        chip = { index ->
            val block = blocks[index]
            BlockChipLabel(chipLabel(block) + (numbers.getOrNull(index)?.let(::circled) ?: ""))
        },
        chipColor = { index -> blockColor(index) },
        editor = { index, close ->
            FindBlockEditor(
                block = blocks[index],
                onChange = { changed -> state.updateFindBlocks(blocks.toMutableList().also { it[index] = changed }) },
                onDelete = {
                    close()
                    state.updateFindBlocks(blocks.toMutableList().also { it.removeAt(index) })
                },
            )
        },
        addMenu = { add ->
            FIND_BLOCK_CHOICES.forEach { (label, block) -> DropdownMenuItem(text = { Text(label) }, onClick = { add { state.appendFind(block) } }) }
        },
        trailing = {
            // 只列能换算成积木的；换算不了的只在文本模式的下拉里，选了会把人挤到文本模式
            val recents = state.recentSearches.mapNotNull { raw -> regexToFindBlocks(raw)?.takeIf { it.isNotEmpty() }?.let { raw to describeFind(it) } }
            RecentBlocksMenu(recents, enabled, state::useRecentSearch)
        },
    )
}

/** 积木模式的最近使用：列的是每条换算成积木后的说明，选中即换成那组积木。 */
@Composable
private fun RecentBlocksMenu(entries: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Outlined.History, "最近使用", { open = true }, enabled = enabled && entries.isNotEmpty(), modifier = Modifier.size(40.dp))
        PikoDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((raw, description) in entries) {
                DropdownMenuItem(
                    text = { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp)) },
                    onClick = {
                        open = false
                        onPick(raw)
                    },
                )
            }
        }
    }
}

/** 替换的积木条。 */
@Composable
internal fun ReplaceBlockBar(state: BatchRenameState, enabled: Boolean, onSubmit: () -> Unit) {
    val blocks = state.replaceBlocks
    val captureCount = captureNumbers(state.effectiveFindBlocks).count { it != null }
    BlockBar(
        caption = "替换为：" + describeReplace(state.effectiveReplaceBlocks),
        count = blocks.size,
        enabled = enabled,
        placeholder = "输入替换成的文字",
        addLabel = "添加替换积木",
        pendingText = state.pendingReplaceText,
        // 含「${」的文字写不进替换串（见 isExpressibleText），输入框直接不收
        onPendingTextChange = { if (isExpressibleText(it)) state.pendingReplaceText = it },
        onCommit = state::commitPendingText,
        onSubmit = onSubmit,
        onMove = { from, to -> state.updateReplaceBlocks(blocks.toMutableList().apply { add(to, removeAt(from)) }) },
        chip = { index -> BlockChipLabel(chipLabel(blocks[index])) },
        chipColor = { index ->
            val block = blocks[index]
            val source = (block as? ReplaceBlock.Piece)?.number?.let { if (it == 0) null else state.captureBlockIndex(it) }
            if (source != null) blockColor(source) else MaterialTheme.colorScheme.surfaceContainerHighest
        },
        editor = { index, close ->
            ReplaceBlockEditor(
                block = blocks[index],
                captureCount = captureCount,
                onChange = { changed -> state.updateReplaceBlocks(blocks.toMutableList().also { it[index] = changed }) },
                onDelete = {
                    close()
                    state.updateReplaceBlocks(blocks.toMutableList().also { it.removeAt(index) })
                },
            )
        },
        addMenu = { add ->
            for (number in 1..captureCount.coerceAtMost(9)) {
                DropdownMenuItem(text = { Text("片段" + circled(number)) }, onClick = { add { state.appendReplace(ReplaceBlock.Piece(number)) } })
            }
            REPLACE_BLOCK_CHOICES.forEach { (label, block) -> DropdownMenuItem(text = { Text(label) }, onClick = { add { state.appendReplace(block) } }) }
        },
        trailing = {
            val recents = state.recentReplacements.mapNotNull { raw -> templateToReplaceBlocks(raw)?.takeIf { it.isNotEmpty() }?.let { raw to describeReplace(it) } }
            RecentBlocksMenu(recents, enabled, state::useRecentReplacement)
        },
    )
}

private fun BatchRenameState.appendFind(block: FindBlock) {
    commitPendingText()
    updateFindBlocks(findBlocks + block)
}

private fun BatchRenameState.appendReplace(block: ReplaceBlock) {
    commitPendingText()
    updateReplaceBlocks(replaceBlocks + block)
}

// 文字不在这里加：积木条末尾的输入框直接打字就是文字积木
private val FIND_BLOCK_CHOICES = listOf(
    "数字" to FindBlock.Digits(),
    "字母" to FindBlock.Letters(),
    "任意字符" to FindBlock.AnyText(),
    "括号内容" to FindBlock.Bracketed(),
    "若干文字之一" to FindBlock.OneOf(listOf("甲", "乙")),
    "开头" to FindBlock.Start,
    "结尾" to FindBlock.End,
)

private val REPLACE_BLOCK_CHOICES = listOf(
    "整个匹配" to ReplaceBlock.Piece(0),
    "序号" to ReplaceBlock.Counter(start = 1, padding = 2),
    "日期" to ReplaceBlock.Date("YYYY"),
    "随机字符" to ReplaceBlock.RandomText(RandomKind.ALNUM, 8),
    "随机 UUID" to ReplaceBlock.Uuid,
)

/** 积木上写的字：文字积木写文字本身，首尾空格换成可见的符号；其余写说明。 */
internal fun chipLabel(block: FindBlock): String = when (block) {
    is FindBlock.Text -> visibleSpaces(block.text)
    else -> block.describe()
}

internal fun chipLabel(block: ReplaceBlock): String = when (block) {
    is ReplaceBlock.Text -> visibleSpaces(block.text)
    else -> block.describe()
}

// 首尾的空格在积木上看不出来，写成 ␣
private fun visibleSpaces(text: String): String {
    val start = text.length - text.trimStart(' ').length
    val end = text.length - text.trimEnd(' ').length
    if (start == text.length) return "␣".repeat(text.length)
    return "␣".repeat(start) + text.substring(start, text.length - end) + "␣".repeat(end)
}

@Composable
private fun BlockChipLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
}

/**
 * 积木条的共同外形：像输入框一样的描边容器，里面一排可换行的积木、末尾一个输入框和添加按钮。
 *
 * 换序：鼠标按住积木直接拖，触屏上长按后拖（直接拖会和外层的滚动抢手势）；松手时落在哪块积木上就挪到那里。
 * 拖动时其余积木不让位，只在松手时挪一次：边拖边换位，积木在 FlowRow 里换行跳动，按住的那块会跑到手指下面以外。
 * 编辑面板里另有左移、右移，键盘与读屏也能换序。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockBar(
    caption: String,
    count: Int,
    enabled: Boolean,
    placeholder: String,
    addLabel: String,
    inputModifier: Modifier = Modifier,
    pendingText: String,
    onPendingTextChange: (String) -> Unit,
    onCommit: () -> Unit,
    onSubmit: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    chip: @Composable (index: Int) -> Unit,
    chipColor: @Composable (index: Int) -> Color,
    editor: @Composable (index: Int, close: () -> Unit) -> Unit,
    addMenu: @Composable (add: (() -> Unit) -> Unit) -> Unit,
    trailing: @Composable () -> Unit = {},
    note: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.primary) }
        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = Color.Transparent,
            border = BorderStroke(1.dp, colors.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 40.dp),
            ) {
                for (index in 0 until count) {
                    Box(
                        // 手势在平移层外面：放在里面的话，积木跟着手指移动，手势读到的位移会把自己的移动也算进去
                        modifier = Modifier
                            .onGloballyPositioned { bounds[index] = it.boundsInParent() }
                            .zIndex(if (dragging == index) 1f else 0f)
                            .pointerInput(index, enabled) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val start = if (down.type == PointerType.Mouse) {
                                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                    } else {
                                        awaitLongPressOrCancellation(down.id)
                                    } ?: return@awaitEachGesture
                                    val home = bounds[index] ?: return@awaitEachGesture
                                    dragging = index
                                    grab = down.position
                                    dragPointer = home.topLeft + start.position
                                    val finished = drag(start.id) { change ->
                                        dragPointer += change.positionChange()
                                        change.consume()
                                    }
                                    val target = bounds.entries.firstOrNull { it.key < count && it.value.contains(dragPointer) }?.key
                                    dragging = null
                                    if (finished && target != null && target != index) onMove(index, target)
                                }
                            }
                            .graphicsLayer {
                                val home = bounds[index]
                                if (dragging == index && home != null) {
                                    translationX = dragPointer.x - grab.x - home.left
                                    translationY = dragPointer.y - grab.y - home.top
                                }
                            },
                    ) {
                        Surface(
                            onClick = { editing = index },
                            enabled = enabled,
                            shape = RoundedCornerShape(8.dp),
                            color = chipColor(index),
                            contentColor = colors.onSurface,
                            // 中性色的积木与对话框底色只差一级，描一圈边才看得出块的边界
                            border = BorderStroke(1.dp, colors.outlineVariant),
                        ) {
                            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { chip(index) }
                        }
                        PikoDropdownMenu(expanded = editing == index || (editing == LAST && index == count - 1), onDismissRequest = { editing = null }) {
                            Column(
                                modifier = Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                editor(index) { editing = null }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "左移", { onMove(index, index - 1); editing = index - 1 }, enabled = index > 0)
                                    TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowForward, "右移", { onMove(index, index + 1); editing = index + 1 }, enabled = index < count - 1)
                                }
                            }
                        }
                    }
                }
                BasicTextField(
                    value = pendingText,
                    onValueChange = onPendingTextChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pendingText.isEmpty()) onSubmit() else onCommit() }),
                    modifier = inputModifier
                        // 失焦时不收成积木：按下添加按钮的一刻输入框失焦，积木条随之重排，
                        // 按钮在抬起之前挪了位置，这一下就点空了。没收的文字本来就参与预览，加别的积木时再收
                        .widthIn(min = 80.dp)
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (!isEnter || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // 有没收的文字时回车先收成积木，再按一次才执行
                            if (pendingText.isEmpty()) onSubmit() else onCommit()
                            true
                        },
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (pendingText.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            }
                            field()
                        }
                    },
                )
                Box {
                    TooltipIconButton(Icons.Outlined.Add, addLabel, { adding = true }, enabled = enabled, modifier = Modifier.size(40.dp))
                    PikoDropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        addMenu { add ->
                            adding = false
                            add()
                            // 新加的积木排在最后（输入框里的文字先收成了积木，位置此刻还算不准），直接打开它的编辑面板
                            editing = LAST
                        }
                    }
                }
                trailing()
            }
        }
    }
}

private const val LAST = Int.MAX_VALUE

// region 编辑面板

@Composable
private fun FindBlockEditor(block: FindBlock, onChange: (FindBlock) -> Unit, onDelete: () -> Unit) {
    Text(block.describe(), style = MaterialTheme.typography.titleSmall)
    when (block) {
        is FindBlock.Text -> EditorField("文字", block.text) { if (it.isNotEmpty()) onChange(block.copy(text = it)) }
        is FindBlock.Digits -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("最少位数", block.min.toString(), Modifier.weight(1f)) { it.toIntOrNull()?.takeIf { n -> n >= 1 }?.let { n -> onChange(block.copy(min = n)) } }
            NumberField("最多位数", block.max?.toString().orEmpty(), Modifier.weight(1f), placeholder = "不限") {
                onChange(block.copy(max = it.toIntOrNull()?.coerceAtLeast(block.min)))
            }
        }
        is FindBlock.AnyText -> EditorField("直到这个字符之前", block.until?.toString().orEmpty(), placeholder = "不限") {
            onChange(block.copy(until = it.lastOrNull()))
        }
        is FindBlock.Bracketed -> {
            FlowChoices(
                choices = listOf<Pair<BracketKind?, String>>(null to "任意") + BracketKind.entries.map { it to it.label },
                selected = block.kind,
            ) { onChange(block.copy(kind = it)) }
            EditorSwitch("可有可无", block.optional) { onChange(block.copy(optional = it)) }
        }
        is FindBlock.OneOf -> EditorField("候选，每行一个", block.options.joinToString("\n"), singleLine = false) { text ->
            val options = text.lines().filter { it.isNotEmpty() }
            if (options.size >= 2) onChange(block.copy(options = options))
        }
        FindBlock.Start, FindBlock.End, is FindBlock.Letters -> Unit
    }
    if (block != FindBlock.Start && block != FindBlock.End) {
        EditorSwitch("取出，供替换引用", block.capture) { capture ->
            onChange(
                when (block) {
                    is FindBlock.Text -> block.copy(capture = capture)
                    is FindBlock.Digits -> block.copy(capture = capture)
                    is FindBlock.Letters -> block.copy(capture = capture)
                    is FindBlock.AnyText -> block.copy(capture = capture)
                    is FindBlock.Bracketed -> block.copy(capture = capture)
                    is FindBlock.OneOf -> block.copy(capture = capture)
                    FindBlock.Start, FindBlock.End -> block
                },
            )
        }
    }
    DeleteRow(onDelete)
}

@Composable
private fun ReplaceBlockEditor(block: ReplaceBlock, captureCount: Int, onChange: (ReplaceBlock) -> Unit, onDelete: () -> Unit) {
    Text(block.describe(), style = MaterialTheme.typography.titleSmall)
    when (block) {
        is ReplaceBlock.Text -> EditorField("文字", block.text) { if (it.isNotEmpty() && isExpressibleText(it)) onChange(block.copy(text = it)) }
        is ReplaceBlock.Piece -> FlowChoices(
            choices = listOf(0 to "整个匹配") + (1..captureCount.coerceAtMost(9)).map { it to "片段" + circled(it) },
            selected = block.number,
        ) { onChange(ReplaceBlock.Piece(it)) }
        is ReplaceBlock.Counter -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("起始", block.start.toString(), Modifier.weight(1f), allowNegative = true) { it.toIntOrNull()?.let { n -> onChange(block.copy(start = n)) } }
            NumberField("步长", block.increment.toString(), Modifier.weight(1f), allowNegative = true) { it.toIntOrNull()?.let { n -> onChange(block.copy(increment = n)) } }
            NumberField("位数", block.padding.toString(), Modifier.weight(1f)) { onChange(block.copy(padding = it.toIntOrNull() ?: 0)) }
        }
        is ReplaceBlock.Date -> FlowChoices(choices = DATE_TOKEN_CHOICES, selected = block.token) { onChange(ReplaceBlock.Date(it)) }
        is ReplaceBlock.RandomText -> {
            FlowChoices(choices = RandomKind.entries.map { it to it.label }, selected = block.kind) { onChange(block.copy(kind = it)) }
            NumberField("位数", block.length.toString()) { it.toIntOrNull()?.let { n -> onChange(block.copy(length = n.coerceIn(1, 64))) } }
        }
        ReplaceBlock.Uuid -> Unit
    }
    DeleteRow(onDelete)
}

@Composable
private fun EditorField(label: String, value: String, placeholder: String? = null, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    // 输入框自己持有文字：积木只收合法的值（如候选至少两条），不能让输入框跟着积木回弹
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    allowNegative: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            // 只收 ASCII 数字：Char.isDigit 认全角与其他文字的数字，toInt 会失败
            val filtered = input.filterIndexed { index, char -> char in '0'..'9' || (allowNegative && index == 0 && char == '-') }
            text = filtered
            onValueChange(filtered)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowChoices(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in choices) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun EditorSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DeleteRow(onDelete: () -> Unit) {
    DropdownMenuItem(
        text = { Text("删除积木", color = MaterialTheme.colorScheme.error) },
        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onDelete,
    )
}

// endregion
