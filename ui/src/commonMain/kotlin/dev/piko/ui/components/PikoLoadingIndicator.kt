package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikoLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 使用 Material 3 Expressive 的原生 LoadingIndicator
        LoadingIndicator(
            modifier = Modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        PikoLoadingIndicator()
    }
}
