package dev.piko.ui.screens.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.TooltipIconButton

/**
 * 多选态顶栏。动作都作用于整批选中项，没有可以下放到别处的。
 * [onExtract] 为 null 表示所选里没有压缩包，不显示解压。
 */
@Composable
internal fun DriveSelectionTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onTrash: () -> Unit,
    onExtract: (() -> Unit)?,
) {
    PikoTopBar(
        scrollBehavior = scrollBehavior,
        title = "已选择 $selectedCount 项",
        navigationIcon = {
            TooltipIconButton(Icons.Outlined.Close, "退出多选", onExit, shortcut = "Esc")
        },
        actions = {
            TooltipIconButton(Icons.Outlined.SelectAll, "全选", onSelectAll, shortcut = "Ctrl+A")
            if (onExtract != null) TooltipIconButton(Icons.Outlined.Unarchive, "解压所选压缩包", onExtract)
            TooltipIconButton(Icons.Outlined.DriveFileMove, "移动所选", onMove, enabled = selectedCount > 0)
            TooltipIconButton(Icons.Outlined.ContentCopy, "复制所选", onCopy, enabled = selectedCount > 0)
            TooltipIconButton(
                icon = Icons.Outlined.Delete,
                label = "将所选移入回收站",
                onClick = onTrash,
                shortcut = "Delete",
                enabled = selectedCount > 0,
                tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * 搜索态顶栏：输入框取代标题，占用的仍是顶栏那一行。
 *
 * 搜索入口原先是列表上方一条常驻的 56dp 输入框，加上下边距约 68dp，任何时候都挤占
 * 列表。M3 对「搜索是次要动作」的页面给出的入口是顶栏里的搜索图标按钮，点开后才出现
 * 输入框；这里就是点开后的样子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    isGlobalSearching: Boolean,
    isGlobalSearchActive: Boolean,
    onStartGlobalSearch: () -> Unit,
    onCancelGlobalSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "关闭搜索")
            }
        },
        title = {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // 当前目录是边输边滤，回车只需收起键盘
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索当前文件夹",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        },
        actions = {
            when {
                isGlobalSearching -> {
                    PikoLoadingIndicator(size = 24.dp)
                    // 只取消遍历，已找到的结果留在列表里
                    TextButton(onClick = onCancelGlobalSearch, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("停止")
                    }
                }
                query.isNotBlank() && !isGlobalSearchActive -> {
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            onStartGlobalSearch()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("全盘")
                    }
                }
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "清除搜索词")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

/**
 * 浏览态顶栏：目录名，以及结构化列表下当前滚动到的分区。点副标题弹出分区菜单，选哪个跳到哪个。
 *
 * 分区标题随网格滚走（海报墙没有吸顶标题），所以「身在哪一区」由这里常驻给出。
 * PikoTopBar 只有单行标题，这里直接用 TopAppBar，配色与它一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveBrowseTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    currentSection: String?,
    sections: List<String>,
    onSectionSelected: (Int) -> Unit,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                if (currentSection != null && sections.isNotEmpty()) {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClickLabel = "跳转到分区") { showMenu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentSection,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            sections.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showMenu = false
                                        onSectionSelected(index)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scrollBehavior = scrollBehavior,
    )
}
