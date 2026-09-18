package dev.piko.ui.screens.transfers

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.download.DownloadsScreen

@Composable
fun TransfersScreen(
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onNavigateToInstant: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DownloadsScreen(
        onPlayVideo = { task ->
            onNavigateToVideoPlayer(task.fileId, task.fileName, task.destinationPath)
        },
        modifier = modifier,
    )
}
