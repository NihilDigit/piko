package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import dev.piko.ui.components.connectedToggleShapes
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import dev.piko.shared.rename.RenamePresets
import dev.piko.shared.rename.RenameScope
import dev.piko.shared.rename.TextCase
import dev.piko.shared.rename.TimeSource
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.TooltipIconButton

/**
 * 规则区，按 M3 的分工用几类控件：两种写法用一组连体按钮切换，几选一用下拉菜单，
 * 开关用复选框（对话框里的改动到点「重命名」才生效，M3 规定这种场合不用开关），常用动作用 assist chip。
 * 几选一试过连体按钮组，左栏排了四组，太重。
 * 分组靠间距，不加小标题；控件不压缩，点击区域保持 48dp（M3 明确不在对话框里提高密度）。
 *
 * 原先照设置页排成分段行，每个开关一行，左栏要翻一页多；后来改成一排可勾选的筛选小块，
 * 但 filter chip 是给过滤内容用的，拿来当设置开关，左栏四种小块混在一起分不出层级。
 * 回车在两个输入框里即执行，与 PowerRename 的「应用」相同；不挂在整个区域上，否则焦点停在复选框上时回车既切换它又执行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchRenameOptions(
    state: BatchRenameState,
    enabled: Boolean,
    searchFocus: FocusRequester,
    changedOnly: Boolean,
    onChangedOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = state.options
    fun update(change: FindReplaceOptions.() -> FindReplaceOptions) {
        state.options = state.options.change()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 写法的切换放在最上面：块拼不出的，要能马上找到正则。左栏只有这一组连体按钮，几选一的选项都用下拉菜单，
        // 四组连体按钮排在一起太重；标签页也试过，与下面的控件不成一体
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(false to "块", true to "正则表达式").forEachIndexed { index, (textMode, label) ->
                ToggleButton(
                    checked = state.textMode == textMode,
                    onCheckedChange = { if (state.textMode != textMode) { if (textMode) state.switchToTextMode() else state.switchToBlockMode() } },
                    shapes = connectedToggleShapes(index, 2),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text(label, maxLines = 1) }
            }
        }
        state.modeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (state.textMode) {
            val patternError = state.patternError
            RenameTextField(
                value = options.search,
                onValueChange = { update { copy(search = it) } },
                label = "查找（正则表达式）",
                // 提示行常驻、出错只变色：Android 的对话框按内容定高，多出一行整个对话框会跳
                supporting = when {
                    patternError != null -> "正则表达式有误：$patternError"
                    options.search.isEmpty() -> "留空将仅调整大小写"
                    else -> "在替换中用 \$1 到 \$9 引用分组"
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
                supporting = "可插入序号、随机字符和日期",
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
            FindBlockBar(state, enabled, searchFocus, onSubmit = state::rename)
            ReplaceBlockBar(state, enabled, replaceFocus, onSubmit = state::rename)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownChoice(
                title = "应用于",
                choices = listOf(RenameScope.NAME to "主名", RenameScope.EXTENSION to "扩展名", RenameScope.FULL to "全名"),
                selected = options.scope,
                enabled = enabled,
            ) { update { copy(scope = it) } }
            // 原先是「包含文件」「包含文件夹」两个开关，两个都关掉没有意义，实为三选一
            DropdownChoice(
                title = "对象",
                choices = listOf(ItemKinds.ALL to "文件和文件夹", ItemKinds.FILES to "仅文件", ItemKinds.FOLDERS to "仅文件夹"),
                selected = ItemKinds.of(options),
                enabled = enabled,
            ) { kinds -> update { copy(includeFiles = kinds != ItemKinds.FOLDERS, includeFolders = kinds != ItemKinds.FILES) } }
            DropdownChoice("大小写", CASE_LABELS, options.textCase, enabled) { update { copy(textCase = it) } }
            // 只在替换串里写了日期时才有意义，平时不占地方
            if (state.usesTime) {
                DropdownChoice(
                    title = "日期取自",
                    choices = listOf(TimeSource.CREATED to "创建时间", TimeSource.MODIFIED to "修改时间"),
                    selected = options.timeSource,
                    enabled = enabled,
                ) { update { copy(timeSource = it) } }
            }
        }

        Column {
            // 开着时常驻，正则文本模式下也能从这里关掉
            if (state.avNaming) {
                CheckboxRow("按番号规范命名", checked = true, enabled) { if (!it) state.stopAvNaming() }
                // 只在用户配了 MetaTube 时出现
                if (state.metaTubeAvailable) {
                    CheckboxRow("片名取自 MetaTube", state.useMetaTubeTitles, enabled, onChange = state::updateUseMetaTubeTitles)
                }
            }
            Row {
                CheckboxRow("区分大小写", options.caseSensitive, enabled, Modifier.weight(1f)) { update { copy(caseSensitive = it) } }
                CheckboxRow("全部替换", options.matchAll, enabled, Modifier.weight(1f)) { update { copy(matchAll = it) } }
            }
            // 首尾的空格在界面上看不出来，用引号框住
            state.detected.prefix.takeIf { it.isNotEmpty() }?.let { prefix ->
                CheckboxRow("移除开头「$prefix」", state.stripPrefix, enabled) { state.stripPrefix = it }
            }
            state.detected.suffix.takeIf { it.isNotEmpty() }?.let { suffix ->
                CheckboxRow("移除结尾「$suffix」", state.stripSuffix, enabled) { state.stripSuffix = it }
            }
            // 只过滤预览，不改规则；放在这里是为了预览卡片里只剩文件名
            CheckboxRow("仅显示变更项", changedOnly, enabled = true, onChange = onChangedOnlyChange)
        }
    }
}

private enum class ItemKinds {
    ALL, FILES, FOLDERS;

    companion object {
        // 两个都关掉的旧值不对应任何一项，三个按钮都不选中，点哪个都能回到有效状态
        fun of(options: FindReplaceOptions): ItemKinds? = when {
            options.includeFiles && options.includeFolders -> ALL
            options.includeFiles -> FILES
            options.includeFolders -> FOLDERS
            else -> null
        }
    }
}

/** 复选框连同文字整行可点，至少 48dp 高。 */
@Composable
internal fun CheckboxRow(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
        )
    }
}

/**
 * 一行下拉选择：标签在左，描边按钮占满其余宽度，点开是菜单，当前项带勾。几项选项统一用它，不各自用连体按钮：
 * 左栏四组连体按钮排在一起太重，菜单项也能写全称（「标题格式，虚词小写」），不必另起一行解释。
 * 不用 ExposedDropdownMenuBox：它自建弹层，桌面端测量途中销毁弹层的崩溃（见 desktopApp/CLAUDE.md）不好排查。
 * [selected] 为 null 时（旧的无效组合）按钮上写「请选择」。
 */
@Composable
private fun <T> DropdownChoice(title: String, choices: List<Pair<T, String>>, selected: T?, enabled: Boolean, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = choices.firstOrNull { it.first == selected }?.second ?: "请选择"
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            PikoDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, label) in choices) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (value == selected) ({ Icon(Icons.Outlined.Check, contentDescription = "当前") }) else null,
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 起手式一排，放在查找条上面：不会拼块的人按要做的事挑一个，块随即换上、预览随即变化，看着结果再改。
 * 要填文字的（添加前缀、添加后缀）把焦点交给替换条的输入框，接着打字就是前缀。
 * 用 assist chip：M3 的 suggestion chip 是给动态生成的建议用的，固定的、动词开头的动作属于 assist。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(state: BatchRenameState, enabled: Boolean, replaceFocus: FocusRequester) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("常用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 所选里有番号才给：多数人的网盘里没有番号，常驻一块只是噪声
        if (state.avNamingAvailable) {
            AssistChip(onClick = state::startAvNaming, enabled = enabled, label = { Text("按番号规范命名") })
        }
        for (preset in RenamePresets) {
            AssistChip(
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

private val CASE_LABELS = listOf(
    TextCase.NONE to "保持原样",
    TextCase.UPPER to "全部大写",
    TextCase.LOWER to "全部小写",
    TextCase.TITLE to "标题格式，虚词小写",
    TextCase.CAPITALIZED to "每词首字母大写",
)

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
