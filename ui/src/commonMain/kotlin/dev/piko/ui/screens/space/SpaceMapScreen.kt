package dev.piko.ui.screens.space

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpaceNodeItem
import dev.piko.shared.space.SpacePreviewBlock
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.ContributePaletteItems
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.WindowAnchoredActionMenu
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.formatTreeIndexProgressLabel
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.format.formatPercentage
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * 独立空间分布总览页（Squarified Treemap）。
 *
 * 专注于空间洞察、嵌套子块直接交互下钻、可用配额对比与向网盘主列表定位跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceMapScreen(
    rootFolderId: String = "",
    rootFolderName: String = "",
    resetRequests: Int = 0,
    onBackClick: (() -> Unit)?,
    /** 切换回网盘文件页并导航至指定目录。 */
    onOpenFolderInDrive: (folderId: String, folderName: String, breadcrumbs: List<PikoPathBreadcrumb>) -> Unit,
    /** 在网盘中打开条目所在目录并高亮定位它（已知完整父路径时直接零延迟秒跳），找不到返回 false。 */
    onLocate: suspend (fileId: String, knownParentBreadcrumbs: List<PikoPathBreadcrumb>) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val driveRepo = services.driveRepository
    val platform = LocalPikoPlatform.current
    val snackbarHostState = LocalRootSnackbar.current
    val scope = rememberCoroutineScope()
    val shortcutFocus = remember { FocusRequester() }
    val pointerSource = LocalPointerSource.current

    var currentFolderId by rememberSaveable(rootFolderId) { mutableStateOf(rootFolderId) }
    var backHistory by rememberSaveable(rootFolderId) { mutableStateOf(emptyList<String>()) }
    var forwardHistory by rememberSaveable(rootFolderId) { mutableStateOf(emptyList<String>()) }
    var includeVaulted by rememberSaveable { mutableStateOf(false) }

    var handledReset by rememberSaveable { mutableIntStateOf(resetRequests) }
    LaunchedEffect(resetRequests) {
        if (resetRequests != handledReset) {
            handledReset = resetRequests
            if (currentFolderId != rootFolderId || backHistory.isNotEmpty()) {
                currentFolderId = rootFolderId
                backHistory = emptyList()
                forwardHistory = emptyList()
            }
        }
    }

    val client by services.clientManager.currentClient.collectAsStateWithLifecycle()
    val quotaResponse by driveRepo.quotaFlow.collectAsStateWithLifecycle()
    LaunchedEffect(driveRepo, client?.account) {
        if (client != null && quotaResponse == null) {
            driveRepo.getQuota(maxAge = 60.seconds)
        }
    }

    val snapshotFlow = remember(driveRepo, currentFolderId, includeVaulted) {
        driveRepo.observeSpaceFolder(currentFolderId, includeVaulted)
    }
    val snapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val treeBuildProgress by driveRepo.treeBuildProgressFlow.collectAsStateWithLifecycle()

    fun drillInto(folderId: String) {
        if (folderId == currentFolderId) return
        backHistory = backHistory + currentFolderId
        forwardHistory = emptyList()
        currentFolderId = folderId
    }

    /** 点击外层大目录内部的嵌套子目录时直接一步下钻到位，并把中间父目录压入回退栈，按返回可逐级退回。 */
    fun drillIntoNested(parentFolderId: String, childFolderId: String) {
        if (childFolderId == currentFolderId) return
        val additions = if (parentFolderId != currentFolderId && parentFolderId != childFolderId) {
            listOf(currentFolderId, parentFolderId)
        } else {
            listOf(currentFolderId)
        }
        backHistory = backHistory + additions
        forwardHistory = emptyList()
        currentFolderId = childFolderId
    }

    fun goBackInSpace(): Boolean {
        val previous = backHistory.lastOrNull() ?: return false
        backHistory = backHistory.dropLast(1)
        forwardHistory = forwardHistory + currentFolderId
        currentFolderId = previous
        return true
    }

    fun goForwardInSpace(): Boolean {
        val next = forwardHistory.lastOrNull() ?: return false
        forwardHistory = forwardHistory.dropLast(1)
        backHistory = backHistory + currentFolderId
        currentFolderId = next
        return true
    }

    BackHandler(enabled = backHistory.isNotEmpty()) {
        goBackInSpace()
    }

    LaunchedEffect(currentFolderId, pointerSource.isTouchLike) {
        if (!pointerSource.isTouchLike) runCatching { shortcutFocus.requestFocus() }
    }

    val breadcrumbs = remember(snapshot, currentFolderId, rootFolderId, rootFolderName) {
        val fromSnapshot = snapshot?.breadcrumbs.orEmpty()
        when {
            fromSnapshot.isNotEmpty() -> fromSnapshot
            currentFolderId.isEmpty() -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            currentFolderId == rootFolderId && rootFolderName.isNotEmpty() -> listOf(
                PikoDriveRepository.ROOT_BREADCRUMB,
                PikoPathBreadcrumb(rootFolderId, rootFolderName),
            )
            else -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        }
    }

    fun parentBreadcrumbsFor(nestedParentId: String? = null, nestedParentName: String? = null): List<PikoPathBreadcrumb> {
        if (breadcrumbs.isEmpty() || breadcrumbs.first().id.isNotEmpty() || breadcrumbs.last().id != currentFolderId) {
            return emptyList()
        }
        return if (nestedParentId != null && nestedParentId != currentFolderId) {
            val resolvedName = nestedParentName ?: snapshot?.items?.firstOrNull { it.id == nestedParentId }?.name ?: return emptyList()
            breadcrumbs + PikoPathBreadcrumb(nestedParentId, resolvedName)
        } else {
            breadcrumbs
        }
    }

    fun locateInDrive(itemId: String, nestedParentId: String? = null, nestedParentName: String? = null) {
        val knownStack = parentBreadcrumbsFor(nestedParentId, nestedParentName)
        scope.launch {
            if (!onLocate(itemId, knownStack)) {
                snackbarHostState.showSnackbar(
                    tr(
                        "在网盘中未找到该条目，可能已被移动或删除",
                        "Item not found in drive; it may have moved or been deleted",
                        "ドライブ内に項目が見つかりません。移動または削除された可能性があります",
                        "드라이브에서 항목을 찾을 수 없습니다. 이동되었거나 삭제되었을 수 있습니다",
                        "在雲端硬碟中找不到此項目，可能已被移動或刪除",
                    ),
                    withDismissAction = true,
                )
            }
        }
    }

    fun openCurrentInDrive() {
        val currentName = breadcrumbs.lastOrNull()?.takeIf { it.id == currentFolderId }?.localizedName
            ?: rootFolderName.takeIf { currentFolderId == rootFolderId }.orEmpty()
        val completeBreadcrumbs = breadcrumbs.takeIf {
            snapshot?.isTreeComplete == true && it.lastOrNull()?.id == currentFolderId
        }.orEmpty()
        onOpenFolderInDrive(currentFolderId, currentName, completeBreadcrumbs)
    }

    fun openFolderByIdInDrive(folderId: String, folderName: String, parentFolderId: String? = null) {
        val childBreadcrumbs = when {
            snapshot?.isTreeComplete != true || breadcrumbs.lastOrNull()?.id != currentFolderId -> emptyList()
            parentFolderId == null || parentFolderId == currentFolderId -> breadcrumbs + PikoPathBreadcrumb(folderId, folderName)
            else -> {
                val parentName = snapshot?.items?.firstOrNull { it.id == parentFolderId }?.name
                if (parentName != null) {
                    breadcrumbs + PikoPathBreadcrumb(parentFolderId, parentName) + PikoPathBreadcrumb(folderId, folderName)
                } else {
                    emptyList()
                }
            }
        }
        onOpenFolderInDrive(folderId, folderName, childBreadcrumbs)
    }

    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val primary = platform.shortcutModifier.isPressed(event)
        val isMac = platform.shortcutModifier == ShortcutModifier.Command
        return when {
            (event.isAltPressed && event.key == Key.DirectionLeft) ||
                (isMac && primary && event.key == Key.LeftBracket) ||
                (!isMac && !primary && event.key == Key.Backspace) -> {
                if (goBackInSpace()) true
                else if (onBackClick != null) {
                    onBackClick()
                    true
                } else {
                    false
                }
            }
            (event.isAltPressed && event.key == Key.DirectionRight) ||
                (isMac && primary && event.key == Key.RightBracket) -> goForwardInSpace()
            ((!isMac && event.isAltPressed) || (isMac && primary)) &&
                event.key == Key.DirectionUp && breadcrumbs.size > 1 -> {
                drillInto(breadcrumbs[breadcrumbs.lastIndex - 1].id)
                true
            }
            else -> false
        }
    }

    ContributePaletteItems("space_map") {
        buildList {
            add(
                PaletteItem(
                    title = if (includeVaulted) {
                        tr("隐藏归档内容", "Hide archived items", "アーカイブ項目を隠す", "보관 항목 숨기기", "隱藏歸檔內容")
                    } else {
                        tr("显示归档内容", "Show archived items", "アーカイブ項目を表示", "보관 항목 표시", "顯示歸檔內容")
                    },
                    icon = Icons.Outlined.Inventory2,
                    group = tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈"),
                    keywords = "vault archive 归档 配额",
                ) { includeVaulted = !includeVaulted },
            )
            add(
                PaletteItem(
                    title = tr("在网盘中打开当前目录", "Open current folder in drive", "現在のフォルダーをドライブで開く", "현재 폴더를 드라이브에서 열기", "在雲端硬碟中開啟目前資料夾"),
                    icon = Icons.Outlined.FolderOpen,
                    group = tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈"),
                    keywords = "open drive folder 打开 网盘",
                ) { openCurrentInDrive() },
            )
            if (backHistory.isNotEmpty()) {
                add(
                    PaletteItem(
                        title = tr("返回上一层目录", "Back to previous folder", "前のフォルダーに戻る", "이전 폴더로 돌아가기", "返回上一層資料夾"),
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        group = tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈"),
                        keywords = "back previous 返回",
                    ) { goBackInSpace() },
                )
            }
        }
    }

    val pageTitle = tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈")
    val isCalculating = snapshot?.isTreeBuilding == true || treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates
    val displayedTotalBytes = snapshot?.let {
        if (includeVaulted) it.physicalBytes + it.vaultedBytes else it.physicalBytes
    } ?: 0L

    // 根目录下计算未使用的剩余配额空间，使可用空间与已用目录按真实比例同屏对比
    val cloudQuota = quotaResponse?.quota?.takeIf { it.limitBytes > 0L }
    val rootFreeQuotaBytes = remember(currentFolderId, cloudQuota, snapshot?.physicalBytes) {
        if (currentFolderId.isNotEmpty() || cloudQuota == null || snapshot == null) {
            0L
        } else {
            val effectiveUsed = max(cloudQuota.usageBytes, snapshot?.physicalBytes ?: 0L)
            (cloudQuota.limitBytes - effectiveUsed).coerceAtLeast(0L)
        }
    }

    val summaryText = remember(snapshot, includeVaulted, isCalculating, treeBuildProgress, displayedTotalBytes, rootFreeQuotaBytes) {
        val current = snapshot
        when {
            current == null -> if (treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates) {
                formatTreeIndexProgressLabel(treeBuildProgress)
            } else {
                tr("读取统计…", "Loading statistics…", "統計を読み込み中…", "통계 불러오는 중…", "讀取統計…")
            }
            current.syncError != null -> current.syncError.orEmpty()
            treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates -> formatTreeIndexProgressLabel(treeBuildProgress)
            isCalculating -> tr(
                "正在统计 ${displayedTotalBytes.toReadableSize()}",
                "Calculating ${displayedTotalBytes.toReadableSize()}",
                "集計中 ${displayedTotalBytes.toReadableSize()}",
                "계산 중 ${displayedTotalBytes.toReadableSize()}",
                "正在統計 ${displayedTotalBytes.toReadableSize()}",
            )
            current.fileCount > 0L -> tr(
                "${displayedTotalBytes.toReadableSize()}（${current.fileCount} 个文件）",
                "${displayedTotalBytes.toReadableSize()} (${enCount(current.fileCount, "file", "files")})",
                "${displayedTotalBytes.toReadableSize()}（${current.fileCount} ファイル）",
                "${displayedTotalBytes.toReadableSize()} (파일 ${current.fileCount}개)",
                "${displayedTotalBytes.toReadableSize()}（${current.fileCount} 個檔案）",
            )
            else -> displayedTotalBytes.toReadableSize()
        }
    }

    val toggleVaultedLabel = tr("显示归档内容", "Show archived items", "アーカイブ項目を表示", "보관 항목 표시", "顯示歸檔內容")
    val openInDriveLabel = tr("在网盘中打开", "Open in drive", "ドライブで開く", "드라이브에서 열기", "在雲端硬碟中開啟")

    val toggleVaultedAction = SheetAction(
        icon = Icons.Outlined.Inventory2,
        label = toggleVaultedLabel,
        onClick = { includeVaulted = !includeVaulted },
        group = ActionGroup.View,
        checked = includeVaulted,
    )
    val openInDriveAction = SheetAction(
        icon = Icons.Outlined.FolderOpen,
        label = openInDriveLabel,
        onClick = ::openCurrentInDrive,
        group = ActionGroup.Open,
    )

    val canNavigateBack = backHistory.isNotEmpty() || onBackClick != null
    val backLabel = if (backHistory.isNotEmpty()) {
        tr("返回上一层", "Back to previous folder", "前のフォルダーに戻る", "이전 폴더로 돌아가기", "返回上一層")
    } else {
        tr("返回", "Back", "戻る", "뒤로", "返回")
    }
    val onNavigateBack: () -> Unit = {
        if (!goBackInSpace()) onBackClick?.invoke()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pageFocusTarget(shortcutFocus)
            .onKeyEvent(::handleShortcut)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        when {
                            event.buttons.isBackPressed -> onNavigateBack()
                            event.buttons.isForwardPressed -> goForwardInSpace()
                        }
                    }
                }
            },
    ) {
        PikoScaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            island = IslandPage(
                header = {
                    AdaptiveBar(
                        modifier = Modifier.weight(1f),
                        leading = if (canNavigateBack) {
                            listOf(
                                BarItem("back", PinnedPriority) {
                                    TooltipIconButton(
                                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                        label = backLabel,
                                        onClick = onNavigateBack,
                                    )
                                },
                            )
                        } else {
                            emptyList()
                        },
                        middle = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                IslandTitle(pageTitle)
                                StatusSummaryBadge(isCalculating = isCalculating, text = summaryText)
                            }
                        },
                        middleMinWidth = 120.dp,
                        reserveMiddle = true,
                        trailing = listOf(
                            BarItem(
                                key = "includeVaulted",
                                priority = 80,
                                overflow = listOf(toggleVaultedAction),
                            ) {
                                IncludeVaultedChip(
                                    checked = includeVaulted,
                                    label = toggleVaultedLabel,
                                    onToggle = { includeVaulted = !includeVaulted },
                                )
                            },
                            BarItem(
                                key = "openInDrive",
                                priority = 90,
                                overflow = listOf(openInDriveAction),
                            ) {
                                FilledTonalButton(
                                    onClick = ::openCurrentInDrive,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(openInDriveLabel, maxLines = 1)
                                }
                            },
                        ),
                        gap = 8.dp,
                        dragWindow = true,
                    )
                },
                belowHeader = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BreadcrumbBar(
                            breadcrumbs = breadcrumbs,
                            onBreadcrumbClick = { index ->
                                breadcrumbs.getOrNull(index)?.let { drillInto(it.id) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        val current = snapshot
                        if (current != null && (includeVaulted && current.vaultedBytes > 0L || rootFreeQuotaBytes > 0L)) {
                            Spacer(Modifier.width(12.dp))
                            val breakdownText = buildList {
                                add(
                                    tr(
                                        "占配额 ${current.physicalBytes.toReadableSize()}",
                                        "Quota ${current.physicalBytes.toReadableSize()}",
                                        "容量枠 ${current.physicalBytes.toReadableSize()}",
                                        "할당량 ${current.physicalBytes.toReadableSize()}",
                                        "佔配額 ${current.physicalBytes.toReadableSize()}",
                                    ),
                                )
                                if (includeVaulted && current.vaultedBytes > 0L) {
                                    add(
                                        tr(
                                            "归档 ${current.vaultedBytes.toReadableSize()}",
                                            "Archived ${current.vaultedBytes.toReadableSize()}",
                                            "アーカイブ ${current.vaultedBytes.toReadableSize()}",
                                            "보관 ${current.vaultedBytes.toReadableSize()}",
                                            "歸檔 ${current.vaultedBytes.toReadableSize()}",
                                        ),
                                    )
                                }
                                if (rootFreeQuotaBytes > 0L) {
                                    add(
                                        tr(
                                            "可用 ${rootFreeQuotaBytes.toReadableSize()}",
                                            "Free ${rootFreeQuotaBytes.toReadableSize()}",
                                            "空き ${rootFreeQuotaBytes.toReadableSize()}",
                                            "여유 ${rootFreeQuotaBytes.toReadableSize()}",
                                            "可用 ${rootFreeQuotaBytes.toReadableSize()}",
                                        ),
                                    )
                                }
                            }.joinToString("   ")
                            Text(
                                text = breakdownText,
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
            ),
            topBar = {
                PikoTopBar(
                    title = pageTitle,
                    navigationIcon = if (canNavigateBack) {
                        {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backLabel)
                            }
                        }
                    } else {
                        null
                    },
                    actions = listOf(
                        iconBarItem(
                            icon = Icons.Outlined.FolderOpen,
                            label = openInDriveLabel,
                            onClick = ::openCurrentInDrive,
                            priority = 90,
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                if (!dev.piko.ui.theme.LocalFramed.current) {
                    BreadcrumbBar(
                        breadcrumbs = breadcrumbs,
                        onBreadcrumbClick = { index ->
                            breadcrumbs.getOrNull(index)?.let { drillInto(it.id) }
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            StatusSummaryBadge(isCalculating = isCalculating, text = summaryText)
                        }
                        IncludeVaultedChip(
                            checked = includeVaulted,
                            label = toggleVaultedLabel,
                            onToggle = { includeVaulted = !includeVaulted },
                        )
                    }
                }

                snapshot?.syncError?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { driveRepo.refreshSpaceFolder(currentFolderId) }) {
                            Text(tr("重试", "Retry", "再試行", "다시 시도", "重試"))
                        }
                    }
                }
                SpaceTreemapContent(
                    snapshot = snapshot,
                    treeBuildProgress = treeBuildProgress,
                    includeVaulted = includeVaulted,
                    freeQuotaBytes = rootFreeQuotaBytes,
                    quotaLimitBytes = cloudQuota?.limitBytes ?: 0L,
                    onEnableIncludeVaulted = { includeVaulted = true },
                    onDrillInto = ::drillInto,
                    onDrillIntoNested = ::drillIntoNested,
                    onOpenFolderInDrive = ::openFolderByIdInDrive,
                    onLocate = ::locateInDrive,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryBadge(isCalculating: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isCalculating) {
            InlineLoadingIndicator()
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (isCalculating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IncludeVaultedChip(
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
) {
    FilterChip(
        selected = checked,
        onClick = onToggle,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

/** 顶层树图中的条目：既包含真实目录/文件节点，也包含根目录下的「未使用配额空间」色块。 */
private sealed interface SpaceDisplayEntry {
    val key: String
    val bytes: Long

    data class Node(val item: SpaceNodeItem, override val bytes: Long, val hueIndex: Int) : SpaceDisplayEntry {
        override val key: String get() = item.id
    }

    data class FreeQuota(override val bytes: Long, val limitBytes: Long) : SpaceDisplayEntry {
        override val key: String get() = "__piko_free_quota__"
    }
}

/** 鼠标悬停或右键/长按命中的具体交互目标（既可是顶层色块，也可是外层大文件夹内部的嵌套子色块）。 */
private sealed interface SpaceInteractiveTarget {
    val targetKey: String
    val displayBytes: Long

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
        override val targetKey: String
            get() = if (nestedUnderFolderId != null) "nested:$nestedUnderFolderId/$id" else "node:$id"
    }

    data class OtherGroup(
        val groupId: String,
        val items: List<SpaceNodeItem>,
        override val displayBytes: Long,
    ) : SpaceInteractiveTarget {
        override val targetKey: String get() = "other:$groupId"
    }

    data class FreeQuota(
        override val displayBytes: Long,
        val limitBytes: Long,
    ) : SpaceInteractiveTarget {
        override val targetKey: String get() = "__piko_free_quota__"
    }
}

/**
 * 隔离指针高频坐标状态：鼠标在同一个色块内部移动时仅在布局阶段更新悬浮卡片偏移量，
 * 不触发整棵 `SpaceTreemapContent` 与全部色块的重组。
 */
@Stable
private class SpaceHoverTracker {
    var pointer by mutableStateOf(Offset.Zero)
    var activeTarget by mutableStateOf<SpaceInteractiveTarget?>(null)

    fun clear() {
        activeTarget = null
    }
}

private class TargetMenuRequest(
    val target: SpaceInteractiveTarget,
    val pointerInWindow: IntOffset,
)

@Composable
private fun SpaceTreemapContent(
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
    val isCalculating = snapshot?.isTreeBuilding == true || treeBuildProgress.isBuilding
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
                            text = if (treeBuildProgress.isBuilding) {
                                formatTreeIndexProgressLabel(treeBuildProgress)
                            } else {
                                tr(
                                    "正在建立本地索引…",
                                    "Building local index…",
                                    "ローカル索引を構築しています…",
                                    "로컬 색인을 구축하는 중…",
                                    "正在建立本機索引…",
                                )
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = tr(
                                "首次建立完成后将持久保存在本机，后续打开与跨目录排序可直接秒开并增量复用",
                                "Once built, the index is persisted locally for instant startup and incremental updates",
                                "初回構築後は端末に保存され、次回から即座に表示・差分更新されます",
                                "최초 구축이 완료되면 기기에 저장되어 다음부터는 즉시 열리고 증분 업데이트됩니다",
                                "首次建立完成後將持久保存在本機，後續開啟與跨資料夾排序可直接秒開並增量複用",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            "当前尚未发现占用配额的文件",
                            "No quota-consuming files found yet",
                            "容量枠を使用しているファイルはまだありません",
                            "할당량을 사용하는 파일이 아직 없습니다",
                            "目前尚未發現佔用配額的檔案",
                        ),
                    )
                }
                else -> {
                    PikoEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = tr(
                            "暂无占用空间的条目",
                            "No items using storage",
                            "容量を使用している項目はありません",
                            "공간을 사용하는 항목이 없습니다",
                            "暫無佔用空間的項目",
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

    fun actionsForTarget(target: SpaceInteractiveTarget): List<SheetAction> = when (target) {
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
                onClick = { activeOtherGroup = target.items },
                group = ActionGroup.Open,
            ),
        )
        is SpaceInteractiveTarget.FreeQuota -> emptyList()
    }

    fun handleTargetPrimaryClick(target: SpaceInteractiveTarget) {
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
        if (target is SpaceInteractiveTarget.FreeQuota) return
        if ((!fromTouch || desktopLayout) && windowPos != null) {
            desktopMenuRequest = TargetMenuRequest(target, windowPos)
        } else {
            mobileSheetTarget = target
        }
    }

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
            val activeHoverKey = if (pointerSource.isTouchLike || desktopMenuRequest != null) {
                null
            } else {
                hoverTracker.activeTarget?.targetKey
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
                            activeHoverKey = activeHoverKey,
                            onHoverTarget = { target -> hoverTracker.activeTarget = target },
                            onLeaveTarget = { target ->
                                if (hoverTracker.activeTarget?.targetKey == target.targetKey) {
                                    hoverTracker.activeTarget = null
                                }
                            },
                            onPrimaryClick = ::handleTargetPrimaryClick,
                            onContextMenu = ::handleTargetMenu,
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

        val currentHovered = if (pointerSource.isTouchLike || desktopMenuRequest != null) null else hoverTracker.activeTarget
        if (currentHovered != null) {
            SpaceHoverCard(
                target = currentHovered,
                totalBytes = totalDisplayedBytes,
                includeVaulted = includeVaulted,
                hoverTracker = hoverTracker,
                canvasSize = canvasSize,
            )
        }

        desktopMenuRequest?.let { req ->
            val actions = actionsForTarget(req.target)
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
                actions = actionsForTarget(target),
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

private class BlockPalette(
    val container: Color,
    val headerContainer: Color,
    val content: Color,
    val secondaryContent: Color,
    val border: Color,
    val hoverBorder: Color,
    val dashed: Boolean = false,
)

/**
 * 8 色和谐色环种子（兼顾明暗主题色相对比）：用于区分并列大目录与大容器内的子分支，
 * 再叠加上按体量排名的饱和度衰减，做到「大块鲜明突出、小块柔和退后、归档与可用空间一眼可辨」。
 */
private val LightBranchSeeds = listOf(
    Color(0xFF356AE6), // 钴蓝 Sapphire
    Color(0xFF0E8A7D), // 青碧 Teal
    Color(0xFFD97724), // 琥珀 Amber
    Color(0xFF7C4DFF), // 紫罗兰 Iris
    Color(0xFF2E8B57), // 翠绿 Emerald
    Color(0xFFC84B6B), // 珊瑚玫红 Rose
    Color(0xFF1F7EA8), // 湖蓝 Cerulean
    Color(0xFF9C6631), // 赭石 Ochre
)

private val DarkBranchSeeds = listOf(
    Color(0xFF6B9BFF), // 亮钴蓝
    Color(0xFF45C4B5), // 亮青碧
    Color(0xFFF3A152), // 亮琥珀
    Color(0xFFB18CFF), // 亮紫罗兰
    Color(0xFF63C78A), // 亮翠绿
    Color(0xFFF07D9A), // 亮珊瑚
    Color(0xFF56B8E6), // 亮湖蓝
    Color(0xFFD6A16A), // 亮赭石
)

private fun resolveBlockPalette(
    hueIndex: Int,
    rankFraction: Float,
    isFolder: Boolean,
    isPurelyVaulted: Boolean,
    isOuterGroupFrame: Boolean,
    colors: ColorScheme,
): BlockPalette {
    val isDark = colors.surface.luminance() < 0.5f
    val seeds = if (isDark) DarkBranchSeeds else LightBranchSeeds
    val seed = seeds[(hueIndex and Int.MAX_VALUE) % seeds.size]

    if (isPurelyVaulted) {
        val container = if (isDark) {
            lerp(colors.tertiaryContainer, colors.surfaceContainerHigh, if (isOuterGroupFrame) 0.55f else 0.35f + rankFraction * 0.20f)
        } else {
            lerp(colors.tertiaryContainer, colors.surfaceContainerLow, if (isOuterGroupFrame) 0.52f else 0.24f + rankFraction * 0.22f)
        }
        val header = lerp(container, colors.tertiary, if (isDark) 0.16f else 0.12f)
        val fg = if (container.luminance() < 0.45f) Color(0xFFF4EFF8) else Color(0xFF2A1F33)
        return BlockPalette(
            container = container,
            headerContainer = header,
            content = fg,
            secondaryContent = fg.copy(alpha = 0.78f),
            border = colors.tertiary.copy(alpha = if (isDark) 0.42f else 0.36f),
            hoverBorder = colors.tertiary,
        )
    }

    if (isOuterGroupFrame) {
        // 含嵌套子块的外层大目录退为沉稳的结构底框，把视觉焦点让给内部彩色子块
        val frameBase = if (isDark) colors.surfaceContainer else colors.surfaceContainerHigh
        val container = lerp(frameBase, seed, if (isDark) 0.14f else 0.12f)
        val header = lerp(frameBase, seed, if (isDark) 0.28f else 0.24f)
        val fg = if (header.luminance() < 0.42f) Color(0xFFF3F5FA) else Color(0xFF171C26)
        return BlockPalette(
            container = container,
            headerContainer = header,
            content = fg,
            secondaryContent = fg.copy(alpha = 0.78f),
            border = lerp(colors.outlineVariant, seed, 0.45f),
            hoverBorder = seed,
        )
    }

    // 占配额真实块：排名越靠前色彩越饱满（主），越往长尾越向中性容器色收敛（次）；文件比文件夹略轻一档
    val rankAttenuation = rankFraction.coerceIn(0f, 1f) * 0.16f
    val fileAttenuation = if (isFolder) 0f else 0.08f
    val tintStrength = if (isDark) {
        (0.36f - rankAttenuation - fileAttenuation).coerceAtLeast(0.16f)
    } else {
        (0.32f - rankAttenuation - fileAttenuation).coerceAtLeast(0.14f)
    }
    val surfaceAnchor = if (isDark) colors.surfaceContainerHigh else Color(0xFFF8F9FC)
    val container = lerp(surfaceAnchor, seed, tintStrength)
    val fg = if (container.luminance() < 0.44f) Color(0xFFF6F8FC) else Color(0xFF141923)
    return BlockPalette(
        container = container,
        headerContainer = container,
        content = fg,
        secondaryContent = fg.copy(alpha = 0.76f),
        border = lerp(container, seed, if (isDark) 0.50f else 0.45f),
        hoverBorder = if (isDark) lerp(seed, Color.White, 0.25f) else lerp(seed, Color.Black, 0.15f),
    )
}

private fun freeQuotaPalette(colors: ColorScheme): BlockPalette {
    val isDark = colors.surface.luminance() < 0.5f
    val bg = if (isDark) colors.surfaceContainerLowest else colors.surfaceContainerLow
    return BlockPalette(
        container = bg,
        headerContainer = bg,
        content = colors.onSurfaceVariant,
        secondaryContent = colors.onSurfaceVariant.copy(alpha = 0.78f),
        border = colors.outlineVariant.copy(alpha = 0.75f),
        hoverBorder = colors.outline,
        dashed = true,
    )
}

private fun otherGroupPalette(colors: ColorScheme): BlockPalette = BlockPalette(
    container = colors.surfaceContainerHighest,
    headerContainer = colors.surfaceContainerHighest,
    content = colors.onSurface,
    secondaryContent = colors.onSurfaceVariant,
    border = colors.outlineVariant.copy(alpha = 0.70f),
    hoverBorder = colors.primary,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceTreemapOuterTile(
    tile: TreemapTile<SpaceDisplayEntry>,
    rect: TreemapRect,
    includeVaulted: Boolean,
    isTreeBuilding: Boolean,
    totalDisplayedBytes: Double,
    rankFraction: Float,
    activeHoverKey: String?,
    onHoverTarget: (SpaceInteractiveTarget) -> Unit,
    onLeaveTarget: (SpaceInteractiveTarget) -> Unit,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
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
            val title = tr(
                "其他 ${groupedNodes.size} 项",
                "${groupedNodes.size} other items",
                "ほか ${groupedNodes.size} 項目",
                "기타 ${groupedNodes.size}개 항목",
                "其他 ${groupedNodes.size} 項",
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
                palette = otherGroupPalette(colors),
                isHovered = activeHoverKey == target.targetKey,
                clickable = groupedNodes.isNotEmpty(),
                onHoverTarget = onHoverTarget,
                onLeaveTarget = onLeaveTarget,
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
                        palette = freeQuotaPalette(colors),
                        isHovered = activeHoverKey == target.targetKey,
                        clickable = false,
                        onHoverTarget = onHoverTarget,
                        onLeaveTarget = onLeaveTarget,
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
                            activeHoverKey = activeHoverKey,
                            onHoverTarget = onHoverTarget,
                            onLeaveTarget = onLeaveTarget,
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
                            isHovered = activeHoverKey == outerTarget.targetKey,
                            clickable = true,
                            onHoverTarget = onHoverTarget,
                            onLeaveTarget = onLeaveTarget,
                            onPrimaryClick = onPrimaryClick,
                            onContextMenu = onContextMenu,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface NestedSlot {
    data class Child(val block: SpacePreviewBlock, val hueIndex: Int) : NestedSlot
    data class Remainder(val bytes: Long) : NestedSlot
    data object LoadingArea : NestedSlot
}

/**
 * 含嵌套子块的大文件夹组：
 * - 顶部 36dp 标题头栏代表父文件夹自身（悬停/点击操作父文件夹）；
 * - 内部每个子块均为独立可交互节点，鼠标悬停直接高亮并展示该子块详情，单击直接一步下钻进入该子块；
 * - 过小的长尾子项自动折叠为单个「其余项目」块，避免单屏实例化上千个微观节点造成主线程卡顿。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NestedGroupTile(
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
    activeHoverKey: String?,
    onHoverTarget: (SpaceInteractiveTarget) -> Unit,
    onLeaveTarget: (SpaceInteractiveTarget) -> Unit,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val pointerSource = LocalPointerSource.current
    val outerShape = RoundedCornerShape(10.dp)
    val isHeaderHovered = activeHoverKey == outerTarget.targetKey

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
    val latestOnHover by rememberUpdatedState(onHoverTarget)
    val latestOnLeave by rememberUpdatedState(onLeaveTarget)
    val latestOnClick by rememberUpdatedState(onPrimaryClick)
    val latestOnMenu by rememberUpdatedState(onContextMenu)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(outerShape)
            .background(framePalette.container)
            .border(
                width = if (isHeaderHovered) 2.dp else 1.5.dp,
                color = if (isHeaderHovered) framePalette.hoverBorder else framePalette.border,
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
                .pointerInput(outerTarget.targetKey) {
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
                                    PointerEventType.Enter, PointerEventType.Move -> latestOnHover(outerTarget)
                                    PointerEventType.Exit -> latestOnLeave(outerTarget)
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
                                    isHovered = activeHoverKey == subTarget.targetKey,
                                    clickable = true,
                                    onHoverTarget = onHoverTarget,
                                    onLeaveTarget = onLeaveTarget,
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
                                    palette = otherGroupPalette(colors),
                                    isHovered = activeHoverKey == outerTarget.targetKey,
                                    clickable = true,
                                    onHoverTarget = onHoverTarget,
                                    onLeaveTarget = onLeaveTarget,
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
private fun LeafInteractiveBlock(
    target: SpaceInteractiveTarget,
    title: String,
    bytes: Long,
    totalDisplayedBytes: Double,
    fileCount: Long?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    widthDp: Dp,
    heightDp: Dp,
    palette: BlockPalette,
    isHovered: Boolean,
    clickable: Boolean,
    onHoverTarget: (SpaceInteractiveTarget) -> Unit,
    onLeaveTarget: (SpaceInteractiveTarget) -> Unit,
    onPrimaryClick: (SpaceInteractiveTarget) -> Unit,
    onContextMenu: (target: SpaceInteractiveTarget, windowPos: IntOffset?, fromTouch: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val pointerSource = LocalPointerSource.current
    val cornerDp = minOf(8.dp, minOf(widthDp, heightDp) * 0.22f).coerceAtLeast(3.dp)
    val shape = RoundedCornerShape(cornerDp)

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastPressOffset by remember { mutableStateOf(Offset.Zero) }
    var lastPressType by remember { mutableStateOf(PointerType.Unknown) }
    val latestOnHover by rememberUpdatedState(onHoverTarget)
    val latestOnLeave by rememberUpdatedState(onLeaveTarget)
    val latestOnClick by rememberUpdatedState(onPrimaryClick)
    val latestOnMenu by rememberUpdatedState(onContextMenu)

    val borderModifier = if (palette.dashed) {
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
            .pointerInput(target.targetKey) {
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
                                PointerEventType.Enter, PointerEventType.Move -> latestOnHover(target)
                                PointerEventType.Exit -> latestOnLeave(target)
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

@Composable
private fun SpaceHoverCard(
    target: SpaceInteractiveTarget,
    totalBytes: Double,
    includeVaulted: Boolean,
    hoverTracker: SpaceHoverTracker,
    canvasSize: IntSize,
) {
    val density = LocalDensity.current
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val offsetPx = with(density) { 16.dp.roundToPx() }
    val edgeMarginPx = with(density) { 8.dp.roundToPx() }

    val sharePercent = ((target.displayBytes.toDouble() / totalBytes) * 100.0).coerceIn(0.0, 100.0)
    val shareText = formatPercentage(sharePercent)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
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
                    Text(
                        text = tr(
                            "占配额 ${target.physicalBytes.toReadableSize()}（$shareText）",
                            "Quota used: ${target.physicalBytes.toReadableSize()} ($shareText)",
                            "容量枠使用 ${target.physicalBytes.toReadableSize()}（$shareText）",
                            "할당량 사용 ${target.physicalBytes.toReadableSize()} ($shareText)",
                            "佔配額 ${target.physicalBytes.toReadableSize()}（$shareText）",
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target.vaultedBytes > 0L) {
                        Text(
                            text = tr(
                                "归档大小 ${target.vaultedBytes.toReadableSize()}",
                                "Archived: ${target.vaultedBytes.toReadableSize()}",
                                "アーカイブ ${target.vaultedBytes.toReadableSize()}",
                                "보관 크기 ${target.vaultedBytes.toReadableSize()}",
                                "歸檔大小 ${target.vaultedBytes.toReadableSize()}",
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (target.isFolder) {
                        val countText = tr(
                            "${target.fileCount} 个文件",
                            enCount(target.fileCount, "file", "files"),
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
                        color = MaterialTheme.colorScheme.primary,
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
private fun TileActionSheet(
    target: SpaceInteractiveTarget,
    includeVaulted: Boolean,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (target.isFolder) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                target.isVaulted -> Icons.Outlined.Inventory2
                                target.isFolder -> Icons.Outlined.Folder
                                else -> Icons.Outlined.Description
                            },
                            contentDescription = null,
                            tint = if (target.isFolder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
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
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
private fun OtherGroupSheet(
    items: List<SpaceNodeItem>,
    includeVaulted: Boolean,
    onDismiss: () -> Unit,
    onDrillInto: (folderId: String) -> Unit,
    onLocate: (itemId: String) -> Unit,
) {
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
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            sheet.hideThen {
                                if (item.isFolder) onDrillInto(item.id) else onLocate(item.id)
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                purelyVaulted -> Icons.Outlined.Inventory2
                                item.isFolder -> Icons.Outlined.Folder
                                else -> Icons.Outlined.Description
                            },
                            contentDescription = null,
                            tint = when {
                                purelyVaulted -> MaterialTheme.colorScheme.tertiary
                                item.isFolder -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
