package dev.piko.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb

// 单级目录名的宽度上限。不设上限时一个超长的目录名会独占整行，
// 其余层级全被挤到滚动区外，面包屑失去「一眼看到路径」的作用。
private val CrumbMaxWidth = 180.dp
private val CrumbPadding = PaddingValues(horizontal = 8.dp)

/**
 * 路径面包屑。整行 48dp，正好是触控目标下限，不再额外加上下内边距和底色带：
 * 它与顶栏、列表同为 surface 底，层级靠位置而不是色块表达。
 */
@Composable
fun BreadcrumbBar(
    breadcrumbs: List<PathBreadcrumb>,
    onBreadcrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(breadcrumbs) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onBreadcrumbClick(0) },
            shape = MaterialTheme.shapes.small,
            contentPadding = CrumbPadding,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Home,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "网盘", style = MaterialTheme.typography.labelLarge)
        }

        breadcrumbs.forEachIndexed { index, crumb ->
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            val isLast = index == breadcrumbs.lastIndex
            TextButton(
                onClick = { onBreadcrumbClick(index + 1) },
                enabled = !isLast,
                shape = MaterialTheme.shapes.small,
                contentPadding = CrumbPadding,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = crumb.name,
                    style = MaterialTheme.typography.labelLarge,
                    // 当前层不可点，禁用态默认的 38% 透明度会让它看起来比上级更弱，
                    // 这里显式用 onSurface 表明「你在这里」
                    color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = CrumbMaxWidth),
                )
            }
        }
    }
}
