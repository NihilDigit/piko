package dev.piko.ui.screens.rename

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.rename.BatchRenameMemory
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.BatchRenameState.Phase
import dev.piko.shared.rename.RenamePlan
import dev.piko.shared.rename.RenameProblem
import dev.piko.shared.rename.RenameRow
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.FrameCardShape
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.first

/**
 * 批量重命名，照 PowerToys 的 PowerRename：查找替换（可用正则）、序号与随机字符、日期、大小写格式，逐行预览，
 * 点「重命名」即确认，此前不发任何改名请求。
 *
 * 用对话框，不用侧边面板：预览要并排放原名与新名，侧边面板太窄；执行前所选的一批也不该随着左边的浏览改变。
 * expanded 下规则在左、预览是右边一张卡片；medium 是居中的单栏对话框，compact 全屏，都是规则在上、预览接在下面一起滚。
 * 分区靠底色与圆角，不画分隔线。积木的教程在底栏左下角的问号里。
 * 关闭照 M3：全屏对话框（compact）才在左上角放关闭；浮着的基本对话框不画关闭，底栏「取消」在主按钮左边。
 * Esc 在各档都等于取消。
 *
 * [onFinished] 在执行结束时收到一句结果，由调用方显示并退出多选。全部成功时对话框随即关闭；
 * 有失败或中途停止时留着，列出没改成的项，由用户点「完成」关闭。执行期间不能关闭，
 * 状态的协程作用域随对话框走，关掉就断在半路。
 */
@Composable
fun BatchRenameDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
    onFinished: (message: String) -> Unit,
    /** 打开时即换上「按番号规范命名」，命令面板的同名命令经这里进来。 */
    startWithAvNaming: Boolean = false,
) {
    val services = LocalPikoServices.current
    val (memory, textMode) = rememberRenameMemory() ?: return
    val scope = rememberCoroutineScope()
    val state = remember(files) {
        BatchRenameState(services.driveRepository, services.preferences, scope, files, memory, textMode, startWithAvNaming, services.metaTube)
    }
    CloseWhenFinished(state, onDismiss, onFinished)
    val dismiss = { if (state.phase != Phase.RUNNING) onDismiss() }
    BatchRenameWindow(dismiss) { twoPane, fullscreen ->
        BatchRenameContent(state, tr("批量重命名", "Batch rename", "一括で名前を変更", "이름 일괄 변경", "批次重新命名"), dismiss, twoPane, fullscreen)
    }
}

/** 上次执行时的选项与写法。读出来之前为 null，调用方先不画：先画默认值再跳成上次的，选项会闪一下。 */
@Composable
internal fun rememberRenameMemory(): Pair<BatchRenameMemory, Boolean>? {
    val services = LocalPikoServices.current
    val remembered by produceState<Pair<BatchRenameMemory, Boolean>?>(null) {
        value = BatchRenameMemory.load(services.preferences) to services.preferences.renameRegexTextModeFlow.first()
    }
    return remembered
}

/** 执行结束时交出结果；全部成功即关闭，有失败或中途停止时留着列出没改成的项。 */
@Composable
internal fun CloseWhenFinished(state: BatchRenameState, onDismiss: () -> Unit, onFinished: (message: String) -> Unit) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // 结果与关闭放在同一个协程里依次做：分成两个 effect 的话，对话框可能先关掉，结果就收不到了
    LaunchedEffect(state) {
        state.messages.collect { message ->
            latestOnFinished(message)
            if (state.failures.isEmpty() && !state.wasStopped) latestOnDismiss()
        }
    }
}

/**
 * 批量重命名的外框，按窗口大小取全屏、单栏或两栏的对话框。[content] 收到的是要不要两栏、是不是全屏。
 * 高度 compact 时规则与预览上下叠着放不下，同窄屏一样全屏。
 */
@Composable
internal fun BatchRenameWindow(onDismiss: () -> Unit, content: @Composable (twoPane: Boolean, fullscreen: Boolean) -> Unit) {
    when (if (isHeightCompact()) WidthClass.Compact else currentWidthClass()) {
        WidthClass.Compact -> LocalPikoPlatform.current.FullscreenDialog(
            onDismiss = onDismiss,
            immersive = false,
            systemBarsVisible = true,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                // 底部由底栏自己让开手势横条，底色才能铺到横条下面
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    CompositionLocalProvider(LocalRenameRowColor provides MaterialTheme.colorScheme.surfaceContainer) {
                        content(false, true)
                    }
                }
            }
        }
        WidthClass.Medium -> RenameDialogSurface(onDismiss, maxWidth = 640) { content(false, false) }
        WidthClass.Expanded -> RenameDialogSurface(onDismiss, maxWidth = 1120) { content(true, false) }
    }
}

/**
 * 分段行与预览卡片的底色。照 M3：全屏形态铺在 surface 上，行取 surfaceContainer，与设置页一致；
 * 浮动的对话框本身是 surfaceContainerHigh，行要再高一级才分得出来，取 surfaceContainerHighest。
 */
internal val LocalRenameRowColor = staticCompositionLocalOf { Color.Unspecified }

/** 批量重命名的外框。使用说明也用它，传小一圈的尺寸，盖在上面时看得出是另一层。 */
@Composable
internal fun RenameDialogSurface(onDismiss: () -> Unit, maxWidth: Int, heightFraction: Float = 0.88f, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // 高度写死比例，不随内容变：Android 的对话框按内容定高，预览行数一变整个对话框就跳
            modifier = Modifier
                .widthIn(max = maxWidth.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(heightFraction),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            CompositionLocalProvider(LocalRenameRowColor provides MaterialTheme.colorScheme.surfaceContainerHighest) {
                content()
            }
        }
    }
}

/**
 * [treeSummary] 只给一棵树（[BatchRenameState.isNamingTree]）：扫描的范围，写在规则区的位置上，那里没有规则可调。
 */
@Composable
internal fun BatchRenameContent(
    state: BatchRenameState,
    title: String,
    onClose: () -> Unit,
    twoPane: Boolean,
    fullscreen: Boolean,
    treeSummary: String? = null,
) {
    val focusSearch = !fullscreen && !state.isNamingTree
    // 一棵树里多数项不改名，几千行里找改动的那几行，所以默认只列变更项
    var changedOnly by remember { mutableStateOf(state.isNamingTree) }
    var editing by remember { mutableStateOf<RenameRow?>(null) }
    val searchFocus = remember { FocusRequester() }
    // 触屏上一打开就弹出键盘会盖住预览，只在宽窗口里直接把焦点给查找框
    if (focusSearch) {
        LaunchedEffect(state) {
            // 等一帧：单栏时查找框在懒加载列表里，测量时才组合出来
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
        }
    }
    // 有问题的排在前面：几百项里只有一两项冲突时，不必滚到底去找
    val rows by remember(state) {
        derivedStateOf {
            if (state.phase == Phase.DONE) {
                state.failures.toList()
            } else {
                state.plan.rows
                    .map { row -> state.proposedRow(row) }
                    .filter { !changedOnly || it.isChanged || it.problem != null }
                    .sortedBy { it.problem == null }
            }
        }
    }
    val editable = state.phase == Phase.EDITING
    val showRules = state.phase != Phase.DONE
    val rules: @Composable (Modifier) -> Unit = { modifier ->
        if (treeSummary != null) {
            CanonicalTreeOptions(state, treeSummary, editable, changedOnly, { changedOnly = it }, modifier)
        } else {
            BatchRenameOptions(state, editable, searchFocus, changedOnly, { changedOnly = it }, modifier)
        }
    }
    val onEdit: ((RenameRow) -> Unit)? = if (state.isNamingTree && editable) ({ editing = it }) else null

    Column(modifier = Modifier.fillMaxSize()) {
        RenameTopBar(title, onClose, fullscreen, closeEnabled = state.phase != Phase.RUNNING)
        if (twoPane) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                if (showRules) {
                    rules(
                        Modifier
                            .width(380.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Surface(
                    shape = FrameCardShape,
                    color = LocalRenameRowColor.current,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    // 卡片里只有文件名：不写「原名」「新名」列头，箭头与新名的加粗已分得清两边
                    PreviewList(state, rows, wide = true, segmented = false, contentPadding = PaddingValues(vertical = 8.dp), onEdit, Modifier.fillMaxSize()) {}
                }
            }
        } else {
            PreviewList(state, rows, wide = false, segmented = true, contentPadding = PaddingValues(horizontal = 16.dp), onEdit, Modifier.weight(1f)) {
                if (showRules) {
                    item(key = "rules") { rules(Modifier) }
                }
                item(key = "gap") { Spacer(Modifier.height(24.dp)) }
            }
        }
        RenameActionBar(state, onClose, showCancel = !fullscreen)
    }
    editing?.let { row ->
        EditNewNameDialog(
            row = row,
            overridden = row.source.id in state.overrides,
            onConfirm = { name ->
                state.setOverride(row.source.id, name)
                state.setIncluded(row.source.id, true)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * 标题栏。关闭照 M3：全屏对话框才在左上角放关闭；浮着的基本对话框不画关闭，底栏「取消」在主按钮左边。
 */
@Composable
internal fun RenameTopBar(title: String, onClose: () -> Unit, fullscreen: Boolean, closeEnabled: Boolean = true) {
    PikoTopBar(
        title = title,
        navigationIcon = if (fullscreen) {
            {
                IconButton(onClick = onClose, enabled = closeEnabled) {
                    Icon(Icons.Outlined.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
                }
            }
        } else {
            null
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/**
 * 一棵树里取消勾选的项照样写出勾上时的新名称（[BatchRenameState.proposedName]）：冲突的默认不勾，
 * 不写出来就看不出它本要改成什么。
 */
private fun BatchRenameState.proposedRow(row: RenameRow): RenameRow {
    if (row.source.id !in excludedIds) return row
    val proposed = proposedName(row.source.id) ?: return row
    return row.copy(newName = proposed)
}

/**
 * 预览列表。[header] 放在各行之前，单栏时规则区与计数即由此接进同一个列表一起滚。
 * [segmented] 时每行自带设置页那样的分段底色；两栏时整个列表已在一张卡片里，行不再另上底色。
 * 一棵树里每行另写所在的目录，[onEdit] 不为 null 时行尾可手改新名称。
 */
@Composable
private fun PreviewList(
    state: BatchRenameState,
    rows: List<RenameRow>,
    wide: Boolean,
    segmented: Boolean,
    contentPadding: PaddingValues,
    onEdit: ((RenameRow) -> Unit)?,
    modifier: Modifier,
    header: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            header()
            itemsIndexed(rows, key = { _, row -> row.source.id }) { index, row ->
                // 一棵树没有查找，原名不分查找够得着的部分与够不着的部分
                val plain = state.phase == Phase.DONE || state.isNamingTree
                PreviewRow(
                    row = row,
                    highlights = if (plain) emptyList() else state.highlights(row.source),
                    searchRange = if (plain) row.source.name.indices else state.searchRange(row.source),
                    included = row.source.id !in state.excludedIds,
                    onIncludedChange = if (state.phase == Phase.DONE) null else { checked -> state.setIncluded(row.source.id, checked) },
                    wide = wide,
                    container = if (segmented) ListItemDefaults.segmentedShapes(index = index, count = rows.size).shape else null,
                    location = state.locationOf(row.source.id),
                    // 只给要改名的行：不改的行每行一个按钮只是噪声，要改也多半是规则漏认的番号，交给单个文件的重命名
                    onEdit = onEdit?.takeIf { row.isChanged || row.problem != null }?.let { edit -> { edit(row) } },
                )
                if (segmented && index < rows.lastIndex) Spacer(Modifier.height(ListItemDefaults.SegmentedGap))
            }
            if (state.phase == Phase.EDITING && rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.text_no_changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        LocalPikoPlatform.current.ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * 底栏：左边一句说明，右边是操作，说明写的正是按钮为什么不可用或正在做什么。能执行时不写说明，
 * 按钮上的「重命名 N 项」已经说全了。[showCancel] 用于浮着的对话框：取消在主按钮左边；全屏形态由左上角的关闭代替。
 */
@Composable
private fun RenameActionBar(state: BatchRenameState, onClose: () -> Unit, showCancel: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.phase == Phase.RUNNING) {
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 教程只讲积木，正则文本模式与一棵树（没有积木）不给
            if (state.phase == Phase.EDITING && !state.textMode && !state.isNamingTree) {
                var showGuide by remember { mutableStateOf(false) }
                TooltipIconButton(Icons.AutoMirrored.Outlined.HelpOutline, tr("使用说明", "Instructions", "使い方", "사용 안내", "使用說明"), { showGuide = true })
                if (showGuide) RenameGuideDialog(onDismiss = { showGuide = false })
            }
            val (status, isError) = actionStatus(state)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val count = state.plan.changeCount
            val renameButton: @Composable () -> Unit = {
                Button(onClick = state::rename, enabled = state.canRename, shape = MaterialTheme.shapes.medium) {
                    Text(if (count == 0) tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名") else tr("重命名 $count 项", "Rename ${enCount(count, "item", "items")}", "$count 項目の名前を変更", "${count}개 항목 이름 변경", "重新命名 $count 項"))
                }
            }
            when (state.phase) {
                Phase.EDITING -> {
                    if (state.siblingsFailed) TextButton(onClick = state::loadSiblings) { Text(stringResource(Res.string.common_retry)) }
                    // 查到的照用，其余用原名里的片名
                    if (state.metaTubeProgress != null) TextButton(onClick = state::skipMetaTube) { Text(stringResource(Res.string.text_skip)) }
                    if (showCancel) TextButton(onClick = onClose) { Text(stringResource(Res.string.common_cancel)) }
                    renameButton()
                }
                // 执行中「停止」占「取消」的位置，全屏形态本来没有取消，也放在同一处
                Phase.RUNNING -> {
                    TextButton(onClick = state::stop) { Text(stringResource(Res.string.text_stop)) }
                    renameButton()
                }
                Phase.DONE -> Button(onClick = onClose, shape = MaterialTheme.shapes.medium) { Text(stringResource(Res.string.common_done)) }
            }
        }
    }
}

/**
 * 有问题时底栏的说明。撞上同目录现有名称的与受其牵连的分开报数：只选一部分重新编号时，真正要处理的是那一两个重名，
 * 把它们一并选中或换个起始序号即可，其余会跟着解开。撞上的是哪个名称写在各行里，这里不重复，文件名太长放不下。
 */
private fun problemStatus(plan: RenamePlan): String {
    val taken = plan.rows.count { it.problem == RenameProblem.TAKEN }
    val blocked = plan.rows.count { it.problem == RenameProblem.BLOCKED }
    if (taken == 0) return tr("${plan.problemCount} 项存在命名冲突或格式错误，请修正或取消勾选", "${enCount(plan.problemCount, "item", "items")} have naming conflicts or invalid names. Fix or deselect them", "${plan.problemCount} 項目に名前の競合または形式エラーがあります。修正するか選択を解除してください", "${plan.problemCount}개 항목에 이름 충돌 또는 형식 오류가 있습니다. 수정하거나 선택을 해제해 주세요", "${plan.problemCount} 項存在命名衝突或格式錯誤，請修正或取消勾選")
    val others = plan.problemCount - taken - blocked
    return buildString {
        append(tr("$taken 项与同目录现有文件重名", "${enCount(taken, "item", "items")} conflict with existing names", "$taken 項目が既存の名前と重複", "${taken}개 항목이 기존 파일 이름과 중복", "$taken 項與同一資料夾中的現有檔案同名"))
        if (blocked > 0) append(tr("，另有 $blocked 项因此无法重命名", "; $blocked more cannot be renamed as a result", "、そのためほか $blocked 項目も改名できません", ", 이로 인해 추가 ${blocked}개 항목 이름 변경 불가", "，另有 $blocked 項因此無法重新命名"))
        if (others > 0) append(tr("，$others 项名称无效", "; $others have invalid names", "、$others 項目の名前が無効です", ", ${others}개 항목의 이름이 잘못되었습니다", "，$others 項名稱無效"))
        append(tr("。可同时选中重名的文件，或调整新名称", ". Select conflicting files together or adjust the new names", "。同名ファイルを一緒に選択するか、新しい名前を変更してください", ". 같은 이름의 파일을 함께 선택하거나 새 이름을 수정하세요", "。可同時選取同名的檔案，或調整新名稱"))
    }
}

/** 底栏左边的说明，以及它是否是错误。 */
private fun actionStatus(state: BatchRenameState): Pair<String, Boolean> {
    val plan = state.plan
    return when (state.phase) {
        Phase.RUNNING -> tr("正在重命名 ${state.processed}/${state.total}", "Renaming ${state.processed}/${state.total}", "名前を変更中 ${state.processed}/${state.total}", "이름 변경 중 ${state.processed}/${state.total}", "正在重新命名 ${state.processed}/${state.total}") to false
        Phase.DONE -> {
            val succeeded = state.processed - state.failures.size
            when {
                state.wasStopped -> tr("已停止，已重命名 $succeeded 项", "Stopped; renamed ${enCount(succeeded, "item", "items")}", "停止しました。$succeeded 項目の名前を変更しました", "중지됨. ${succeeded}개 항목 이름 변경 완료", "已停止，已重新命名 $succeeded 項") to false
                state.failures.isEmpty() -> tr("已重命名 $succeeded 项", "Renamed ${enCount(succeeded, "item", "items")}", "$succeeded 項目の名前を変更しました", "${succeeded}개 항목 이름 변경 완료", "已重新命名 $succeeded 項") to false
                else -> tr("已重命名 $succeeded 项，${state.failures.size} 项失败", "Renamed ${enCount(succeeded, "item", "items")}; ${state.failures.size} failed", "$succeeded 項目を改名、${state.failures.size} 項目失敗", "${succeeded}개 이름 변경 완료, ${state.failures.size}개 실패", "已重新命名 $succeeded 項，${state.failures.size} 項失敗") to true
            }
        }
        Phase.EDITING -> when {
            state.patternError != null -> tr("正则表达式有误", "Invalid regular expression", "正規表現が正しくありません", "잘못된 정규식", "正規表示式有誤") to true
            state.metaTubeProgress != null -> state.metaTubeProgress!!.let { (done, total) -> tr("正在从 MetaTube 查询片名 $done/$total", "Fetching titles from MetaTube $done/$total", "MetaTubeから作品名を取得中 $done/$total", "MetaTube에서 제목 조회 중 $done/$total", "正在從 MetaTube 查詢片名 $done/$total") } to false
            state.isCheckingSiblings -> tr("正在检查同目录名称", "Checking sibling names", "同じフォルダーの名前を確認中", "같은 폴더의 이름 확인 중", "正在檢查同一資料夾中的名稱") to false
            state.siblingsFailed -> tr("无法检查同目录名称", "Unable to check sibling names", "同じフォルダーの名前を確認できません", "같은 폴더의 이름을 확인할 수 없습니다", "無法檢查同一資料夾中的名稱") to true
            plan.problemCount > 0 -> problemStatus(plan) to true
            plan.changeCount == 0 -> tr("无需重命名", "No rename needed", "名前の変更は不要です", "이름 변경 불필요", "無需重新命名") to false
            // 查不成的用了原名里的片名，说一句，免得以为都查到了
            state.useMetaTubeTitles && state.metaTubeFailed > 0 -> tr("${state.metaTubeFailed} 个番号查询失败，沿用原名中的片名", "${state.metaTubeFailed} code lookups failed; keeping original titles", "${state.metaTubeFailed} 件の作品番号の照会に失敗。元の作品名を保持", "품번 ${state.metaTubeFailed}개 조회 실패. 기존 제목 유지", "${state.metaTubeFailed} 個番號查詢失敗，沿用原名中的片名") to false
            else -> "" to false
        }
    }
}
