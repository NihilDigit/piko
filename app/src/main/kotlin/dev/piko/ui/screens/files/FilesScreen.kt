package dev.piko.ui.screens.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.drive.DriveScreen

@Composable
fun FilesScreen(
    onNavigateToVideoPlayer: (fileId: String, fileName: String) -> Unit,
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    DriveScreen(
        onNavigateToFolder = onNavigateToFolder,
        onNavigateToVideoPlayer = onNavigateToVideoPlayer,
        modifier = modifier,
    )
}
