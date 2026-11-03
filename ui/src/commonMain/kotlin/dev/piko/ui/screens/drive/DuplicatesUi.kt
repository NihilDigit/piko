package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount
import dev.piko.shared.presentation.localizedName

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.presentation.DuplicateReportViewModel
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize

/**
 * 查重行的位置相对扫描起点；保留建议由组内排序后的行标签呈现，避免与当前策略冲突。
 */
internal fun duplicateLocations(finder: DuplicateFinderState?): Map<String, String> {
    val report = finder?.report ?: return emptyMap()
    return (report.identical + report.versions).flatMap { it.rows }.associate { row ->
        val folder = row.file.folderPath
        val location = if (folder.isEmpty()) finder.root.localizedName else "${finder.root.localizedName}/$folder"
        row.file.id to location
    }
}

/** 宽窗口里查重标签上的样子：扫描中转圈，悬停说明进度或结果。 */
internal fun duplicatesTab(finder: DuplicateFinderState): SessionTab {
    val groups = finder.report.identical.size + finder.report.versions.size
    val tooltip = when {
        finder.isScanning -> tr("已扫描 ${finder.scannedFolders} 个文件夹", "Scanned ${enCount(finder.scannedFolders, "folder", "folders")}", "${finder.scannedFolders} フォルダーを検索済み", "폴더 ${finder.scannedFolders}개 검색 완료", "已掃描 ${finder.scannedFolders} 個資料夾")
        groups == 0 -> tr("未发现重复文件", "No duplicate files found", "重複ファイルはありません", "중복 파일이 없습니다", "未發現重複檔案")
        else -> tr("$groups 组重复文件", "$groups duplicate groups", "$groups 組の重複ファイル", "중복 파일 ${groups}개 그룹", "$groups 組重複檔案")
    }
    return SessionTab(tr("查重：${finder.root.localizedName}", "Duplicates: ${finder.root.localizedName}", "重複検索：${finder.root.localizedName}", "중복 검색: ${finder.root.localizedName}", "查重：${finder.root.localizedName}"), tooltip, busy = finder.isScanning, icon = Icons.Outlined.FileCopy)
}

internal fun duplicateCommandBarLeading(
    finder: DuplicateFinderState,
    selectedKind: DuplicateKind,
    onKindChange: (DuplicateKind) -> Unit,
    reportView: DuplicateReportViewModel,
    selectedIds: Set<String>,
    onSelectIds: (Set<String>) -> Unit,
): List<BarItem> {
    val identical = selectedKind == DuplicateKind.IDENTICAL
    val groups = if (identical) finder.report.identical else finder.report.versions
    val summary = if (identical) {
        tr("${groups.size} 组，可释放 ${groups.sumOf { it.reclaimableBytes }.toReadableSize()}", "${enCount(groups.size, "group", "groups")}; ${groups.sumOf { it.reclaimableBytes }.toReadableSize()} reclaimable", "${groups.size} 組、${groups.sumOf { it.reclaimableBytes }.toReadableSize()} 解放可能", "${groups.size}개 그룹, ${groups.sumOf { it.reclaimableBytes }.toReadableSize()} 확보 가능", "${groups.size} 組，可釋出 ${groups.sumOf { it.reclaimableBytes }.toReadableSize()}")
    } else {
        tr("${groups.size} 组，${groups.sumOf { it.rows.size }} 个版本", "${enCount(groups.size, "group", "groups")}, ${groups.sumOf { it.rows.size }} versions", "${groups.size} 組、${groups.sumOf { it.rows.size }} バージョン", "${groups.size}개 그룹, ${groups.sumOf { it.rows.size }}개 버전", "${groups.size} 組，${groups.sumOf { it.rows.size }} 個版本")
    }
    return buildList {
        add(BarItem("dupKind", PinnedPriority) {
            Row(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                DuplicateKind.entries.forEachIndexed { index, kind ->
                    TonalToggleButton(
                        checked = selectedKind == kind,
                        onCheckedChange = { if (selectedKind != kind) onKindChange(kind) },
                        shapes = connectedToggleShapes(index, DuplicateKind.entries.size),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(if (kind == DuplicateKind.IDENTICAL) tr("相同文件", "Identical files", "同一ファイル", "동일한 파일", "相同檔案") else tr("不同版本", "Different versions", "異なるバージョン", "다른 버전", "不同版本"), maxLines = 1)
                    }
                }
            }
        })
        if (groups.isNotEmpty()) {
            add(BarItem(
                key = "dupRule",
                priority = 70,
                overflow = DuplicateKeepRule.entries.map { rule ->
                    SheetAction(
                        Icons.Outlined.TaskAlt,
                        tr("规则：${rule.label}", "Rule: ${rule.label}", "ルール：${rule.label}", "규칙: ${rule.label}", "規則：${rule.label}"),
                        { reportView.selectKeepRule(rule) },
                        group = ActionGroup.Sort,
                        checked = reportView.keepRule == rule,
                    )
                },
            ) {
                var ruleMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { ruleMenu = true }) {
                        Text(tr("规则：${reportView.keepRule.label} ▾", "Rule: ${reportView.keepRule.label} ▾", "ルール：${reportView.keepRule.label} ▾", "규칙: ${reportView.keepRule.label} ▾", "規則：${reportView.keepRule.label} ▾"), maxLines = 1)
                    }
                    DropdownMenu(expanded = ruleMenu, onDismissRequest = { ruleMenu = false }) {
                        DuplicateKeepRule.entries.forEach { rule ->
                            DropdownMenuItem(
                                text = { Text(rule.label) },
                                onClick = { reportView.selectKeepRule(rule); ruleMenu = false },
                            )
                        }
                    }
                }
            })
            add(BarItem(
                key = "dupInvert",
                priority = 65,
                overflow = listOf(SheetAction(Icons.Outlined.FileCopy, tr("反选", "Invert selection", "選択を反転", "선택 반전", "反向選取"), { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) }, group = ActionGroup.Select)),
            ) {
                TextButton(onClick = { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) }) {
                    Text(stringResource(Res.string.text_invert_selection), maxLines = 1)
                }
            })
        }
        add(BarItem("dupSummary", 25) {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        })
    }
}

@Composable
internal fun DuplicatesBanner(
    finder: DuplicateFinderState,
    selectedKind: DuplicateKind,
    onKindChange: (DuplicateKind) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    reportView: DuplicateReportViewModel? = null,
    selectedIds: Set<String> = emptySet(),
    onSelectIds: ((Set<String>) -> Unit)? = null,
    controlsInCommandBar: Boolean = false,
) {
    val report = finder.report
    val identical = selectedKind == DuplicateKind.IDENTICAL
    val groups = if (identical) report.identical else report.versions
    var menuOpen by remember { mutableStateOf(false) }
    var ruleMenu by remember { mutableStateOf(false) }
    val warning = when (finder.scanStop) {
        ScanStop.CANCELLED -> tr("扫描已停止，结果不完整", "Scan stopped; results incomplete", "検索停止。結果は不完全です", "검색 중지됨. 불완전한 결과", "掃描已停止，結果不完整")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> tr("已达扫描上限，结果不完整", "Scan limit reached; results incomplete", "検索上限に到達。結果は不完全です", "검색 한도 도달. 불완전한 결과", "已達掃描上限，結果不完整")
        ScanStop.TIMEOUT -> tr("扫描超时，结果不完整", "Scan timed out; results incomplete", "検索がタイムアウト。結果は不完全です", "검색 시간 초과. 불완전한 결과", "掃描逾時，結果不完整")
        null -> null
    }
    val hasWarning = warning != null || finder.failedFolders > 0
    if (controlsInCommandBar && !hasWarning) return
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (controlsInCommandBar) 4.dp else 8.dp, bottom = if (controlsInCommandBar) 4.dp else 8.dp)) {
        if (!controlsInCommandBar) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    DuplicateKind.entries.forEachIndexed { index, kind ->
                        TonalToggleButton(
                            checked = selectedKind == kind,
                            onCheckedChange = { if (selectedKind != kind) onKindChange(kind) },
                            shapes = connectedToggleShapes(index, DuplicateKind.entries.size),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(if (kind == DuplicateKind.IDENTICAL) tr("相同文件", "Identical files", "同一ファイル", "동일한 파일", "相同檔案") else tr("不同版本", "Different versions", "異なるバージョン", "다른 버전", "不同版本"), maxLines = 1)
                        }
                    }
                }
                if (groups.isNotEmpty() && reportView != null) {
                    Box {
                        TextButton(
                            onClick = { ruleMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(tr("规则：${reportView.keepRule.label} ▾", "Rule: ${reportView.keepRule.label} ▾", "ルール：${reportView.keepRule.label} ▾", "규칙: ${reportView.keepRule.label} ▾", "規則：${reportView.keepRule.label} ▾"), maxLines = 1)
                        }
                        DropdownMenu(expanded = ruleMenu, onDismissRequest = { ruleMenu = false }) {
                            DuplicateKeepRule.entries.forEach { rule ->
                                DropdownMenuItem(
                                    text = { Text(rule.label) },
                                    onClick = { reportView.selectKeepRule(rule); ruleMenu = false },
                                )
                            }
                        }
                    }
                    if (onSelectIds != null) {
                        TextButton(
                            onClick = { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(stringResource(Res.string.text_invert_selection), maxLines = 1)
                        }
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(tr("${groups.size} 组", "${enCount(groups.size, "group", "groups")}", "${groups.size} 組", "${groups.size}개 그룹", "${groups.size} 組"), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        if (identical) tr("可释放 ${groups.sumOf { it.reclaimableBytes }.toReadableSize()}", "${groups.sumOf { it.reclaimableBytes }.toReadableSize()} reclaimable", "${groups.sumOf { it.reclaimableBytes }.toReadableSize()} 解放可能", "${groups.sumOf { it.reclaimableBytes }.toReadableSize()} 확보 가능", "可釋出 ${groups.sumOf { it.reclaimableBytes }.toReadableSize()}")
                        else tr("${groups.sumOf { it.rows.size }} 个版本", "${groups.sumOf { it.rows.size }} versions", "${groups.sumOf { it.rows.size }} バージョン", "${groups.sumOf { it.rows.size }}개 버전", "${groups.sumOf { it.rows.size }} 個版本"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box {
                    TooltipIconButton(Icons.Outlined.MoreVert, tr("查重选项", "Duplicate search options", "重複検索のオプション", "중복 검색 옵션", "查重選項"), { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.text_scan_again)) }, onClick = { menuOpen = false; finder.rescan() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.text_end_duplicate_search)) }, onClick = { menuOpen = false; onEnd() })
                    }
                }
            }
        }
        if (hasWarning) {
            Text(
                listOfNotNull(warning, tr("${finder.failedFolders} 个文件夹读取失败", "${enCount(finder.failedFolders, "folder", "folders")} failed to load", "${finder.failedFolders} フォルダーの読み込みに失敗", "폴더 ${finder.failedFolders}개 불러오기 실패", "${finder.failedFolders} 個資料夾讀取失敗").takeIf { finder.failedFolders > 0 }).joinToString("，"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (controlsInCommandBar) 0.dp else 4.dp),
            )
        }
    }
}

/**
 * 没有可列的组时整页的样子：还在扫、扫失败、扫完没有重复，或这一次已经结束（重启后停在这里、或别处结束了它）。
 * 结束了的拿不回结果：结果只是扫描那一刻的快照，不存盘。
 */
@Composable
internal fun DuplicatesEmptyState(finder: DuplicateFinderState?, onLeave: () -> Unit, canLeave: Boolean, modifier: Modifier = Modifier, cleaning: Boolean = false, selectedKind: DuplicateKind = DuplicateKind.IDENTICAL) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (finder?.phase) {
            null -> {
                PikoEmptyState(
                    title = tr("查找已结束", "Search finished", "検索は終了しました", "검색 종료됨", "搜尋已結束"),
                    description = tr("扫描结果未保存", "Scan results not saved", "検索結果は未保存です", "검색 결과가 저장되지 않았습니다", "掃描結果未儲存"),
                    icon = if (cleaning) Icons.Outlined.FileCopy else Icons.Outlined.TaskAlt,
                )
                TextButton(onClick = onLeave) { Text(stringResource(Res.string.text_back_to_drive)) }
            }
            Phase.SCANNING, Phase.ANALYZING -> {
                // 要等几分钟、总量事先不知道：不确定的线性进度条，与窄窗口 sheet 里的同一种（progress-indicators.md）
                LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (finder.phase == Phase.ANALYZING) tr("正在比对", "Comparing", "照合中", "비교 중", "正在比對") else tr("正在扫描「${finder.root.localizedName}」", "Scanning “${finder.root.localizedName}”", "「${finder.root.localizedName}」を検索中", "“${finder.root.localizedName}” 검색 중", "正在掃描「${finder.root.localizedName}」"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // 窄窗口里离开即结束查找，见 TaskSlot
                    text = tr("已扫描 ${finder.scannedFolders} 个文件夹、${finder.scannedFiles} 个文件", "Scanned ${enCount(finder.scannedFolders, "folder", "folders")} and ${enCount(finder.scannedFiles, "file", "files")}", "${finder.scannedFolders} フォルダー、${finder.scannedFiles} ファイルを検索済み", "폴더 ${finder.scannedFolders}개, 파일 ${finder.scannedFiles}개 검색 완료", "已掃描 ${finder.scannedFolders} 個資料夾、${finder.scannedFiles} 個檔案") + if (canLeave) tr("，离开此页后继续", "; continues after leaving this page", "。この画面を離れても続行します", ". 이 페이지를 나가도 계속합니다", "，離開此頁後繼續") else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (finder.phase == Phase.SCANNING) {
                    Spacer(Modifier.height(24.dp))
                    // 停止不丢弃已扫描的部分，按钮文案据此写明
                    OutlinedButton(onClick = finder::stopScan) { Text(stringResource(Res.string.text_stop_and_view_results)) }
                }
            }
            Phase.FAILED -> PikoErrorState(title = tr("扫描失败", "Scan failed", "検索に失敗しました", "검색 실패", "掃描失敗"), message = finder.errorMessage.orEmpty(), onRetry = finder::rescan)
            Phase.DONE -> {
                PikoEmptyState(
                    title = if (cleaning) tr("正在清理所选文件", "Cleaning selected files", "選択したファイルを整理中", "선택한 파일 정리 중", "正在清理所選檔案") else if (selectedKind == DuplicateKind.IDENTICAL) tr("没有相同文件", "No identical files", "同一ファイルはありません", "동일한 파일이 없습니다", "沒有相同檔案") else tr("没有不同版本", "No different versions", "異なるバージョンはありません", "다른 버전이 없습니다", "沒有不同版本"),
                    icon = Icons.Outlined.TaskAlt,
                )
                if (!cleaning) TextButton(onClick = finder::rescan) { Text(stringResource(Res.string.text_scan_again)) }
            }
        }
    }
}
