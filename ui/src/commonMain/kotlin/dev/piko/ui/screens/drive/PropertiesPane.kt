package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isVaulted
import dev.piko.shared.state.DriveItemName
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.DetailAction
import dev.piko.ui.components.DetailActionIconButton
import dev.piko.ui.components.DetailField
import dev.piko.ui.components.DetailHeading
import dev.piko.ui.components.DetailPanelKey
import dev.piko.ui.components.DetailPanelSpec
import dev.piko.ui.components.DetailRow
import dev.piko.ui.components.DetailSection
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException

/**
 * 属性看的是哪几项：一项、几项，或者没指着条目时的当前目录。在打开的那一刻取定，之后选中怎么变都不跟，照资源管理器。
 */
internal sealed interface PropertiesTarget {
    class Single(val name: DriveItemName, val tags: List<String>, val location: String?, val isBlurred: Boolean) : PropertiesTarget {
        val file: FileStat get() = name.file
    }

    class Selection(val files: List<FileStat>, val location: String?) : PropertiesTarget

    class Folder(val name: String, val files: List<FileStat>, val location: String?, val id: String = "") : PropertiesTarget
}

/**
 * 条目的属性：窄窗口为底部模态 Sheet，中宽与桌面窗口为随内容定高的居中 M3 对话框（[DetailHost]）。
 * 采用 M3 Expressive 分段式卡片分组展示概览、媒体信息与位置溯源，并提供文件名、位置、来源链接和哈希的一键操作。
 */
@Composable
internal fun PropertiesPane(
    target: PropertiesTarget,
    modifier: Modifier = Modifier,
    onLocate: ((FileStat) -> Unit)? = null,
    onParseSource: ((String) -> Unit)? = null,
    onToggleBlur: ((FileStat) -> Unit)? = null,
    isRevealed: ((FileStat) -> Boolean)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(min = PropertiesMinWidth, max = PropertiesMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (target) {
            is PropertiesTarget.Single -> SingleDetails(
                target = target,
                onLocate = onLocate,
                onParseSource = onParseSource,
                onToggleBlur = onToggleBlur,
                isRevealed = isRevealed,
            )
            is PropertiesTarget.Selection -> SelectionDetails(target)
            is PropertiesTarget.Folder -> FolderDetails(target)
        }
    }
}

@Composable
private fun SingleDetails(
    target: PropertiesTarget.Single,
    onLocate: ((FileStat) -> Unit)?,
    onParseSource: ((String) -> Unit)?,
    onToggleBlur: ((FileStat) -> Unit)?,
    isRevealed: ((FileStat) -> Boolean)?,
) {
    val file = target.file
    val platform = LocalPikoPlatform.current
    val driveRepo = LocalPikoServices.current.driveRepository
    val vaultedFolders by driveRepo.vaultedFolders.collectAsStateWithLifecycle()
    val fullyVaultedFolders by driveRepo.fullyVaultedFolders.collectAsStateWithLifecycle()
    val blurred = if (isRevealed != null && onToggleBlur != null) !isRevealed(file) else target.isBlurred
    val copyNameAction = DetailAction(
        icon = Icons.Outlined.ContentCopy,
        label = tr("复制名称", "Copy name", "名前をコピー", "이름 복사"),
        onClick = { platform.copyToClipboard(tr("名称", "Name", "名前", "이름"), file.name) },
        copyFeedback = true,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!file.isFolder && file.thumbnailLink.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(MaterialTheme.shapes.large),
            ) {
                SpoilerThumbnail(
                    model = file.thumbnailLink,
                    isBlurred = blurred,
                    blur = PosterSpoilerBlur,
                    modifier = Modifier.fillMaxSize(),
                )
                if (onToggleBlur != null) {
                    Surface(
                        onClick = { onToggleBlur(file) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(36.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (blurred) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = if (blurred) tr("显示预览", "Show preview", "プレビューを表示", "미리 보기 표시") else tr("隐藏预览", "Hide preview", "プレビューを隠す", "미리 보기 숨기기"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.weight(1f)) {
                    FullName(target.name)
                }
                Spacer(Modifier.width(4.dp))
                DetailActionIconButton(copyNameAction)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FileTypeIcon(file = file, iconSize = 26.dp, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    FullName(target.name)
                }
                Spacer(Modifier.width(4.dp))
                DetailActionIconButton(copyNameAction)
            }
        }

        if (target.tags.isNotEmpty()) {
            MediaTagRow(tags = target.tags)
        }
    }

    val usage = if (file.isFolder) folderUsageById(file.id) else ""
    DetailSection(
        buildList {
            if (file.isFolder) {
                val typeField = DetailField(tr("类型", "Type", "種類", "유형"), tr("文件夹", "Folder", "フォルダー", "폴더"))
                val usageField = usage.takeIf { it.isNotEmpty() }?.let { DetailField(tr("内容", "Contents", "内容", "내용"), it) }
                if (usageField != null) add(DetailRow.PairRow(typeField, usageField)) else add(DetailRow.Single(typeField))
                when (file.id) {
                    in fullyVaultedFolders -> add(DetailRow.Single(DetailField(tr("归档状态", "Archive status", "アーカイブ状態", "보관 상태"), tr("完全归档（仅保留引用，不占网盘空间）", "Fully archived (references only; no cloud storage used)", "完全アーカイブ（参照のみ、ドライブ容量を使用しません）", "전체 보관 (참조만 유지, 클라우드 공간 사용 안 함)"))))
                    in vaultedFolders -> add(DetailRow.Single(DetailField(tr("归档状态", "Archive status", "アーカイブ状態", "보관 상태"), tr("部分归档（包含归档引用）", "Partially archived (includes archive references)", "一部アーカイブ（アーカイブ参照を含む）", "일부 보관 (보관 참조 포함)"))))
                }
            } else {
                val ext = file.name.substringAfterLast('.', "").uppercase().ifEmpty { tr("文件", "Files", "ファイル", "파일") }
                val typeField = DetailField(tr("类型", "Type", "種類", "유형"), ext)
                val sizeField = DetailField(
                    label = tr("大小", "Size", "サイズ", "크기"),
                    value = file.sizeBytes.toReadableSize(),
                    supportingText = if (file.isVaulted) tr("已归档引用，不占网盘空间", "Archived reference; uses no cloud storage", "アーカイブ参照。ドライブ容量を使用しません", "보관 참조. 클라우드 공간을 사용하지 않습니다") else null,
                )
                add(DetailRow.PairRow(typeField, sizeField))
                if (file.isPlayableVideo()) {
                    val duration = file.params["duration"]?.toDoubleOrNull()?.let { DetailField(tr("时长", "Duration", "再生時間", "재생 시간"), formatDuration(it.toLong())) }
                    val width = file.params["width"]
                    val height = file.params["height"]
                    val resolution = if (!width.isNullOrEmpty() && !height.isNullOrEmpty()) {
                        DetailField(tr("分辨率", "Resolution", "解像度", "해상도"), "$width × $height")
                    } else null
                    when {
                        duration != null && resolution != null -> add(DetailRow.PairRow(duration, resolution))
                        duration != null -> add(DetailRow.Single(duration))
                        resolution != null -> add(DetailRow.Single(resolution))
                    }
                }
            }
            val modifiedText = timestamp(file.modifiedTime)
            val createdText = timestamp(file.createdTime)?.takeIf { it != modifiedText }
            val modified = modifiedText?.let { DetailField(tr("修改时间", "Modified", "更新日時", "수정한 날짜"), it) }
            val created = createdText?.let { DetailField(tr("创建时间", "Created", "作成日時", "만든 날짜"), it) }
            when {
                modified != null && created != null -> add(DetailRow.PairRow(modified, created))
                modified != null -> add(DetailRow.Single(modified))
                created != null -> add(DetailRow.Single(created))
            }
        },
    )

    DetailSection(
        buildList {
            target.location?.takeIf { it.isNotEmpty() }?.let { loc ->
                add(
                    DetailRow.Single(
                        DetailField(
                            label = tr("位置", "Location", "場所", "위치"),
                            value = loc,
                            actions = buildList {
                                if (onLocate != null) {
                                    add(DetailAction(Icons.Outlined.FolderOpen, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), { onLocate(file) }))
                                }
                                add(
                                    DetailAction(
                                        icon = Icons.Outlined.ContentCopy,
                                        label = tr("复制位置", "Copy location", "場所をコピー", "위치 복사"),
                                        onClick = { platform.copyToClipboard(tr("位置", "Location", "場所", "위치"), loc) },
                                        copyFeedback = true,
                                    ),
                                )
                            },
                        ),
                    ),
                )
            }
            val source = file.source
            val sourceUrl = file.sourceUrl?.takeIf { it.isNotBlank() }
            if (source != null && sourceUrl != null) {
                add(
                    DetailRow.Single(
                        DetailField(
                            label = tr("来源", "Source", "ソース", "출처"),
                            value = source.label.removePrefix(tr("来源：", "Source:", "ソース：", "출처:")),
                            supportingText = summarizeSourceUrl(sourceUrl),
                            actions = buildList {
                                if (onParseSource != null) {
                                    add(DetailAction(Icons.Outlined.Link, tr("解析来源链接", "Resolve source link", "ソースリンクを解析", "출처 링크 분석"), { onParseSource(sourceUrl) }))
                                }
                                add(
                                    DetailAction(
                                        icon = Icons.Outlined.ContentCopy,
                                        label = source.copyLabel,
                                        onClick = { platform.copyToClipboard(source.copyLabel.removePrefix(tr("复制", "Copy", "コピー", "복사")), sourceUrl) },
                                        copyFeedback = true,
                                    ),
                                )
                            },
                        ),
                    ),
                )
            }
            if (!file.isFolder && file.hash.isNotEmpty()) {
                add(
                    DetailRow.Single(
                        DetailField(
                            label = tr("哈希", "Hash", "ハッシュ", "해시"),
                            value = file.hash,
                            monospace = true,
                            actions = listOf(
                                DetailAction(
                                    icon = Icons.Outlined.ContentCopy,
                                    label = tr("复制哈希", "Copy hash", "ハッシュをコピー", "해시 복사"),
                                    onClick = { platform.copyToClipboard(tr("哈希", "Hash", "ハッシュ", "해시"), file.hash) },
                                    copyFeedback = true,
                                ),
                            ),
                        ),
                    ),
                )
            }
        },
    )
}

@Composable
private fun FolderDetails(target: PropertiesTarget.Folder) {
    val platform = LocalPikoPlatform.current
    val driveRepo = LocalPikoServices.current.driveRepository
    val vaultedFolders by driveRepo.vaultedFolders.collectAsStateWithLifecycle()
    val fullyVaultedFolders by driveRepo.fullyVaultedFolders.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            DetailHeading(target.name)
        }
        if (target.id.isNotEmpty() && target.name.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            DetailActionIconButton(
                DetailAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = tr("复制名称", "Copy name", "名前をコピー", "이름 복사"),
                    onClick = { platform.copyToClipboard(tr("名称", "Name", "名前", "이름"), target.name) },
                    copyFeedback = true,
                ),
            )
        }
    }

    val folders = target.files.count { it.isFolder }
    val others = target.files.size - folders
    val recursiveUsage = if (folders > 0) folderUsageById(target.id) else ""
    DetailSection(
        buildList {
            val itemsField = DetailField(tr("项目", "Items", "項目", "항목"), itemsSummary(folders, others))
            val sumField = if (others > 0) {
                DetailField(tr("文件合计", "Total files", "ファイル合計", "파일 합계"), target.files.filterNot { it.isFolder }.sumOf { it.sizeBytes }.toReadableSize())
            } else null
            if (sumField != null) add(DetailRow.PairRow(itemsField, sumField)) else add(DetailRow.Single(itemsField))
            if (recursiveUsage.isNotEmpty()) {
                add(DetailRow.Single(DetailField(tr("包含内容", "Includes", "含まれる内容", "포함 내용"), recursiveUsage)))
            }
            if (target.id.isNotEmpty()) {
                when (target.id) {
                    in fullyVaultedFolders -> add(DetailRow.Single(DetailField(tr("归档状态", "Archive status", "アーカイブ状態", "보관 상태"), tr("完全归档（仅保留引用，不占网盘空间）", "Fully archived (references only; no cloud storage used)", "完全アーカイブ（参照のみ、ドライブ容量を使用しません）", "전체 보관 (참조만 유지, 클라우드 공간 사용 안 함)"))))
                    in vaultedFolders -> add(DetailRow.Single(DetailField(tr("归档状态", "Archive status", "アーカイブ状態", "보관 상태"), tr("部分归档（包含归档引用）", "Partially archived (includes archive references)", "一部アーカイブ（アーカイブ参照を含む）", "일부 보관 (보관 참조 포함)"))))
                }
            }
        },
    )

    target.location?.takeIf { it.isNotEmpty() }?.let { loc ->
        DetailSection(
            listOf(
                DetailRow.Single(
                    DetailField(
                        label = tr("位置", "Location", "場所", "위치"),
                        value = loc,
                        actions = listOf(
                            DetailAction(
                                icon = Icons.Outlined.ContentCopy,
                                label = tr("复制位置", "Copy location", "場所をコピー", "위치 복사"),
                                onClick = { platform.copyToClipboard(tr("位置", "Location", "場所", "위치"), loc) },
                                copyFeedback = true,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun SelectionDetails(target: PropertiesTarget.Selection) {
    val platform = LocalPikoPlatform.current
    val folders = target.files.count { it.isFolder }
    val others = target.files.size - folders
    val vaultedCount = target.files.count { it.isVaulted }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            DetailHeading(tr("已选择 ${target.files.size} 项", "${enCount(target.files.size, "item", "items")} selected", "${target.files.size} 項目を選択中", "${target.files.size}개 선택됨"))
        }
    }

    DetailSection(
        buildList {
            val itemsField = DetailField(tr("项目", "Items", "項目", "항목"), itemsSummary(folders, others))
            val sumField = if (others > 0) {
                DetailField(tr("文件合计", "Total files", "ファイル合計", "파일 합계"), target.files.filterNot { it.isFolder }.sumOf { it.sizeBytes }.toReadableSize())
            } else null
            if (sumField != null) add(DetailRow.PairRow(itemsField, sumField)) else add(DetailRow.Single(itemsField))
            if (vaultedCount > 0) {
                add(DetailRow.Single(DetailField(tr("归档引用", "Archive references", "アーカイブ参照", "보관 참조"), tr("$vaultedCount 项已归档（不占网盘空间）", "${enCount(vaultedCount, "item", "items")} archived (no drive quota used)", "$vaultedCount 項目をアーカイブ済み（容量消費なし）", "${vaultedCount}개 항목 보관됨 (용량 미사용)"))))
            }
        },
    )

    target.location?.takeIf { it.isNotEmpty() }?.let { loc ->
        DetailSection(
            listOf(
                DetailRow.Single(
                    DetailField(
                        label = tr("位置", "Location", "場所", "위치"),
                        value = loc,
                        actions = if (loc != tr("多个位置", "Multiple locations", "複数の場所", "여러 위치")) {
                            listOf(
                                DetailAction(
                                    icon = Icons.Outlined.ContentCopy,
                                    label = tr("复制位置", "Copy location", "場所をコピー", "위치 복사"),
                                    onClick = { platform.copyToClipboard(tr("位置", "Location", "場所", "위치"), loc) },
                                    copyFeedback = true,
                                ),
                            )
                        } else emptyList(),
                    ),
                ),
            ),
        )
    }
}

private fun itemsSummary(folders: Int, others: Int): String =
    listOfNotNull(
        folders.takeIf { it > 0 }?.let { tr("$it 个文件夹", "${enCount(it, "folder", "folders")}", "$it フォルダー", "폴더 ${it}개") },
        others.takeIf { it > 0 }?.let { tr("$it 个文件", "${enCount(it, "file", "files")}", "$it ファイル", "파일 ${it}개") },
    ).joinToString("、").ifEmpty { tr("空", "Empty", "空", "비어 있음") }

private fun summarizeSourceUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("magnet:", ignoreCase = true)) {
        val xt = trimmed.substringAfter("?", "")
            .split('&')
            .firstOrNull { it.startsWith("xt=", ignoreCase = true) }
        return if (xt != null) "magnet:?$xt" else trimmed
    }
    return trimmed
}

// 与卡片上同一个名字，真实名称在它下面；都可以选中复制，长名字换行，不截断
@Composable
private fun FullName(name: DriveItemName) {
    DetailHeading(name.headingText, name.headingOriginal)
}

/** 文件夹的递归统计，逐步长上去；与操作面板用的是同一个统计，换了文件夹就重新数。库与压缩包里的文件夹数不了，为空。 */
@Composable
private fun folderUsageById(folderId: String): String {
    if (!isDriveFolderId(folderId)) return ""
    val driveRepo = LocalPikoServices.current.driveRepository
    val flow = remember(folderId) { driveRepo.folderUsage(folderId) }
    val usage by produceState<FolderUsage?>(null, flow) {
        try {
            flow.collect { value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            value = null
        }
    }
    val counted = usage ?: return tr("统计中", "Calculating", "集計中", "계산 중")
    val text = tr("${counted.fileCount} 个文件，${counted.bytes.toReadableSize()}", "${enCount(counted.fileCount, "file", "files")}, ${counted.bytes.toReadableSize()}", "${counted.fileCount} ファイル、${counted.bytes.toReadableSize()}", "파일 ${counted.fileCount}개, ${counted.bytes.toReadableSize()}")
    return when (counted.progress) {
        FolderUsage.Progress.COUNTING -> tr("$text（统计中）", "$text (calculating)", "$text（集計中）", "$text (계산 중)")
        FolderUsage.Progress.COMPLETE -> text
        FolderUsage.Progress.TRUNCATED -> tr("至少 $text", "At least $text", "少なくとも $text", "최소 $text")
    }
}

// ISO 8601 取到分钟：2024-05-01T12:34:56.789+08:00 -> 2024-05-01 12:34
private fun timestamp(iso: String): String? = iso.takeIf { it.isNotEmpty() }?.take(16)?.replace('T', ' ')

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

internal val PropertiesMinWidth = 360.dp
internal val PropertiesMaxWidth = 440.dp
internal val FilePropertiesPanelKey = DetailPanelKey<PropertiesTarget>(
    DetailPanelSpec({ tr("属性", "Properties", "プロパティ", "속성") }, { tr("关闭属性", "Close properties", "プロパティを閉じる", "속성 닫기") }, PropertiesMinWidth, PropertiesMaxWidth),
)
