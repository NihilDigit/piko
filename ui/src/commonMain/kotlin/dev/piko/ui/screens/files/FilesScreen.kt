package dev.piko.ui.screens.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.drive.DriveScreen
import io.github.nihildigit.pikpak.FileStat

@Composable
fun FilesScreen(
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit = { _, _ -> },
    /** 见 DriveScreen 的同名参数。 */
    scrollToTopRequests: Int = 0,
    modifier: Modifier = Modifier,
) {
    DriveScreen(
        onNavigateToFolder = onNavigateToFolder,
        onNavigateToVideoPlayer = onNavigateToVideoPlayer,
        scrollToTopRequests = scrollToTopRequests,
        modifier = modifier,
    )
}
