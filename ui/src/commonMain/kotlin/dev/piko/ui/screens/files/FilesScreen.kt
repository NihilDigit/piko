package dev.piko.ui.screens.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.drive.DriveScreen
import io.github.nihildigit.pikpak.FileStat

@Composable
fun FilesScreen(
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    DriveScreen(
        onNavigateToFolder = onNavigateToFolder,
        onNavigateToVideoPlayer = onNavigateToVideoPlayer,
        modifier = modifier,
    )
}
