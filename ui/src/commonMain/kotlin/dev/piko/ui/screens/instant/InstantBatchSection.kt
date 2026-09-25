package dev.piko.ui.screens.instant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.InstantBatchRow
import dev.piko.shared.state.InstantBatchRowStatus
import dev.piko.shared.state.InstantBatchState
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize

/**
 * 一次粘进多条链接时的列表。每行一条链接，点开是单条时的工作台，见 [BatchRowDetail]。
 * 保存位置对全部链接生效，底部的「全部保存」按各行自己的路线提交。
 */
@Composable
internal fun ColumnScope.BatchList(
    batch: InstantBatchState,
    state: InstantSheetState,
    notice: String?,
    onPickTarget: () -> Unit,
) {
    Column {
        Text(
            text = "添加链接",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${batch.rows.size} 条链接，点开可勾选文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 与单条时的文件列表一样占去剩下的高度，短列表照常收缩
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.weight(1f, fill = false),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(batch.rows, key = { it.key }) { row ->
                BatchRowItem(
                    row = row,
                    saveError = batch.saveErrors[row.key],
                    enabled = !batch.isSaving,
                    onOpen = { batch.open(row) },
                    onRemove = { batch.remove(row) },
                )
            }
        }
    }

    notice?.let { ErrorBanner(message = it, onRetry = null) }

    if (batch.lacksSpace) {
        ErrorBanner(
            message = "网盘空间不足：需要 ${batch.packBytes.toReadableSize()}，" +
                "剩余 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}",
            onRetry = null,
        )
    }
    TargetRow(
        target = state.target,
        notice = state.targetNotice,
        enabled = !batch.isSaving,
        onClick = onPickTarget,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SaveButton(
            label = "全部保存",
            enabled = batch.canSaveAll,
            isSaving = batch.isSaving,
            onClick = batch::saveAll,
        )
        val skipped = batch.rows.count { it.status == InstantBatchRowStatus.NOTHING_SELECTED }
        val caption = batch.blockedReason ?: "$skipped 项未勾选文件，将跳过".takeIf { skipped > 0 }
        caption?.let { SaveCaption(it) }
    }
}

@Composable
private fun BatchRowItem(
    row: InstantBatchRow,
    saveError: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheet = row.state
    val status = row.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "查看文件", onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatchStatusIcon(status)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            val resolvedName = sheet.resolution?.resource?.name
            Text(
                text = resolvedName ?: row.link.uri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // 未解析时显示的是链接本身，一行即可，多了只是一串 hash
                maxLines = if (resolvedName != null) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            val error = saveError ?: when (status) {
                InstantBatchRowStatus.FAILED -> sheet.errorMessage ?: "解析失败"
                InstantBatchRowStatus.NEEDS_NAME -> "文件夹名为空"
                else -> null
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (status == InstantBatchRowStatus.READY) {
                MetaRow(
                    parts = listOf("已选 ${sheet.selectedEntryCount} / ${sheet.entryCount}", row.selectedBytes.toReadableSize()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = when (status) {
                        InstantBatchRowStatus.RESOLVING -> if (sheet.isAnalyzing) "正在整理文件" else "正在查询云端索引"
                        InstantBatchRowStatus.NOTHING_SELECTED -> "未勾选文件，不保存"
                        InstantBatchRowStatus.WHOLE_OFFLINE ->
                            if (row.link.isMagnet) "云端未收录，整条离线下载" else "整条离线下载"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status == InstantBatchRowStatus.FAILED) {
            TextButton(onClick = sheet::retryResolve) { Text("重试") }
        }
        TooltipIconButton(
            icon = Icons.Outlined.Close,
            label = "移除",
            onClick = onRemove,
            enabled = enabled,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchStatusIcon(status: InstantBatchRowStatus) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            InstantBatchRowStatus.RESOLVING -> InlineLoadingIndicator()
            InstantBatchRowStatus.FAILED, InstantBatchRowStatus.NEEDS_NAME -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            InstantBatchRowStatus.WHOLE_OFFLINE -> Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            InstantBatchRowStatus.READY, InstantBatchRowStatus.NOTHING_SELECTED -> Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 点开的一行：与只粘一条时的工作台相同，只是没有输入框与保存栏，保存统一回列表点。
 * 返回键与 Esc 先回列表，不直接收起面板。
 */
@Composable
internal fun ColumnScope.BatchRowDetail(batch: InstantBatchState, row: InstantBatchRow, notice: String?) {
    BackHandler { batch.closeRow() }
    val sheet = row.state
    val position = batch.rows.indexOf(row) + 1

    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "返回列表",
            onClick = batch::closeRow,
            shortcut = "Esc",
            // 图标贴齐内容左缘，触控区不变
            modifier = Modifier.offset(x = (-12).dp),
        )
        Text(
            text = "第 $position 项，共 ${batch.rows.size} 项",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }

    if (sheet.isResolving) {
        ResolvingRow(text = if (sheet.isAnalyzing) "正在整理文件" else "正在查询云端索引")
    }
    sheet.errorMessage?.let { err ->
        val canRetry = sheet.normalizedMagnet != null && sheet.resolution == null && !sheet.isResolving
        ErrorBanner(message = err, onRetry = if (canRetry) sheet::retryResolve else null)
    }
    notice?.let { ErrorBanner(message = it, onRetry = null) }

    val result = sheet.resolution
    if (result != null) {
        ResolutionSection(state = sheet, resourceName = result.resource.name, showCopyLink = true)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.link.uri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyLinkButton(link = row.link.uri, modifier = Modifier.padding(start = 4.dp).offset(x = 12.dp))
        }
    }
}
