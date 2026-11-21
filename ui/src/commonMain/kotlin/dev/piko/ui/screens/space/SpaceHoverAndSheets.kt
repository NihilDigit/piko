package dev.piko.ui.screens.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.space.SpaceNodeItem
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.FileListLeadingVisual
import dev.piko.ui.components.FileVisual
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.VaultMark
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.format.formatPercentage
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import kotlin.math.roundToInt

/** 鼠标悬停或右键/长按命中的具体交互目标（既可是顶层色块，也可是外层大文件夹内部的嵌套子色块）。 */
@Immutable
internal sealed interface SpaceInteractiveTarget {
    val targetKey: String
    val displayBytes: Long

    @Immutable
    data class Node(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val isVaulted: Boolean,
        override val displayBytes: Long,
        val physicalBytes: Long,
        val vaultedBytes: Long,
        val fileCount: Long,
        val isComplete: Boolean = true,
        /** 若属于某个外层大文件夹内部的子块，记录其直接父文件夹 ID 与名称以便一步下钻或拼面包屑。 */
        val nestedUnderFolderId: String? = null,
        val nestedUnderFolderName: String? = null,
    ) : SpaceInteractiveTarget {
        override val targetKey: String =
            if (nestedUnderFolderId != null) "nested:$nestedUnderFolderId/$id" else "node:$id"
    }

    @Immutable
    data class OtherGroup(
        val groupId: String,
        val items: List<SpaceNodeItem>,
        override val displayBytes: Long,
    ) : SpaceInteractiveTarget {
        override val targetKey: String = "other:$groupId"
    }

    @Immutable
    data class FreeQuota(
        override val displayBytes: Long,
        val limitBytes: Long,
    ) : SpaceInteractiveTarget {
        override val targetKey: String = "__piko_free_quota__"
    }
}

/**
 * 隔离指针高频坐标状态：鼠标在同一个色块内部移动时仅在布局阶段更新悬浮卡片偏移量，
 * 不触发整棵 `SpaceTreemapContent` 与全部色块的重组。
 */
@Stable
internal class SpaceHoverTracker {
    var pointer by mutableStateOf(Offset.Zero)
    var activeTarget by mutableStateOf<SpaceInteractiveTarget?>(null)

    fun hover(target: SpaceInteractiveTarget) {
        if (activeTarget?.targetKey != target.targetKey || activeTarget != target) {
            activeTarget = target
        }
    }

    fun leave(target: SpaceInteractiveTarget) {
        if (activeTarget?.targetKey == target.targetKey) {
            activeTarget = null
        }
    }

    fun clear() {
        activeTarget = null
    }
}

internal class TargetMenuRequest(
    val target: SpaceInteractiveTarget,
    val pointerInWindow: IntOffset,
)

internal fun actionsForSpaceTarget(
    target: SpaceInteractiveTarget,
    onDrillInto: (folderId: String) -> Unit,
    onDrillIntoNested: (parentFolderId: String, childFolderId: String) -> Unit,
    onOpenFolderInDrive: (folderId: String, folderName: String, parentFolderId: String?) -> Unit,
    onLocate: (itemId: String, nestedParentId: String?, nestedParentName: String?) -> Unit,
    onOpenOtherGroup: (List<SpaceNodeItem>) -> Unit,
): List<SheetAction> = when (target) {
    is SpaceInteractiveTarget.Node -> buildList {
        if (target.isFolder) {
            add(
                SheetAction(
                    icon = Icons.Outlined.SubdirectoryArrowRight,
                    label = tr("进入此文件夹", "Enter folder", "このフォルダーに入る", "이 폴더로 이동", "進入此資料夾"),
                    onClick = {
                        val parent = target.nestedUnderFolderId
                        if (parent != null) onDrillIntoNested(parent, target.id) else onDrillInto(target.id)
                    },
                    group = ActionGroup.Open,
                ),
            )
            add(
                SheetAction(
                    icon = Icons.Outlined.FolderOpen,
                    label = tr("在网盘中打开此文件夹", "Open folder in drive", "ドライブでこのフォルダーを開く", "드라이브에서 이 폴더 열기", "在雲端硬碟中開啟此資料夾"),
                    onClick = { onOpenFolderInDrive(target.id, target.name, target.nestedUnderFolderId) },
                    group = ActionGroup.Open,
                ),
            )
        }
        add(
            SheetAction(
                icon = if (target.isFolder) Icons.Outlined.PieChart else Icons.Outlined.FolderOpen,
                label = tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"),
                onClick = { onLocate(target.id, target.nestedUnderFolderId, target.nestedUnderFolderName) },
                group = ActionGroup.Open,
            ),
        )
    }
    is SpaceInteractiveTarget.OtherGroup -> listOf(
        SheetAction(
            icon = Icons.Outlined.MoreHoriz,
            label = tr(
                "查看包含的 ${target.items.size} 项",
                "View ${enCount(target.items.size, "grouped item", "grouped items")}",
                "まとめた ${target.items.size} 項目を表示",
                "묶인 ${target.items.size}개 항목 보기",
                "檢視包含的 ${target.items.size} 項",
            ),
            onClick = { onOpenOtherGroup(target.items) },
            group = ActionGroup.Open,
        ),
    )
    is SpaceInteractiveTarget.FreeQuota -> emptyList()
}

@Composable
internal fun SpaceHoverOverlay(
    enabled: Boolean,
    totalBytes: Double,
    includeVaulted: Boolean,
    hoverTracker: SpaceHoverTracker,
    canvasSize: IntSize,
) {
    val currentHovered = if (enabled) hoverTracker.activeTarget else null
    if (currentHovered != null) {
        SpaceHoverCard(
            target = currentHovered,
            totalBytes = totalBytes,
            includeVaulted = includeVaulted,
            hoverTracker = hoverTracker,
            canvasSize = canvasSize,
        )
    }
}

@Composable
internal fun SpaceHoverCard(
    target: SpaceInteractiveTarget,
    totalBytes: Double,
    includeVaulted: Boolean,
    hoverTracker: SpaceHoverTracker,
    canvasSize: IntSize,
) {
    val density = LocalDensity.current
    val isEInk = LocalAppearance.current.isEInk
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val offsetPx = with(density) { 16.dp.roundToPx() }
    val edgeMarginPx = with(density) { 8.dp.roundToPx() }

    val sharePercent = ((target.displayBytes.toDouble() / totalBytes) * 100.0).coerceIn(0.0, 100.0)
    val shareText = formatPercentage(sharePercent)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp,
        shadowElevation = if (isEInk) 0.dp else 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (isEInk) Color.Black else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier
            .offset {
                // 仅在布局阶段读取 hoverTracker.pointer，移动鼠标零重组
                val pointer = hoverTracker.pointer
                val rawRight = pointer.x.roundToInt() + offsetPx
                val resolvedX = if (cardSize.width > 0 && rawRight + cardSize.width + edgeMarginPx > canvasSize.width) {
                    pointer.x.roundToInt() - cardSize.width - offsetPx
                } else {
                    rawRight
                }
                val x = if (canvasSize.width > cardSize.width + edgeMarginPx * 2) {
                    resolvedX.coerceIn(edgeMarginPx, canvasSize.width - cardSize.width - edgeMarginPx)
                } else {
                    edgeMarginPx
                }

                val rawBelow = pointer.y.roundToInt() + offsetPx
                val resolvedY = if (cardSize.height > 0 && rawBelow + cardSize.height + edgeMarginPx > canvasSize.height) {
                    pointer.y.roundToInt() - cardSize.height - offsetPx
                } else {
                    rawBelow
                }
                val y = if (canvasSize.height > cardSize.height + edgeMarginPx * 2) {
                    resolvedY.coerceIn(edgeMarginPx, canvasSize.height - cardSize.height - edgeMarginPx)
                } else {
                    edgeMarginPx
                }
                IntOffset(x, y)
            }
            .widthIn(min = 196.dp, max = 340.dp)
            .onSizeChanged { cardSize = it },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (target) {
                is SpaceInteractiveTarget.Node -> {
                    if (target.nestedUnderFolderName != null) {
                        Text(
                            text = target.nestedUnderFolderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = target.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val physicalSizeText = target.physicalBytes.toReadableSize()
                    Text(
                        text = tr(
                            "占配额 $physicalSizeText（$shareText）",
                            "Quota used: $physicalSizeText ($shareText)",
                            "容量枠使用 $physicalSizeText（$shareText）",
                            "할당량 사용 $physicalSizeText ($shareText)",
                            "佔配額 $physicalSizeText（$shareText）",
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target.vaultedBytes > 0L) {
                        val vaultedSizeText = target.vaultedBytes.toReadableSize()
                        Text(
                            text = tr(
                                "归档大小 $vaultedSizeText",
                                "Archived: $vaultedSizeText",
                                "アーカイブ $vaultedSizeText",
                                "보관 크기 $vaultedSizeText",
                                "歸檔大小 $vaultedSizeText",
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = if (isEInk) Color.Black else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (target.isFolder) {
                        val enFiles = enCount(target.fileCount, "file", "files")
                        val countText = tr(
                            "${target.fileCount} 个文件",
                            enFiles,
                            "${target.fileCount} ファイル",
                            "파일 ${target.fileCount}개",
                            "${target.fileCount} 個檔案",
                        )
                        val completeSuffix = if (!target.isComplete) {
                            tr("（统计中）", " (calculating)", "（集計中）", " (계산 중)", "（統計中）")
                        } else {
                            ""
                        }
                        Text(
                            text = countText + completeSuffix,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SpaceInteractiveTarget.OtherGroup -> {
                    val count = target.items.size
                    Text(
                        text = tr(
                            "其他 $count 项",
                            "$count other items",
                            "ほか $count 項目",
                            "기타 ${count}개 항목",
                            "其他 $count 項",
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${target.displayBytes.toReadableSize()}（$shareText）",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (includeVaulted) {
                            tr("点击查看全部小条目明细", "Click to view all grouped items", "クリックしてすべての小項目を表示", "클릭하여 묶인 항목 모두 보기", "點擊檢視全部小項目明細")
                        } else {
                            tr("点击查看全部占配额小条目", "Click to view all small quota items", "クリックして小容量項目を表示", "클릭하여 작은 할당량 항목 보기", "點擊檢視全部佔配額小項目")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEInk) Color.Black else MaterialTheme.colorScheme.primary,
                    )
                }
                is SpaceInteractiveTarget.FreeQuota -> {
                    Text(
                        text = tr("可用配额空间", "Available storage quota", "利用可能な空き容量", "사용 가능한 여유 공간", "可用配額空間"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val quotaPct = if (target.limitBytes > 0L) {
                        formatPercentage(target.displayBytes.toDouble() / target.limitBytes.toDouble() * 100.0)
                    } else {
                        shareText
                    }
                    Text(
                        text = if (target.limitBytes > 0L) {
                            tr(
                                "剩余 ${target.displayBytes.toReadableSize()} / 总配额 ${target.limitBytes.toReadableSize()}（$quotaPct）",
                                "${target.displayBytes.toReadableSize()} free of ${target.limitBytes.toReadableSize()} ($quotaPct)",
                                "残り ${target.displayBytes.toReadableSize()} / 合計 ${target.limitBytes.toReadableSize()}（$quotaPct）",
                                "남음 ${target.displayBytes.toReadableSize()} / 전체 ${target.limitBytes.toReadableSize()} ($quotaPct)",
                                "剩餘 ${target.displayBytes.toReadableSize()} / 總配額 ${target.limitBytes.toReadableSize()}（$quotaPct）",
                            )
                        } else {
                            "${target.displayBytes.toReadableSize()}（$shareText）"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TileActionSheet(
    target: SpaceInteractiveTarget,
    includeVaulted: Boolean,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    when (target) {
        is SpaceInteractiveTarget.Node -> {
            val meta = buildList {
                add(
                    tr(
                        "占配额 ${target.physicalBytes.toReadableSize()}",
                        "Quota ${target.physicalBytes.toReadableSize()}",
                        "容量枠 ${target.physicalBytes.toReadableSize()}",
                        "할당량 ${target.physicalBytes.toReadableSize()}",
                        "佔配額 ${target.physicalBytes.toReadableSize()}",
                    ),
                )
                if (target.vaultedBytes > 0L || (includeVaulted && target.isVaulted)) {
                    add(
                        tr(
                            "归档 ${target.vaultedBytes.toReadableSize()}",
                            "Archived ${target.vaultedBytes.toReadableSize()}",
                            "アーカイブ ${target.vaultedBytes.toReadableSize()}",
                            "보관 ${target.vaultedBytes.toReadableSize()}",
                            "歸檔 ${target.vaultedBytes.toReadableSize()}",
                        ),
                    )
                }
                if (target.isFolder) {
                    add(
                        tr(
                            "${target.fileCount} 个文件",
                            enCount(target.fileCount, "file", "files"),
                            "${target.fileCount} ファイル",
                            "파일 ${target.fileCount}개",
                            "${target.fileCount} 個檔案",
                        ),
                    )
                }
            }
            ItemDetailsSheet(
                title = target.name,
                headerIcon = {
                    val visual = remember(target.name, target.isFolder) {
                        FileVisual(target.name, isFolder = target.isFolder)
                    }
                    FileListLeadingVisual(
                        file = visual,
                        size = 40.dp,
                        allowThumbnail = false,
                    )
                },
                actions = actions,
                metaParts = meta,
                onDismiss = onDismiss,
            )
        }
        is SpaceInteractiveTarget.OtherGroup -> {
            val count = target.items.size
            ItemDetailsSheet(
                title = tr("其他 $count 项", "$count other items", "ほか $count 項目", "기타 ${count}개 항목", "其他 $count 項"),
                headerIcon = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(if (isEInk) Modifier.border(1.dp, Color.Black, MaterialTheme.shapes.small) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = null)
                    }
                },
                actions = actions,
                metaParts = listOf(target.displayBytes.toReadableSize()),
                onDismiss = onDismiss,
            )
        }
        is SpaceInteractiveTarget.FreeQuota -> {
            LaunchedEffect(target) { onDismiss() }
        }
    }
}

@Composable
internal fun OtherGroupSheet(
    items: List<SpaceNodeItem>,
    includeVaulted: Boolean,
    onDismiss: () -> Unit,
    onDrillInto: (folderId: String) -> Unit,
    onLocate: (itemId: String) -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    val title = tr(
        "其他 ${items.size} 项",
        "${items.size} other items",
        "ほか ${items.size} 項目",
        "기타 ${items.size}개 항목",
        "其他 ${items.size} 項",
    )
    PikoSheet(
        onDismissRequest = onDismiss,
        sideSheetTitle = title,
        maxHeightFraction = 0.78f,
    ) {
        val sheet = this
        if (!sheet.isSideSheet) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = items, key = { it.id }) { item ->
                val purelyVaulted = item.isVaulted || (item.isFolder && item.physicalBytes <= 0L && item.vaultedBytes > 0L)
                Surface(
                    onClick = {
                        sheet.hideThen {
                            if (item.isFolder) onDrillInto(item.id) else onLocate(item.id)
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
                    tonalElevation = 0.dp,
                    shadowElevation = if (isEInk) 0.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val visual = remember(item.name, item.isFolder) {
                            FileVisual(item.name, isFolder = item.isFolder)
                        }
                        FileListLeadingVisual(
                            file = visual,
                            size = 36.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (purelyVaulted) {
                                    VaultMark(inFolder = item.isFolder)
                                }
                            }
                            val subText = if (item.isFolder && item.fileCount > 0L) {
                                tr(
                                    "${item.bytes(includeVaulted).toReadableSize()}（${item.fileCount} 个文件）",
                                    "${item.bytes(includeVaulted).toReadableSize()} (${enCount(item.fileCount, "file", "files")})",
                                    "${item.bytes(includeVaulted).toReadableSize()}（${item.fileCount} ファイル）",
                                    "${item.bytes(includeVaulted).toReadableSize()} (파일 ${item.fileCount}개)",
                                    "${item.bytes(includeVaulted).toReadableSize()}（${item.fileCount} 個檔案）",
                                )
                            } else {
                                item.bytes(includeVaulted).toReadableSize()
                            }
                            Text(
                                text = subText,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TooltipIconButton(
                            icon = Icons.Outlined.FolderOpen,
                            label = tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"),
                            onClick = {
                                sheet.hideThen { onLocate(item.id) }
                            },
                        )
                    }
                }
            }
        }
    }
}
