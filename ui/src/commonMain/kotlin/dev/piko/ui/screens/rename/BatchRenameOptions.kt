package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import dev.piko.shared.rename.BlockGuide
import dev.piko.shared.rename.BlockGuideExample
import dev.piko.shared.rename.RenamePresets
import dev.piko.shared.rename.ReplaceBlock
import androidx.compose.material3.SuggestionChip
import dev.piko.shared.rename.captureNumbers
import dev.piko.shared.rename.circled
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.FindReplaceOptions
import dev.piko.shared.rename.RenameScope
import dev.piko.shared.rename.TextCase
import dev.piko.shared.rename.TimeSource
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes

/**
 * 规则区，照设置页的分段列表分组：开关是带开关的行，互斥的选项是连体按钮组，每组一个小标题。
 * 回车在两个输入框里即执行，与 PowerRename 的「应用」相同；不挂在整个区域上，否则焦点停在开关行上时
 * 回车既切换它又执行。
 *
 * [collapsible] 用于单栏：预览接在规则下面，选项全展开要滚过一整屏才看得到预览，打字时看不到结果。
 * 所以单栏只常驻两个输入框，其余选项收进一行，行上写着眼下生效的选项，点开才展开。
 */
@Composable
internal fun BatchRenameOptions(
    state: BatchRenameState,
    enabled: Boolean,
    searchFocus: FocusRequester,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
) {
    val options = state.options
    var expanded by remember { mutableStateOf(false) }
    fun update(change: FindReplaceOptions.() -> FindReplaceOptions) {
        state.options = state.options.change()
    }

    Column(modifier = modifier) {
        OptionGroup("查找与替换", first = true) {
            if (state.textMode) {
                val patternError = state.patternError
                RenameTextField(
                    value = options.search,
                    onValueChange = { update { copy(search = it) } },
                    label = "查找（正则表达式）",
                    // 提示行常驻、出错只变色：Android 的对话框按内容定高，多出一行整个对话框会跳
                    supporting = when {
                        patternError != null -> "正则表达式有误：$patternError"
                        options.search.isEmpty() -> "留空则仅调整大小写"
                        else -> "替换中以 \$1 至 \$9 引用分组"
                    },
                    isError = patternError != null,
                    enabled = enabled,
                    onSubmit = state::rename,
                    modifier = Modifier.focusRequester(searchFocus),
                ) {
                    RecentMenu(state.recentSearches, enabled, state::useRecentSearch)
                }
                RenameTextField(
                    value = options.replacement,
                    onValueChange = { update { copy(replacement = it) } },
                    label = "替换为",
                    supporting = "可插入序号、随机字符与日期",
                    isError = false,
                    enabled = enabled,
                    onSubmit = state::rename,
                ) {
                    SnippetMenu(useRegex = true, enabled) { snippet -> update { copy(replacement = replacement + snippet) } }
                    RecentMenu(state.recentReplacements, enabled, state::useRecentReplacement)
                }
            } else {
                val replaceFocus = remember { FocusRequester() }
                PresetRow(state, enabled, replaceFocus)
                Spacer(Modifier.height(12.dp))
                FindBlockBar(state, enabled, searchFocus, onSubmit = state::rename)
                Spacer(Modifier.height(8.dp))
                ReplaceBlockBar(state, enabled, replaceFocus, onSubmit = state::rename)
                Spacer(Modifier.height(8.dp))
                BlockGuideSection()
                Spacer(Modifier.height(8.dp))
            }
            // 写法的切换一直露在外面，不随「选项」收起：积木拼不出的，要能马上找到正则
            SwitchRow(
                index = 0,
                count = if (collapsible) 1 else 3,
                title = "正则表达式",
                supporting = state.modeNote ?: if (state.textMode) "关闭可切回积木图形化编辑" else "打开可直接编辑正则文本",
                checked = state.textMode,
                enabled = enabled,
            ) { if (it) state.switchToTextMode() else state.switchToBlockMode() }
            if (!collapsible) MatchRows(options, enabled, first = 1, count = 3, ::update)
        }

        if (collapsible) {
            Spacer(Modifier.height(12.dp))
            MoreOptionsRow(expanded, optionSummary(state)) { expanded = !expanded }
            if (!expanded) return@Column
            OptionGroup("匹配") { MatchRows(options, enabled, first = 0, count = 2, ::update) }
        }

        val detected = state.detected
        val affixes = listOfNotNull(
            detected.prefix.takeIf { it.isNotEmpty() }?.let { Triple("去掉共同开头", it, true) },
            detected.suffix.takeIf { it.isNotEmpty() }?.let { Triple("去掉共同结尾", it, false) },
        )
        if (affixes.isNotEmpty()) {
            OptionGroup("共同部分") {
                affixes.forEachIndexed { index, (title, affix, isPrefix) ->
                    // 首尾的空格在界面上看不出来，用引号框住
                    SwitchRow(index, affixes.size, title, "「$affix」", if (isPrefix) state.stripPrefix else state.stripSuffix, enabled) {
                        if (isPrefix) state.stripPrefix = it else state.stripSuffix = it
                    }
                }
            }
        }

        OptionGroup("范围") {
            ChoiceRow(
                index = 0,
                count = 3,
                title = "应用于",
                supporting = null,
                choices = listOf(RenameScope.NAME to "主名", RenameScope.EXTENSION to "扩展名", RenameScope.FULL to "全名"),
                selected = options.scope,
                enabled = enabled,
            ) { update { copy(scope = it) } }
            SwitchRow(1, 3, "包含文件", null, options.includeFiles, enabled) { update { copy(includeFiles = it) } }
            SwitchRow(2, 3, "包含文件夹", null, options.includeFolders, enabled) { update { copy(includeFolders = it) } }
        }

        OptionGroup("格式") {
            val count = if (state.usesTime) 2 else 1
            ChoiceRow(
                index = 0,
                count = count,
                title = "大小写",
                // 按钮只放得下两个字，完整的说法写在这里
                supporting = CASE_DESCRIPTIONS.getValue(options.textCase),
                choices = CASE_LABELS,
                selected = options.textCase,
                enabled = enabled,
            ) { update { copy(textCase = it) } }
            // 只在替换串里写了日期时才有意义，平时不占地方
            if (state.usesTime) {
                ChoiceRow(
                    index = 1,
                    count = count,
                    title = "日期取自",
                    supporting = null,
                    choices = listOf(TimeSource.CREATED to "创建时间", TimeSource.MODIFIED to "修改时间"),
                    selected = options.timeSource,
                    enabled = enabled,
                ) { update { copy(timeSource = it) } }
            }
        }
    }
}

@Composable
private fun MatchRows(
    options: FindReplaceOptions,
    enabled: Boolean,
    first: Int,
    count: Int,
    update: (FindReplaceOptions.() -> FindReplaceOptions) -> Unit,
) {
    SwitchRow(first, count, "区分大小写", null, options.caseSensitive, enabled) { update { copy(caseSensitive = it) } }
    SwitchRow(first + 1, count, "全部替换", "关闭时仅替换第一处", options.matchAll, enabled) { update { copy(matchAll = it) } }
}

/**
 * 积木用法说明，只在积木模式出现，默认收起，写给不会写正则的人；会写正则的人打开开关就是文本模式，不需要教程。
 * 展开的内容排在规则区里，随规则区一起滚：规则区本来就在可滚动的区域里，对话框高度写死，展开不会让 Android 上的
 * 对话框整体跳动。不做弹层：边看例子边拼积木，弹层会挡住积木条。
 */
@Composable
private fun BlockGuideSection() {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val count = if (open) BlockGuide.size + 1 else 1
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = { open = !open },
            shapes = stableShapes(0, count),
            colors = renameRowColors(),
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
            trailingContent = { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (open) "收起" else "展开") },
            supportingContent = { Text("拼法、取出与从预览生成，附文件名例子") },
            content = { Text("积木用法") },
        )
        if (!open) return@Column
        BlockGuide.forEachIndexed { index, entry ->
            Surface(shape = stableShapes(index + 1, count).shape, color = LocalRenameRowColor.current, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(entry.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    entry.example?.let { GuideExample(it) }
                }
            }
        }
    }
}

/**
 * 起手式一排，放在积木条上面：不会拼积木的人按要做的事挑一个，积木随即换上、预览随即变化，看着结果再改。
 * 要填文字的（加前缀、加后缀）把焦点交给替换条的输入框，接着打字就是前缀。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(state: BatchRenameState, enabled: Boolean, replaceFocus: FocusRequester) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("常用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (preset in RenamePresets) {
            SuggestionChip(
                onClick = {
                    state.applyPreset(preset)
                    if (preset.focusReplace) runCatching { replaceFocus.requestFocus() }
                },
                enabled = enabled,
                label = { Text(preset.label) },
            )
        }
    }
}

/** 例子里的积木照积木条的样子画：同样的颜色、同样的字，读者对照着就能在积木条上拼出来。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideExample(example: BlockGuideExample) {
    val label = MaterialTheme.typography.bodySmall
    val colors = MaterialTheme.colorScheme
    val numbers = captureNumbers(example.find)
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GuideBlocks("查找") {
            example.find.forEachIndexed { index, block ->
                MiniChip(chipLabel(block) + (numbers[index]?.let(::circled) ?: ""), blockColor(index))
            }
        }
        GuideBlocks("替换") {
            if (example.replace.isEmpty()) Text("（留空）", style = label, color = colors.onSurfaceVariant)
            example.replace.forEach { block ->
                val source = (block as? ReplaceBlock.Piece)?.number?.let { number -> numbers.indexOf(number).takeIf { it >= 0 } }
                MiniChip(chipLabel(block), if (source != null) blockColor(source) else colors.surfaceContainerHighest)
            }
        }
        Text("「${example.input}」改为「${example.result}」", style = label, color = colors.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideBlocks(name: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun MiniChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun MoreOptionsRow(expanded: Boolean, summary: String, onToggle: () -> Unit) {
    SegmentedListItem(
        onClick = onToggle,
        shapes = stableShapes(0, 1),
        colors = renameRowColors(),
        trailingContent = {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "收起" else "展开")
        },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        content = { Text("选项") },
    )
}

/** 收起时写在「选项」一行上的摘要：眼下生效的各项，默认的也写，读者不必记得默认是什么。 */
private fun optionSummary(state: BatchRenameState): String {
    val options = state.options
    return buildList {
        if (state.stripPrefix && state.detected.prefix.isNotEmpty()) add("去掉共同开头")
        if (state.stripSuffix && state.detected.suffix.isNotEmpty()) add("去掉共同结尾")
        if (options.caseSensitive) add("区分大小写")
        add(if (options.matchAll) "全部替换" else "仅替换第一处")
        add(
            when (options.scope) {
                RenameScope.NAME -> "应用于主名"
                RenameScope.EXTENSION -> "应用于扩展名"
                RenameScope.FULL -> "应用于全名"
            },
        )
        if (!options.includeFiles) add("不含文件")
        if (!options.includeFolders) add("不含文件夹")
        if (options.textCase != TextCase.NONE) add(CASE_DESCRIPTIONS.getValue(options.textCase))
    }.joinToString("，")
}

private val CASE_LABELS = listOf(
    TextCase.NONE to "原样",
    TextCase.UPPER to "大写",
    TextCase.LOWER to "小写",
    TextCase.TITLE to "标题",
    TextCase.CAPITALIZED to "词首",
)

private val CASE_DESCRIPTIONS = mapOf(
    TextCase.NONE to "保持原样",
    TextCase.UPPER to "全部大写",
    TextCase.LOWER to "全部小写",
    TextCase.TITLE to "标题格式，虚词小写",
    TextCase.CAPITALIZED to "每词首字母大写",
)

/** 一组选项，小标题的样式与间距同设置页的分组。 */
@Composable
private fun OptionGroup(title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = if (first) 8.dp else 24.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), content = content)
}

/**
 * 查找与替换共用的输入框：同高、单行，提示行常驻。行尾按钮的位置两个框一样，
 * 没有最近记录时按钮灰着而不是不画，两个框才对得齐。
 */
@Composable
private fun RenameTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    isError: Boolean,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isEnter || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                onSubmit()
                true
            },
        shape = MaterialTheme.shapes.largeIncreased,
    )
}

@Composable
private fun SwitchRow(
    index: Int,
    count: Int,
    title: String,
    supporting: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = stableShapes(index, count),
        colors = renameRowColors(),
        // 开关只作指示，整行的 checked 语义已由列表项提供
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        content = { Text(title) },
    )
}

/** 标题下面一排连体按钮的分段行，照设置页「深色模式」那一行。 */
@Composable
private fun <T> ChoiceRow(
    index: Int,
    count: Int,
    title: String,
    supporting: String?,
    choices: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = stableShapes(index, count).shape, color = LocalRenameRowColor.current, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                choices.forEachIndexed { choiceIndex, (value, label) ->
                    ToggleButton(
                        checked = value == selected,
                        onCheckedChange = { if (value != selected) onSelect(value) },
                        shapes = connectedToggleShapes(choiceIndex, choices.size),
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

// 选中色与底色取同一值，理由见设置页的 settingsRowColors；底色随对话框形态变，见 LocalRenameRowColor
@Composable
private fun renameRowColors() = LocalRenameRowColor.current.let { ListItemDefaults.segmentedColors(containerColor = it, selectedContainerColor = it) }

/** 各状态同一个形状，理由见设置页的 stableSegmentedShapes。 */
@Composable
private fun stableShapes(index: Int, count: Int): ListItemShapes =
    ListItemDefaults.segmentedShapes(index = index, count = count).let {
        it.copy(selectedShape = it.shape, pressedShape = it.shape, focusedShape = it.shape, hoveredShape = it.shape, draggedShape = it.shape)
    }

/** 最近用过的查找或替换串，照 PowerRename 的下拉历史。 */
@Composable
internal fun RecentMenu(entries: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Outlined.History, "最近使用", { open = true }, enabled = enabled && entries.isNotEmpty())
        PikoDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (entry in entries) {
                DropdownMenuItem(
                    text = { Text(entry, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onPick(entry)
                    },
                )
            }
        }
    }
}

/**
 * 可插入的占位符，照 PowerRename 替换框旁的语法速查，点一下接在替换串末尾。
 * 捕获组引用只在正则模式下有意义，普通文本模式不列。
 */
@Composable
private fun SnippetMenu(useRegex: Boolean, enabled: Boolean, onInsert: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val snippets = buildList {
        add("\${}" to "序号，从 0 开始")
        add("\${start=1,padding=2}" to "两位序号，从 1 开始")
        add("\${start=10,increment=5}" to "序号，从 10 开始，步长 5")
        add("\${rstringalnum=8}" to "8 位随机字母与数字")
        add("\${rstringdigit=6}" to "6 位随机数字")
        add("\${ruuidv4}" to "随机 UUID")
        add("\$YYYY-\$MM-\$DD" to "日期")
        add("\$hh\$mm\$ss" to "时间，24 小时制")
        if (useRegex) {
            add("\$1" to "第一个分组")
            add("\$&" to "整个匹配")
        }
    }
    Box {
        TooltipIconButton(Icons.Outlined.DataObject, "插入占位符", { open = true }, enabled = enabled)
        PikoDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((snippet, description) in snippets) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(snippet, style = MaterialTheme.typography.bodyMedium)
                            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        open = false
                        onInsert(snippet)
                    },
                )
            }
        }
    }
}
