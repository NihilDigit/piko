package dev.piko.ui.screens.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.DuplicateReportViewModel
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.shared.state.DuplicateGroup
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.state.DuplicateKind
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlin.concurrent.Volatile

private class CachedDuplicateLocations(
    val report: dev.piko.shared.state.DuplicateReport,
    val rootName: String,
    val map: Map<String, String>,
)

@Volatile
private var lastDuplicateLocations: CachedDuplicateLocations? = null

/**
 * 查重行的位置相对扫描起点；保留建议由组内排序后的行标签呈现，避免与当前策略冲突。
 */
internal fun duplicateLocations(finder: DuplicateFinderState?): Map<String, String> {
    val report = finder?.report ?: return emptyMap()
    if (report.identical.isEmpty() && report.versions.isEmpty()) return emptyMap()
    val rootName = finder.root.localizedName
    val cached = lastDuplicateLocations
    if (cached != null && cached.report === report && cached.rootName == rootName) return cached.map
    val estimatedSize = (report.identical.size + report.versions.size) * 3
    val map = HashMap<String, String>(estimatedSize * 4 / 3 + 1)
    fun fill(groups: List<DuplicateGroup>) {
        for (i in 0 until groups.size) {
            val rows = groups[i].rows
            for (j in 0 until rows.size) {
                val file = rows[j].file
                val folder = file.folderPath
                map[file.id] = if (folder.isEmpty()) rootName else "$rootName/$folder"
            }
        }
    }
    fill(report.identical)
    fill(report.versions)
    lastDuplicateLocations = CachedDuplicateLocations(report, rootName, map)
    return map
}

private class CachedDuplicateGroupStats(
    val groups: List<DuplicateGroup>,
    val identical: Boolean,
    val reclaimableText: String,
    val versionCount: Int,
)

@Volatile
private var lastDuplicateGroupStats: CachedDuplicateGroupStats? = null

private fun summaryStatsOf(groups: List<DuplicateGroup>, identical: Boolean): CachedDuplicateGroupStats {
    val cached = lastDuplicateGroupStats
    if (cached != null && cached.groups === groups && cached.identical == identical) return cached
    val computed = if (identical) {
        CachedDuplicateGroupStats(groups, true, groups.sumOf { it.reclaimableBytes }.toReadableSize(), 0)
    } else {
        CachedDuplicateGroupStats(groups, false, "", groups.sumOf { it.rows.size })
    }
    lastDuplicateGroupStats = computed
    return computed
}

/** 宽窗口里查重标签上的样子：扫描中转圈，悬停说明进度或结果。 */
fun duplicatesTab(finder: DuplicateFinderState): SessionTab {
    val groups = finder.report.identical.size + finder.report.versions.size
    val rootName = finder.root.localizedName
    val scannedFolders = finder.scannedFolders
    val tooltip = when {
        finder.isScanning -> tr("已扫描 $scannedFolders 个文件夹", "Scanned ${enCount(scannedFolders, "folder", "folders")}", "$scannedFolders フォルダーを検索済み", "폴더 ${scannedFolders}개 검색 완료", "已掃描 $scannedFolders 個資料夾")
        groups == 0 -> tr("未发现重复文件", "No duplicate files found", "重複ファイルはありません", "중복 파일이 없습니다", "未發現重複檔案")
        else -> tr("$groups 组重复文件", "$groups duplicate groups", "$groups 組の重複ファイル", "중복 파일 ${groups}개 그룹", "$groups 組重複檔案")
    }
    return SessionTab(tr("查重：$rootName", "Duplicates: $rootName", "重複検索：$rootName", "중복 검색: $rootName", "查重：$rootName"), tooltip, busy = finder.isScanning, icon = Icons.Outlined.FileCopy)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DuplicateKindToggleGroup(
    selectedKind: DuplicateKind,
    onKindChange: (DuplicateKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = if (isEInk) Color.White else Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
        checkedContainerColor = if (isEInk) Color.Black else colors.secondaryContainer,
        checkedContentColor = if (isEInk) Color.White else colors.onSecondaryContainer,
    )
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        DuplicateKind.entries.forEachIndexed { index, kind ->
            val checked = selectedKind == kind
            OutlinedToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onKindChange(kind) },
                shapes = connectedToggleShapes(index, DuplicateKind.entries.size),
                colors = toggleColors,
                border = if (isEInk) {
                    BorderStroke(1.dp, colors.outline)
                } else {
                    OutlinedToggleButtonDefaults.border(enabled = true, checked = checked)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = if (kind == DuplicateKind.IDENTICAL) {
                        tr("相同文件", "Identical files", "同一ファイル", "동일한 파일", "相同檔案")
                    } else {
                        tr("不同版本", "Different versions", "異なるバージョン", "다른 버전", "不同版本")
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DuplicateRuleMenuButton(
    reportView: DuplicateReportViewModel,
    subtleTextColor: Boolean,
) {
    var ruleMenu by remember { mutableStateOf(false) }
    val keepRuleLabel = reportView.keepRule.label
    Box {
        TextButton(
            onClick = { ruleMenu = true },
            colors = if (subtleTextColor) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ButtonDefaults.textButtonColors()
            },
            contentPadding = PaddingValues(start = 10.dp, end = 6.dp, top = 0.dp, bottom = 0.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text(
                text = tr(
                    "规则：$keepRuleLabel",
                    "Rule: $keepRuleLabel",
                    "ルール：$keepRuleLabel",
                    "규칙: $keepRuleLabel",
                    "規則：$keepRuleLabel",
                ),
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PikoDropdownMenu(expanded = ruleMenu, onDismissRequest = { ruleMenu = false }) {
            val rules = DuplicateKeepRule.entries
            rules.forEachIndexed { index, rule ->
                val current = reportView.keepRule == rule
                DropdownMenuItem(
                    text = { Text(rule.label, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    trailingContent = if (current) ({
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }) else null,
                    shape = menuItemShape(index, rules.size),
                    onClick = {
                        reportView.selectKeepRule(rule)
                        ruleMenu = false
                    },
                )
            }
        }
    }
}

private fun duplicateRuleOverflowActions(reportView: DuplicateReportViewModel): List<SheetAction> =
    DuplicateKeepRule.entries.map { rule ->
        val ruleLabel = rule.label
        SheetAction(
            icon = Icons.Outlined.TaskAlt,
            label = tr("规则：$ruleLabel", "Rule: $ruleLabel", "ルール：$ruleLabel", "규칙: $ruleLabel", "規則：$ruleLabel"),
            onClick = { reportView.selectKeepRule(rule) },
            group = ActionGroup.Sort,
            checked = reportView.keepRule == rule,
        )
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val groupCount = groups.size
    val stats = summaryStatsOf(groups, identical)
    val summary = if (identical) {
        val reclaimableText = stats.reclaimableText
        tr("$groupCount 组，可释放 $reclaimableText", "${enCount(groupCount, "group", "groups")}; $reclaimableText reclaimable", "$groupCount 組、$reclaimableText 解放可能", "${groupCount}개 그룹, $reclaimableText 확보 가능", "$groupCount 組，可釋出 $reclaimableText")
    } else {
        val versionCount = stats.versionCount
        tr("$groupCount 组，$versionCount 个版本", "${enCount(groupCount, "group", "groups")}, $versionCount versions", "$groupCount 組、$versionCount バージョン", "${groupCount}개 그룹, ${versionCount}개 버전", "$groupCount 組，$versionCount 個版本")
    }
    return buildList {
        add(BarItem("dupKind", PinnedPriority) {
            DuplicateKindToggleGroup(selectedKind = selectedKind, onKindChange = onKindChange)
        })
        if (groups.isNotEmpty()) {
            add(BarItem(
                key = "dupRule",
                priority = 70,
                overflow = duplicateRuleOverflowActions(reportView),
            ) {
                DuplicateRuleMenuButton(reportView = reportView, subtleTextColor = true)
            })
            add(BarItem(
                key = "dupInvert",
                priority = 65,
                overflow = listOf(SheetAction(Icons.Outlined.FileCopy, tr("反选", "Invert selection", "選択を反転", "선택 반전", "反向選取"), { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) }, group = ActionGroup.Select)),
            ) {
                TextButton(
                    onClick = { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(stringResource(Res.string.text_invert_selection), maxLines = 1)
                }
            })
        }
        add(BarItem("dupSummary", 25) {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val isEInk = LocalAppearance.current.isEInk
    val report = finder.report
    val identical = selectedKind == DuplicateKind.IDENTICAL
    val groups = if (identical) report.identical else report.versions
    val warning = when (finder.scanStop) {
        ScanStop.CANCELLED -> tr("扫描已停止，结果不完整", "Scan stopped; results incomplete", "検索停止。結果は不完全です", "검색 중지됨. 불완전한 결과", "掃描已停止，結果不完整")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> tr("已达扫描上限，结果不完整", "Scan limit reached; results incomplete", "検索上限に到達。結果は不完全です", "검색 한도 도달. 불완전한 결과", "已達掃描上限，結果不完整")
        ScanStop.TIMEOUT -> tr("扫描超时，结果不完整", "Scan timed out; results incomplete", "検索がタイムアウト。結果は不完全です", "검색 시간 초과. 불완전한 결과", "掃描逾時，結果不完整")
        null -> null
    }
    val hasWarning = warning != null || finder.failedFolders > 0
    if (controlsInCommandBar && !hasWarning) return

    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 8.dp,
                top = if (controlsInCommandBar) 4.dp else 6.dp,
                bottom = if (controlsInCommandBar) 4.dp else 8.dp,
            ),
    ) {
        if (!controlsInCommandBar) {
            val stats = remember(groups, identical) { summaryStatsOf(groups, identical) }
            val groupCount = groups.size
            val scanAgainLabel = stringResource(Res.string.text_scan_again)
            val endSearchLabel = stringResource(Res.string.text_end_duplicate_search)

            AdaptiveBar(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                leading = buildList {
                    add(BarItem("dupKind", PinnedPriority) {
                        DuplicateKindToggleGroup(selectedKind = selectedKind, onKindChange = onKindChange)
                    })
                    if (groups.isNotEmpty() && reportView != null) {
                        add(BarItem(
                            key = "dupRule",
                            priority = 60,
                            overflow = duplicateRuleOverflowActions(reportView),
                        ) {
                            DuplicateRuleMenuButton(reportView = reportView, subtleTextColor = false)
                        })
                        if (onSelectIds != null) {
                            add(BarItem(
                                key = "dupInvert",
                                priority = 50,
                                overflow = listOf(
                                    SheetAction(
                                        icon = Icons.Outlined.FileCopy,
                                        label = tr("反选", "Invert selection", "選択を反転", "선택 반전", "反向選取"),
                                        onClick = { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) },
                                        group = ActionGroup.Select,
                                    ),
                                ),
                            ) {
                                TextButton(
                                    onClick = { onSelectIds(reportView.invertSelectionIds(finder, selectedIds)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                ) {
                                    Text(stringResource(Res.string.text_invert_selection), maxLines = 1)
                                }
                            })
                        }
                    }
                },
                trailing = listOf(
                    BarItem("dupSummary", PinnedPriority) {
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 2.dp)) {
                            Text(
                                text = tr("$groupCount 组", enCount(groupCount, "group", "groups"), "$groupCount 組", "${groupCount}개 그룹", "$groupCount 組"),
                                style = MaterialTheme.typography.titleSmall.tabular,
                                maxLines = 1,
                            )
                            Text(
                                text = if (identical) {
                                    val reclaimableText = stats.reclaimableText
                                    tr("可释放 $reclaimableText", "$reclaimableText reclaimable", "$reclaimableText 解放可能", "$reclaimableText 확보 가능", "可釋出 $reclaimableText")
                                } else {
                                    val versionCount = stats.versionCount
                                    tr("$versionCount 个版本", "$versionCount versions", "$versionCount バージョン", "${versionCount}개 버전", "$versionCount 個版本")
                                },
                                style = MaterialTheme.typography.labelSmall.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                ),
                alwaysMore = true,
                moreActions = listOf(
                    SheetAction(Icons.Outlined.Refresh, scanAgainLabel, finder::rescan, group = ActionGroup.Refresh),
                    SheetAction(Icons.Outlined.Close, endSearchLabel, onEnd, destructive = true),
                ),
                gap = 6.dp,
            )
        }
        if (hasWarning) {
            val failedFolders = finder.failedFolders
            val warningText = listOfNotNull(
                warning,
                if (failedFolders > 0) tr("$failedFolders 个文件夹读取失败", "${enCount(failedFolders, "folder", "folders")} failed to load", "$failedFolders フォルダーの読み込みに失敗", "폴더 ${failedFolders}개 불러오기 실패", "$failedFolders 個資料夾讀取失敗") else null,
            ).joinToString("，")
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isEInk) Color.White else MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = if (isEInk) Color.Black else MaterialTheme.colorScheme.onTertiaryContainer,
                border = if (isEInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (controlsInCommandBar) 4.dp else 6.dp, end = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall.tabular,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 没有可列的组时整页的样子：还在扫、正在比对、扫失败、扫完没有重复，或这一次已经结束。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DuplicatesEmptyState(
    finder: DuplicateFinderState?,
    onLeave: () -> Unit,
    canLeave: Boolean,
    modifier: Modifier = Modifier,
    cleaning: Boolean = false,
    selectedKind: DuplicateKind = DuplicateKind.IDENTICAL,
) {
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
                if (finder.phase == Phase.ANALYZING) {
                    PikoLoadingIndicator(size = 48.dp)
                } else {
                    val staticMode = LocalPikoMotion.current.reduced || !LocalWindowVisible.current
                    val colors = MaterialTheme.colorScheme
                    if (staticMode) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHighest),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.45f)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.55f)),
                            )
                        }
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round,
                            trackColor = colors.surfaceContainerHighest,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                val rootName = finder.root.localizedName
                val scannedFolders = finder.scannedFolders
                val scannedFiles = finder.scannedFiles
                Text(
                    text = if (finder.phase == Phase.ANALYZING) {
                        tr("正在比对", "Comparing", "照合中", "비교 중", "正在比對")
                    } else {
                        tr("正在扫描「$rootName」", "Scanning “$rootName”", "「$rootName」を検索中", "“$rootName” 검색 중", "正在掃描「$rootName」")
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tr("已扫描 $scannedFolders 个文件夹、$scannedFiles 个文件", "Scanned ${enCount(scannedFolders, "folder", "folders")} and ${enCount(scannedFiles, "file", "files")}", "$scannedFolders フォルダー、$scannedFiles ファイルを検索済み", "폴더 ${scannedFolders}개, 파일 ${scannedFiles}개 검색 완료", "已掃描 $scannedFolders 個資料夾、$scannedFiles 個檔案") +
                        if (canLeave) tr("，离开此页后继续", "; continues after leaving this page", "。この画面を離れても続行します", ". 이 페이지를 나가도 계속합니다", "，離開此頁後繼續") else "",
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (finder.phase == Phase.SCANNING) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = finder::stopScan,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.text_stop_and_view_results))
                    }
                }
            }
            Phase.FAILED -> PikoErrorState(
                title = tr("扫描失败", "Scan failed", "検索に失敗しました", "검색 실패", "掃描失敗"),
                message = finder.errorMessage.orEmpty(),
                onRetry = finder::rescan,
            )
            Phase.DONE -> {
                PikoEmptyState(
                    title = if (cleaning) {
                        tr("正在清理所选文件", "Cleaning selected files", "選択したファイルを整理中", "선택한 파일 정리 중", "正在清理所選檔案")
                    } else if (selectedKind == DuplicateKind.IDENTICAL) {
                        tr("没有相同文件", "No identical files", "同一ファイルはありません", "동일한 파일이 없습니다", "沒有相同檔案")
                    } else {
                        tr("没有不同版本", "No different versions", "異なるバージョンはありません", "다른 버전이 없습니다", "沒有不同版本")
                    },
                    icon = Icons.Outlined.TaskAlt,
                )
                if (!cleaning) {
                    TextButton(onClick = finder::rescan, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.text_scan_again))
                    }
                }
            }
        }
    }
}
