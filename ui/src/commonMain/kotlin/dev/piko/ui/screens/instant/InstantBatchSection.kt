package dev.piko.ui.screens.instant

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

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
    notice: PanelNotice?,
    /** 侧栏形态的标题在面板顶上那一行，这里只留说明。 */
    showTitle: Boolean,
    /** 侧边面板里列表部分占满中间，保存栏钉在底部，与单条时相同。 */
    fillHeight: Boolean,
    saveSettings: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f, fill = fillHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            if (showTitle) {
                Text(
                    text = stringResource(Res.string.text_add_link),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = tr("${batch.rows.size} 条链接，点开可勾选文件", "${enCount(batch.rows.size, "link", "links")}; open to select files", "${batch.rows.size} 件のリンク。開くとファイルを選択できます", "링크 ${batch.rows.size}개. 열어서 파일을 선택하세요"),
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

        notice?.let { NoticeBanner(it) }
    }

    if (batch.lacksSpace) {
        ErrorBanner(
            message = tr("网盘空间不足：需要 ${batch.neededBytes.toReadableSize()}，", "Insufficient cloud space: ${batch.neededBytes.toReadableSize()} required, ", "空き容量不足：${batch.neededBytes.toReadableSize()} 必要、", "클라우드 공간 부족: ${batch.neededBytes.toReadableSize()} 필요, ") +
                tr("剩余 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}", "${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()} available", "空き容量 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}", "남은 공간 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}"),
            onRetry = null,
        )
    } else if (batch.lacksOfflineCount) {
        ErrorBanner(message = tr("需要 ${batch.offlineCount} 次离线，今日还剩 ${batch.offlineLeft} 次", "Requires ${batch.offlineCount} cloud downloads; ${batch.offlineLeft} left today", "${batch.offlineCount} 回のクラウドダウンロードが必要。本日の残り ${batch.offlineLeft} 回", "클라우드 다운로드 ${batch.offlineCount}회 필요, 오늘 ${batch.offlineLeft}회 남음"), onRetry = null)
    }
    saveSettings()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SaveButton(
            label = tr("全部保存", "Save all", "すべて保存", "모두 저장"),
            enabled = batch.canSaveAll,
            isSaving = batch.isSaving,
            onClick = batch::saveAll,
        )
        val skipped = batch.rows.count { it.status == InstantBatchRowStatus.NOTHING_SELECTED }
        // 批量时不逐条确认离线，只在这里写明要用几次，想换成秒传的点开那一行改
        val offline = tr("其中 ${batch.offlineCount} 项需离线", "${batch.offlineCount} require cloud downloads", "うち ${batch.offlineCount} 件はクラウドダウンロードが必要", "그중 ${batch.offlineCount}개는 클라우드 다운로드 필요").takeIf { batch.confirmsOffline && batch.offlineCount > 0 }
        val caption = batch.blockedReason
            ?: listOfNotNull(tr("$skipped 项未勾选文件，将跳过", "${enCount(skipped, "item", "items")} have no selected files and will be skipped", "$skipped 件はファイルが未選択のためスキップ", "${skipped}개 항목에 선택한 파일이 없어 건너뜁니다").takeIf { skipped > 0 }, offline).joinToString("，").ifEmpty { null }
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
            .clickable(onClickLabel = tr("查看文件", "View files", "ファイルを表示", "파일 보기"), onClick = onOpen)
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
                InstantBatchRowStatus.FAILED -> sheet.errorMessage ?: tr("解析失败", "Failed to resolve", "解析に失敗しました", "분석 실패")
                InstantBatchRowStatus.NEEDS_NAME -> tr("文件夹名为空", "Folder name is empty", "フォルダー名が空です", "폴더 이름이 비어 있습니다")
                InstantBatchRowStatus.NEEDS_REVIEW -> sheet.saveBlockReason ?: sheet.saveIssue?.message ?: tr("保存结果需要核对", "Save results need inspection", "保存結果の確認が必要です", "저장 결과 확인 필요")
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
                    parts = listOf(tr("已选 ${sheet.selectedEntryCount} / ${sheet.entryCount}", "Selected ${sheet.selectedEntryCount} / ${sheet.entryCount}", "選択済み ${sheet.selectedEntryCount} / ${sheet.entryCount}", "${sheet.selectedEntryCount} / ${sheet.entryCount}개 선택됨"), row.selectedBytes.toReadableSize()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 与点开后的横幅同一说法：列表里就看得出哪条链接里有慢的，不必逐条点开
                val unindexed = sheet.unindexedEntryCount
                if (unindexed > 0) {
                    Text(
                        text = tr("$unindexed 项$UNINDEXED_HINT", "${enCount(unindexed, "item", "items")}: $UNINDEXED_HINT", "$unindexed 項目：$UNINDEXED_HINT", "${unindexed}개 항목: $UNINDEXED_HINT"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = when (status) {
                        InstantBatchRowStatus.RESOLVING -> resolvingText(sheet)
                        InstantBatchRowStatus.NOTHING_SELECTED -> tr("未勾选文件，不保存", "No files selected; nothing to save", "未選択のため保存しません", "선택한 파일이 없어 저장하지 않습니다")
                        InstantBatchRowStatus.WHOLE_OFFLINE ->
                            if (row.link.isMagnet) tr("未收录，整条离线下载", "Not indexed; download entire link to cloud", "未収録のためリンク全体をクラウドダウンロード", "미등록. 전체 링크 클라우드 다운로드") else tr("整条离线下载", "Download entire link to cloud", "リンク全体をクラウドダウンロード", "전체 링크 클라우드 다운로드")
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status == InstantBatchRowStatus.FAILED) {
            TextButton(onClick = sheet::retryResolve) { Text(stringResource(Res.string.common_retry)) }
        }
        TooltipIconButton(
            icon = Icons.Outlined.Close,
            label = tr("移除", "Remove", "削除", "제거"),
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
            InstantBatchRowStatus.FAILED, InstantBatchRowStatus.NEEDS_NAME, InstantBatchRowStatus.NEEDS_REVIEW -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            // 整条离线同样慢，与文件行上未收录的标记同一图标
            InstantBatchRowStatus.WHOLE_OFFLINE -> Icon(
                imageVector = UnindexedIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
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
internal fun ColumnScope.BatchRowDetail(batch: InstantBatchState, row: InstantBatchRow, notice: PanelNotice?, onReceipt: (String) -> Unit) {
    BackHandler { batch.closeRow() }
    val sheet = row.state
    val position = batch.rows.indexOf(row) + 1

    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = tr("返回列表", "Back to list", "一覧に戻る", "목록으로 돌아가기"),
            onClick = batch::closeRow,
            shortcut = "Esc",
            // 图标贴齐内容左缘，触控区不变
            modifier = Modifier.offset(x = (-12).dp),
        )
        Text(
            text = tr("第 $position 项，共 ${batch.rows.size} 项", "Item $position of ${batch.rows.size}", "$position / ${batch.rows.size} 項目", "$position / ${batch.rows.size}번째 항목"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }

    if (sheet.isResolving) {
        ResolvingRow(text = resolvingText(sheet))
    }
    sheet.errorMessage?.let { err ->
        val canRetry = sheet.normalizedMagnet != null && sheet.resolution == null && !sheet.isResolving
        ErrorBanner(message = err, onRetry = if (canRetry) sheet::retryResolve else null)
    }
    notice?.let { NoticeBanner(it) }

    val result = sheet.resolution
    if (result != null) {
        ResolutionSection(
            state = sheet,
            resourceName = result.resource.name,
            showCopyLink = true,
            onDownloaded = { onReceipt(QUEUED_RECEIPT) },
        )
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
