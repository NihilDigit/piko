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
    /** 见 DriveScreen 的同名参数。 */
    feedShown: Boolean = false,
    onFeedShownChange: ((Boolean) -> Unit)? = null,
    /** 见 DriveScreen 的同名参数。 */
    feedStashed: Boolean = false,
    /** 见 DriveScreen 的同名参数。 */
    feedTabId: Long? = null,
    /** 把网盘页的列表区（页眉下面）包进去的外框，宽窗口里由它在右侧放信息流侧栏。 */
    feedFrame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    /** 见 DriveScreen 的同名参数。 */
    addressDestinations: List<PaletteItem> = emptyList(),
    /** 见 DriveScreen 的同名参数。 */
    onLibraryLeft: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DriveScreen(
        addressDestinations = addressDestinations,
        onLibraryLeft = onLibraryLeft,
        contentFrame = feedFrame,
        onNavigateToFolder = onNavigateToFolder,
        onNavigateToVideoPlayer = onNavigateToVideoPlayer,
        scrollToTopRequests = scrollToTopRequests,
        onOpenTransfers = onOpenTransfers,
        feedShown = feedShown,
        onFeedShownChange = onFeedShownChange,
        feedStashed = feedStashed,
        feedTabId = feedTabId,
        modifier = modifier,
    )
}
