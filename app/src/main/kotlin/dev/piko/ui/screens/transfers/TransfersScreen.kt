package dev.piko.ui.screens.transfers

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.screens.download.DownloadsScreen

@Composable
fun TransfersScreen(
    onNavigateToInstant: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DownloadsScreen(modifier = modifier)
}
