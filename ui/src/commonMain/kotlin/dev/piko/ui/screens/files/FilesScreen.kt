package dev.piko.ui.screens.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.screens.drive.DriveScreen
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
    onBrowseInFeed: ((PathBreadcrumb) -> Unit)? = null,
    /** 把网盘页包进去的外框，宽窗口里由它在右侧放信息流侧栏。 */
    feedFrame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    modifier: Modifier = Modifier,
) {
    feedFrame {
        DriveScreen(
            onNavigateToFolder = onNavigateToFolder,
            onNavigateToVideoPlayer = onNavigateToVideoPlayer,
            scrollToTopRequests = scrollToTopRequests,
            onOpenTransfers = onOpenTransfers,
            feedShown = feedShown,
            onFeedShownChange = onFeedShownChange,
            onBrowseInFeed = onBrowseInFeed,
            modifier = modifier,
        )
    }
}
