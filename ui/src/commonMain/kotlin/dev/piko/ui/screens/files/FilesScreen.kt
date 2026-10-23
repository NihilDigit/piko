package dev.piko.ui.screens.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.drive.DriveScreen
import dev.piko.ui.components.PaletteItem
import io.github.nihildigit.pikpak.FileStat

@Composable
fun FilesScreen(
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit = { _, _ -> },
    /** 见 DriveScreen 的同名参数。 */
    scrollToTopRequests: Int = 0,
    onOpenTransfers: () -> Unit = {},
    onOpenDuplicateReport: (() -> Unit)? = null,
    /** 见 DriveScreen 的同名参数。 */
    feedShown: Boolean = false,
    onFeedShownChange: ((Boolean) -> Unit)? = null,
    onOpenSelectedFeed: ((List<FileStat>) -> Unit)? = null,
    /** 见 DriveScreen 的同名参数。 */
    feedStashed: Boolean = false,
    onResumeFeed: (() -> Unit)? = null,
    /** 见 DriveScreen 的同名参数。 */
    feedBlockedReason: String? = null,
    /** 见 DriveScreen 的同名参数。 */
    feedTabId: Long? = null,
    /** 右栏放得下时添加链接停在右栏，否则退化到底部 Sheet。 */
    addLinkInSidePanel: Boolean = false,
    /** 把网盘页的列表区（页眉下面）包进去的外框，宽窗口里由它在右侧放信息流或添加链接。 */
    feedFrame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    /** 见 DriveScreen 的同名参数。 */
    addressDestinations: List<PaletteItem> = emptyList(),
    /** 见 DriveScreen 的同名参数。 */
    onLibraryLeft: (() -> Unit)? = null,
    canLeaveOrigin: Boolean = false,
    browserSession: dev.piko.shared.state.DriveBrowserSession? = null,
    onRevealInDrive: ((FileStat) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    DriveScreen(
        addressDestinations = addressDestinations,
        onLibraryLeft = onLibraryLeft,
        canLeaveOrigin = canLeaveOrigin,
        browserSession = browserSession,
        onRevealInDrive = onRevealInDrive,
        contentFrame = feedFrame,
        onNavigateToFolder = onNavigateToFolder,
        onNavigateToVideoPlayer = onNavigateToVideoPlayer,
        scrollToTopRequests = scrollToTopRequests,
        onOpenTransfers = onOpenTransfers,
        onOpenDuplicateReport = onOpenDuplicateReport,
        feedShown = feedShown,
        onFeedShownChange = onFeedShownChange,
        onOpenSelectedFeed = onOpenSelectedFeed,
        feedStashed = feedStashed,
        onResumeFeed = onResumeFeed,
        feedBlockedReason = feedBlockedReason,
        feedTabId = feedTabId,
        addLinkInSidePanel = addLinkInSidePanel,
        modifier = modifier,
    )
}
