package dev.piko.ui.screens.rename

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
 * 分区靠底色与圆角，不画分隔线：规则是设置页那样的分段行，预览是一张卡片。
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
) {
    val services = LocalPikoServices.current
    // 上次的选项读出来之前不画：先画默认值再跳成上次的，选项会闪一下
    val remembered by produceState<Pair<BatchRenameMemory, Boolean>?>(null) {
        value = BatchRenameMemory.load(services.preferences) to services.preferences.renameRegexTextModeFlow.first()
    }
    val (memory, textMode) = remembered ?: return
    val scope = rememberCoroutineScope()
    val state = remember(files) { BatchRenameState(services.driveRepository, services.preferences, scope, files, memory, textMode) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // 结果与关闭放在同一个协程里依次做：分成两个 effect 的话，对话框可能先关掉，结果就收不到了
    LaunchedEffect(state) {
        state.messages.collect { message ->
            latestOnFinished(message)
            if (state.failures.isEmpty() && !state.wasStopped) latestOnDismiss()
        }
    }
    val dismiss = { if (state.phase != Phase.RUNNING) onDismiss() }

    // 高度 compact 时规则与预览上下叠着放不下，同窄屏一样全屏
    when (if (isHeightCompact()) WidthClass.Compact else currentWidthClass()) {
        WidthClass.Compact -> LocalPikoPlatform.current.FullscreenDialog(
            onDismiss = dismiss,
            immersive = false,
            systemBarsVisible = true,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                // 底部由底栏自己让开手势横条，底色才能铺到横条下面
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    CompositionLocalProvider(LocalRenameRowColor provides MaterialTheme.colorScheme.surfaceContainer) {
                        BatchRenameContent(state, dismiss, twoPane = false, fullscreen = true)
                    }
                }
            }
        }
        WidthClass.Medium -> RenameDialogSurface(dismiss, maxWidth = 640) {
            BatchRenameContent(state, dismiss, twoPane = false, fullscreen = false)
        }
        WidthClass.Expanded -> RenameDialogSurface(dismiss, maxWidth = 1120) {
            BatchRenameContent(state, dismiss, twoPane = true, fullscreen = false)
        }
    }
}

/**
 * 分段行与预览卡片的底色。照 M3：全屏形态铺在 surface 上，行取 surfaceContainer，与设置页一致；
 * 浮动的对话框本身是 surfaceContainerHigh，行要再高一级才分得出来，取 surfaceContainerHighest。
 */
internal val LocalRenameRowColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
private fun RenameDialogSurface(onDismiss: () -> Unit, maxWidth: Int, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // 高度写死比例，不随内容变：Android 的对话框按内容定高，预览行数一变整个对话框就跳
            modifier = Modifier
                .widthIn(max = maxWidth.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            CompositionLocalProvider(LocalRenameRowColor provides MaterialTheme.colorScheme.surfaceContainerHighest) {
                content()
            }
        }
    }
}

@Composable
private fun BatchRenameContent(state: BatchRenameState, onClose: () -> Unit, twoPane: Boolean, fullscreen: Boolean) {
    val focusSearch = !fullscreen
    var changedOnly by remember { mutableStateOf(false) }
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
                    .filter { !changedOnly || it.isChanged || it.problem != null }
                    .sortedBy { it.problem == null }
            }
        }
    }
    val editable = state.phase == Phase.EDITING
    val showRules = state.phase != Phase.DONE

    Column(modifier = Modifier.fillMaxSize()) {
        PikoTopBar(
            title = "批量重命名",
            navigationIcon = if (fullscreen) {
                {
                    IconButton(onClick = onClose, enabled = state.phase != Phase.RUNNING) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
            } else {
                null
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        if (twoPane) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                if (showRules) {
                    BatchRenameOptions(
                        state = state,
                        enabled = editable,
                        searchFocus = searchFocus,
                        modifier = Modifier
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
                    Column {
                        PreviewHeader(state, changedOnly, { changedOnly = it }, Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp))
                        PreviewColumnHeader(Modifier.padding(bottom = 4.dp))
                        PreviewList(state, rows, wide = true, segmented = false, contentPadding = PaddingValues(bottom = 8.dp), modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        } else {
            PreviewList(state, rows, wide = false, segmented = true, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.weight(1f)) {
                if (showRules) {
                    item(key = "rules") {
                        BatchRenameOptions(state = state, enabled = editable, searchFocus = searchFocus, collapsible = true)
                    }
                }
                item(key = "header") {
                    PreviewHeader(state, changedOnly, { changedOnly = it }, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
                }
            }
        }
        RenameActionBar(state, onClose, showCancel = !fullscreen)
    }
}

/**
 * 预览列表。[header] 放在各行之前，单栏时规则区与计数即由此接进同一个列表一起滚。
 * [segmented] 时每行自带设置页那样的分段底色；两栏时整个列表已在一张卡片里，行不再另上底色。
 */
@Composable
private fun PreviewList(
    state: BatchRenameState,
    rows: List<RenameRow>,
    wide: Boolean,
    segmented: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier,
    header: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            header()
            itemsIndexed(rows, key = { _, row -> row.source.id }) { index, row ->
                PreviewRow(
                    row = row,
                    highlights = if (state.phase == Phase.DONE) emptyList() else state.highlights(row.source),
                    included = row.source.id !in state.excludedIds,
                    onIncludedChange = if (state.phase == Phase.DONE) null else { checked -> state.setIncluded(row.source.id, checked) },
                    wide = wide,
                    container = if (segmented) ListItemDefaults.segmentedShapes(index = index, count = rows.size).shape else null,
                    selectionActions = if (state.phase != Phase.EDITING) {
                        null
                    } else {
                        SelectionActions(
                            propose = { range, edit, text -> state.proposeSelection(row.source, range, edit, text) },
                            apply = state::applySelection,
                            replacesRule = state.effectiveOptions.search.isNotEmpty() || state.effectiveOptions.replacement.isNotEmpty(),
                        )
                    },
                )
                if (segmented && index < rows.lastIndex) Spacer(Modifier.height(ListItemDefaults.SegmentedGap))
            }
            if (state.phase == Phase.EDITING && rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "无变更项",
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
                    Text(if (count == 0) "重命名" else "重命名 $count 项")
                }
            }
            when (state.phase) {
                Phase.EDITING -> {
                    if (state.siblingsFailed) TextButton(onClick = state::loadSiblings) { Text("重试") }
                    if (showCancel) TextButton(onClick = onClose) { Text("取消") }
                    renameButton()
                }
                // 执行中「停止」占「取消」的位置，全屏形态本来没有取消，也放在同一处
                Phase.RUNNING -> {
                    TextButton(onClick = state::stop) { Text("停止") }
                    renameButton()
                }
                Phase.DONE -> Button(onClick = onClose, shape = MaterialTheme.shapes.medium) { Text("完成") }
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
    if (taken == 0) return "${plan.problemCount} 项存在问题，修正或取消勾选后方可执行"
    val others = plan.problemCount - taken - blocked
    return buildString {
        append("$taken 项与同目录现有文件重名")
        if (blocked > 0) append("，另有 $blocked 项受其牵连")
        if (others > 0) append("，$others 项另有问题")
        append("。可一并选中重名的文件，或调整新名称")
    }
}

/** 底栏左边的说明，以及它是否是错误。 */
private fun actionStatus(state: BatchRenameState): Pair<String, Boolean> {
    val plan = state.plan
    return when (state.phase) {
        Phase.RUNNING -> "正在重命名 ${state.processed}/${state.total}" to false
        Phase.DONE -> {
            val succeeded = state.processed - state.failures.size
            if (state.wasStopped) "已停止，已重命名 $succeeded 项" to false else "已重命名 $succeeded 项，${state.failures.size} 项失败" to true
        }
        Phase.EDITING -> when {
            state.patternError != null -> "正则表达式有误" to true
            state.isCheckingSiblings -> "正在检查同目录名称" to false
            state.siblingsFailed -> "无法检查同目录名称" to true
            plan.problemCount > 0 -> problemStatus(plan) to true
            plan.changeCount == 0 -> "无需改名" to false
            else -> "" to false
        }
    }
}
