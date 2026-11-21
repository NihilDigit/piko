package dev.piko.ui.screens.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.ContributePaletteItems
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.formatTreeIndexProgressLabel
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlin.math.max
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
    val isEInk = LocalAppearance.current.isEInk

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

    val snapshotCache = remember(client?.account) {
        mutableStateMapOf<Pair<String, Boolean>, SpaceFolderSnapshot>()
    }
    val snapshotFlow = remember(driveRepo, currentFolderId, includeVaulted) {
        driveRepo.observeSpaceFolder(currentFolderId, includeVaulted)
    }
    val rawSnapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = snapshotCache[currentFolderId to includeVaulted])
    LaunchedEffect(rawSnapshot, includeVaulted) {
        val current = rawSnapshot
        if (current != null) {
            snapshotCache[current.folderId to includeVaulted] = current
        }
    }
    val snapshot = rawSnapshot?.takeIf { it.folderId == currentFolderId } ?: snapshotCache[currentFolderId to includeVaulted]
    val treeBuildProgress by driveRepo.treeBuildProgressFlow.collectAsStateWithLifecycle()
    val isBlockingLoad = snapshot == null || (!snapshot.isTreeComplete && (snapshot.isTreeBuilding || treeBuildProgress.isBuilding))
    val isCalculating = isBlockingLoad || snapshot?.isTreeBuilding == true || treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates
    var pendingBreadcrumbs by remember(rootFolderId) { mutableStateOf<List<PikoPathBreadcrumb>>(emptyList()) }

    val breadcrumbs = remember(snapshot, pendingBreadcrumbs, currentFolderId, rootFolderId, rootFolderName) {
        val fromSnapshot = snapshot?.breadcrumbs.orEmpty()
        val fromPending = pendingBreadcrumbs.takeIf { it.lastOrNull()?.id == currentFolderId }.orEmpty()
        when {
            fromSnapshot.isNotEmpty() -> fromSnapshot
            fromPending.isNotEmpty() -> fromPending
            currentFolderId.isEmpty() -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            currentFolderId == rootFolderId && rootFolderName.isNotEmpty() -> listOf(
                PikoDriveRepository.ROOT_BREADCRUMB,
                PikoPathBreadcrumb(rootFolderId, rootFolderName),
            )
            else -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        }
    }

    fun drillInto(folderId: String) {
        if (isBlockingLoad || folderId == currentFolderId) return
        val existingIndex = breadcrumbs.indexOfFirst { it.id == folderId }
        pendingBreadcrumbs = when {
            existingIndex >= 0 -> breadcrumbs.take(existingIndex + 1)
            else -> {
                val childName = snapshot?.items?.firstOrNull { it.id == folderId }?.name
                if (childName != null) breadcrumbs + PikoPathBreadcrumb(folderId, childName) else emptyList()
            }
        }
        backHistory = backHistory + currentFolderId
        forwardHistory = emptyList()
        currentFolderId = folderId
    }

    /** 点击外层大目录内部的嵌套子目录时直接一步下钻到位，并把中间父目录压入回退栈，按返回可逐级退回。 */
    fun drillIntoNested(parentFolderId: String, childFolderId: String) {
        if (isBlockingLoad || childFolderId == currentFolderId) return
        val parentItem = snapshot?.items?.firstOrNull { it.id == parentFolderId }
        val childName = parentItem?.previewChildren?.firstOrNull { it.id == childFolderId }?.name
        if (parentItem != null && childName != null) {
            pendingBreadcrumbs = breadcrumbs +
                PikoPathBreadcrumb(parentFolderId, parentItem.name) +
                PikoPathBreadcrumb(childFolderId, childName)
        } else {
            pendingBreadcrumbs = emptyList()
        }
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
        val existingIndex = breadcrumbs.indexOfFirst { it.id == previous }
        pendingBreadcrumbs = if (existingIndex >= 0) breadcrumbs.take(existingIndex + 1) else emptyList()
        backHistory = backHistory.dropLast(1)
        forwardHistory = forwardHistory + currentFolderId
        currentFolderId = previous
        return true
    }

    fun goForwardInSpace(): Boolean {
        if (isBlockingLoad) return false
        val next = forwardHistory.lastOrNull() ?: return false
        pendingBreadcrumbs = emptyList()
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
        if (isBlockingLoad) return
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
        if (isBlockingLoad) return
        val currentName = breadcrumbs.lastOrNull()?.takeIf { it.id == currentFolderId }?.localizedName
            ?: rootFolderName.takeIf { currentFolderId == rootFolderId }.orEmpty()
        val completeBreadcrumbs = breadcrumbs.takeIf {
            snapshot?.isTreeComplete == true && it.lastOrNull()?.id == currentFolderId
        }.orEmpty()
        onOpenFolderInDrive(currentFolderId, currentName, completeBreadcrumbs)
    }

    fun openFolderByIdInDrive(folderId: String, folderName: String, parentFolderId: String? = null) {
        if (isBlockingLoad) return
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
    val displayedTotalBytes = snapshot?.let {
        if (includeVaulted) it.physicalBytes + it.vaultedBytes else it.physicalBytes
    } ?: 0L

    // 根目录下计算未使用的剩余配额空间，使可用空间与已用目录按真实比例同屏对比
    val cloudQuota = quotaResponse?.quota?.takeIf { it.limitBytes > 0L }
    val rootFreeQuotaBytes = remember(currentFolderId, cloudQuota, snapshot?.physicalBytes) {
        if (currentFolderId.isNotEmpty() || cloudQuota == null || snapshot == null) {
            0L
        } else {
            val effectiveUsed = max(cloudQuota.usageBytes, snapshot.physicalBytes)
            (cloudQuota.limitBytes - effectiveUsed).coerceAtLeast(0L)
        }
    }

    val showQuotaLegend = snapshot != null && (
        (includeVaulted && snapshot.vaultedBytes > 0L) ||
            rootFreeQuotaBytes > 0L ||
            (currentFolderId.isEmpty() && cloudQuota != null)
        )

    val summaryText = remember(snapshot, includeVaulted, isCalculating, treeBuildProgress, displayedTotalBytes, showQuotaLegend) {
        val current = snapshot
        when {
            current == null -> if (treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates) {
                formatTreeIndexProgressLabel(treeBuildProgress)
            } else {
                tr("读取统计…", "Loading statistics…", "統計を読み込み中…", "통계 불러오는 중…", "讀取統計…")
            }
            current.syncError != null -> tr("统计同步失败", "Sync failed", "同期失敗", "동기화 실패", "統計同步失敗")
            treeBuildProgress.isBuilding || treeBuildProgress.isCheckingUpdates -> formatTreeIndexProgressLabel(treeBuildProgress)
            isCalculating -> {
                val totalSizeText = displayedTotalBytes.toReadableSize()
                tr(
                    "正在统计 $totalSizeText",
                    "Calculating $totalSizeText",
                    "集計中 $totalSizeText",
                    "계산 중 $totalSizeText",
                    "正在統計 $totalSizeText",
                )
            }
            // 下方已展示「已用 / 可用 / 归档」图例时，顶栏仅报文件数，避免同一总容量重复出现两次
            showQuotaLegend && current.fileCount > 0L -> {
                val enFiles = enCount(current.fileCount, "file", "files")
                tr(
                    "${current.fileCount} 个文件",
                    enFiles,
                    "${current.fileCount} ファイル",
                    "파일 ${current.fileCount}개",
                    "${current.fileCount} 個檔案",
                )
            }
            current.fileCount > 0L -> {
                val totalSizeText = displayedTotalBytes.toReadableSize()
                val enFiles = enCount(current.fileCount, "file", "files")
                tr(
                    "$totalSizeText（${current.fileCount} 个文件）",
                    "$totalSizeText ($enFiles)",
                    "$totalSizeText（${current.fileCount} ファイル）",
                    "$totalSizeText (파일 ${current.fileCount}개)",
                    "$totalSizeText（${current.fileCount} 個檔案）",
                )
            }
            showQuotaLegend -> ""
            else -> displayedTotalBytes.toReadableSize()
        }
    }

    val toggleVaultedLabel = tr("显示归档内容", "Show archived items", "アーカイブ項目を表示", "보관 항목 표시", "顯示歸檔內容")
    val openInDriveLabel = tr("在网盘中打开", "Open in drive", "ドライブで開く", "드라이브에서 열기", "在雲端硬碟中開啟")

    val toggleVaultedAction = SheetAction(
        icon = Icons.Outlined.Inventory2,
        label = toggleVaultedLabel,
        onClick = { if (!isBlockingLoad) includeVaulted = !includeVaulted },
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
                                if (isCalculating || summaryText.isNotEmpty()) {
                                    StatusSummaryBadge(isCalculating = isCalculating, text = summaryText)
                                }
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
                                    enabled = !isBlockingLoad,
                                    onToggle = { if (!isBlockingLoad) includeVaulted = !includeVaulted },
                                )
                            },
                            BarItem(
                                key = "openInDrive",
                                priority = 90,
                                overflow = listOf(openInDriveAction),
                            ) {
                                SpaceTonalActionButton(
                                    onClick = ::openCurrentInDrive,
                                    enabled = !isBlockingLoad,
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
                        if (showQuotaLegend && current != null) {
                            Spacer(Modifier.width(12.dp))
                            SpaceQuotaLegendStrip(
                                physicalBytes = current.physicalBytes,
                                vaultedBytes = if (includeVaulted) current.vaultedBytes else 0L,
                                freeQuotaBytes = rootFreeQuotaBytes,
                                quotaLimitBytes = if (currentFolderId.isEmpty()) cloudQuota?.limitBytes ?: 0L else 0L,
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
                if (!LocalFramed.current) {
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
                            enabled = !isBlockingLoad,
                            onToggle = { if (!isBlockingLoad) includeVaulted = !includeVaulted },
                        )
                    }
                    val current = snapshot
                    if (showQuotaLegend && current != null) {
                        SpaceQuotaLegendStrip(
                            physicalBytes = current.physicalBytes,
                            vaultedBytes = if (includeVaulted) current.vaultedBytes else 0L,
                            freeQuotaBytes = rootFreeQuotaBytes,
                            quotaLimitBytes = if (currentFolderId.isEmpty()) cloudQuota?.limitBytes ?: 0L else 0L,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                snapshot?.syncError?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error, modifier = Modifier.weight(1f), color = if (isEInk) Color.Black else MaterialTheme.colorScheme.error)
                        SpaceTonalActionButton(onClick = { driveRepo.refreshSpaceFolder(currentFolderId) }) {
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
private fun SpaceTonalActionButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    if (isEInk) {
        OutlinedButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContainerColor = Color.White,
                disabledContentColor = Color(0xFF555555),
            ),
            border = BorderStroke(1.dp, if (enabled) Color.Black else Color(0xFF555555)),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
internal fun SpaceQuotaLegendStrip(
    physicalBytes: Long,
    vaultedBytes: Long,
    freeQuotaBytes: Long,
    quotaLimitBytes: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (quotaLimitBytes > 0L) {
            val usedFraction = (physicalBytes.toFloat() / quotaLimitBytes.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (isEInk) Color.White else colors.surfaceContainerHighest)
                    .then(if (isEInk) Modifier.border(1.dp, Color.Black, CircleShape) else Modifier),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(usedFraction)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isEInk -> Color.Black
                                usedFraction >= 0.95f -> colors.error
                                usedFraction >= 0.85f -> colors.tertiary
                                else -> colors.primary
                            },
                        ),
                )
            }
        }
        SpaceLegendItem(
            dotColor = if (isEInk) Color.Black else colors.primary,
            dotBorder = if (isEInk) Color.Black else null,
            label = tr(
                "已用 ${physicalBytes.toReadableSize()}",
                "Used ${physicalBytes.toReadableSize()}",
                "使用済み ${physicalBytes.toReadableSize()}",
                "사용됨 ${physicalBytes.toReadableSize()}",
                "已使用 ${physicalBytes.toReadableSize()}",
            ),
        )
        if (freeQuotaBytes > 0L) {
            SpaceLegendItem(
                dotColor = if (isEInk) Color.White else colors.outlineVariant,
                dotBorder = if (isEInk) Color.Black else null,
                label = tr(
                    "可用 ${freeQuotaBytes.toReadableSize()}",
                    "Free ${freeQuotaBytes.toReadableSize()}",
                    "空き ${freeQuotaBytes.toReadableSize()}",
                    "여유 ${freeQuotaBytes.toReadableSize()}",
                    "可用 ${freeQuotaBytes.toReadableSize()}",
                ),
            )
        }
        if (vaultedBytes > 0L) {
            SpaceLegendItem(
                dotColor = if (isEInk) Color(0xFFB5B5B5) else colors.tertiary,
                dotBorder = if (isEInk) Color.Black else null,
                label = tr(
                    "归档 ${vaultedBytes.toReadableSize()}",
                    "Archived ${vaultedBytes.toReadableSize()}",
                    "アーカイブ ${vaultedBytes.toReadableSize()}",
                    "보관 ${vaultedBytes.toReadableSize()}",
                    "歸檔 ${vaultedBytes.toReadableSize()}",
                ),
            )
        }
    }
}

@Composable
private fun SpaceLegendItem(dotColor: Color, dotBorder: Color? = null, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
                .then(if (dotBorder != null) Modifier.border(1.dp, dotBorder, CircleShape) else Modifier),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
            style = MaterialTheme.typography.labelLarge.tabular,
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
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    FilterChip(
        selected = checked,
        onClick = onToggle,
        enabled = enabled,
        colors = if (isEInk) {
            FilterChipDefaults.filterChipColors(
                containerColor = Color.White,
                labelColor = Color.Black,
                iconColor = Color.Black,
                selectedContainerColor = Color.Black,
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
                disabledContainerColor = Color.White,
                disabledLabelColor = Color(0xFF555555),
                disabledLeadingIconColor = Color(0xFF555555),
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
        border = if (isEInk) {
            BorderStroke(1.dp, if (enabled) Color.Black else Color(0xFF555555))
        } else {
            FilterChipDefaults.filterChipBorder(enabled = enabled, selected = checked)
        },
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
