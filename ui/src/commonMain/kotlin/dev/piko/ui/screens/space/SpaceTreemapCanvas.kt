package dev.piko.ui.screens.space

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpaceNodeItem
import dev.piko.shared.space.SpacePreviewBlock
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.WindowAnchoredActionMenu
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.formatTreeIndexProgressLabel
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.format.formatPercentage
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import kotlin.math.roundToInt

/** 顶层树图中的条目：既包含真实目录/文件节点，也包含根目录下的「未使用配额空间」色块。 */
internal sealed interface SpaceDisplayEntry {
    val key: String
    val bytes: Long

    data class Node(val item: SpaceNodeItem, override val bytes: Long, val hueIndex: Int) : SpaceDisplayEntry {
        override val key: String get() = item.id
    }

    data class FreeQuota(override val bytes: Long, val limitBytes: Long) : SpaceDisplayEntry {
        override val key: String get() = "__piko_free_quota__"
    }
}

private sealed interface NestedSlot {
    data class Child(val block: SpacePreviewBlock, val hueIndex: Int) : NestedSlot
    data class Remainder(val bytes: Long) : NestedSlot
    data object LoadingArea : NestedSlot
}

@Composable
internal fun SpaceTreemapContent(
    snapshot: SpaceFolderSnapshot?,
    treeBuildProgress: PikoDriveRepository.TreeBuildProgress,
    includeVaulted: Boolean,
    freeQuotaBytes: Long,
    quotaLimitBytes: Long,
    onEnableIncludeVaulted: () -> Unit,
    onDrillInto: (folderId: String) -> Unit,
    onDrillIntoNested: (parentFolderId: String, childFolderId: String) -> Unit,
    onOpenFolderInDrive: (folderId: String, folderName: String, parentFolderId: String?) -> Unit,
    onLocate: (itemId: String, nestedParentId: String?, nestedParentName: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positiveNodes = remember(snapshot, includeVaulted) {
        snapshot?.items?.filter { it.bytes(includeVaulted) > 0L }.orEmpty()
    }
    val displayEntries = remember(positiveNodes, includeVaulted, freeQuotaBytes, quotaLimitBytes) {
        buildList {
            positiveNodes.forEachIndexed { index, node ->
                add(SpaceDisplayEntry.Node(item = node, bytes = node.bytes(includeVaulted), hueIndex = index))
            }
            if (freeQuotaBytes > 0L && positiveNodes.isNotEmpty()) {
                add(SpaceDisplayEntry.FreeQuota(bytes = freeQuotaBytes, limitBytes = quotaLimitBytes))
            }
        }
    }
    val isBlockingLoad = snapshot == null || (!snapshot.isTreeComplete && (snapshot.isTreeBuilding || treeBuildProgress.isBuilding))
    val isCalculating = isBlockingLoad || snapshot?.isTreeBuilding == true || treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates
    val phaseKey = when {
        snapshot == null -> "loading"
        displayEntries.isEmpty() -> "empty:${snapshot.folderId}:$isCalculating:$includeVaulted"
        else -> "content:${snapshot.folderId}"
    }

    Crossfade(
        targetState = phaseKey,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        modifier = modifier.fillMaxSize(),
        label = "space_treemap_phase",
    ) { currentPhase ->
        if (currentPhase == "loading" || displayEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isCalculating -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            InlineLoadingIndicator()
                            Text(
                                text = if (treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates) {
                                    formatTreeIndexProgressLabel(treeBuildProgress)
                                } else {
                                    tr(
                                        "正在统计当前目录…",
                                        "Calculating current folder…",
                                        "現在のフォルダーを集計しています…",
                                        "현재 폴더를 계산하는 중…",
                                        "正在統計目前資料夾…",
                                    )
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (treeBuildProgress.isBuilding) {
                                Text(
                                    text = tr(
                                        "首次建立完成后将持久保存在本机，后续打开与跨目录排序可直接秒开并增量复用",
                                        "Once built, the index is persisted locally for instant startup and incremental updates",
                                        "初回構築後は端末に保存され、次回から即座に表示、差分更新されます",
                                        "최초 구축이 완료되면 기기에 저장되어 다음부터는 즉시 열리고 증분 업데이트됩니다",
                                        "首次建立完成後將持久保存在本機，後續開啟與跨資料夾排序可直接秒開並增量複用",
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    snapshot == null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InlineLoadingIndicator()
                            Text(
                                text = tr("读取统计…", "Loading statistics…", "統計を読み込み中…", "통계 불러오는 중…", "讀取統計…"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    snapshot.syncError != null -> Unit
                    !includeVaulted && (snapshot.vaultedBytes > 0L) -> {
                        val archivedSize = snapshot.vaultedBytes.toReadableSize()
                        PikoEmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = tr(
                                "当前目录不占用网盘配额",
                                "No storage quota used in this folder",
                                "このフォルダーは容量枠を使用していません",
                                "이 폴더는 할당량을 사용하지 않습니다",
                                "目前資料夾不佔用雲端硬碟配額",
                            ),
                            description = tr(
                                "包含 $archivedSize 已归档内容，开启「显示归档内容」即可查看空间分布",
                                "Contains $archivedSize of archived items. Turn on Show archived items to view their breakdown.",
                                "$archivedSize のアーカイブ項目があります。「アーカイブ項目を表示」をオンにすると内訳を確認できます",
                                "${archivedSize}의 보관 항목이 있습니다. '보관 항목 표시'를 켜면 분포를 확인할 수 있습니다",
                                "包含 $archivedSize 已歸檔內容，開啟「顯示歸檔內容」即可檢視空間分佈",
                            ),
                            actionText = tr(
                                "显示归档内容",
                                "Show archived items",
                                "アーカイブ項目を表示",
                                "보관 항목 표시",
                                "顯示歸檔內容",
                            ),
                            onActionClick = onEnableIncludeVaulted,
                        )
                    }
                    freeQuotaBytes > 0L -> {
                        PikoEmptyState(
                            icon = Icons.Outlined.CloudQueue,
                            title = tr(
                                "网盘空间充足（可用 ${freeQuotaBytes.toReadableSize()}）",
                                "${freeQuotaBytes.toReadableSize()} of cloud storage available",
                                "${freeQuotaBytes.toReadableSize()} の空き容量があります",
                                "${freeQuotaBytes.toReadableSize()}의 여유 공간이 있습니다",
                                "雲端硬碟空間充足（可用 ${freeQuotaBytes.toReadableSize()}）",
                            ),
                            description = tr(
                                "当前未发现占用配额的文件",
                                "No quota-consuming files found",
                                "容量枠を使用しているファイルはありません",
                                "할당량을 사용하는 파일이 없습니다",
                                "目前未發現佔用配額的檔案",
                            ),
                        )
                    }
                    else -> {
                        PikoEmptyState(
                            icon = Icons.Outlined.PieChart,
                            title = tr(
                                "无占用空间的条目",
                                "No items using storage",
                                "容量を使用している項目はありません",
                                "공간을 사용하는 항목이 없습니다",
                                "無佔用空間的項目",
                            ),
                            description = tr(
                                "空文件夹与 0 字节文件不占用存储配额",
                                "Empty folders and 0-byte files do not use storage quota",
                                "空のフォルダーや 0 バイトのファイルは容量を使用しません",
                                "빈 폴더와 0바이트 파일은 저장 공간을 사용하지 않습니다",
                                "空資料夾與 0 位元組檔案不佔用儲存配額",
                            ),
                        )
                    }
                }
            }
            return@Crossfade
        }

        val density = LocalDensity.current
        val desktopLayout = isDesktopLayout()
        val pointerSource = LocalPointerSource.current
        val isEInk = LocalAppearance.current.isEInk
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val hoverTracker = remember { SpaceHoverTracker() }
        var desktopMenuRequest by remember { mutableStateOf<TargetMenuRequest?>(null) }
        var mobileSheetTarget by remember { mutableStateOf<SpaceInteractiveTarget?>(null) }
        var activeOtherGroup by remember { mutableStateOf<List<SpaceNodeItem>?>(null) }

        val minTileWidthPx = with(density) { 42.dp.toPx().toDouble() }
        val minTileHeightPx = with(density) { 32.dp.toPx().toDouble() }
        val sliverSidePx = with(density) { 12.dp.toPx().toDouble() }
        val canvasPadPx = with(density) { 12.dp.toPx().toDouble() }
        val tileGapHalfPx = with(density) { 2.dp.toPx().toDouble() }

        val treemapInputs = remember(displayEntries) {
            displayEntries.map { entry ->
                TreemapInput(id = entry.key, bytes = entry.bytes, payload = entry)
            }
        }

        val tiles = remember(treemapInputs, canvasSize, minTileWidthPx, minTileHeightPx, sliverSidePx, canvasPadPx) {
            if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                emptyList()
            } else {
                val container = TreemapRect(
                    left = canvasPadPx,
                    top = canvasPadPx,
                    width = (canvasSize.width.toDouble() - canvasPadPx * 2.0).coerceAtLeast(0.0),
                    height = (canvasSize.height.toDouble() - canvasPadPx * 2.0).coerceAtLeast(0.0),
                )
                layoutTreemap(
                    inputs = treemapInputs,
                    container = container,
                    minTileWidth = minTileWidthPx,
                    minTileHeight = minTileHeightPx,
                    sliverSide = sliverSidePx,
                    enableGrouping = true,
                )
            }
        }

        val totalDisplayedBytes = remember(tiles) {
            tiles.sumOf { it.bytes.toDouble() }.coerceAtLeast(1.0)
        }

        fun actionsFor(target: SpaceInteractiveTarget) = actionsForSpaceTarget(
            target = target,
            onDrillInto = onDrillInto,
            onDrillIntoNested = onDrillIntoNested,
            onOpenFolderInDrive = onOpenFolderInDrive,
            onLocate = onLocate,
            onOpenOtherGroup = { activeOtherGroup = it },
        )

        fun handleTargetPrimaryClick(target: SpaceInteractiveTarget) {
            if (isBlockingLoad) return
            when (target) {
                is SpaceInteractiveTarget.Node -> {
                    if (target.isFolder) {
                        val parent = target.nestedUnderFolderId
                        if (parent != null) onDrillIntoNested(parent, target.id) else onDrillInto(target.id)
                    } else {
                        onLocate(target.id, target.nestedUnderFolderId, target.nestedUnderFolderName)
                    }
                }
                is SpaceInteractiveTarget.OtherGroup -> {
                    activeOtherGroup = target.items
                }
                is SpaceInteractiveTarget.FreeQuota -> Unit
            }
        }

        fun handleTargetMenu(target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) {
            if (isBlockingLoad || target is SpaceInteractiveTarget.FreeQuota) return
            if ((!fromTouch || desktopLayout) && windowPos != null) {
                desktopMenuRequest = TargetMenuRequest(target, windowPos)
            } else {
                mobileSheetTarget = target
            }
        }

        val latestHandlePrimaryClick by rememberUpdatedState(::handleTargetPrimaryClick)
        val latestHandleTargetMenu by rememberUpdatedState(::handleTargetMenu)
        val stableOnPrimaryClick = remember {
            { target: SpaceInteractiveTarget -> latestHandlePrimaryClick(target) }
        }
        val stableOnContextMenu = remember {
            { target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean ->
                latestHandleTargetMenu(target, windowPos, fromTouch)
            }
        }
        val hoverEnabled = !pointerSource.isTouchLike && desktopMenuRequest == null

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when {
                                change.type != PointerType.Mouse -> hoverTracker.clear()
                                event.type == PointerEventType.Exit -> hoverTracker.clear()
                                event.type == PointerEventType.Move || event.type == PointerEventType.Enter -> {
                                    hoverTracker.pointer = change.position
                                }
                            }
                        }
                    }
                },
        ) {
            if (tiles.isNotEmpty()) {
                val insetRects = remember(tiles, tileGapHalfPx) {
                    tiles.map { tile -> tile.bounds.inset(tileGapHalfPx) }
                }
                Layout(
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        tiles.forEachIndexed { index, tile ->
                            val rect = insetRects[index]
                            val rankFraction = if (tiles.size <= 1) 0f else index.toFloat() / (tiles.size - 1).toFloat()
                            SpaceTreemapOuterTile(
                                tile = tile,
                                rect = rect,
                                includeVaulted = includeVaulted,
                                isTreeBuilding = isCalculating,
                                totalDisplayedBytes = totalDisplayedBytes,
                                rankFraction = rankFraction,
                                hoverTracker = hoverTracker,
                                hoverEnabled = hoverEnabled,
                                onPrimaryClick = stableOnPrimaryClick,
                                onContextMenu = stableOnContextMenu,
                            )
                        }
                    },
                ) { measurables, constraints ->
                    val placeables = measurables.mapIndexed { index, measurable ->
                        val rect = insetRects[index]
                        val w = rect.width.roundToInt().coerceAtLeast(1)
                        val h = rect.height.roundToInt().coerceAtLeast(1)
                        measurable.measure(Constraints.fixed(w, h))
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeables.forEachIndexed { index, placeable ->
                            val rect = insetRects[index]
                            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                        }
                    }
                }
            }

            SpaceHoverOverlay(
                enabled = !isBlockingLoad && hoverEnabled,
                totalBytes = totalDisplayedBytes,
                includeVaulted = includeVaulted,
                hoverTracker = hoverTracker,
                canvasSize = canvasSize,
            )

            if (isBlockingLoad) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (isEInk) 0.12f else 0.2f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
                        tonalElevation = if (isEInk) 0.dp else 6.dp,
                        shadowElevation = if (isEInk) 0.dp else 8.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            InlineLoadingIndicator()
                            Text(
                                text = if (treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates) {
                                    formatTreeIndexProgressLabel(treeBuildProgress)
                                } else {
                                    tr(
                                        "正在统计当前目录…",
                                        "Calculating current folder…",
                                        "現在のフォルダーを集計しています…",
                                        "현재 폴더를 계산하는 중…",
                                        "正在統計目前資料夾…",
                                    )
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            desktopMenuRequest?.let { req ->
                val actions = actionsFor(req.target)
                if (actions.isNotEmpty()) {
                    WindowAnchoredActionMenu(
                        pointerInWindow = req.pointerInWindow,
                        actions = actions,
                        onDismiss = { desktopMenuRequest = null },
                    )
                } else {
                    LaunchedEffect(req) { desktopMenuRequest = null }
                }
            }

            mobileSheetTarget?.let { target ->
                TileActionSheet(
                    target = target,
                    includeVaulted = includeVaulted,
                    actions = actionsFor(target),
                    onDismiss = { mobileSheetTarget = null },
                )
            }

            activeOtherGroup?.let { groupedItems ->
                OtherGroupSheet(
                    items = groupedItems,
                    includeVaulted = includeVaulted,
                    onDismiss = { activeOtherGroup = null },
                    onDrillInto = onDrillInto,
                    onLocate = { itemId -> onLocate(itemId, null, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpaceTreemapOuterTile(
    tile: TreemapTile<SpaceDisplayEntry>,
    rect: TreemapRect,
    includeVaulted: Boolean,
    isTreeBuilding: Boolean,
    totalDisplayedBytes: Double,
    rankFraction: Float,
    hoverTracker: SpaceHoverTracker,
    hoverEnabled: Boolean,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val widthDp = with(density) { rect.width.toFloat().toDp() }
    val heightDp = with(density) { rect.height.toFloat().toDp() }

    when (val content = tile.content) {
        is TreemapTileContent.OtherGroup -> {
            val groupedNodes = remember(content.items) {
                content.items.mapNotNull { (it.payload as? SpaceDisplayEntry.Node)?.item }
            }
            val target = remember(tile.id, groupedNodes, tile.bytes) {
                SpaceInteractiveTarget.OtherGroup(tile.id, groupedNodes, tile.bytes)
            }
            val groupedCount = groupedNodes.size
            val title = tr(
                "其他 $groupedCount 项",
                "$groupedCount other items",
                "ほか $groupedCount 項目",
                "기타 ${groupedCount}개 항목",
                "其他 $groupedCount 項",
            )
            LeafInteractiveBlock(
                target = target,
                title = title,
                bytes = tile.bytes,
                totalDisplayedBytes = totalDisplayedBytes,
                fileCount = null,
                leadingIcon = Icons.Outlined.MoreHoriz,
                widthDp = widthDp,
                heightDp = heightDp,
                palette = otherGroupPalette(colors, isEInk = isEInk),
                hoverTracker = hoverTracker,
                hoverEnabled = hoverEnabled,
                clickable = groupedNodes.isNotEmpty(),
                onPrimaryClick = onPrimaryClick,
                onContextMenu = onContextMenu,
            )
        }
        is TreemapTileContent.Single -> {
            when (val entry = content.item.payload) {
                is SpaceDisplayEntry.FreeQuota -> {
                    val target = remember(entry.bytes, entry.limitBytes) {
                        SpaceInteractiveTarget.FreeQuota(entry.bytes, entry.limitBytes)
                    }
                    val title = tr("可用空间", "Free space", "空き容量", "여유 공간", "可用空間")
                    LeafInteractiveBlock(
                        target = target,
                        title = title,
                        bytes = entry.bytes,
                        totalDisplayedBytes = totalDisplayedBytes,
                        fileCount = null,
                        leadingIcon = Icons.Outlined.CloudQueue,
                        widthDp = widthDp,
                        heightDp = heightDp,
                        palette = freeQuotaPalette(colors, isEInk = isEInk),
                        hoverTracker = hoverTracker,
                        hoverEnabled = hoverEnabled,
                        clickable = false,
                        onPrimaryClick = onPrimaryClick,
                        onContextMenu = onContextMenu,
                    )
                }
                is SpaceDisplayEntry.Node -> {
                    val item = entry.item
                    val outerTarget = remember(item, entry.bytes) {
                        SpaceInteractiveTarget.Node(
                            id = item.id,
                            name = item.name,
                            isFolder = item.isFolder,
                            isVaulted = item.isVaulted,
                            displayBytes = entry.bytes,
                            physicalBytes = item.physicalBytes,
                            vaultedBytes = item.vaultedBytes,
                            fileCount = item.fileCount,
                            isComplete = item.isComplete,
                        )
                    }
                    val purelyVaulted = item.isVaulted || (item.isFolder && item.physicalBytes <= 0L && item.vaultedBytes > 0L)
                    val minNestedPx = with(density) { 108.dp.toPx().toDouble() }
                    val headerPx = with(density) { 36.dp.toPx().toDouble() }
                    val innerPadPx = with(density) { 6.dp.toPx().toDouble() }
                    val localOuterRect = remember(rect.width, rect.height) {
                        TreemapRect(0.0, 0.0, rect.width, rect.height)
                    }
                    val previewChildren = remember(item.previewChildren) {
                        item.previewChildren.filter { it.bytes > 0L }
                    }
                    val previewArea = remember(localOuterRect, item.isFolder, previewChildren.isNotEmpty(), minNestedPx, headerPx, innerPadPx) {
                        if (item.isFolder && previewChildren.isNotEmpty()) {
                            nestedPreviewBounds(
                                tileBounds = localOuterRect,
                                minContainerWidth = minNestedPx,
                                minContainerHeight = minNestedPx,
                                headerHeight = headerPx,
                                padding = innerPadPx,
                            )
                        } else {
                            null
                        }
                    }

                    if (previewArea != null) {
                        val framePalette = resolveBlockPalette(
                            hueIndex = entry.hueIndex,
                            rankFraction = rankFraction,
                            isFolder = true,
                            isPurelyVaulted = purelyVaulted,
                            isOuterGroupFrame = true,
                            colors = colors,
                            isEInk = isEInk,
                        )
                        NestedGroupTile(
                            parentItem = item,
                            parentHueIndex = entry.hueIndex,
                            includeVaulted = includeVaulted,
                            isTreeBuilding = isTreeBuilding,
                            outerTarget = outerTarget,
                            outerBytes = tile.bytes,
                            totalDisplayedBytes = totalDisplayedBytes,
                            previewChildren = previewChildren,
                            previewArea = previewArea,
                            widthDp = widthDp,
                            purelyVaulted = purelyVaulted,
                            framePalette = framePalette,
                            hoverTracker = hoverTracker,
                            hoverEnabled = hoverEnabled,
                            onPrimaryClick = onPrimaryClick,
                            onContextMenu = onContextMenu,
                        )
                    } else {
                        val palette = resolveBlockPalette(
                            hueIndex = entry.hueIndex,
                            rankFraction = rankFraction,
                            isFolder = item.isFolder,
                            isPurelyVaulted = purelyVaulted,
                            isOuterGroupFrame = false,
                            colors = colors,
                            isEInk = isEInk,
                        )
                        val icon = when {
                            purelyVaulted -> Icons.Outlined.Inventory2
                            item.isFolder -> Icons.Outlined.Folder
                            else -> Icons.Outlined.Description
                        }
                        LeafInteractiveBlock(
                            target = outerTarget,
                            title = item.name,
                            bytes = tile.bytes,
                            totalDisplayedBytes = totalDisplayedBytes,
                            fileCount = item.fileCount.takeIf { item.isFolder && it > 0L },
                            leadingIcon = icon,
                            widthDp = widthDp,
                            heightDp = heightDp,
                            palette = palette,
                            hoverTracker = hoverTracker,
                            hoverEnabled = hoverEnabled,
                            clickable = true,
                            onPrimaryClick = onPrimaryClick,
                            onContextMenu = onContextMenu,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 含嵌套子块的大文件夹组：
 * - 顶部 36dp 标题头栏代表父文件夹自身（悬停/点击操作父文件夹）；
 * - 内部每个子块均为独立可交互节点，鼠标悬停直接高亮并展示该子块详情，单击直接一步下钻进入该子块；
 * - 过小的长尾子项自动折叠为单个「其余项目」块，避免单屏实例化上千个微观节点造成主线程卡顿。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NestedGroupTile(
    parentItem: SpaceNodeItem,
    parentHueIndex: Int,
    includeVaulted: Boolean,
    isTreeBuilding: Boolean,
    outerTarget: SpaceInteractiveTarget.Node,
    outerBytes: Long,
    totalDisplayedBytes: Double,
    previewChildren: List<SpacePreviewBlock>,
    previewArea: TreemapRect,
    widthDp: Dp,
    purelyVaulted: Boolean,
    framePalette: BlockPalette,
    hoverTracker: SpaceHoverTracker,
    hoverEnabled: Boolean,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val haptic = LocalHapticFeedback.current
    val pointerSource = LocalPointerSource.current
    val outerShape = RoundedCornerShape(10.dp)
    val isHeaderHovered by remember(outerTarget.targetKey, hoverTracker, hoverEnabled) {
        derivedStateOf { hoverEnabled && hoverTracker.activeTarget?.targetKey == outerTarget.targetKey }
    }

    val activeChildren = previewChildren
    val activeSum = remember(activeChildren) { activeChildren.sumOf { it.bytes.coerceAtLeast(0L) } }
    val isPendingMore = !parentItem.isComplete || isTreeBuilding

    val childGapHalfPx = with(density) { 2.dp.toPx().toDouble() }
    val minNestedTileWidthPx = with(density) { 36.dp.toPx().toDouble() }
    val minNestedTileHeightPx = with(density) { 26.dp.toPx().toDouble() }
    val nestedSliverSidePx = with(density) { 10.dp.toPx().toDouble() }
    val innerWidthPx = previewArea.width
    val innerHeightPx = previewArea.height
    val nestedTiles = remember(
        outerBytes,
        activeChildren,
        activeSum,
        isPendingMore,
        innerWidthPx,
        innerHeightPx,
        parentHueIndex,
        minNestedTileWidthPx,
        minNestedTileHeightPx,
        nestedSliverSidePx,
    ) {
        val localArea = TreemapRect(0.0, 0.0, innerWidthPx, innerHeightPx)
        val inputs = buildList {
            activeChildren.forEachIndexed { idx, block ->
                if (block.bytes <= 0L) return@forEachIndexed
                // 让大目录内部的子块按独立色环散开，彻底消除一整屏同色的单调感
                val childHue = parentHueIndex * 3 + idx + 1
                add(TreemapInput(id = block.id, bytes = block.bytes, payload = NestedSlot.Child(block, childHue)))
            }
            val remainder = outerBytes - activeSum
            if (remainder > 0L) {
                val slot = if (isPendingMore) NestedSlot.LoadingArea else NestedSlot.Remainder(remainder)
                add(TreemapInput(id = "__nested_tail__:${parentItem.id}", bytes = remainder, payload = slot))
            }
        }
        layoutTreemap(
            inputs = inputs,
            container = localArea,
            minTileWidth = minNestedTileWidthPx,
            minTileHeight = minNestedTileHeightPx,
            sliverSide = nestedSliverSidePx,
            enableGrouping = true,
        )
    }

    val nestedRects = remember(nestedTiles, childGapHalfPx) {
        nestedTiles.map { it.bounds.inset(childGapHalfPx) }
    }

    var headerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastPressOffset by remember { mutableStateOf(Offset.Zero) }
    var lastPressType by remember { mutableStateOf(PointerType.Unknown) }
    val latestOnClick by rememberUpdatedState(onPrimaryClick)
    val latestOnMenu by rememberUpdatedState(onContextMenu)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(outerShape)
            .background(framePalette.container)
            .border(
                width = when {
                    isHeaderHovered -> 2.dp
                    isEInk -> 1.dp
                    else -> 1.5.dp
                },
                color = if (isEInk) {
                    Color.Black
                } else if (isHeaderHovered) {
                    framePalette.hoverBorder
                } else {
                    framePalette.border
                },
                shape = outerShape,
            ),
    ) {
        // 顶部父文件夹交互标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(framePalette.headerContainer)
                .onPlaced { headerCoords = it }
                .pointerInput(outerTarget.targetKey, hoverTracker) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Press) {
                                lastPressOffset = change.position
                                lastPressType = change.type
                            }
                            if (change.type == PointerType.Mouse) {
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> hoverTracker.hover(outerTarget)
                                    PointerEventType.Exit -> hoverTracker.leave(outerTarget)
                                }
                            }
                        }
                    }
                }
                .pointerInput(outerTarget.targetKey) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                                headerCoords?.takeIf { it.isAttached }?.let { coords ->
                                    latestOnMenu(outerTarget, coords.localToWindow(change.position).round(), false)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .combinedClickable(
                    role = Role.Button,
                    onClick = { latestOnClick(outerTarget) },
                    onLongClick = {
                        val touch = lastPressType == PointerType.Touch ||
                            lastPressType == PointerType.Stylus ||
                            (pointerSource.isTouchLike && lastPressType != PointerType.Mouse)
                        if (touch) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val pos = headerCoords?.takeIf { it.isAttached }?.localToWindow(lastPressOffset)?.round()
                            latestOnMenu(outerTarget, pos, true)
                        }
                    },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (purelyVaulted) Icons.Outlined.Inventory2 else Icons.Outlined.Folder,
                contentDescription = null,
                tint = framePalette.content,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = parentItem.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = framePalette.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (widthDp >= 140.dp) {
                Text(
                    text = outerBytes.toReadableSize(),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = framePalette.secondaryContent,
                    maxLines = 1,
                )
            }
        }
        if (isEInk) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        }

        // 内部可交互子块布局区（与顶栏上下分栏，互不遮挡指针命中）
        if (nestedTiles.isNotEmpty()) {
            Layout(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(6.dp),
                content = {
                    nestedTiles.forEachIndexed { idx, subTile ->
                        val subRect = nestedRects[idx]
                        val subWidthDp = with(density) { subRect.width.toFloat().toDp() }
                        val subHeightDp = with(density) { subRect.height.toFloat().toDp() }
                        val subRank = if (nestedTiles.size <= 1) 0f else idx.toFloat() / (nestedTiles.size - 1).toFloat()
                        val slot = (subTile.content as? TreemapTileContent.Single)?.item?.payload
                        val isGroupedOther = subTile.content is TreemapTileContent.OtherGroup
                        when {
                            slot is NestedSlot.Child -> {
                                val block = slot.block
                                val subTarget = remember(block, parentItem.id, parentItem.name) {
                                    SpaceInteractiveTarget.Node(
                                        id = block.id,
                                        name = block.name,
                                        isFolder = block.isFolder,
                                        isVaulted = block.isVaulted,
                                        displayBytes = block.bytes,
                                        physicalBytes = block.physicalBytes,
                                        vaultedBytes = block.vaultedBytes,
                                        fileCount = block.fileCount,
                                        isComplete = true,
                                        nestedUnderFolderId = parentItem.id,
                                        nestedUnderFolderName = parentItem.name,
                                    )
                                }
                                val childPurelyVaulted = block.isVaulted ||
                                    (block.isFolder && block.physicalBytes <= 0L && block.vaultedBytes > 0L)
                                val childPalette = resolveBlockPalette(
                                    hueIndex = slot.hueIndex,
                                    rankFraction = subRank,
                                    isFolder = block.isFolder,
                                    isPurelyVaulted = childPurelyVaulted,
                                    isOuterGroupFrame = false,
                                    colors = colors,
                                    isEInk = isEInk,
                                )
                                val childIcon = when {
                                    childPurelyVaulted -> Icons.Outlined.Inventory2
                                    block.isFolder -> Icons.Outlined.Folder
                                    else -> Icons.Outlined.Description
                                }
                                LeafInteractiveBlock(
                                    target = subTarget,
                                    title = block.name,
                                    bytes = block.bytes,
                                    totalDisplayedBytes = totalDisplayedBytes,
                                    fileCount = block.fileCount.takeIf { block.isFolder && it > 0L },
                                    leadingIcon = childIcon,
                                    widthDp = subWidthDp,
                                    heightDp = subHeightDp,
                                    palette = childPalette,
                                    hoverTracker = hoverTracker,
                                    hoverEnabled = hoverEnabled,
                                    clickable = true,
                                    onPrimaryClick = onPrimaryClick,
                                    onContextMenu = onContextMenu,
                                )
                            }
                            isGroupedOther || slot is NestedSlot.Remainder -> {
                                LeafInteractiveBlock(
                                    target = outerTarget,
                                    title = tr("其余项目", "Other items", "その他の項目", "기타 항목", "其餘項目"),
                                    bytes = subTile.bytes,
                                    totalDisplayedBytes = totalDisplayedBytes,
                                    fileCount = null,
                                    leadingIcon = Icons.Outlined.MoreHoriz,
                                    widthDp = subWidthDp,
                                    heightDp = subHeightDp,
                                    palette = otherGroupPalette(colors, isEInk = isEInk),
                                    hoverTracker = hoverTracker,
                                    hoverEnabled = hoverEnabled,
                                    clickable = true,
                                    onPrimaryClick = onPrimaryClick,
                                    onContextMenu = onContextMenu,
                                )
                            }
                            slot == NestedSlot.LoadingArea -> {
                                // 余量加载区不绘制色块背景与边框，直接在留出的底框空位正中呈现加载指示器
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (subWidthDp >= 28.dp && subHeightDp >= 28.dp) {
                                        InlineLoadingIndicator()
                                    }
                                }
                            }
                            else -> Box(Modifier)
                        }
                    }
                },
            ) { measurables, constraints ->
                val placeables = measurables.mapIndexed { idx, measurable ->
                    val r = nestedRects[idx]
                    val w = r.width.roundToInt().coerceAtLeast(1)
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    measurable.measure(Constraints.fixed(w, h))
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { idx, placeable ->
                        val r = nestedRects[idx]
                        placeable.place(r.left.roundToInt(), r.top.roundToInt())
                    }
                }
            }
        }
    }
}

/**
 * 叶子色块组件（顶层独立块与大文件夹内的嵌套子块共用）：
 * 按色块实际宽高分 4 档自适应放大字号与容量/占比读数，解决大块上字太小、信息空洞的问题。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LeafInteractiveBlock(
    target: SpaceInteractiveTarget,
    title: String,
    bytes: Long,
    totalDisplayedBytes: Double,
    fileCount: Long?,
    leadingIcon: ImageVector?,
    widthDp: Dp,
    heightDp: Dp,
    palette: BlockPalette,
    hoverTracker: SpaceHoverTracker,
    hoverEnabled: Boolean,
    clickable: Boolean,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    val haptic = LocalHapticFeedback.current
    val pointerSource = LocalPointerSource.current
    val isHovered by remember(target.targetKey, hoverTracker, hoverEnabled) {
        derivedStateOf { hoverEnabled && hoverTracker.activeTarget?.targetKey == target.targetKey }
    }
    val cornerDp = minOf(8.dp, minOf(widthDp, heightDp) * 0.22f).coerceAtLeast(3.dp)
    val shape = RoundedCornerShape(cornerDp)

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastPressOffset by remember { mutableStateOf(Offset.Zero) }
    var lastPressType by remember { mutableStateOf(PointerType.Unknown) }
    val latestOnClick by rememberUpdatedState(onPrimaryClick)
    val latestOnMenu by rememberUpdatedState(onContextMenu)

    val borderModifier = if (isEInk) {
        Modifier.border(
            width = if (isHovered) 2.dp else 1.dp,
            color = Color.Black,
            shape = shape,
        )
    } else if (palette.dashed) {
        val strokeColor = if (isHovered) palette.hoverBorder else palette.border
        Modifier.drawBehind {
            val strokePx = (if (isHovered) 2.dp else 1.25.dp).toPx()
            val radiusPx = cornerDp.toPx()
            drawRoundRect(
                color = strokeColor,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                ),
            )
        }
    } else {
        Modifier.border(
            width = if (isHovered) 2.25.dp else 1.dp,
            color = if (isHovered) palette.hoverBorder else palette.border,
            shape = shape,
        )
    }

    val interactionModifier = if (clickable) {
        Modifier
            .focusIndication(shape, showPointerFocus = false)
            .combinedClickable(
                role = Role.Button,
                onClick = { latestOnClick(target) },
                onLongClick = {
                    val touch = lastPressType == PointerType.Touch ||
                        lastPressType == PointerType.Stylus ||
                        (pointerSource.isTouchLike && lastPressType != PointerType.Mouse)
                    if (touch) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val pos = coordinates?.takeIf { it.isAttached }?.localToWindow(lastPressOffset)?.round()
                        latestOnMenu(target, pos, true)
                    }
                },
            )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { coordinates = it }
            .pointerInput(target.targetKey, hoverTracker) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press) {
                            lastPressOffset = change.position
                            lastPressType = change.type
                        }
                        if (change.type == PointerType.Mouse) {
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> hoverTracker.hover(target)
                                PointerEventType.Exit -> hoverTracker.leave(target)
                            }
                        }
                    }
                }
            }
            .pointerInput(target.targetKey, clickable) {
                if (!clickable) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                            coordinates?.takeIf { it.isAttached }?.let { coords ->
                                latestOnMenu(target, coords.localToWindow(change.position).round(), false)
                                change.consume()
                            }
                        }
                    }
                }
            }
            .clip(shape)
            .background(palette.container)
            .then(borderModifier)
            .then(interactionModifier),
    ) {
        when {
            // 超大色块（如主目录或占比 > 10% 的核心分支）：大号粗体标题 + 醒目容量与占比
            widthDp >= 220.dp && heightDp >= 140.dp -> {
                val sharePct = ((bytes.toDouble() / totalDisplayedBytes) * 100.0).coerceIn(0.0, 100.0)
                val shareStr = formatPercentage(sharePct)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (leadingIcon != null) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = palette.content,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = palette.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${bytes.toReadableSize()}  ($shareStr)",
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = palette.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (fileCount != null) {
                            Text(
                                text = tr(
                                    "$fileCount 个文件",
                                    enCount(fileCount, "file", "files"),
                                    "$fileCount ファイル",
                                    "파일 ${fileCount}개",
                                    "$fileCount 個檔案",
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = palette.secondaryContent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // 中大色块：中号清晰标题 + 容量读数
            widthDp >= 132.dp && heightDp >= 82.dp -> {
                val sharePct = ((bytes.toDouble() / totalDisplayedBytes) * 100.0).coerceIn(0.0, 100.0)
                val sizeStr = if (widthDp >= 170.dp) {
                    "${bytes.toReadableSize()} (${formatPercentage(sharePct)})"
                } else {
                    bytes.toReadableSize()
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (leadingIcon != null) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = palette.content,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = sizeStr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Medium,
                        color = palette.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 常规中等色块
            widthDp >= 64.dp && heightDp >= 46.dp -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (leadingIcon != null && widthDp >= 82.dp) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = palette.content,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.content,
                            maxLines = if (heightDp >= 68.dp) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = bytes.toReadableSize(),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = palette.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 紧凑窄色块
            widthDp >= 36.dp && heightDp >= 22.dp -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = palette.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
