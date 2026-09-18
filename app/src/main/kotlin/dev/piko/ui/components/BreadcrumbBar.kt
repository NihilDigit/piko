package dev.piko.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb

@Composable
fun BreadcrumbBar(
    breadcrumbs: List<PathBreadcrumb>,
    onBreadcrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(breadcrumbs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onBreadcrumbClick(0) },
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = "Root",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = " 网盘",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            breadcrumbs.forEachIndexed { index, crumb ->
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(16.dp),
                )
                val isLast = index == breadcrumbs.lastIndex
                TextButton(
                    onClick = { onBreadcrumbClick(index + 1) },
                    enabled = !isLast,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = crumb.name,
                        style = if (isLast) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
