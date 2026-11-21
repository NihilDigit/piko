package dev.piko.ui.screens.instant

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.ImportTreeNode
import dev.piko.shared.presentation.ImportTreeSourceVersion
import dev.piko.shared.presentation.importTree
import dev.piko.shared.presentation.visibleImportTree
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.ShareNodeCheckState
import dev.piko.shared.state.ShareTreeBrowser
import dev.piko.shared.state.ShareTreeRow
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileItemName
import dev.piko.ui.components.FileItemSelection
import dev.piko.ui.components.FileItemSelectionControl
import dev.piko.ui.components.FilePresentation
import dev.piko.ui.components.FileVisual
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.WorkspaceTreeRow
import dev.piko.ui.components.filePresentation
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.components.typeIcon
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*
import dev.piko.ui.theme.LocalStatusColors
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.delay

private const val LARGE_TREE_AUTO_COLLAPSE_THRESHOLD = 200
private const val MAX_AUTO_EXPAND_TOP_FOLDERS = 20

// 每深一层缩进这么多，让子项的复选框落在上一层名字的起点附近
private val TreeIndent = 16.dp

private fun defaultExpandedImportKeys(nodes: List<ImportTreeNode>): Set<String> {
    if (nodes.size <= LARGE_TREE_AUTO_COLLAPSE_THRESHOLD) {
        return nodes.filter { it.isFolder }.mapTo(HashSet()) { it.key }
    }
    val topFolders = nodes.filter { it.isFolder && it.depth == 0 }
    return if (topFolders.size <= MAX_AUTO_EXPAND_TOP_FOLDERS) {
        topFolders.mapTo(HashSet()) { it.key }
    } else {
        emptySet()
    }
}

@Composable
internal fun ShareTreeProgressBanner(
    browser: ShareTreeBrowser,
    onCancel: () -> Unit,
) {
    val vault = browser.vaultProgress
    val restore = browser.restoreProgress
    if (vault == null && restore == null) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineLoadingIndicator(color = MaterialTheme.colorScheme.onSecondaryContainer)
                val label = when {
                    vault != null -> tr(
                        "正在流式保存引用：已扫描 ${vault.foldersScanned} 个文件夹，已保存 ${vault.filesSaved} / ${vault.filesCollected} 个文件",
                        "Streaming references: ${vault.foldersScanned} folders scanned, ${vault.filesSaved} / ${vault.filesCollected} files saved",
                        "参照を保存中：${vault.foldersScanned} フォルダー確認済み、${vault.filesSaved} / ${vault.filesCollected} ファイル保存済み",
                        "참조 저장 중: 폴더 ${vault.foldersScanned}개 확인, 파일 ${vault.filesSaved} / ${vault.filesCollected}개 저장됨",
                        "正在串流儲存引用：已掃描 ${vault.foldersScanned} 個資料夾，已儲存 ${vault.filesSaved} / ${vault.filesCollected} 個檔案",
                    )
                    restore != null -> tr(
                        "正在云端转存：第 ${restore.completedBatches} / ${restore.totalBatches} 批（已提交 ${restore.submittedItems} 项）",
                        "Saving in cloud: batch ${restore.completedBatches} / ${restore.totalBatches} (${restore.submittedItems} items)",
                        "クラウドに保存中：${restore.completedBatches} / ${restore.totalBatches} バッチ（${restore.submittedItems} 項目）",
                        "클라우드 저장 중: ${restore.completedBatches} / ${restore.totalBatches} 배치 (${restore.submittedItems}개 항목)",
                        "正在雲端轉存：第 ${restore.completedBatches} / ${restore.totalBatches} 批（已提交 ${restore.submittedItems} 項）",
                    )
                    else -> ""
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.tabular,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
            if (restore != null && restore.totalBatches > 0) {
                LinearProgressIndicator(
                    progress = { (restore.completedBatches.toFloat() / restore.totalBatches).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 右侧显示原始文件目录树，默认勾选需要的条目，支持展开目录精细调整。 */
@Composable
internal fun FileTreeList(state: InstantSheetState, onDownloaded: () -> Unit, modifier: Modifier = Modifier, retainedListState: LazyListState? = null) {
    val shareBrowser = state.shareTree?.takeIf { it.isLazyMode }
    if (shareBrowser != null) {
        ShareTreeList(
            browser = shareBrowser,
            state = state,
            onDownloaded = onDownloaded,
            modifier = modifier,
            retainedListState = retainedListState,
        )
        return
    }
    val version = remember(state.resolution) {
        ImportTreeSourceVersion("single", state.resolution?.resource?.name ?: state.input, state.resolution?.resource?.files)
    }
    val nodes = remember(version) {
        importTree(version.files.orEmpty().map { it.path })
    }
    var expanded by remember(version) { mutableStateOf(defaultExpandedImportKeys(nodes)) }
    val filtering = state.isUnindexedFilterActive
    val visible = remember(nodes, expanded, filtering, state.items) {
        val base = visibleImportTree(nodes, expanded, "")
        if (!filtering) base else base.filter { node -> node.indices.any { !state.items[it].isInstantReady } }
    }
    val hasBranches = visible.any { it.isFolder }
    val editable = state.canEdit
    val selection = state.sourceSelection

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        val listState = retainedListState ?: rememberLazyListState()
        var previousFilter by rememberSaveable { mutableStateOf(filtering) }
        LaunchedEffect(filtering) {
            if (previousFilter != filtering) {
                listState.scrollToItem(0)
                previousFilter = filtering
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(visible, key = { it.key }) { node ->
                val selected = selection.countIn(node.indices)
                val checked = when (selected) {
                    0 -> ToggleableState.Off
                    node.indices.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val index = node.fileIndex
                val toggle = { expanded = if (node.key in expanded) expanded - node.key else expanded + node.key }
                val item = if (index == null) FilePresentation(
                    node.key, FileItemName(node.name), FileVisual(node.name, isFolder = true),
                    metadata = listOf(tr("${node.indices.size} 个文件", "${enCount(node.indices.size, "file", "files")}", "${node.indices.size} ファイル", "파일 ${node.indices.size}개", "${node.indices.size} 個檔案")),
                ) else state.items[index].file.filePresentation(node.key)
                val open = { if (node.isFolder) toggle() else if (editable) state.setSourceSelected(node.indices, selected == 0) }
                WorkspaceTreeRow(
                    name = node.name,
                    depth = node.depth,
                    icon = item.visual.typeIcon(),
                    expanded = if (node.isFolder) node.key in expanded else null,
                    onExpand = toggle,
                    onOpen = open,
                    onLongClick = null,
                    item = item,
                    reserveExpandSpace = hasBranches,
                    leading = {
                        FileItemSelectionControl(
                            FileItemSelection(
                                checked,
                                { if (editable) state.setSourceSelected(node.indices, checked != ToggleableState.On) },
                                enabled = editable,
                                label = tr("选择来源 ${node.name}", "Select source ${node.name}", "ソース ${node.name} を選択", "원본 ${node.name} 선택", "選取來源 ${node.name}"),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun ShareTreeList(
    browser: ShareTreeBrowser,
    state: InstantSheetState,
    onDownloaded: () -> Unit,
    modifier: Modifier = Modifier,
    retainedListState: LazyListState? = null,
) {
    val services = LocalPikoServices.current
    val downloads = rememberDownloadLauncher()
    val editable = state.canEdit
    val rows = remember(browser, browser.revision) { browser.visibleRows() }
    val hasBranches = remember(rows) { rows.any { it is ShareTreeRow.NodeRow && it.node.isFolder } }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        val listState = retainedListState ?: rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ShareTreeRow.NodeRow -> {
                        val node = row.node
                        val checked = when (row.checkState) {
                            ShareNodeCheckState.OFF -> ToggleableState.Off
                            ShareNodeCheckState.ON -> ToggleableState.On
                            ShareNodeCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                        }
                        val presentation = if (node.isFolder) {
                            FilePresentation(
                                key = node.id,
                                name = FileItemName(node.name),
                                visual = FileVisual(node.name, isFolder = true),
                                metadata = if (row.isLoading) {
                                    listOf(tr("加载中…", "Loading…", "読み込み中…", "불러오는 중…", "載入中…"))
                                } else emptyList(),
                            )
                        } else {
                            ResolvedFile(node.relativePath, node.sizeBytes, node.hash.ifBlank { null }).filePresentation(node.id)
                        }
                        val canPreview = state.canPreviewShareNode(node)
                        val canDownload = !node.isFolder && node.hash.isNotBlank()
                        WorkspaceTreeRow(
                            name = node.name,
                            depth = row.depth,
                            icon = presentation.visual.typeIcon(),
                            expanded = if (node.isFolder) row.expanded else null,
                            onExpand = { browser.toggleExpand(node.id) },
                            onOpen = {
                                if (node.isFolder) browser.toggleExpand(node.id)
                                else if (editable) browser.toggleNodeSelected(node)
                            },
                            onLongClick = null,
                            item = presentation,
                            reserveExpandSpace = hasBranches,
                            leading = {
                                FileItemSelectionControl(
                                    FileItemSelection(
                                        checked,
                                        { if (editable) browser.toggleNodeSelected(node) },
                                        enabled = editable,
                                        label = tr("选择来源 ${node.name}", "Select source ${node.name}", "ソース ${node.name} を選択", "원본 ${node.name} 선택", "選取來源 ${node.name}"),
                                    ),
                                )
                            },
                            trailing = {
                                if (row.isLoading) {
                                    InlineLoadingIndicator(modifier = Modifier.padding(end = 8.dp))
                                }
                                if (canPreview) {
                                    PreviewButton(
                                        onClick = { state.previewShareNode(node) },
                                        isPreviewing = state.previewingShareNodeId == node.id,
                                    )
                                }
                                if (canDownload) {
                                    DownloadContentButton(
                                        onClick = {
                                            downloads.downloadResolved(
                                                listOf(ResolvedFile(node.name, node.sizeBytes, node.hash)),
                                                state.target?.id.orEmpty(),
                                            ) { notice ->
                                                notice?.let(services::noticeOnTransfers)
                                                onDownloaded()
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                    is ShareTreeRow.LoadMoreRow -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !row.isLoading) { browser.loadMore(row.parentId) }
                                .padding(start = 16.dp + TreeIndent * row.depth, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (row.isLoading) {
                                InlineLoadingIndicator()
                                Text(
                                    text = tr("正在加载更多…", "Loading more…", "さらに読み込み中…", "더 불러오는 중…", "正在載入更多…"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = row.errorMessage ?: tr("加载更多…", "Load more…", "さらに読み込む…", "더 불러오기…", "載入更多…"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (row.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    is ShareTreeRow.ErrorRow -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp + TreeIndent * row.depth, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = row.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { browser.retryFolder(row.parentId) }) {
                                Text(stringResource(Res.string.common_retry))
                            }
                        }
                    }
                    is ShareTreeRow.EmptyRow -> {
                        Text(
                            text = tr("空文件夹", "Empty folder", "空のフォルダー", "빈 폴더", "空資料夾"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp + TreeIndent * row.depth, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 复制磁力链的图标按钮。用链接图标而不是剪贴板：剪贴板图标挨着标题，读起来像「复制标题」；
 * 链接图标说明复制的是什么。长按出提示文字，点完换成对勾一秒半作为回执，
 * 系统在 Android 13 以下不提示已复制。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CopyLinkButton(link: String, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    // 批量列表里也有 http 与 ed2k 链接
    val kind = if (link.startsWith("magnet:", ignoreCase = true)) tr("磁力链接", "Magnet link", "マグネットリンク", "마그넷 링크", "磁力連結") else tr("链接", "Link", "リンク", "링크", "連結")
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tr("复制$kind", "Copy $kind", "$kind をコピー", "$kind 복사", "複製$kind")) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = {
            platform.copyToClipboard(kind, link)
            copied = true
        }) {
            Crossfade(
                targetState = copied,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "copy-link",
            ) { done ->
                Icon(
                    imageVector = if (done) Icons.Outlined.Check else Icons.Outlined.Link,
                    contentDescription = if (done) tr("已复制", "Copied", "コピーしました", "복사했습니다", "已複製") else tr("复制$kind", "Copy $kind", "$kind をコピー", "$kind 복사", "複製$kind"),
                    tint = if (done) LocalStatusColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 预览按钮。秒传进 Piko-Temp 要一两秒，期间换成转圈，免得连点。 */
@Composable
internal fun PreviewButton(onClick: () -> Unit, isPreviewing: Boolean) {
    if (isPreviewing) {
        Box(modifier = Modifier.padding(start = 4.dp).size(48.dp), contentAlignment = Alignment.Center) {
            InlineLoadingIndicator()
        }
    } else {
        TooltipIconButton(
            icon = Icons.Outlined.PlayCircle,
            label = tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"),
            onClick = onClick,
            modifier = Modifier.padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DownloadContentButton(onClick: () -> Unit) {
    TooltipIconButton(
        Icons.Outlined.CloudDownload,
        tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"),
        onClick,
        modifier = Modifier.padding(start = 4.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
