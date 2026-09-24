package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.SelectAll
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

/** 多选态顶栏。三个动作都作用于整批选中项，没有可以下放到别处的。 */
@Composable
internal fun DriveSelectionTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
) {
    PikoTopBar(
        scrollBehavior = scrollBehavior,
        title = "已选择 $selectedCount 项",
        navigationIcon = {
            TooltipIconButton(Icons.Outlined.Close, "退出多选", onExit, shortcut = "Esc")
        },
        actions = {
            TooltipIconButton(Icons.Outlined.SelectAll, "全选", onSelectAll, shortcut = "Ctrl+A")
            TooltipIconButton(Icons.Outlined.DriveFileMove, "移动所选", onMove, enabled = selectedCount > 0)
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
