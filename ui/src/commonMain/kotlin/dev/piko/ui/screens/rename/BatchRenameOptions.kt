package dev.piko.ui.screens.rename

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
            listOf(false to tr("块", "Blocks", "ブロック", "블록"), true to tr("正则表达式", "Regular expression", "正規表現", "정규식")).forEachIndexed { index, (textMode, label) ->
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
                label = tr("查找（正则表达式）", "Find (regular expression)", "検索（正規表現）", "찾기 (정규식)"),
                // 提示行常驻、出错只变色：Android 的对话框按内容定高，多出一行整个对话框会跳
                supporting = when {
                    patternError != null -> tr("正则表达式有误：$patternError", "Invalid regular expression: $patternError", "正規表現が正しくありません：$patternError", "잘못된 정규식: $patternError")
                    options.search.isEmpty() -> tr("留空将仅调整大小写", "Leave empty to change case only", "空欄にすると大文字・小文字のみ変更", "비워 두면 대소문자만 변경")
                    else -> tr("在替换中用 \$1 到 \$9 引用分组", "Use \$1 to \$9 for groups in replacements", "置換で \$1 ～ \$9 を使ってグループを参照", "바꾸기에서 \$1~\$9로 그룹 참조")
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
                label = tr("替换为", "Replace with", "置換後", "바꿀 내용"),
                supporting = tr("可插入序号、随机字符和日期", "Insert counters, random text and dates", "連番・ランダム文字・日時を挿入可能", "번호, 무작위 문자 및 날짜 삽입 가능"),
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
                title = tr("应用于", "Apply to", "適用先", "적용 대상"),
                choices = listOf(RenameScope.NAME to tr("主名", "Base name", "拡張子を除く名前", "기본 이름"), RenameScope.EXTENSION to tr("扩展名", "Extension", "拡張子", "확장자"), RenameScope.FULL to tr("全名", "Full name", "名前全体", "전체 이름")),
                selected = options.scope,
                enabled = enabled,
            ) { update { copy(scope = it) } }
            // 原先是「包含文件」「包含文件夹」两个开关，两个都关掉没有意义，实为三选一
            DropdownChoice(
                title = tr("对象", "Targets", "対象", "대상"),
                choices = listOf(ItemKinds.ALL to tr("文件和文件夹", "Files and folders", "ファイルとフォルダー", "파일 및 폴더"), ItemKinds.FILES to tr("仅文件", "Files only", "ファイルのみ", "파일만"), ItemKinds.FOLDERS to tr("仅文件夹", "Folders only", "フォルダーのみ", "폴더만")),
                selected = ItemKinds.of(options),
                enabled = enabled,
            ) { kinds -> update { copy(includeFiles = kinds != ItemKinds.FOLDERS, includeFolders = kinds != ItemKinds.FILES) } }
            DropdownChoice(tr("大小写", "Letter case", "大文字・小文字", "대소문자"), CASE_LABELS, options.textCase, enabled) { update { copy(textCase = it) } }
            // 只在替换串里写了日期时才有意义，平时不占地方
            if (state.usesTime) {
                DropdownChoice(
                    title = tr("日期取自", "Date source", "日時の取得元", "날짜 기준"),
                    choices = listOf(TimeSource.CREATED to tr("创建时间", "Created", "作成日時", "만든 날짜"), TimeSource.MODIFIED to tr("修改时间", "Modified", "更新日時", "수정한 날짜")),
                    selected = options.timeSource,
                    enabled = enabled,
                ) { update { copy(timeSource = it) } }
            }
        }

        Column {
            // 开着时常驻，正则文本模式下也能从这里关掉
            if (state.avNaming) {
                CheckboxRow(tr("按番号规范命名", "Normalize names by catalog code", "作品番号で名前を整理", "품번으로 이름 정리"), checked = true, enabled) { if (!it) state.stopAvNaming() }
                // 只在用户配了 MetaTube 时出现
                if (state.metaTubeAvailable) {
                    CheckboxRow(tr("片名取自 MetaTube", "Titles from MetaTube", "作品名はMetaTubeから取得", "MetaTube에서 제목 가져오기"), state.useMetaTubeTitles, enabled, onChange = state::updateUseMetaTubeTitles)
                }
            }
            Row {
                CheckboxRow(tr("区分大小写", "Case sensitive", "大文字・小文字を区別", "대소문자 구분"), options.caseSensitive, enabled, Modifier.weight(1f)) { update { copy(caseSensitive = it) } }
                CheckboxRow(tr("全部替换", "Replace all", "すべて置換", "모두 바꾸기"), options.matchAll, enabled, Modifier.weight(1f)) { update { copy(matchAll = it) } }
            }
            // 首尾的空格在界面上看不出来，用引号框住
            state.detected.prefix.takeIf { it.isNotEmpty() }?.let { prefix ->
                CheckboxRow(tr("移除开头「$prefix」", "Remove prefix “$prefix”", "先頭の「$prefix」を削除", "접두사 “$prefix” 제거"), state.stripPrefix, enabled) { state.stripPrefix = it }
            }
            state.detected.suffix.takeIf { it.isNotEmpty() }?.let { suffix ->
                CheckboxRow(tr("移除结尾「$suffix」", "Remove suffix “$suffix”", "末尾の「$suffix」を削除", "접미사 “$suffix” 제거"), state.stripSuffix, enabled) { state.stripSuffix = it }
            }
            // 只过滤预览，不改规则；放在这里是为了预览卡片里只剩文件名
            CheckboxRow(tr("仅显示变更项", "Show changes only", "変更項目のみ表示", "변경 항목만 표시"), changedOnly, enabled = true, onChange = onChangedOnlyChange)
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
                val label = choices.firstOrNull { it.first == selected }?.second ?: tr("请选择", "Please select", "選択してください", "선택하세요")
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            PikoDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, label) in choices) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (value == selected) ({ Icon(Icons.Outlined.Check, contentDescription = tr("当前", "Current", "現在", "현재")) }) else null,
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
        Text(stringResource(Res.string.text_common), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 所选里有番号才给：多数人的网盘里没有番号，常驻一块只是噪声
        if (state.avNamingAvailable) {
            AssistChip(onClick = state::startAvNaming, enabled = enabled, label = { Text(stringResource(Res.string.text_normalize_names_by_catalog_code)) })
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

private val CASE_LABELS get() = listOf(
    TextCase.NONE to tr("保持原样", "Keep unchanged", "変更しない", "변경 안 함"),
    TextCase.UPPER to tr("全部大写", "UPPERCASE", "すべて大文字", "모두 대문자"),
    TextCase.LOWER to tr("全部小写", "lowercase", "すべて小文字", "모두 소문자"),
    TextCase.TITLE to tr("标题格式，虚词小写", "Title case, lowercase minor words", "タイトル形式（助詞などは小文字）", "제목 형식, 기능어 소문자"),
    TextCase.CAPITALIZED to tr("每词首字母大写", "Capitalize each word", "各単語の先頭を大文字", "각 단어 첫 글자 대문자"),
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
        TooltipIconButton(Icons.Outlined.History, tr("最近使用", "Recently used", "最近使用した項目", "최근 사용"), { open = true }, enabled = enabled && entries.isNotEmpty())
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
        add("\${}" to tr("序号，从 0 开始", "Counter starting at 0", "0からの連番", "0부터 시작하는 번호"))
        add("\${start=1,padding=2}" to tr("两位序号，从 1 开始", "Two-digit counter starting at 1", "1からの2桁連番", "1부터 시작하는 2자리 번호"))
        add("\${start=10,increment=5}" to tr("序号，从 10 开始，步长 5", "Counter starting at 10, step 5", "10から5刻みの連番", "10부터 5씩 증가하는 번호"))
        add("\${rstringalnum=8}" to tr("8 位随机字母与数字", "8 random letters and digits", "ランダムな英数字8文字", "무작위 영숫자 8자리"))
        add("\${rstringdigit=6}" to tr("6 位随机数字", "6 random digits", "ランダムな数字6桁", "무작위 숫자 6자리"))
        add("\${ruuidv4}" to tr("随机 UUID", "Random UUID", "ランダムUUID", "무작위 UUID"))
        add("\$YYYY-\$MM-\$DD" to tr("日期", "Day", "日", "일"))
        add("\$hh\$mm\$ss" to tr("时间，24 小时制", "Time, 24-hour format", "時刻（24時間制）", "시간, 24시간제"))
        if (useRegex) {
            add("\$1" to tr("第一个分组", "First group", "最初のグループ", "첫 번째 그룹"))
            add("\$&" to tr("整个匹配", "Entire match", "一致全体", "전체 일치"))
        }
    }
    Box {
        TooltipIconButton(Icons.Outlined.DataObject, tr("插入占位符", "Insert placeholder", "プレースホルダーを挿入", "자리 표시자 삽입"), { open = true }, enabled = enabled)
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
