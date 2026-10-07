package dev.piko.ui.screens.drive

import dev.piko.shared.data.isVaulted
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.ContributePaletteItems
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.ui.zIndex
import dev.piko.ui.components.PrimaryActionFab
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.SheetAction
import dev.piko.shared.state.DriveListItem
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Info
import dev.piko.ui.components.FileDragPayload
import androidx.compose.material3.SnackbarDuration
import androidx.compose.animation.Crossfade
import dev.piko.ui.components.pageFocusTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.ContentCut
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.components.formatTimeMs
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.UploadFile
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.FrameCardBottomMargin
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.IslandInnerCorner
import dev.piko.ui.theme.islandHeader
import dev.piko.ui.components.islandTopStart
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.piko.ui.components.LocalShowExtensions
import dev.piko.ui.components.rememberListScrollTint
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import dev.piko.ui.theme.frame
import androidx.compose.foundation.background
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.library
import androidx.compose.material.icons.outlined.Home
import dev.piko.shared.data.LastFolderStack
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import dev.piko.shared.media.proxy.openForExternalPlayer
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.isUploading
import dev.piko.shared.naming.av.hasAvCode
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.screens.share.ShareDialog
import dev.piko.ui.screens.rename.BatchRenameDialog
import dev.piko.ui.LocalPikoServices
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isExtractableArchive
import dev.piko.ui.screens.archive.ArchiveBrowsePasswordDialog
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.showsRefreshButton
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.QualityDownloadDialog
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.UnsupportedNameDialog
import dev.piko.ui.components.driveNameHint
import dev.piko.ui.components.isUnfixableDriveName
import dev.piko.ui.components.submitDriveName
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.screens.instant.summary
import io.github.nihildigit.pikpak.FileStat
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 高亮的条目最多等这么久露面，之后照常开始渐隐。略长于 DriveScreenState 重列的总退避。 */
private const val HighlightAppearTimeoutMs = 4000L

// 目录图里点了别处的文件，等它所在的文件夹列出来的上限；平常一两百毫秒，有缓存时当场就有
private const val MapOpenTimeoutMs = 10_000L

/**
 * 网盘主界面：目录导航、列表与海报墙两种视图、防窥遮蔽、秒传入口与批量操作。
 *
 * 布局上把常驻的界面元素压到最少：顶栏之下只有子目录里才出现的面包屑，
 * 排序、视图切换、折叠提示都作为列表的首几项随内容滚走，搜索框只在点开搜索后
 * 取代顶栏标题。原先列表之上常驻顶栏、面包屑、搜索框与折叠横幅四层，首屏约
 * 230dp 被它们占去。
 *
 * Documentation references:
 * - m3-material-mirror/pages/components/app-bars.md（顶栏只放一到两个动作）
 * - m3-material-mirror/pages/components/search.md（搜索为次要动作时用图标按钮入口）
 * - m3-material-mirror/pages/components/bottom-sheets.md（移动端以模态面板代替菜单）
 * - android-docs-mirror/pages/develop/ui/compose/lists.md（key 与 contentType）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    currentFolderId: String = "",
    currentFolderName: String = "网盘",
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit,
    /** playlist 是当前列表里可播的视频，顺序与眼前看到的一致。 */
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    /**
     * 再点一次底栏的「文件」时加一：回到列表顶部。计数而不是布尔，连点两下要滚两次，
     * 而布尔第二下没有变化。
     */
    scrollToTopRequests: Int = 0,
    /** 下载或离线任务已提交，切到传输页看进度。上传由主界面直接订阅调度器，不经这里。 */
    onOpenTransfers: () -> Unit = {},
    /**
     * 信息流开着没有，以及视图切换里信息流一项的回调，为 null 时不给这一项。信息流在哪里呈现、
     * 播哪个文件夹由主界面决定，网盘页只管开关。
     */
    feedShown: Boolean = false,
    onFeedShownChange: ((Boolean) -> Unit)? = null,
    /** 信息流挂起着（队列还在、应用内不画），宽窗口命令栏的「信息流」按钮据此点一个小圆点。 */
    feedStashed: Boolean = false,
    /** 从信息流跳出来浏览的那个标签，标签栏上与别的分开画。没有时为 null。 */
    feedTabId: Long? = null,
    /**
     * 地址栏里输入页面名（回收站、星标、传输、设置）时给出的前往项。与命令面板的「前往」「页面」两组是同一份，
     * 由主界面传进来：怎么打开这些页只有主界面知道。
     */
    addressDestinations: List<PaletteItem> = emptyList(),
    /**
     * 人从库里退了出来（返回键或库顶栏上的返回），网盘已回到打开库之前的位置。库若是从别的页打开的，
     * 主界面据此切回那一页；网盘页不知道库从哪里打开。
     */
    onLibraryLeft: () -> Unit = {},
    /** 把页眉下面的列表区包进去的外框，宽窗口里主界面由它在列表右边放信息流侧栏；页眉不在里面。 */
    contentFrame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    modifier: Modifier = Modifier,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val clientManager = LocalPikoServices.current.clientManager
    val instantRepo = LocalPikoServices.current.instantMagnetRepository
    val instantSession = LocalPikoServices.current.instantSession
    val duplicateSession = LocalPikoServices.current.duplicateSession
    val downloadManager = LocalPikoServices.current.downloadManager
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 防窥遮蔽开关是渲染选择，不进共享状态
    val sessionManager = LocalPikoServices.current.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)

    val archiveBrowser = LocalPikoServices.current.archiveBrowser
    val state = remember { DriveScreenState(driveRepo, sessionManager, scope, duplicateSession, archiveBrowser) }
    // 目录图：宽窗口里浮在列表上的面板，见 FolderMap
    val folderMap = remember(state) { FolderMapState(state::folderMapLevel, scope) }
    LaunchedEffect(folderMap) { driveRepo.folderChanges.collect(folderMap::onChange) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    // 做完一次可撤销的改动，提示上带「撤销」，停留长一些好来得及点
    LaunchedEffect(state) {
        state.changeEvents.collect { event ->
            val change = event.change
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = if (change != null) "撤销" else null,
                withDismissAction = true,
                duration = if (change != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (change != null && result == SnackbarResult.ActionPerformed) state.undo(change)
        }
    }

    val archiveSession = LocalPikoServices.current.archiveExtractSession
    LaunchedEffect(archiveSession) {
        archiveSession.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    // 归档文件夹：成功经改动日志带「撤销」提示，失败与没有可归档的在这里提示
    val vaultSession = LocalPikoServices.current.folderVaultSession
    LaunchedEffect(vaultSession) {
        vaultSession.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    var vaultTarget by remember { mutableStateOf<FileStat?>(null) }
    var restoreVaultTarget by remember { mutableStateOf<FileStat?>(null) }

    // 视图模式存进偏好，切 Tab 与重启后保持上次的选择
    // 初值同步读：异步给默认值的话，选了列表的用户每次进来都先闪一帧海报墙。DataStore 在
    // MainActivity 读外观时已载入，这里只是取内存里的值
    val initialViewMode = remember { runBlocking { sessionManager.driveViewModeFlow.first() } }
    val viewModeName by sessionManager.driveViewModeFlow.collectAsStateWithLifecycle(initialViewMode)
    val viewMode = DriveViewMode.of(viewModeName)
    LaunchedEffect(state, viewMode) { state.updateThumbnailsOnly(viewMode == DriveViewMode.GALLERY) }

    // 目录导航栈：持久化并与全局单例共享，切 Tab / 重启不丢失
    val folderStack by state.folderStack.collectAsStateWithLifecycle()
    val activeFolder = folderStack.lastOrNull() ?: PathBreadcrumb(currentFolderId, currentFolderName)
    val activeFolderId = activeFolder.id
    // 眼前是不是一个库（星标、回收站这些，见 DriveLibrary）。库不是文件夹：不能新建、上传、粘贴，
    // 回收站里的条目另有一套操作
    val libraryView = state.libraryView
    val inTrash = libraryView == DriveLibrary.TRASH
    var libraryConfirm by remember { mutableStateOf<LibraryConfirm?>(null) }
    // 回网盘根目录：整条栈换掉，不是退到第一级（库里的子文件夹栈底是库）。命令栏与目录图的主页按钮同用这一个
    fun goHome() = driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
    // 库是路径栈的第一级，没有上一级可回；退出库是回到打开它之前的地方，没有就回网盘根目录
    fun leaveLibrary() {
        if (!state.goBack()) goHome()
        onLibraryLeft()
    }

    LaunchedEffect(Unit) {
        // 位置按账号存在标签里（restoreTabs）。有多账号之前只存一份在偏好里，那时只有一个账号：
        // 这个账号没存过标签时接过去一次，随即清掉，不留给之后登录的别的账号。
        // 换号后网盘页随账号重建，此刻仓库可能还停在上一个账号的位置，先换过来再看
        driveRepo.enterAccount(clientManager.currentClient.value?.account)
        val startStack = driveRepo.folderStackFlow.value
        val legacyStack = if (startStack.size == 1 && startStack[0].id.isEmpty() && currentFolderId.isEmpty()) {
            val (lastId, lastName, serialized) = sessionManager.getLastFolder()
            when {
                lastId.isEmpty() -> emptyList()
                serialized.isEmpty() -> listOf(PathBreadcrumb(lastId, lastName))
                else -> LastFolderStack.decode(serialized).ifEmpty { listOf(PathBreadcrumb(lastId, lastName)) }
            }
        } else emptyList()
        if (legacyStack.isNotEmpty()) sessionManager.saveLastFolder("", "", "")

        // restoreFolderStack 与 restoreTabs 自带加载，几条路各触发一次，不能都调
        when {
            currentFolderId.isEmpty() && state.restoreTabs() -> Unit
            legacyStack.isNotEmpty() -> state.restoreFolderStack(legacyStack)
            else -> state.load()
        }
    }

    // 搜索框是否展开。状态类在换目录时会清空搜索词，展开态跟着收起，
    // 否则从搜索结果点进文件夹后，顶栏还停在一个空的搜索框上。
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeFolderId) { isSearchOpen = false }
    fun closeSearch() {
        state.updateSearchQuery("")
        isSearchOpen = false
    }

    // 返回键（桌面上是 Esc）的优先级，后声明的 BackHandler 先收到：属性 > 多选
    // > 单击高亮 > 搜索 > 上一级目录。越临时、越晚出现的越先被吃掉；后三样声明在下面，挨着它们要看的状态
    BackHandler(enabled = folderStack.size > 1) { state.navigateUp() }
    BackHandler(enabled = folderStack.size == 1 && libraryView != null) { leaveLibrary() }
    BackHandler(enabled = isSearchOpen) { closeSearch() }

    // 对话框与面板状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<FileStat?>(null) }
    // 键盘焦点所在的那一项，方向键、菜单键、Delete 与 F2 作用于它；以及要把焦点移过去的那一行（行的 key）
    var focusedFile by remember { mutableStateOf<FileStat?>(null) }
    // 宽窗口命令栏作用的那一项：最近取得焦点的一项，失焦后仍留着。点开命令栏的下拉菜单时焦点进了弹窗，
    // 按 focusedFile 算的话菜单里的操作就落空了。换目录、点空白处时清掉，与资源管理器的选中相同
    var commandFile by remember { mutableStateOf<FileStat?>(null) }
    var locatedFileId by remember { mutableStateOf<String?>(null) }
    // 宽窗口里常驻搜索框取得焦点的请求，主修饰键+F 加一
    var searchFocusRequests by remember { mutableIntStateOf(0) }
    var keyboardFocusTarget by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    var renameNewName by remember { mutableStateOf("") }
    // 桌面的顶栏是地址栏加后退、前进与上一级，照资源管理器；移动端是目录名作标题、上级另成一行面包屑，平板也是。
    // 按交互模型分而不按宽度，见 FormFactor
    val desktop = isDesktopLayout()
    val pathInTopBar = desktop
    // 标签栏、命令栏、浮动的属性卡片与目录图、右键菜单代替条目上的更多按钮：桌面那一套
    val twoPane = desktop
    // 目录图只在宽窗口里有：导航栏搜索旁的按钮、快捷键与命令面板打开，面板上的 × 关掉；开着与否存进偏好（每台设备各自的）
    val folderMapOpen by sessionManager.folderMapOpenFlow.collectAsStateWithLifecycle(initialValue = false)
    fun setFolderMapOpen(open: Boolean) {
        scope.launch { sessionManager.setFolderMapOpen(open) }
    }
    // 地址栏进入输入的请求，快捷键加一，见 DrivePathTitle
    var addressEditRequests by remember { mutableIntStateOf(0) }
    // 输入框从原名开始改；只设目标的话，框里留着上一次改名时输入的字
    fun startRename(file: FileStat) {
        renameTargetFile = file
        renameNewName = file.name
    }
    val segmentSession = LocalPikoServices.current.segmentSession
    var actionTargetFile by remember { mutableStateOf<FileStat?>(null) }
    // 待移动的条目。选择器只负责选目录，移动本身与刷新在这里做，
    // 所以单项操作和多选工具栏可以共用同一套状态。
    var moveTargetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val duplicateState = duplicateSession.state
    val inDuplicates = state.libraryView == DriveLibrary.DUPLICATES
    // 行下面那一栏：全盘搜索时是所在的目录，库里是何时添加、看到哪里、何时清除
    val libraryEvents = state.libraryEvents
    val libraryNotes = remember(libraryView, state.files, libraryEvents) {
        if (libraryView == null) emptyMap()
        else if (libraryView == DriveLibrary.DUPLICATES) duplicateLocations(duplicateState)
        else state.files.mapNotNull { file -> libraryNote(libraryView, file, libraryEvents[file.id])?.let { file.id to it } }.toMap()
    }
    val rowNotes = if (libraryNotes.isEmpty()) state.hitLocations else state.hitLocations + libraryNotes

    // 属性看的几项，打开那一刻取定，之后选中怎么变都不跟。一次只开一个，再对别的开就换掉内容
    var properties by remember { mutableStateOf<PropertiesTarget?>(null) }
    // 宽窗口里属性卡片放在触发它的地方旁边（anchor）；已经开着时只换内容，位置不动
    val propertiesPanel = remember { FloatingPanelState(PropertiesMinWidth, followsEnd = false) }
    val propertiesAnchors = remember { PropertiesAnchors() }
    LaunchedEffect(activeFolderId) { propertiesAnchors.forgetItems() }
    // 操作面板开了就不是右键菜单：之前那次右键按下的位置不再作数，面板里点「属性」时卡片贴着条目
    LaunchedEffect(actionTargetFile) { if (actionTargetFile != null) propertiesAnchors.forgetPress() }
    // 「位置」一行：搜索结果与库里取行下面那一栏，平常的目录里是眼前的路径
    fun locationOf(file: FileStat): String = rowNotes[file.id] ?: folderStack.joinToString(" › ") { it.name }
    fun showProperties(files: List<FileStat>, anchor: PanelAnchor) {
        if (properties == null) propertiesPanel.placeAt(anchor)
        val single = files.singleOrNull()
        properties = if (single != null) {
            val item = state.displayItems.firstOrNull { it is DriveListItem.File && it.file.id == single.id } as? DriveListItem.File
            val text = item?.let { cellText(it, if (single.isFolder && state.isNameParsing) state.folderViews[single.id] else null) }
            PropertiesTarget.Single(
                file = single,
                tags = listOfNotNull(text?.code) + text?.tags.orEmpty(),
                location = locationOf(single),
                isBlurred = isSpoilerBlurEnabled && single.id !in state.revealedFileIds,
            )
        } else {
            PropertiesTarget.Selection(files, files.map(::locationOf).distinct().singleOrNull() ?: "多个位置")
        }
    }
    // 当前目录不对着哪一项，卡片放在列表中央偏上
    fun showFolderProperties() {
        if (properties == null) propertiesPanel.placeAt(PanelAnchor.Centered)
        val parent = folderStack.dropLast(1).joinToString(" › ") { it.name }
        properties = PropertiesTarget.Folder(activeFolder.name, state.displayedFiles, parent.ifEmpty { null })
    }
    // 记住的回调（右键菜单）里取最新的一份：局部函数抓着的是头一次组合时的路径与防窥开关
    val latestShowProperties by rememberUpdatedState<(List<FileStat>, PanelAnchor) -> Unit> { files, anchor -> showProperties(files, anchor) }

    val taskSlot = LocalTaskSlot.current

    /** 去查重结果那一页。同一个起点的扫描还在就回到它，不重扫；换了起点就结束旧的、从头扫。 */
    fun openDuplicates(root: PathBreadcrumb) {
        duplicateSession.open(root)
        if (inDuplicates) return
        // 窄窗口里结果页是压栈进去的一个位置，从此离开结果页即结束查找（主界面按这个锚点判断）
        if (!twoPane) taskSlot.duplicatesAnchor = listOf(DriveLibrary.DUPLICATES.crumb)
        driveRepo.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
    }

    /**
     * 「查找重复」：人留在原处。原来点了就跳进结果页，扫描的几分钟里那一页只有转圈，人以为整个界面被占住了。
     * 有标签栏时在后台开一个查重标签，扫描进度与结果都在标签上，切过去就是结果，切回来网盘原样，关掉标签结束这次查重；
     * 已有查重标签时沿用它，同一个起点已有结果就切过去。
     * 没有标签栏时进度在底部的 sheet 里（TaskSheetScaffold），扫完有结果才进结果页；sheet 是独占的，别的事占着时先确认结束它。
     */
    // 桌面上查重在后台标签里跑，人留在原处：开始与扫完各提示一次，「查看」切到那个标签。
    // 只有标签上的转圈时，点了像没反应，扫完了也无从知道
    fun announceDuplicates(message: String) = scope.launch {
        val result = snackbarHostState.showSnackbar(message, actionLabel = "查看", withDismissAction = true, duration = SnackbarDuration.Long)
        if (result != SnackbarResult.ActionPerformed) return@launch
        driveRepo.tabsFlow.value.firstOrNull { it.isDuplicates }?.let { driveRepo.switchTab(it.id) }
    }

    fun findDuplicates(root: PathBreadcrumb) {
        if (twoPane) {
            duplicateSession.open(root)
            val done = duplicateSession.state?.isScanning == false
            val existing = driveRepo.tabsFlow.value.firstOrNull { it.isDuplicates }
            val tabId = existing?.id ?: driveRepo.openTab(listOf(DriveLibrary.DUPLICATES.crumb), activate = false)
            if (done) driveRepo.switchTab(tabId) else announceDuplicates("正在查找「${root.name}」中的重复文件")
            return
        }
        val current = duplicateSession.state
        taskSlot.claim(
            action = "查找「${root.name}」中的重复文件",
            confirmLabel = "查找重复",
            task = TaskSlot.Task.DUPLICATES,
            replacesSame = current != null && current.root.id != root.id,
        ) {
            val sameRoot = duplicateSession.state?.root?.id == root.id
            if (!sameRoot) taskSlot.duplicatesAnchor = folderStack
            duplicateSession.open(root)
            taskSlot.duplicatesExpanded = true
            val groups = duplicateSession.state?.report?.let { it.identical.size + it.versions.size } ?: 0
            if (sameRoot && duplicateSession.state?.phase == DuplicateFinderState.Phase.DONE && groups > 0) openDuplicates(root)
        }
    }
    // 查重结束了（结果页里点了结束、换号、重启后恢复出旧的查重标签），查重标签随之关掉：留着只剩一页「已结束」。
    // 只剩它一个标签时关不掉，留给那一页的「回到网盘」
    LaunchedEffect(Unit) {
        combine(driveRepo.tabsFlow, snapshotFlow { duplicateSession.state }) { tabs, session -> tabs to session }
            .collect { (tabs, session) ->
                if (session != null || tabs.size <= 1) return@collect
                tabs.filter { it.isDuplicates }.forEach { driveRepo.closeTab(it.id) }
            }
    }
    // 窄窗口里人看着展开的 sheet 等到扫完，有结果就直接进结果页；sheet 收着时不把人拽走，sheet 上有「查看」。
    // 只认眼看着发生的那一下：切去别的页时扫完的，回来看到的是 sheet 上的结果，不是突然跳页。
    // 桌面不跳，提示一句结果；人已在查重标签上时不提示
    val latestTwoPane by rememberUpdatedState(twoPane)
    LaunchedEffect(duplicateState) {
        val finder = duplicateState ?: return@LaunchedEffect
        if (!finder.isScanning) return@LaunchedEffect
        val phase = snapshotFlow { finder.phase }.first { it == DuplicateFinderState.Phase.DONE || it == DuplicateFinderState.Phase.FAILED }
        val groups = finder.report.identical.size + finder.report.versions.size
        if (latestTwoPane) {
            if (state.libraryView == DriveLibrary.DUPLICATES) return@LaunchedEffect
            announceDuplicates(
                when {
                    phase == DuplicateFinderState.Phase.FAILED -> "查找重复失败"
                    groups > 0 -> "找到 $groups 组重复文件"
                    else -> "未发现重复文件"
                },
            )
            return@LaunchedEffect
        }
        if (phase != DuplicateFinderState.Phase.DONE) return@LaunchedEffect
        if (taskSlot.duplicatesExpanded && groups > 0 && state.libraryView != DriveLibrary.DUPLICATES) openDuplicates(finder.root)
    }
    val selectedArchives by remember(state) {
        derivedStateOf { state.displayedFiles.filter { it.id in state.selectedFileIds && (it.isExtractableArchive || it.isArchiveVolume) } }
    }
    var copyTargetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var shareTargets by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var qualityTarget by remember { mutableStateOf<FileStat?>(null) }
    var batchRenameTargets by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var previewImage by remember { mutableStateOf<FileStat?>(null) }
    // 外部打开的磁力链是一次明确的新请求：开新会话并就地取走，面板收起后不再靠它续命。
    // 已有一次粘过东西的添加链接时不静默换掉它，与别的占着 sheet 的事一样先确认
    val pendingMagnet by instantRepo.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        val magnet = pendingMagnet
        if (!magnet.isNullOrBlank()) {
            instantRepo.clearPendingMagnet()
            val replacing = instantSession.state?.isBlank == false
            taskSlot.claim(
                action = if (replacing) "打开新链接" else "添加链接",
                confirmLabel = if (replacing) "打开新链接" else "添加链接",
                task = TaskSlot.Task.ADD_LINK,
                replacesSame = replacing,
            ) { instantSession.start(magnet) }
        }
    }

    // 保存结果在这里收而不在面板里：面板可能正收起着。
    // 秒传的文件当场就在网盘里，留在网盘页定位过去；离线要等下完，网盘里暂时没有东西可看，去传输页。
    // 批量里有秒传也有离线时按离线处理：秒传的那几项在传输页里另有记录，点开即可定位
    val openTransfers by rememberUpdatedState(onOpenTransfers)
    val instantState = instantSession.state
    // 添加链接的会话收起着（面板关了、解析与勾选都还在）时点它是放回来，不另起一个
    fun openAddLink() = taskSlot.claim("添加链接", "添加链接", TaskSlot.Task.ADD_LINK) {
        if (instantSession.state != null) instantSession.reopen() else instantSession.start()
    }
    LaunchedEffect(instantState) {
        instantState?.outcomes?.collect { outcome ->
            instantSession.end()
            when (outcome) {
                is InstantSaveOutcome.InstantSaved -> {
                    state.navigateToFolder(outcome.target)
                    state.highlight(outcome.createdIds.toSet())
                    snackbarHostState.showSnackbar("已保存 ${outcome.createdIds.size} 个文件", withDismissAction = true)
                }
                is InstantSaveOutcome.OfflineTaskCreated -> openTransfers()
            }
        }
    }

    // 没有浮动卡片的窗口里，解压与归档的进度只在传输页。开始时提示一次并给出去处；不像离线那样直接跳过去：
    // 解压多在几秒内完成，跳走反而打断人手上的事
    LaunchedEffect(twoPane) {
        if (twoPane) return@LaunchedEffect
        fun announce(message: String) = launch {
            val result = snackbarHostState.showSnackbar(message, actionLabel = "查看", withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) openTransfers()
        }
        // 只认从无到有：进页时已经在跑的之前提示过，队列里追加压缩包也不再提示
        var wasExtracting = archiveSession.jobs.isNotEmpty()
        var wasVaulting = vaultSession.isRunning
        snapshotFlow { archiveSession.jobs.isNotEmpty() to vaultSession.isRunning }.collect { (extracting, vaulting) ->
            if (extracting && !wasExtracting) announce("已开始解压")
            if (vaulting && !wasVaulting) announce(if (vaultSession.restoreProgress != null) "已开始取消归档" else "已开始归档")
            wasExtracting = extracting
            wasVaulting = vaulting
        }
    }

    // 每个目录一份列表状态，按「列表此刻显示的目录」重建，初值取仓库里记下的位置：
    // 新目录的第一帧就落在该在的地方。共用一份再在加载后 scrollToItem 的话，第一帧会
    // 先停在上一个目录的位置上，返回上级也会带回子目录的偏移。
    val loadedFolderId = state.loadedFolderId
    val gridState = remember(loadedFolderId) {
        val anchor = loadedFolderId?.let(driveRepo::scrollAnchor)
        LazyGridState(anchor?.index ?: 0, anchor?.offset ?: 0)
    }
    LaunchedEffect(gridState) {
        val folderId = loadedFolderId ?: return@LaunchedEffect
        snapshotFlow { ScrollAnchor(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { driveRepo.saveScrollAnchor(folderId, it) }
    }
    // 只响应计数的变化：snapshotFlow 的首个值是组合时的初值，不是请求，照滚会把刚恢复的位置冲回顶部。
    // gridState 按目录重建，以它为 key，滚的总是眼前这份列表
    val latestScrollToTop by rememberUpdatedState(scrollToTopRequests)
    LaunchedEffect(gridState) {
        snapshotFlow { latestScrollToTop }.drop(1).collect { gridState.animateScrollToItem(0) }
    }
    var isFabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isFabMenuExpanded) { isFabMenuExpanded = false }

    val displayedFiles = state.displayedFiles
    val folderEmptiness by state.folderEmptiness.collectAsStateWithLifecycle()
    val vaultedFolders by state.vaultedFolders.collectAsStateWithLifecycle()
    val highlightedFileIds = state.highlightedFileIds
    val highlightedRowIds = state.highlightedRowIds
    // 每一项都要问一次「是否选中」，SnapshotStateList 的 contains 是线性查找
    val selectedIdSet by remember { derivedStateOf { state.selectedFileIds.toSet() } }
    LaunchedEffect(activeFolderId) { commandFile = null; locatedFileId = null }
    // 命令栏与剪切、复制快捷键作用的条目：多选时是选中的几项，否则是 commandFile（已不在列表里的不算）
    val commandTargets: List<FileStat> = if (state.isSelectionMode) {
        displayedFiles.filter { it.id in selectedIdSet }
    } else {
        listOfNotNull(commandFile?.let { current -> displayedFiles.firstOrNull { it.id == current.id } })
    }
    val clipboard by driveRepo.clipboardFlow.collectAsStateWithLifecycle()

    // 渐隐单独一个 effect：并进滚动定位那个的话，视图模式到位会把 8 秒重新计一遍。
    // 从条目出现在列表里起算：刚存进去的文件要等状态类重列几次才露面，从请求起算的话看到的高亮只剩一半
    LaunchedEffect(highlightedFileIds, state.highlightRevision) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        withTimeoutOrNull(HighlightAppearTimeoutMs) {
            snapshotFlow { state.isDisplayStructureReady && state.highlightedRowIds.isNotEmpty() }.first { it }
        }
        delay(8000)
        state.clearHighlight()
    }
    LaunchedEffect(highlightedFileIds, state.highlightRevision) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        locatedFileId = snapshotFlow {
            if (state.isDisplayStructureReady) state.highlightedRowIds.firstOrNull() else null
        }.first { it != null }
    }

    val platform = LocalPikoPlatform.current

    // 磁力链接与分享链接都只复制；复制完给个回执，剪贴板本身看不见
    fun copySource(file: FileStat) {
        val url = file.sourceUrl ?: return
        platform.copyToClipboard("来源链接", url)
        scope.launch { snackbarHostState.showSnackbar(if (url.startsWith("magnet:", true)) "已复制磁力链接" else "已复制分享链接", withDismissAction = true) }
    }

    val mediaRepository = LocalPikoServices.current.mediaRepository
    fun openInExternalPlayer(file: FileStat) {
        val player = platform.externalPlayer ?: return
        scope.launch {
            val url = mediaRepository.openForExternalPlayer(file.id).logFailure("Drive", "外部播放器取不到代理地址").getOrNull()
            val opened = url != null && player.open(url, file.name)
            if (!opened) snackbarHostState.showSnackbar("无法用外部播放器打开", withDismissAction = true)
        }
    }

    // 从网盘页发起的上传就传到眼前这个目录，不再问目标；应用外进来的由 UploadRequestHost 问。
    // 排进队列后切到传输页由主界面订阅调度器完成，这里只提交
    val uploadManager = LocalPikoServices.current.uploadManager
    LaunchedEffect(uploadManager) {
        uploadManager.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    fun upload(selection: UploadSelection) {
        if (state.isVirtualPlace) return
        uploadManager.enqueue(selection, activeFolderId, activeFolder.name)
    }
    val pickFiles = platform.uploadPicker.rememberFilesLauncher { upload(UploadSelection(files = it)) }
    val pickFolder = platform.uploadPicker.rememberFolderLauncher { upload(UploadSelection(folders = listOf(it))) }

    // 下载的成品只在传输页里看得到，与上传、离线一样提交后切过去。文件夹各成一批，在后台列出其中的文件
    fun download(files: List<FileStat>) {
        // 压缩包里的文件先变成借得出来的样子；包里的文件夹不在网盘里，列不出内容，不下
        if (state.archiveView != null) {
            scope.launch {
                val ready = files.filter { !it.isFolder }.mapNotNull { state.prepareArchiveEntry(it) }
                downloadManager.enqueueFiles(ready)
                if (ready.isNotEmpty()) openTransfers()
            }
            return
        }
        val singles = files.filter { !it.isFolder && !it.isUploading }
        downloadManager.enqueueFiles(singles)
        val batches = downloadManager.enqueueFolders(files.filter { it.isFolder }, DriveDownloadFolderSource(driveRepo))
        if (singles.isNotEmpty() || batches > 0) openTransfers()
    }

    fun enqueueDownload(file: FileStat) = download(listOf(file))

    // 在压缩包里解压：[items] 为空是整个包。解压到压缩包旁边，与在网盘里解压同一处，任务照旧进解压会话
    fun extractFromArchive(items: List<FileStat>) {
        val (archive, password) = state.currentArchive() ?: return
        archiveSession.extractEntries(archive, state.archivePaths(items), password)
        state.exitSelection()
    }

    // 回调对象只建一次，列表项拿到的引用不变；外部传入的导航回调经 rememberUpdatedState 取最新值
    val navigateToPlayer by rememberUpdatedState(onNavigateToVideoPlayer)
    // 标签：宽窗口里一个标签一个位置，见 PikoDriveRepository.tabsFlow
    val tabs by driveRepo.tabsFlow.collectAsStateWithLifecycle()
    val activeTabId by driveRepo.activeTabId.collectAsStateWithLifecycle()

    // 关掉查重标签就是结束这次查重：结果只在会话里，标签是它唯一的入口
    fun closeTab(id: Long) {
        if (tabs.firstOrNull { it.id == id }?.isDuplicates == true) duplicateSession.end()
        driveRepo.closeTab(id)
    }
    val tabsAvailable = twoPane
    val openInNewTab: ((FileStat) -> Unit)? = if (tabsAvailable) {
        { folder -> driveRepo.openTab(folderStack + PathBreadcrumb(folder.id, folder.name), activate = false) }
    } else {
        null
    }
    // 记住的回调里读它的最新值：窗口从宽变窄时不该还能开标签
    val latestOpenInNewTab by rememberUpdatedState(openInNewTab)
    // 快速访问列在侧边栏与命令面板里：有侧边栏（桌面）才给固定的入口，移动端固定了也看不到
    val pinnedFolders by driveRepo.pinnedFoldersFlow.collectAsStateWithLifecycle(emptyList())
    val togglePin: ((FileStat) -> Unit)? = if (desktop) {
        { folder ->
            if (pinnedFolders.any { it.id == folder.id }) {
                driveRepo.unpinFolder(folder.id)
            } else {
                driveRepo.pinFolder(PathBreadcrumb(folder.id, folder.name))
            }
        }
    } else {
        null
    }
    val latestTogglePin by rememberUpdatedState(togglePin)
    val latestPinnedFolders by rememberUpdatedState(pinnedFolders)

    // 眼前在哪，命令栏与条目的菜单都按它定做不做得了。记住的回调里也调它，所以只读 state 与仓库上的当下值
    fun commandPlace(stackDepth: Int = driveRepo.folderStackFlow.value.size): CommandPlace = when {
        state.archiveView != null -> CommandPlace.ARCHIVE
        state.libraryView == DriveLibrary.TRASH -> CommandPlace.TRASH
        state.libraryView == DriveLibrary.HISTORY -> CommandPlace.HISTORY
        state.libraryView == DriveLibrary.RECENT -> CommandPlace.RECENT
        state.libraryView != null -> CommandPlace.LIBRARY
        state.searchQuery.isNotBlank() || state.isGlobalSearchActive -> CommandPlace.SEARCH
        stackDepth == 1 -> CommandPlace.ROOT
        else -> CommandPlace.FOLDER
    }

    // 压缩包里的条目不在网盘里：能做的只有下载文件与解压出来，打开就是单击
    fun archiveEntryActions(files: List<FileStat>, commands: ItemCommands): List<SheetAction> = buildList {
        if (commands.download) add(DriveActions.download { download(files) })
        add(DriveActions.extractEntries { extractFromArchive(files) })
    }

    // 选中的几项一起的操作
    fun selectionActions(files: List<FileStat>, commands: ItemCommands): List<SheetAction> = buildList {
        val ids = files.map { it.id }
        val settled = files.filterNot { it.isUploading || it.isVaulted }
        if (commands.download) add(DriveActions.download { download(files.filterNot { it.isUploading }) })
        // 按列表顺序：服务端取第一项的名字作分享标题
        if (commands.share) add(DriveActions.share { shareTargets = settled })
        if (commands.rename) add(DriveActions.batchRename { batchRenameTargets = settled })
        files.filter { it.isVaulted }.takeIf { it.isNotEmpty() }?.let { vaulted ->
            add(DriveActions.restoreFromVault {
                state.exitSelection()
                vaultSession.restore(vaulted)
            })
        }
        if (commands.moveCopyTo) {
            add(DriveActions.moveTo { moveTargetIds = ids.toSet() })
            add(DriveActions.copyTo { copyTargetIds = ids.toSet() })
        }
        if (commands.removeRecord) add(DriveActions.removeRecord { state.removeFromLibrary(ids.toSet()) })
        if (commands.moveToTrash) add(DriveActions.moveToTrash { state.moveToTrash(ids) })
    }

    // 归档条目只是清单里的一行：能做的是打开、下载、改名、复制来源，以及恢复成网盘文件或从清单里去掉。
    // 改名改的是清单里的名字，不受「网盘里没有文件就不能改名」那条规则限制
    fun vaultActions(file: FileStat): List<SheetAction> = buildList {
        add(DriveActions.restoreFromVault { vaultSession.restore(listOf(file)) })
        add(DriveActions.download { enqueueDownload(file) })
        add(DriveActions.rename { startRename(file) })
        addAll(DriveActions.sourceActions(file.source, onCopy = { copySource(file) }, onOpen = { file.sourceUrl?.let(platform::openUrl) }))
        add(DriveActions.removeFromVault { state.removeFromVault(listOf(file.id)) })
    }

    // 一项（或 targets 那几项）的全部操作，右键菜单与操作面板只从这里取。读 state 上的当下值：记住的回调里拿不到重组后的局部变量。
    // 点的那一项在几项选中里时，照资源管理器作用于全部选中的：只作用于这一项的话，多选后右键「移入回收站」只删掉一项
    fun operationsOf(file: FileStat, targets: List<FileStat>): List<SheetAction> {
        val library = state.libraryView
        val commands = itemCommands(commandPlace(), targets)
        if (commands.restoreOrDelete) {
            val ids = targets.map { it.id }
            return trashActions(
                onRestore = { state.restoreFromTrash(ids) },
                onDelete = { libraryConfirm = LibraryConfirm.DeleteForever(ids, emptying = false) },
            )
        }
        if (state.archiveView != null) return archiveEntryActions(targets, commands)
        if (targets.size > 1) return selectionActions(targets, commands)
        if (file.isVaulted) return vaultActions(file)
        // 库里的条目多一个在网盘中显示，置首；移除记录由 itemCommands 给
        val reveal = listOfNotNull(library?.let { DriveActions.revealInDrive { state.revealInDrive(file) } })
        val removeRecord = listOfNotNull(DriveActions.removeRecord { state.removeFromLibrary(setOf(file.id)) }.takeIf { commands.removeRecord })
        val handlers = FileActionHandlers(
            toggleStar = { state.setStarred(file, starred = !file.isStarred) },
            download = { enqueueDownload(file) },
            share = { shareTargets = listOf(file) },
            rename = { startRename(file) },
            move = { moveTargetIds = setOf(file.id) },
            copy = { copyTargetIds = setOf(file.id) },
            trash = { state.moveToTrash(listOf(file.id)) },
            extract = { archiveSession.extract(listOf(file)) },
            findDuplicates = { findDuplicates(PathBreadcrumb(file.id, file.name)) },
            downloadSegment = { segmentSession.open(file) },
            downloadQuality = { qualityTarget = file },
            prepareQualities = { mediaRepository.prefetchDownloadQualities(file.id) },
            copySource = { copySource(file) },
            openSource = { file.sourceUrl?.let(platform::openUrl) },
            openInExternalPlayer = platform.externalPlayer?.let { { openInExternalPlayer(file) } },
            openInNewTab = latestOpenInNewTab?.let { { it(file) } },
            togglePin = latestTogglePin?.let { { it(file) } },
            isPinned = latestPinnedFolders.any { it.id == file.id },
            // 库里列的是散落各处的条目，归档一个文件夹要在它所在的地方做。挂着归档标记的才给取消归档；
            // 归档却一直给：跳过小文件或之后又放进新文件时，挂着标记的文件夹里仍有可归档的
            vault = if (library == null) ({ vaultTarget = file }) else null,
            unvault = if (library == null && file.id in vaultedFolders) ({ restoreVaultTarget = file }) else null,
            previewHidden = if (isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty()) file.id !in state.revealedFileIds else null,
            togglePreview = { state.toggleSpoiler(file.id) },
        )
        // 移除记录排在移入回收站之前，两者同在末组
        return reveal + removeRecord + fileActions(file, commands, handlers)
    }

    // 对着一项右键或按属性键时作用于哪几项：它在几项选中里时是全部选中的
    fun targetsOf(file: FileStat): List<FileStat> =
        if (state.isSelectionMode && file.id in state.selectedFileIds && state.selectedFileIds.size > 1) {
            state.displayedFiles.filter { it.id in state.selectedFileIds }
        } else {
            listOf(file)
        }

    // 右键菜单与操作面板的全部项：操作，加上「属性」。右键菜单作用于 targetsOf，操作面板只看它自己那一项。
    // 右键菜单里点的属性卡片放在右键按下的地方，操作面板里点的贴着这一项
    fun itemActions(file: FileStat, targets: List<FileStat>, fromMenu: Boolean): List<SheetAction> =
        operationsOf(file, targets) + DriveActions.properties {
            latestShowProperties(targets, if (fromMenu) propertiesAnchors.fromMenu(file.id) else propertiesAnchors.beside(file.id))
        }

    // 只在移动端的条目上画打开操作面板的按钮：桌面有右键菜单，每一项挂一个按钮只是满屏一样的图标。
    // 曾按宽度判断，平板横握没有右键也没有这个按钮，单项的操作整个不可达
    val itemMoreButton = !desktop

    // 从没解压过的包第一次打开要等几秒。准备完 key 一变，这里的协程取消，提示随之收起；快的不提示
    LaunchedEffect(state.preparingEntryId) {
        if (state.preparingEntryId == null) return@LaunchedEffect
        delay(400)
        snackbarHostState.showSnackbar("正在读取压缩包里的文件…", duration = SnackbarDuration.Indefinite)
    }
    val openFile: (FileStat) -> Unit = { file ->
        when {
            file.isPlayableVideo() -> navigateToPlayer(file, state.displayedFiles.filter { it.isPlayableVideo() })
            file.isPreviewableImage() && file.thumbnailLink.isNotBlank() -> previewImage = file
            // 其余类型没有应用内的打开方式，单击等同于下载
            else -> enqueueDownload(file)
        }
    }
    val callbacks = remember(state) {
        DriveItemCallbacks(
            onOpen = { file ->
                when {
                    // 回收站里的条目打不开，点开的是它的操作面板：恢复与彻底删除都在里面，单击不直接执行其中任何一项
                    state.libraryView == DriveLibrary.TRASH -> actionTargetFile = file
                    file.isFolder -> state.openFolder(file.id, file.name)
                    file.isUploading -> scope.launch { snackbarHostState.showSnackbar("文件仍在上传", withDismissAction = true) }
                    // 压缩包当文件夹进去看，见 ArchiveLocation。分卷读不了、归档条目不在网盘里，照旧往下走
                    // 信息流的临时浏览里再压一层压缩包，继续刷就说不清回到哪，先确认结束信息流
                    file.isExtractableArchive && !file.isVaulted && !ArchiveEntryId.isEntry(file.id) ->
                        taskSlot.claim("打开压缩包", "打开压缩包", task = null, conflictsWith = setOf(TaskSlot.Task.FEED)) { state.openArchive(file) }
                    // 包里的文件先借得出来才能打开，从没解压过的包第一次要等几秒
                    ArchiveEntryId.isEntry(file.id) -> scope.launch { state.prepareArchiveEntry(file)?.let(openFile) }
                    else -> openFile(file)
                }
            },
            onRename = { file -> if (!file.isUploading && state.libraryView != DriveLibrary.TRASH) startRename(file) },
            onMore = { actionTargetFile = it },
            onProperties = { latestShowProperties(targetsOf(it), propertiesAnchors.beside(it.id)) },
            onPlaced = { file, coordinates -> propertiesAnchors.placed(file.id, coordinates) },
            onLongPress = { state.enterSelection(it.id) },
            onSelect = { file, selected -> state.setSelected(file.id, selected) },
            onToggleSelect = state::toggleSelected,
            onExtendSelect = state::selectRange,
            onBoxSelect = state::selectBoxed,
            onMiddleClick = { file -> if (file.isFolder && state.libraryView != DriveLibrary.TRASH) latestOpenInNewTab?.invoke(file) },
            // 拖选中的一项时拖走全部选中的，否则只拖这一项，与文件管理器相同。回收站里的拖不出去：移走即是恢复，
            // 恢复有自己的按钮，拖放的「移到这里」在这里说不通
            dragPayload = dragPayload@{ file ->
                // 压缩包里的条目不在网盘里，移不走；要拿出来是解压
                if (state.libraryView == DriveLibrary.TRASH || state.archiveView != null) return@dragPayload null
                val batch = if (state.isSelectionMode && file.id in state.selectedFileIds) {
                    state.displayedFiles.filter { it.id in state.selectedFileIds }
                } else {
                    listOf(file)
                }.filterNot { it.isUploading || it.isVaulted }
                if (batch.isEmpty()) {
                    null
                } else {
                    FileDragPayload(
                        ids = batch.map { it.id },
                        parentIds = batch.mapTo(HashSet()) { it.parentId },
                        label = batch.singleOrNull()?.name ?: "${batch.size} 项",
                        perform = { target, copy ->
                            val ids = batch.map { it.id }
                            if (copy) state.copy(ids, target.id, target.name) else state.move(ids, target.id, target.name)
                        },
                    )
                }
            },
            onBackgroundClick = {
                commandFile = null
                locatedFileId = null
                if (state.isSelectionMode) state.exitSelection()
            },
            onFocusChanged = { file, focused ->
                if (focused) {
                    if (locatedFileId != file.id) locatedFileId = null
                    focusedFile = file
                    commandFile = file
                } else if (focusedFile?.id == file.id) {
                    focusedFile = null
                }
            },
            contextActions = { file -> itemActions(file, targetsOf(file), fromMenu = true) },
            onToggleSection = state::toggleSection,
            onFolderVisible = state::onFolderVisible,
        )
    }

    // 目录图里点了文件：先到它所在的文件夹，等那里列出来，再当作在列表里单击它。播放列表、压缩包里的引导解压、
    // 打不开的类型改为下载，都照列表里的走，不另写一套；直接打开的话播放列表拿的是眼前另一个文件夹的视频
    fun openFromMap(parent: List<PathBreadcrumb>, file: FileStat) {
        scope.launch {
            val folderId = parent.last().id
            if (driveRepo.folderStackFlow.value.lastOrNull()?.id != folderId) driveRepo.updateFolderStack(parent)
            val listed = withTimeoutOrNull(MapOpenTimeoutMs) {
                snapshotFlow { state.files.takeIf { state.loadedFolderId == folderId && !state.isLoading }?.firstOrNull { it.id == file.id } }
                    .first { it != null }
            }
            if (listed == null) {
                snackbarHostState.showSnackbar("没能在所在文件夹里找到这个文件", withDismissAction = true)
                return@launch
            }
            state.highlight(setOf(listed.id))
            callbacks.onOpen(listed)
        }
    }

    // 顶栏副标题：首个可见项之前最近的分区标题。停在文件夹或作品头上时取第一个分区
    val foldBanner = foldBannerOrNull(state)
    val leadingItemCount = driveLeadingItemCount(foldBanner != null)
    val currentSection by remember(gridState, leadingItemCount) {
        derivedStateOf {
            val headers = state.sectionHeaders
            val first = gridState.firstVisibleItemIndex - leadingItemCount
            (headers.lastOrNull { it.index <= first } ?: headers.firstOrNull())?.value?.menuLabel
        }
    }
    fun jumpToSection(position: Int) {
        val target = state.sectionHeaders.getOrNull(position) ?: return
        state.expandSection(target.value.blockId)
        scope.launch { gridState.animateScrollToItem(leadingItemCount + target.index) }
    }

    // 桌面快捷键。挂在页面根上的 onKeyEvent 收的是冒泡上来的事件：搜索框有焦点时，
    // 退格与 Ctrl+A 先由输入框处理，不会误删文件或全选列表
    val shortcutFocus = remember { FocusRequester() }
    // 换目录后再要一次：点进文件夹时焦点在被点的那一项上，它随旧列表一起没了，焦点落空，
    // 此后 Ctrl+F、Alt+← 这些快捷键都没有地方收
    LaunchedEffect(activeFolderId) { runCatching { shortcutFocus.requestFocus() } }

    // Esc 依次吃掉的几样，优先级见上面 BackHandler 那一段。
    // 单击高亮：鼠标点过的那一项留着焦点底色，命令栏也作用于它；Esc 让它回到没点过的样子，焦点交回页面，快捷键照常
    BackHandler(enabled = commandFile != null && !state.isSelectionMode) {
        commandFile = null
        focusedFile = null
        runCatching { shortcutFocus.requestFocus() }
    }
    BackHandler(enabled = state.isSelectionMode) { state.exitSelection() }
    // 属性：最后声明，Esc 先关它。它是最晚打开、最临时的东西
    BackHandler(enabled = properties != null) { properties = null }

    // 网格空白处的右键菜单，照资源管理器的顺序：怎么看、刷新、粘贴、往这里添东西、全选、属性。
    // 排序不在这里：六种排法平铺出来太长，这套菜单没有二级菜单，命令栏的排序按钮就在上方
    val showExtensions = LocalShowExtensions.current
    fun backgroundActions(): List<SheetAction> = buildList {
        // 三种视图都列出、眼下这一种打勾：只列另外两种的话，看不出现在是哪种
        DriveViewMode.entries.forEach { mode ->
            add(
                SheetAction(
                    mode.icon(selected = mode == viewMode),
                    mode.paletteLabel,
                    { scope.launch { sessionManager.setDriveViewMode(mode.name) } },
                    group = ActionGroup.View,
                    checked = mode == viewMode,
                ),
            )
        }
        // 照资源管理器「查看」里的「文件扩展名」，勾上即显示
        add(
            SheetAction(
                Icons.Outlined.TextFields,
                "文件扩展名",
                { scope.launch { sessionManager.setShowExtensions(!showExtensions) } },
                group = ActionGroup.View,
                checked = showExtensions,
            ),
        )
        add(SheetAction(Icons.Outlined.Refresh, "刷新", { state.load(refresh = true) }, group = ActionGroup.Refresh))
        if (!state.isVirtualPlace) {
            if (clipboard != null) add(SheetAction(Icons.Outlined.ContentPaste, "粘贴", { state.paste() }, group = ActionGroup.Refresh))
            add(SheetAction(Icons.Outlined.CreateNewFolder, "新建文件夹", {
                newFolderName = ""
                showNewFolderDialog = true
            }, group = ActionGroup.Create))
            add(SheetAction(Icons.Outlined.UploadFile, "上传文件", { pickFiles() }, group = ActionGroup.Create))
            add(SheetAction(Icons.Outlined.DriveFolderUpload, "上传文件夹", { pickFolder() }, group = ActionGroup.Create))
            add(SheetAction(Icons.Outlined.Bolt, "添加链接", ::openAddLink, group = ActionGroup.Create))
        }
        add(SheetAction(Icons.Outlined.SelectAll, "全选", { state.toggleSelectAll() }, group = ActionGroup.Select))
        addAll(libraryPageActions(libraryView, state.files.isEmpty(), { libraryConfirm = it }, { state.files.map { it.id } }))
        add(DriveActions.properties(::showFolderProperties))
    }


    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val primary = platform.shortcutModifier.isPressed(event)
        // 键位照各自系统的文件管理器：Windows 照资源管理器，mac 照 Finder
        val isMac = platform.shortcutModifier == ShortcutModifier.Command
        val arrow = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }
        // 方向键在条目间走：焦点已在某一项上就按版面找相邻的那一项；还没有时落到眼前第一项
        if (arrow != null && !event.isAltPressed && !event.isShiftPressed && !primary) {
            if (focusedFile != null) {
                focusManager.moveFocus(arrow)
            } else {
                // 网格的 key 是行的 key，查找重复里不是文件 ID
                val visible = gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                keyboardFocusTarget = state.displayItems.firstOrNull { it is DriveListItem.File && it.key in visible }?.key ?: return false
            }
            return true
        }
        val focused = focusedFile
        // 焦点所在的那一项：菜单键或 Shift+F10 打开它的操作面板，与右键菜单同一组操作
        if (focused != null && (event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10))) {
            actionTargetFile = focused
            return true
        }
        // 删除键在回收站里是彻底删除，要先确认，与资源管理器的回收站相同
        fun delete(ids: List<String>) {
            if (inTrash) libraryConfirm = LibraryConfirm.DeleteForever(ids, emptying = false) else state.moveToTrash(ids)
        }
        if (focused != null && !state.isSelectionMode) {
            when {
                event.key == Key.Delete || (primary && event.key == Key.Backspace) -> {
                    delete(listOf(focused.id))
                    return true
                }
                event.key == Key.F2 && !focused.isUploading && !inTrash -> {
                    startRename(focused)
                    return true
                }
                // Finder 的 ⌘↓ 打开；回车在 Finder 里是改名，由条目自己接住，见 DriveItemCallbacks.onRename
                isMac && primary && event.key == Key.DirectionDown -> {
                    callbacks.onOpen(focused)
                    return true
                }
            }
        }
        // Mac 键盘没有独立的 Delete 键，照 Finder 用 ⌘⌫
        val trashKey = event.key == Key.Delete || (primary && event.key == Key.Backspace)
        // 地址栏输入：资源管理器是 Ctrl+L、Alt+D 与 F4；Finder 没有地址栏，mac 上取浏览器的 ⌘L
        val addressKey = (primary && event.key == Key.L) || (!isMac && event.isAltPressed && event.key == Key.D) ||
            (!isMac && event.key == Key.F4 && !event.isAltPressed)
        when {
            addressKey && pathInTopBar -> addressEditRequests++
            // 照 VS Code 的资源管理器（Ctrl+Shift+E）
            pathInTopBar && primary && event.isShiftPressed && event.key == Key.E -> setFolderMapOpen(!folderMapOpen)
            // 宽窗口的搜索框常驻，取得焦点即可；窄屏点开搜索栏
            primary && event.key == Key.F -> if (pathInTopBar) searchFocusRequests++ else isSearchOpen = true
            // 剪切、复制、粘贴，照资源管理器：换个目录粘贴，剪切的即移过去
            primary && event.key == Key.X && commandTargets.isNotEmpty() && !inTrash ->
                state.putOnClipboard(commandTargets.map { it.id }, cut = true)
            primary && event.key == Key.C && commandTargets.isNotEmpty() && !inTrash ->
                state.putOnClipboard(commandTargets.map { it.id }, cut = false)
            primary && event.key == Key.V && clipboard != null && !state.isVirtualPlace -> state.paste()
            // 标签：新建停在眼前的位置，关掉活动的那个，Ctrl+Tab 与 Ctrl+PageDown/PageUp 前后切换，与浏览器相同
            tabsAvailable && primary && event.key == Key.T -> driveRepo.openTab(folderStack)
            tabsAvailable && primary && event.key == Key.W && tabs.size > 1 -> closeTab(activeTabId)
            tabsAvailable && event.isCtrlPressed && event.key == Key.Tab -> driveRepo.cycleTab(if (event.isShiftPressed) -1 else 1)
            tabsAvailable && primary && event.key == Key.PageDown -> driveRepo.cycleTab(1)
            tabsAvailable && primary && event.key == Key.PageUp -> driveRepo.cycleTab(-1)
            // 属性：主修饰键+I 各平台都认，mac 上即 Finder 的 ⌘I（显示简介）；Alt+Enter 照资源管理器，mac 上不认。
            // 焦点在条目上时 Alt+Enter 由条目先接住，见 DriveItemCallbacks.onProperties
            // 卡片贴着焦点所在的那一项，多选时也以它为准；没指着哪一项时贴第一项
            (primary && event.key == Key.I) || (!isMac && event.isAltPressed && event.key == Key.Enter) -> {
                val near = (commandFile ?: commandTargets.firstOrNull())?.id
                if (commandTargets.isNotEmpty() && near != null) showProperties(commandTargets, propertiesAnchors.beside(near)) else showFolderProperties()
            }
            event.key == Key.F5 || (primary && event.key == Key.R) -> state.load(refresh = true)
            primary && event.key == Key.A -> state.toggleSelectAll()
            // 撤销最近一次移动、移入回收站或重命名；没有可撤销的就不吃掉这个键
            primary && event.key == Key.Z && !event.isShiftPressed -> if (!state.undoLast()) return false
            trashKey && state.isSelectionMode && state.selectedFileIds.isNotEmpty() -> delete(state.selectedFileIds.toList())
            // 文件管理器的惯例：选中一项是改名，几项是批量重命名
            event.key == Key.F2 && state.isSelectionMode && state.selectedFileIds.isNotEmpty() && !inTrash -> {
                val targets = state.displayedFiles.filter { it.id in state.selectedFileIds && !it.isUploading }
                when {
                    targets.size == 1 -> startRename(targets.single())
                    targets.size > 1 -> batchRenameTargets = targets
                }
            }
            // 后退与前进走浏览历史：资源管理器是 Alt+←/→ 与退格，Finder 是 ⌘[ 与 ⌘]。
            // 上一级：资源管理器 Alt+↑，Finder ⌘↑
            (event.isAltPressed && event.key == Key.DirectionLeft) || (primary && event.key == Key.LeftBracket) ||
                (!isMac && !primary && event.key == Key.Backspace) -> state.goBack()
            (event.isAltPressed && event.key == Key.DirectionRight) || (primary && event.key == Key.RightBracket) -> state.goForward()
            ((!isMac && event.isAltPressed) || (isMac && primary)) && event.key == Key.DirectionUp &&
                folderStack.size > 1 -> state.navigateUp()
            else -> return false
        }
        return true
    }

    // 列表离开顶端时顶栏换上填充色与内容分开，M3 app bar 规范的滚动态。按列表眼下的位置判断，
    // gridState 按目录重建，换文件夹、恢复滚动位置都跟着对，见 rememberListScrollTint
    val topBarScrollBehavior = rememberListScrollTint {
        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
    }

    val history by state.history.collectAsStateWithLifecycle()

    // 当前目录名已在顶栏标题上，面包屑只列上级。一级目录的唯一上级是栈底（根或库），返回键已足够
    val ancestorCrumbs = folderStack.dropLast(1)
    val breadcrumbs: @Composable () -> Unit = {
        if (ancestorCrumbs.size > 1 && !pathInTopBar) {
            BreadcrumbBar(
                breadcrumbs = ancestorCrumbs,
                endsWithCurrent = false,
                // 栈底是库时首段写库名、画库的图标，点了回到库本身，与地址栏的首段一致
                firstIcon = folderStack.library?.icon ?: Icons.Outlined.Home,
                onBreadcrumbClick = { index -> state.navigateToBreadcrumb(index) },
            )
        }
    }

    // 各入口上摆哪些操作：宽窗口的命令栏、窄窗口的 FAB 菜单与多选顶栏共用这一份规则，见 DriveCommands.kt
    val commands = driveCommands(
        CommandInputs(
            place = commandPlace(folderStack.size),
            atRoot = folderStack.size == 1 && activeFolderId.isEmpty(),
            targets = commandTargets,
            selecting = state.isSelectionMode && selectedIdSet.isNotEmpty(),
            clipboardFull = clipboard != null,
            itemCount = displayedFiles.size,
            allSelected = displayedFiles.isNotEmpty() && displayedFiles.all { it.id in selectedIdSet },
            typeCount = state.availableTypes.size,
            filtering = state.typeFilter != null,
            feedSupported = platform.videoPreview != null && onFeedShownChange != null,
        ),
    )

    // 库与查重这类位置自己的主操作。窄窗口里是扩展 FAB，宽窗口里在命令栏右端，没有时那里是添加链接
    val placePrimaryAction: SheetAction? = when {
        state.isSelectionMode -> null
        inDuplicates -> duplicateState?.suggestedIds?.size?.takeIf { it > 0 }?.let { count ->
            SheetAction(Icons.Outlined.Checklist, "选中建议移走的 $count 项", state::selectSuggestedDuplicates)
        }
        state.archiveView != null && displayedFiles.isNotEmpty() ->
            SheetAction(Icons.Outlined.Unarchive, "全部解压", { extractFromArchive(emptyList()) })
        commands.emptyPlace -> libraryPageActions(libraryView, state.files.isEmpty(), { libraryConfirm = it }, { state.files.map { it.id } }).firstOrNull()
        else -> null
    }

    // 宽窗口的两行栏，照资源管理器：导航栏（后退、前进、上一级、刷新、地址栏、搜索框）与命令栏。
    // 多选时不换顶栏，命令栏的条目操作本就作用于选中的几项；新建与排序、视图也从 FAB 与列表页眉搬到这里
    val explorerBars: @Composable () -> Unit = {
        val targetIds = commandTargets.map { it.id }
        val movable = commandTargets.filterNot { it.isUploading }
        ExplorerNavBar(
            shortcuts = platform.shortcutModifier,
            canGoBack = history.canGoBack,
            canGoForward = history.canGoForward,
            canGoUp = folderStack.size > 1,
            onBack = { state.goBack() },
            onForward = { state.goForward() },
            onUp = { state.navigateUp() },
            showFolderMap = if (folderMapOpen) null else ({ setFolderMapOpen(true) }),
            address = {
                val recentFolders by driveRepo.recentFoldersFlow.collectAsStateWithLifecycle()
                DrivePathTitle(
                    model = AddressBarModel(
                        stack = folderStack,
                        onNavigate = { index -> state.navigateToBreadcrumb(index) },
                        onOpenStack = driveRepo::updateFolderStack,
                        onSubmitPath = state::goToPath,
                        completions = state::addressCompletions,
                        subfoldersOf = state::subfoldersOf,
                        recent = recentFolders,
                        pinned = pinnedFolders,
                        onOpenPinned = state::openPinned,
                        onRemoveRecent = driveRepo::removeRecentFolder,
                        onOpenLink = { link -> instantSession.start(link) },
                        onSearchHere = { term -> state.updateSearchQuery(term) },
                        onSearchAll = { term ->
                            state.updateSearchQuery(term)
                            state.startGlobalSearch()
                        },
                        destinations = addressDestinations,
                    ),
                    editRequests = addressEditRequests,
                )
            },
            search = {
                ExplorerSearchField(
                    query = state.searchQuery,
                    onQueryChange = { state.updateSearchQuery(it) },
                    placeholder = "搜索 ${activeFolder.name}",
                    shortcut = platform.shortcutModifier.label("F"),
                    isGlobalSearching = state.isGlobalSearching,
                    isGlobalSearchActive = state.isGlobalSearchActive,
                    onStartGlobalSearch = { state.startGlobalSearch() },
                    onCancelGlobalSearch = { state.cancelGlobalSearch() },
                    focusRequests = searchFocusRequests,
                )
            },
        )
        ExplorerCommandBar(
            shortcuts = platform.shortcutModifier,
            commands = commands,
            newActions = listOf(
                SheetAction(Icons.Outlined.CreateNewFolder, "新建文件夹", {
                    newFolderName = ""
                    showNewFolderDialog = true
                }),
                SheetAction(Icons.Outlined.UploadFile, "上传文件", { pickFiles() }),
                SheetAction(Icons.Outlined.DriveFolderUpload, "上传文件夹", { pickFolder() }),
            ),
            targetCount = commandTargets.size,
            selectedCount = if (state.isSelectionMode) state.selectedFileIds.size else 0,
            // 文件夹的大小列表接口不给，只合计文件
            selectedBytes = if (state.isSelectionMode) displayedFiles.filter { it.id in selectedIdSet && !it.isFolder }.sumOf { it.sizeBytes } else 0L,
            onExitSelection = { state.exitSelection() },
            onCut = { state.putOnClipboard(targetIds, cut = true) },
            onCopy = { state.putOnClipboard(targetIds, cut = false) },
            onPaste = { state.paste() },
            onRename = {
                when {
                    movable.size == 1 -> startRename(movable.single())
                    movable.size > 1 -> batchRenameTargets = movable
                }
            },
            // 按列表顺序：服务端取第一项的名字作分享标题
            onShare = { shareTargets = movable },
            onTrash = { state.moveToTrash(targetIds) },
            restoreActions = trashActions(
                onRestore = { state.restoreFromTrash(targetIds) },
                onDelete = { libraryConfirm = LibraryConfirm.DeleteForever(targetIds, emptying = false) },
            ),
            onSelectAll = { state.toggleSelectAll() },
            onFindDuplicates = { findDuplicates(activeFolder) },
            sortOrder = state.sortOrder,
            onSortChange = { state.changeSortOrder(it) },
            typeFilter = state.typeFilter,
            availableTypes = state.availableTypes,
            onTypeFilterChange = state::updateTypeFilter,
            sectionJumper = {
                SectionJumper(
                    currentSection = currentSection,
                    sections = state.sectionHeaders.map { it.value.menuLabel },
                    onSectionSelected = ::jumpToSection,
                )
            },
            // 显不显示由 commands 定，这里只管每一项做什么
            moreActions = buildList {
                if (commands.moveCopyTo) {
                    add(DriveActions.moveTo { moveTargetIds = movable.map { it.id }.toSet() })
                    add(DriveActions.copyTo { copyTargetIds = movable.map { it.id }.toSet() })
                }
                if (commands.download) {
                    val targets = movable
                    add(DriveActions.download { download(targets) })
                }
                if (commands.extract && state.archiveView != null) {
                    val items = commandTargets
                    add(DriveActions.extractEntries { extractFromArchive(items) })
                } else if (commands.extract) {
                    val archives = movable.filter { it.isExtractableArchive || it.isArchiveVolume }
                    add(DriveActions.extract {
                        archiveSession.extract(archives)
                        state.exitSelection()
                    })
                }
                // 找不回来的垫底，与右键菜单一致
                if (commands.removeRecord) add(DriveActions.removeRecord { state.removeFromLibrary(targetIds) })
            },
            onRefresh = { state.load(refresh = true) },
            onHome = ::goHome,
            viewSwitcher = {
                ViewSwitcher(
                    viewMode = viewMode,
                    onViewModeChange = { mode -> scope.launch { sessionManager.setDriveViewMode(mode.name) } },
                    feedShown = feedShown,
                    onFeedShownChange = onFeedShownChange.takeIf { commands.feed },
                    feedSuspended = feedStashed,
                )
            },
            primaryAction = placePrimaryAction ?: if (commands.addLink) SheetAction(Icons.Outlined.Bolt, "添加链接", ::openAddLink, group = ActionGroup.Refresh) else null,
        )
    }

    // 按番号规范命名：打开批量重命名并换上这条规则。没选中时作用于当前目录的全部文件。
    // 右键菜单与操作面板里的入口待它们改完再补
    var avNamingTargets by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    if (avNamingTargets.isNotEmpty()) {
        BatchRenameDialog(
            files = avNamingTargets,
            onDismiss = { avNamingTargets = emptyList() },
            onFinished = { message ->
                if (state.isSelectionMode) state.exitSelection()
                scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
            },
            startWithAvNaming = true,
        )
    }
    val avNamingCandidates = commandTargets.ifEmpty { displayedFiles }.filter { !it.isUploading }

    // 网盘页在眼前时，命令面板里多出这一页的命令
    ContributePaletteItems("drive") {
        val label = platform.shortcutModifier::label
        buildList {
            if (!state.isVirtualPlace) {
                add(PaletteItem("新建文件夹", Icons.Outlined.CreateNewFolder, "网盘", keywords = "new folder mkdir") { showNewFolderDialog = true })
                add(PaletteItem("上传文件", Icons.Outlined.UploadFile, "网盘", keywords = "upload") { pickFiles() })
                add(PaletteItem("上传文件夹", Icons.Outlined.DriveFolderUpload, "网盘", keywords = "upload folder") { pickFolder() })
            }
            if (pathInTopBar) {
                add(PaletteItem(if (folderMapOpen) "关闭目录图" else "打开目录图", Icons.Outlined.AccountTree, "网盘",
                    detail = if (platform.shortcutModifier == ShortcutModifier.Command) "⌘⇧E" else "Ctrl+Shift+E", keywords = "folder tree map 树") {
                    setFolderMapOpen(!folderMapOpen)
                })
            }
            add(PaletteItem("搜索文件", Icons.Outlined.Search, "网盘", detail = label("F"), keywords = "search find") {
                if (pathInTopBar) searchFocusRequests++ else isSearchOpen = true
            })
            add(PaletteItem("属性", Icons.Outlined.Info, "网盘", detail = label("I"), keywords = "properties info details") {
                if (commandTargets.isNotEmpty()) showProperties(commandTargets, PanelAnchor.Centered) else showFolderProperties()
            })
            DriveViewMode.entries.filter { it != viewMode }.forEach { mode ->
                add(PaletteItem("切换到${mode.paletteLabel}", mode.icon(), "网盘", keywords = "视图 view ${mode.name.lowercase()}") {
                    scope.launch { sessionManager.setDriveViewMode(mode.name) }
                })
            }
            add(PaletteItem("全选", Icons.Outlined.SelectAll, "网盘", detail = label("A"), keywords = "select all") { state.toggleSelectAll() })
            add(PaletteItem("刷新", Icons.Outlined.Refresh, "网盘", detail = "F5", keywords = "refresh reload") { state.load(refresh = true) })
            if (folderStack.size > 1) add(PaletteItem("上一级", Icons.Outlined.ArrowUpward, "网盘", detail = "Backspace", keywords = "up parent") { state.navigateUp() })
            if (tabsAvailable) {
                add(PaletteItem("新建标签页", Icons.Outlined.Add, "标签", detail = label("T"), keywords = "new tab") { driveRepo.openTab(folderStack) })
                if (tabs.size > 1) {
                    add(PaletteItem("关闭标签页", Icons.Outlined.Close, "标签", detail = label("W"), keywords = "close tab") { closeTab(activeTabId) })
                    tabs.filter { it.id != activeTabId }.forEach { tab ->
                        add(PaletteItem(tab.title, Icons.Outlined.Tab, "标签", detail = tab.stack.joinToString(" › ") { it.name }, keywords = "tab") {
                            driveRepo.switchTab(tab.id)
                        })
                    }
                }
            }
            if (!state.isVirtualPlace) {
                add(PaletteItem("在当前文件夹查找重复", Icons.Outlined.FileCopy, "网盘", keywords = "duplicate dedupe") { findDuplicates(activeFolder) })
            }
            if (commands.rename && avNamingCandidates.any { !it.isFolder && hasAvCode(it.name) }) {
                add(PaletteItem("按番号规范命名", Icons.Outlined.Edit, "网盘", keywords = "rename av code 番号 重命名") { avNamingTargets = avNamingCandidates })
            }
        }
    }

    // 宽窗口里属性是浮在列表上的卡片，不挡着继续操作列表；窄窗口是面板，见下面 PikoSheet 那一处
    val propertiesFloating = twoPane
    // 目录图与属性同浮在列表这一块里，最近碰过或打开的那块在上面；对别的项再开属性也算打开
    var propertiesOnTop by remember { mutableStateOf(true) }
    LaunchedEffect(properties) { if (properties != null) propertiesOnTop = true }
    LaunchedEffect(folderMapOpen) { if (folderMapOpen) propertiesOnTop = false }

    // 页眉下面的一块：列表，上面浮着目录图与属性。都只在这一块里，不往上伸到页眉：
    // 地址栏与命令栏始终横贯整个宽度，窗口按钮也就始终在地址栏那一行
    val density = LocalDensity.current
    var listAreaWidth by remember { mutableStateOf<Dp?>(null) }
    val listArea: @Composable (PaddingValues, @Composable () -> Unit) -> Unit = { innerPadding, list ->
        Box(
            Modifier
                .fillMaxSize()
                // 宽窗口里卡片停在解压、秒传这些提示条上面，没有提示条时离窗口底边留一截外框色，与别的页相同；
                // 窄窗口照旧让它们浮在列表上
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = if (pathInTopBar) maxOf(innerPadding.calculateBottomPadding(), FrameCardBottomMargin) else 0.dp,
                )
                .consumeWindowInsets(innerPadding)
                // 网格按这一块的宽度定栏数，见 StableColumns
                .onSizeChanged { listAreaWidth = with(density) { it.width.toDp() } },
        ) {
            contentFrame {
                // 属性卡片的锚点在这一块的坐标里：右键按下的点、条目的范围都换算到这里
                Box(Modifier.fillMaxSize().propertiesAnchorArea(propertiesAnchors)) {
                    list()
                    if (pathInTopBar && folderMapOpen) {
                        FolderMapPanel(
                            state = folderMap,
                            current = folderStack,
                            onClose = { setFolderMapOpen(false) },
                            onOpen = driveRepo::updateFolderStack,
                            onOpenFile = ::openFromMap,
                            // 与命令栏的主页按钮同一条规则：已在根目录时不给
                            onHome = if (commands.home) ::goHome else null,
                            modifier = Modifier.zIndex(if (propertiesOnTop) 0f else 1f),
                            avoid = { propertiesPanel.bounds },
                            onActivate = { propertiesOnTop = false },
                        )
                    }
                    val shown = properties
                    if (propertiesFloating && shown != null) {
                        FloatingPanel(
                            state = propertiesPanel,
                            title = "属性",
                            closeLabel = "关闭属性",
                            onClose = { properties = null },
                            minWidth = PropertiesMinWidth,
                            maxWidth = PropertiesMaxWidth,
                            // 随内容长短，最高到列表这一块的底，再长就在卡片里滚
                            autoMaxHeight = Dp.Unspecified,
                            modifier = Modifier.zIndex(if (propertiesOnTop) 1f else 0f),
                            resizable = false,
                            onActivate = { propertiesOnTop = true },
                        ) {
                            // 换了一项从头看起，不停在上一项滚到的地方
                            key(shown) { PropertiesPane(shown, Modifier.weight(1f, fill = false).widthIn(min = PropertiesMinWidth)) }
                        }
                    }
                }
            }
        }
    }

    // 窄窗口底部那一块 sheet：添加链接或查找重复，同一时刻只有一件（TaskSlot 管着入口）。
    // 宽窗口是侧边面板、右下角的浮动卡片与查重标签，不用它
    val taskSheet: TaskSheetModel? = when {
        twoPane -> null
        instantState != null -> TaskSheetModel(
            key = instantState,
            expanded = instantSession.isSheetOpen,
            // 收起时什么都没粘过的直接结束，见 InstantSession.collapse
            onExpandedChange = { expand -> if (expand) instantSession.reopen() else instantSession.collapse() },
            header = {
                val summary = instantState.summary()
                TaskSheetHeader(summary.title, summary.status, "放弃添加", instantSession::end, closeEnabled = !summary.busy)
            },
            body = {
                InstantSheetContent(
                    state = instantState,
                    headerShown = true,
                    // 先收起：播放器压栈进来时网盘页还看得见那一截，回来接着挑
                    onPreview = { fileId, fileName ->
                        instantSession.collapse()
                        navigateToPlayer(FileStat(id = fileId, name = fileName), emptyList())
                    },
                )
            },
        )
        duplicateState != null && !inDuplicates -> duplicatesSheet(
            finder = duplicateState,
            expanded = taskSlot.duplicatesExpanded,
            onExpandedChange = { taskSlot.duplicatesExpanded = it },
            onOpenResults = { openDuplicates(duplicateState.root) },
            onEnd = duplicateSession::end,
        )
        else -> null
    }

    // 焦点与按键挂在最外层：挂在侧栏的子组合里的话，比外面的 LaunchedEffect 晚挂上，首帧要焦点时找不到它
    Box(
        modifier = modifier
            .fillMaxSize()
            .pageFocusTarget(shortcutFocus)
            .onKeyEvent(::handleShortcut)
            // 鼠标侧键是后退与前进，与资源管理器、浏览器相同
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        when {
                            event.buttons.isBackPressed -> state.goBack()
                            event.buttons.isForwardPressed -> state.goForward()
                        }
                    }
                }
            },
    ) { TaskSheetScaffold(taskSheet) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                // 宽窗口的两行栏与标签栏落在页眉的底色上，内容区自己铺页面本色
                containerColor = if (pathInTopBar) MaterialTheme.colorScheme.frame else MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    Column {
                        // 宽窗口一直有标签栏，只开一个标签时也是：开第二个时页面不跳，窗口按钮也一直在这一行
                        if (tabsAvailable) {
                            DriveTabBar(
                                tabs = tabs,
                                activeId = activeTabId,
                                onSelect = driveRepo::switchTab,
                                onClose = ::closeTab,
                                duplicates = duplicateState,
                                feedTabId = feedTabId,
                                onNewTab = { driveRepo.openTab(folderStack) },
                                newTabShortcut = platform.shortcutModifier.label("T"),
                            )
                        }
                        when {
                            // 页眉与下面的列表是同一块岛，页眉与活动标签同色相连：地址栏、命令栏与列表都属于这一个标签，
                            // 切标签时一起换。试过页眉、列表各成一块，中间露一道外框色，读起来像两样东西；
                            // 上下两段靠色阶分开（islandHeader），不画线
                            pathInTopBar && tabsAvailable -> Column(
                                Modifier
                                    // 第一个标签活动时它的左边竖直接下来，岛的左上角不圆
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = islandTopStart(firstActive = tabs.firstOrNull()?.id == activeTabId),
                                            topEnd = IslandCorner,
                                        ),
                                    )
                                    .background(MaterialTheme.colorScheme.islandHeader)
                                    // 命令栏的按钮离下面列表的距离与离上面地址栏的相同：两行各自上下留 8dp，行间合起来是 16dp
                                    .padding(bottom = 8.dp),
                            ) { CompositionLocalProvider(LocalHeaderOnIsland provides true) { explorerBars() } }
                            pathInTopBar -> explorerBars()

                            state.isSelectionMode && inTrash -> TrashSelectionTopBar(
                                scrollBehavior = topBarScrollBehavior,
                                selectedCount = state.selectedFileIds.size,
                                enabled = !state.isTrashActionRunning,
                                onExit = { state.exitSelection() },
                                onSelectAll = { state.toggleSelectAll() },
                                onRestore = { state.restoreFromTrash(state.selectedFileIds.toList()) },
                                onDelete = { libraryConfirm = LibraryConfirm.DeleteForever(state.selectedFileIds.toList(), emptying = false) },
                            )

                            // 摆哪些与命令栏同一份规则（commands），做不了的不摆
                            state.isSelectionMode -> DriveSelectionTopBar(
                                scrollBehavior = topBarScrollBehavior,
                                selectedCount = state.selectedFileIds.size,
                                onExit = { state.exitSelection() },
                                onSelectAll = { state.toggleSelectAll() }.takeIf { commands.selectAll },
                                download = DriveActions.download { download(commandTargets.filterNot { it.isUploading }) }.takeIf { commands.download },
                                moveTo = DriveActions.moveTo { moveTargetIds = state.selectedFileIds.toSet() }.takeIf { commands.moveCopyTo },
                                copyTo = DriveActions.copyTo { copyTargetIds = state.selectedFileIds.toSet() }.takeIf { commands.moveCopyTo },
                                trash = DriveActions.moveToTrash { state.moveToTrash(state.selectedFileIds.toList()) }.takeIf { commands.moveToTrash },
                                extract = if (state.archiveView != null) {
                                    DriveActions.extractEntries { extractFromArchive(state.displayedFiles.filter { it.id in state.selectedFileIds }) }
                                } else selectedArchives.takeIf { commands.extract && it.isNotEmpty() }?.let { archives ->
                                    DriveActions.extract {
                                        archiveSession.extract(archives)
                                        state.exitSelection()
                                    }
                                },
                                share = DriveActions.share {
                                    // 按列表顺序：服务端取第一项的名字作分享标题
                                    shareTargets = state.displayedFiles.filter { it.id in state.selectedFileIds && !it.isUploading }
                                }.takeIf { commands.share },
                                batchRename = DriveActions.batchRename {
                                    batchRenameTargets = state.displayedFiles.filter { it.id in state.selectedFileIds && !it.isUploading }
                                }.takeIf { commands.rename },
                            )

                            isSearchOpen -> DriveSearchTopBar(
                                query = state.searchQuery,
                                onQueryChange = { state.updateSearchQuery(it) },
                                onClose = { closeSearch() },
                                isGlobalSearching = state.isGlobalSearching,
                                isGlobalSearchActive = state.isGlobalSearchActive,
                                onStartGlobalSearch = { state.startGlobalSearch() },
                                onCancelGlobalSearch = { state.cancelGlobalSearch() },
                            )

                            else -> DriveBrowseTopBar(
                                scrollBehavior = topBarScrollBehavior,
                                title = activeFolder.name,
                                currentSection = currentSection,
                                sections = state.sectionHeaders.map { it.value.menuLabel },
                                onSectionSelected = ::jumpToSection,
                                navigationIcon = when {
                                    folderStack.size > 1 -> {
                                        {
                                            TooltipIconButton(
                                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                                label = "返回上一级",
                                                onClick = { state.navigateUp() },
                                                shortcut = if (platform.shortcutModifier == ShortcutModifier.Command) "⌘↑" else "Alt+↑",
                                            )
                                        }
                                    }
                                    libraryView != null -> {
                                        { TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", ::leaveLibrary) }
                                    }
                                    else -> null
                                },
                                // 顶栏只留信息流与搜索：M3 顶栏放一到两个动作，新建与秒传同属「往网盘里添东西」，
                                // 一起收进 FAB 菜单；排序与视图切换作用于列表，放在列表页眉。放不下时先收刷新
                                actions = buildList {
                                    if (commands.feed && onFeedShownChange != null) {
                                        // 标题栏并进内容时这一行还要画三个窗口按钮，信息流收成图标，目录名才露得出来
                                        val iconOnly = LocalWindowCaption.current != null
                                        add(BarItem(
                                            key = "feed",
                                            priority = 20,
                                            overflow = listOf(SheetAction(Icons.Outlined.SwipeVertical, if (feedShown) "关闭信息流" else "信息流", { onFeedShownChange(!feedShown) })),
                                        ) { FeedToggle(shown = feedShown, onShownChange = onFeedShownChange, iconOnly = iconOnly) })
                                    }
                                    add(iconBarItem(Icons.Outlined.Search, "搜索", { isSearchOpen = true }, priority = 30, shortcut = platform.shortcutModifier.label("F")))
                                    if (showsRefreshButton()) {
                                        add(iconBarItem(Icons.Outlined.Refresh, "刷新", { state.load(refresh = true) }, priority = 10, shortcut = "F5"))
                                    }
                                },
                            )
                        }
                    }
                },
                floatingActionButton = {
                    // 一个槽位只放一个主操作（M3：一屏只有一个 FAB，FAB 菜单不与扩展 FAB 同屏）：这个位置自己的主操作
                    // （清空回收站、选中建议移走的）在时就是它，否则是「+」菜单。曾两样依次画在同一个槽位里，查重页上「+」压住了扩展 FAB
                    if (placePrimaryAction != null && !pathInTopBar) {
                        PrimaryActionFab(placePrimaryAction)
                    } else if (!state.isSelectionMode && !pathInTopBar && (commands.addLink || commands.create || commands.findDuplicates)) {
                        // 宽窗口的新建与上传在命令栏的「新建」里。菜单里摆什么与命令栏同一份规则（commands），
                        // 一项也没有时整个按钮不出现
                        // FAB 菜单自带 16dp 的右边距与下边距，Scaffold 的 FAB 槽位又留了 16dp，
                        // 不抵消的话按钮离屏幕角是 32dp。偏移而不是挪出槽位，系统栏避让仍由 Scaffold 处理
                        FloatingActionButtonMenu(
                            expanded = isFabMenuExpanded,
                            modifier = Modifier.offset(x = 16.dp, y = 16.dp),
                            button = {
                                ToggleFloatingActionButton(
                                    checked = isFabMenuExpanded,
                                    onCheckedChange = { isFabMenuExpanded = it },
                                ) {
                                    val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = if (isFabMenuExpanded) "收起" else "添加",
                                        modifier = Modifier.animateIcon({ checkedProgress }),
                                    )
                                }
                            },
                        ) {
                            if (commands.addLink) {
                                FloatingActionButtonMenuItem(
                                    onClick = {
                                        isFabMenuExpanded = false
                                        openAddLink()
                                    },
                                    icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                                    // 会话收起着时是回到它，名字照实说
                                    text = { Text(if (instantState != null) "继续添加链接" else "添加链接") },
                                )
                            }
                            if (commands.create) {
                                FloatingActionButtonMenuItem(
                                    onClick = {
                                        isFabMenuExpanded = false
                                        newFolderName = ""
                                        showNewFolderDialog = true
                                    },
                                    icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                                    text = { Text("新建文件夹") },
                                )
                                FloatingActionButtonMenuItem(
                                    onClick = {
                                        isFabMenuExpanded = false
                                        pickFiles()
                                    },
                                    icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                                    text = { Text("上传文件") },
                                )
                                FloatingActionButtonMenuItem(
                                    onClick = {
                                        isFabMenuExpanded = false
                                        pickFolder()
                                    },
                                    icon = { Icon(Icons.Outlined.DriveFolderUpload, contentDescription = null) },
                                    text = { Text("上传文件夹") },
                                )
                            }
                            if (commands.findDuplicates) {
                                FloatingActionButtonMenuItem(
                                    onClick = {
                                        isFabMenuExpanded = false
                                        findDuplicates(activeFolder)
                                    },
                                    icon = { Icon(Icons.Outlined.FileCopy, contentDescription = null) },
                                    text = { Text("查找重复") },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding -> listArea(innerPadding) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (pathInTopBar) {
                                if (tabsAvailable) {
                                    // 与页眉同一块岛：上沿两角露出一点页眉的底色，像列表被页眉包着，而不是两块拼在一起
                                    Modifier
                                        .clip(RoundedCornerShape(bottomStart = IslandCorner, bottomEnd = IslandCorner))
                                        .background(MaterialTheme.colorScheme.islandHeader)
                                        .clip(RoundedCornerShape(topStart = IslandInnerCorner, topEnd = IslandInnerCorner, bottomStart = IslandCorner, bottomEnd = IslandCorner))
                                        .background(MaterialTheme.colorScheme.surface)
                                } else {
                                    Modifier.clip(FrameCardShape).background(MaterialTheme.colorScheme.surface)
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 首屏三态。换的是同一块区域的三种填充，没有方向，AnimatedContent 还会多一次尺寸过渡；
                    // 淡入淡出属于效果而非位移，取 effects 档。已有内容时的刷新走下拉，不回到骨架
                    val phase = when {
                        state.isLoading -> DrivePhase.Loading
                        // 只认目录里确实什么都没读到：files 未经搜索与折叠筛选，筛空了仍是内容态
                        state.files.isEmpty() && state.loadError != null -> DrivePhase.Failed(state.loadError.orEmpty())
                        else -> DrivePhase.Content
                    }
                    Crossfade(
                        targetState = phase,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "drive_phase",
                    ) { current ->
                        if (current is DrivePhase.Loading) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 面包屑与页眉的位置先空出来，内容换上时各行不挪
                                breadcrumbs()
                                if (!pathInTopBar) Spacer(modifier = Modifier.height(DriveListHeaderHeight))
                                DriveGridSkeleton(viewMode = viewMode, modifier = Modifier.weight(1f))
                            }
                            return@Crossfade
                        }
                        if (current is DrivePhase.Failed) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                breadcrumbs()
                                // 不带 refresh：重新走一次首载，重试期间回到骨架，而不是停在错误页上没有反馈
                                PikoErrorState(
                                    message = current.message,
                                    onRetry = { state.load() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            return@Crossfade
                        }
                        RefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = { state.load(refresh = true) },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 加载失败时列表留在上一次的内容上。Snackbar 弹完就没了，
                                // 这条横幅常驻到重新加载成功，否则用户无从知道眼前是旧数据。
                                state.loadError?.let { reason ->
                                    StaleDataBanner(reason = reason, onRetry = { state.load(refresh = true) })
                                }

                                val bottomPadding = innerPadding.calculateBottomPadding() + FabClearance
                                // 筛选或图库视图下为空时仍给列表：空目录页没有页眉，筛选撤不掉，视图也切不回去
                                if (inDuplicates && state.files.isEmpty()) {
                                    breadcrumbs()
                                    DuplicatesEmptyState(
                                        finder = duplicateState,
                                        onLeave = ::goHome,
                                        canLeave = twoPane,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else if (state.displayItems.isEmpty() && state.typeFilter == null && !state.thumbnailsOnly) {
                                    // 空目录没有列表页眉，面包屑单独放在空状态上方
                                    breadcrumbs()
                                    DriveEmptyState(state = state, modifier = Modifier.weight(1f))
                                } else {
                                    DriveFileGrid(
                                        items = state.displayItems,
                                        folderView = { if (!state.isNameParsing) null else state.folderViews[it.id] },
                                        viewMode = viewMode,
                                        gridState = gridState,
                                        isSelectionMode = state.isSelectionMode,
                                        selectedIds = selectedIdSet,
                                        highlightedIds = highlightedRowIds,
                                        highlightRevision = state.highlightRevision,
                                        structureReady = state.isDisplayStructureReady,
                                        locatedItemId = locatedFileId,
                                        isBlurred = { isSpoilerBlurEnabled && it.id !in state.revealedFileIds },
                                        hitLocations = rowNotes,
                                        callbacks = callbacks,
                                        bottomPadding = bottomPadding,
                                        moreButton = itemMoreButton,
                                        backgroundActions = ::backgroundActions,
                                        columnReferenceWidth = listAreaWidth,
                                        activeItemId = commandFile?.id,
                                        emptyFolders = folderEmptiness,
                                        vaultedFolders = vaultedFolders,
                                        keyboardFocusTarget = keyboardFocusTarget,
                                        onKeyboardFocusMoved = { keyboardFocusTarget = null },
                                        header = {
                                            // 面包屑随列表滚走，而不是钉在顶栏下方：顶栏滚动后换了填充色，
                                            // 钉住的面包屑会在它下面留一条底色不同的带子
                                            Column {
                                                breadcrumbs()
                                                if (inDuplicates && duplicateState != null) {
                                                    DuplicatesBanner(duplicateState, onEnd = duplicateSession::end)
                                                }
                                                // 起始只留 4dp：排序是 TextButton，自带 12dp 内边距，合起来图标落在 16dp
                                                // 页边距上。末端的视图切换是 ToggleButton，没有内边距，要给足 16dp
                                                Box(modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
                                                    DriveListHeader(
                                                        summary = searchSummary(state, displayedFiles),
                                                        sortOrder = state.sortOrder,
                                                        onSortChange = { state.changeSortOrder(it) },
                                                        typeFilter = state.typeFilter,
                                                        availableTypes = state.availableTypes,
                                                        onTypeFilterChange = state::updateTypeFilter,
                                                        viewMode = viewMode,
                                                        onViewModeChange = { mode ->
                                                            scope.launch { sessionManager.setDriveViewMode(mode.name) }
                                                        },
                                                        // 宽窗口的排序、筛选与视图在命令栏上，页眉只剩搜索结果的说明
                                                        showControls = !pathInTopBar,
                                                    )
                                                }
                                            }
                                        },
                                        foldBanner = foldBanner,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            } }
    } }

    actionTargetFile?.let { target ->
        FileActionsSheet(
            file = target,
            locationLabel = rowNotes[target.id],
            // 回收站里的条目查不了详情，文件夹也统计不了。remember 住同一个 flow：每次重组新建的话，produceState 会把统计从头再跑一遍
            folderUsage = remember(target.id, inTrash) { if (target.isFolder && !inTrash && isDriveFolderId(target.id)) driveRepo.folderUsage(target.id) else null },
            // 面板从这一项的更多按钮打开，只作用于它自己，不随多选扩到全部选中的
            actions = itemActions(target, listOf(target), fromMenu = false),
            onDismiss = { actionTargetFile = null },
        )
    }

    // 窄窗口里属性是面板：放不下浮在列表上的卡片，触屏上也拖不动它
    properties?.takeIf { !propertiesFloating }?.let { shown ->
        PikoSheet(onDismissRequest = { properties = null }, sideSheetTitle = "属性") {
            PropertiesPane(shown, Modifier.fillMaxWidth())
        }
    }

    state.archivePasswordRequest?.let { request ->
        ArchiveBrowsePasswordDialog(
            archiveName = request.archiveName,
            incorrect = request.incorrect,
            onSubmit = state::submitArchivePassword,
            onDismiss = state::dismissArchivePassword,
        )
    }

    restoreVaultTarget?.let { folder ->
        RestoreVaultFolderDialog(PathBreadcrumb(folder.id, folder.name), vaultSession, onDismiss = { restoreVaultTarget = null })
    }

    vaultTarget?.let { folder ->
        VaultFolderDialog(PathBreadcrumb(folder.id, folder.name), vaultSession, onDismiss = { vaultTarget = null })
    }

    libraryConfirm?.let { request ->
        LibraryConfirmDialog(
            request = request,
            onConfirm = {
                libraryConfirm = null
                when (request) {
                    is LibraryConfirm.DeleteForever -> state.deletePermanently(request.ids)
                    LibraryConfirm.ClearHistory -> state.clearPlayHistory()
                }
            },
            onDismiss = { libraryConfirm = null },
        )
    }

    qualityTarget?.let { file ->
        QualityDownloadDialog(
            file = file,
            onDownload = { quality ->
                downloadManager.enqueueQuality(file, quality)
                openTransfers()
            },
            onDismiss = { qualityTarget = null },
        )
    }

    if (shareTargets.isNotEmpty()) {
        ShareDialog(
            files = shareTargets,
            onDismiss = { shareTargets = emptyList() },
            onCopied = {
                if (state.isSelectionMode) state.exitSelection()
                scope.launch { snackbarHostState.showSnackbar("已复制分享链接", withDismissAction = true) }
            },
        )
    }

    if (batchRenameTargets.isNotEmpty()) {
        BatchRenameDialog(
            files = batchRenameTargets,
            onDismiss = { batchRenameTargets = emptyList() },
            onFinished = { message ->
                if (state.isSelectionMode) state.exitSelection()
                scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
            },
        )
    }


    // 宽窗口的秒传面板。关掉只是收起，会话还在，右下角留一张浮动卡片，见 InstantSession；窄窗口在底部的 sheet 里（taskSheet）
    if (twoPane && instantState != null && instantSession.isSheetOpen) {
        // 侧栏形态的顶上已有标题与关闭那一行，标题交给它，内容里不再画第二个
        PikoSheet(onDismissRequest = instantSession::collapse, sideSheetTitle = "添加链接") {
            val sideSheet = isSideSheet
            Column {
                InstantSheetContent(
                    state = instantState,
                    inSideSheet = sideSheet,
                    // 先收起面板：Android 上它是独立窗口，会盖在应用内的播放器上面
                    onPreview = { fileId, fileName ->
                        instantSession.collapse()
                        navigateToPlayer(FileStat(id = fileId, name = fileName), emptyList())
                    },
                )
            }
        }
    }

    if (showNewFolderDialog) {
        NameInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            value = newFolderName,
            onValueChange = { newFolderName = it },
            confirmLabel = "创建",
            confirmEnabled = newFolderName.isNotBlank(),
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                state.createFolder(name)
            },
        )
    }

    renameTargetFile?.let { target ->
        NameInputDialog(
            title = "重命名",
            label = "新名称",
            value = renameNewName,
            onValueChange = { renameNewName = it },
            confirmLabel = "确定",
            confirmEnabled = renameNewName.isNotBlank() && renameNewName != target.name,
            // 照资源管理器只选主名：改名多半不动扩展名。文件夹没有扩展名，整个选中
            initialSelection = TextRange(0, if (target.isFolder) target.name.length else target.name.lastIndexOf('.').takeIf { it > 0 } ?: target.name.length),
            onDismiss = { renameTargetFile = null },
            onConfirm = { name ->
                val id = target.id
                renameTargetFile = null
                state.rename(id, name)
            },
        )
    }

    if (moveTargetIds.isNotEmpty()) {
        val pendingIds = moveTargetIds
        MoveTargetDialog(
            itemCount = pendingIds.size,
            movingIds = pendingIds,
            sourceParentId = activeFolderId,
            onDismiss = { moveTargetIds = emptySet() },
            onConfirm = { targetId, targetName ->
                moveTargetIds = emptySet()
                state.move(pendingIds.toList(), targetId, targetName)
            },
        )
    }

    if (copyTargetIds.isNotEmpty()) {
        val pendingIds = copyTargetIds
        // 复制到原目录是允许的（服务端给副本加「(1)」），只挡住复制进自身
        FolderPickerDialog(
            title = "复制 ${pendingIds.size} 项",
            confirmLabel = "复制到这里",
            onDismiss = { copyTargetIds = emptySet() },
            onConfirm = { targetId, targetName ->
                copyTargetIds = emptySet()
                state.copy(pendingIds.toList(), targetId, targetName)
            },
            blockedFolderIds = pendingIds,
            blockedFolderHint = "待复制项",
            confirmBlockedReason = { current -> "不能复制到自身".takeIf { current.id in pendingIds } },
        )
    }

    val segmentTarget = segmentSession.file
    if (segmentTarget != null) {
        SegmentDownloadSheet(
            session = segmentSession,
            onConfirmDownload = { startByte, lengthBytes, timeLabel, startMs, endMs, streamUrl, quality ->
                downloadManager.enqueueSegment(
                    file = segmentTarget,
                    startMs = startMs,
                    endMs = endMs,
                    timeRangeLabel = timeLabel,
                    streamUrl = streamUrl,
                    startByte = startByte,
                    lengthBytes = lengthBytes,
                    quality = quality,
                )
                segmentSession.end()
                openTransfers()
            },
        )
    }

    previewImage?.let { image ->
        // 查看器里左右翻页的范围是当前列表里能看的图，搜索与折叠筛过之后的那一份，
        // 和用户眼前看到的顺序一致。
        val previewables = remember(displayedFiles) {
            displayedFiles.filter { it.isPreviewableImage() && it.thumbnailLink.isNotBlank() }
        }
        val startIndex = previewables.indexOfFirst { it.id == image.id }
        if (startIndex < 0) {
            // 图片刚被移走或筛没了，没有可停的页
            previewImage = null
        } else {
            ImageViewer(
                images = previewables,
                initialIndex = startIndex,
                onDismiss = { previewImage = null },
            )
        }
    }
}

/** 网盘页首屏的三态。失败文案随态带进 Crossfade 的 target：淡出未完时旧分支仍在组合，那一刻 loadError 可能已清空。 */
private sealed interface DrivePhase {
    data object Loading : DrivePhase

    data class Failed(val message: String) : DrivePhase

    data object Content : DrivePhase
}

// 列表页眉那一行（排序与视图切换）的高度，骨架据此空出位置。与 DriveListHeader 的最小行高一致
private val DriveListHeaderHeight = 48.dp

// 列表末尾为 Extended FAB 留出的空间：56dp 高度加 16dp 外边距，再留一段让最后一项
// 能完整滚出 FAB 的遮挡。
private val FabClearance = 88.dp

/**
 * 页眉左侧的说明，只在搜索时出现：全盘搜索是逐层遍历，需要告诉用户仍在进行、已找到多少。
 * 平时不显示条目计数，文件夹与文件的区分由各行的图标承担。
 */
private fun searchSummary(state: DriveScreenState, files: List<FileStat>): String? = when {
    state.isGlobalSearchActive ->
        if (state.isGlobalSearching) "全盘搜索中，已找到 ${files.size} 项" else "全盘找到 ${files.size} 项"
    state.searchQuery.isNotBlank() -> "当前文件夹找到 ${files.size} 项"
    else -> null
}

private fun foldBannerOrNull(state: DriveScreenState): (@Composable () -> Unit)? {
    val hiddenCount = state.potentialHiddenCount
    // 搜索与类型筛选时列表是平铺的，不做折叠，横幅无从谈起
    val applicable = state.isHeuristicFilterEnabled && hiddenCount > 0 &&
        state.searchQuery.isBlank() && !state.isGlobalSearchActive && state.typeFilter == null
    if (!applicable) return null
    val isFolded = !state.showAllFilesTemporarily
    return {
        FoldBanner(
            isFolded = isFolded,
            hiddenCount = hiddenCount,
            onToggle = { state.setShowAllFiles(isFolded) },
        )
    }
}

/** 数据可能过期的常驻提示。只在加载失败后出现，不随列表滚走。 */
@Composable
private fun StaleDataBanner(reason: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "内容可能不是最新的：$reason",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 空目录与无搜索结果。外层包一层可滚动容器：下拉刷新依赖子项的嵌套滚动，
 * 不可滚动的空态下拉不会触发刷新，空目录就没法手动刷新。
 */
@Composable
private fun DriveEmptyState(state: DriveScreenState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.searchQuery.isNotBlank()) {
                    PikoEmptyState(
                        title = if (state.isGlobalSearching) "正在遍历网盘" else "无匹配结果",
                        description = when {
                            state.isGlobalSearching -> "全盘搜索逐层遍历目录，结果会陆续出现"
                            state.isGlobalSearchActive -> "全盘没有名称包含「${state.searchQuery}」的文件"
                            else -> "当前文件夹没有名称包含「${state.searchQuery}」的文件，可点顶栏右侧「全盘」搜索整个网盘"
                        },
                        icon = Icons.Outlined.SearchOff,
                    )
                } else if (state.libraryView != null) {
                    val empty = state.libraryView!!.empty
                    PikoEmptyState(title = empty.title, description = empty.description, icon = empty.icon)
                } else if (state.archiveView != null) {
                    // 要密码时密码框盖在上面，取消就退出了，这里只管读不出来与真的空着两种
                    val error = state.loadError
                    PikoEmptyState(
                        title = if (error != null) "无法读取压缩包" else "此文件夹为空",
                        description = error ?: "压缩包里的这一层没有内容",
                        icon = if (error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.FolderOpen,
                    )
                } else {
                    PikoEmptyState(
                        title = "此文件夹为空",
                        description = "可用右下角的按钮添加链接、上传文件或新建文件夹",
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
            }
        }
    }
}

/** 新建文件夹与重命名共用。[onConfirm] 收到的是最终名称：已去掉首尾空格，或用户同意改用的名称。 */
@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialSelection: TextRange? = null,
) {
    val autoClean by LocalPikoServices.current.preferences.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    var pendingName by remember { mutableStateOf<String?>(null) }
    val unfixable = isUnfixableDriveName(value)
    val canConfirm = confirmEnabled && !unfixable
    fun confirm() {
        pendingName = submitDriveName(value, autoClean, onConfirm)
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FileNameField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                modifier = Modifier.fillMaxWidth(),
                isError = unfixable,
                supportingText = driveNameHint(value, autoClean),
                onDone = { if (canConfirm) confirm() },
                autoFocus = true,
                initialSelection = initialSelection,
            )
        },
        confirmButton = {
            PikoDialogConfirm(confirmLabel, onClick = ::confirm, enabled = canConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    pendingName?.let { name ->
        UnsupportedNameDialog(
            name = name,
            onUseCleaned = { cleaned ->
                pendingName = null
                onConfirm(cleaned)
            },
            onDismiss = { pendingName = null },
        )
    }
}


private val DriveViewMode.paletteLabel: String
    get() = when (this) {
        DriveViewMode.LIST -> "列表视图"
        DriveViewMode.POSTER -> "海报墙"
        DriveViewMode.GALLERY -> "图库"
    }
