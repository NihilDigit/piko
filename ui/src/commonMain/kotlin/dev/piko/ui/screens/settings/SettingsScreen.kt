package dev.piko.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateStatus
import kotlinx.coroutines.launch

/**
 * 设置页：外观、文件名解析、浏览、下载与关于，从「我的」进入。[onBackClick] 为 null 时不显示返回按钮
 * （expanded 窗口里它是「我的」旁边的默认详情栏，没有可返回的地方）。
 *
 * 设置项用 M3 Expressive 的分段列表（SegmentedListItem，组内 2dp 间隙、首尾圆角），
 * 取代原先每组一张 18dp 内边距的卡片加手工拼的行。行高回到列表规范的 56/72dp，
 * 开关行整行可点并由组件报告开关状态，读屏不必再单独聚焦到 Switch 上。
 *
 * Documentation references:
 * - m3-material-mirror/pages/components/lists.md（Gaps & dividers：容器化列表用间隙分组）
 * - m3-material-mirror/pages/components/switch.md
 */
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isConcurrentAccelerationEnabled by sessionManager.concurrentAccelerationFlow.collectAsStateWithLifecycle(initialValue = true)
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()

    var showDownloadDirDialog by remember { mutableStateOf(false) }
    val downloadLocation = platform.downloadLocation
    val resolvedDownloadPath = remember(downloadDirPath) { downloadLocation.displayName(downloadDirPath) }
    val pickDownloadDir = downloadLocation.rememberLauncher { picked ->
        scope.launch { sessionManager.setDownloadDirPath(picked) }
        showDownloadDirDialog = false
    }

    val updater = platform.updater
    var updateInSheet by remember { mutableStateOf<AvailableUpdate?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(updater) {
        updater?.messages?.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = "设置",
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 24.dp)
                .readableWidth(),
        ) {
            SettingsGroup(title = "外观") {
                val appearance = LocalAppearance.current
                ThemeModeRow(
                    mode = appearance.mode,
                    onModeChange = { scope.launch { sessionManager.setThemeMode(it.name) } },
                )
                ThemeColorRow(
                    appearance = appearance,
                    onSeedChange = { scope.launch { sessionManager.setThemeSeed(it?.name) } },
                )
            }

            SettingsGroup(title = "文件名") {
                SettingsSwitchRow(
                    index = 0, count = 3,
                    icon = Icons.Outlined.TextFields,
                    title = "文件名解析",
                    supporting = "按作品、分区与集数整理，标出发布组与清晰度",
                    checked = isNameParsingEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                )
                SettingsSwitchRow(
                    index = 1, count = 3,
                    icon = Icons.Outlined.AutoFixHigh,
                    title = "启发式折叠",
                    supporting = "折叠广告、样片、说明文件等次要项",
                    checked = isHeuristicFilterEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                    enabled = isNameParsingEnabled,
                )
                SettingsSwitchRow(
                    index = 2, count = 3,
                    icon = Icons.Outlined.Subtitles,
                    title = "保存配套字幕",
                    supporting = "添加链接时一并保存视频的外挂字幕",
                    checked = isBundleSubtitlesEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                    enabled = isNameParsingEnabled,
                )
            }

            SettingsGroup(title = "浏览") {
                SettingsSwitchRow(
                    index = 0, count = 1,
                    icon = Icons.Outlined.VisibilityOff,
                    title = "缩略图防窥",
                    supporting = "模糊显示缩略图",
                    checked = isSpoilerBlurEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                )
            }

            SettingsGroup(title = "下载") {
                SettingsSwitchRow(
                    index = 0, count = 2,
                    icon = Icons.Outlined.Speed,
                    title = "并发加速",
                    supporting = "多连接下载，提升速度",
                    checked = isConcurrentAccelerationEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setConcurrentAccelerationEnabled(it) } },
                )
                SettingsNavigationRow(
                    index = 1, count = 2,
                    icon = Icons.Outlined.FolderOpen,
                    title = "下载位置",
                    supporting = resolvedDownloadPath,
                    onClick = { showDownloadDirDialog = true },
                )
            }

            SettingsGroup(title = "关于") {
                // 没有应用内更新的平台少一行，分段圆角按实际行数算
                val aboutCount = if (updater != null) 3 else 2
                StaticSegmentedRow(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = aboutCount),
                    leadingContent = {
                        Icon(
                            imageVector = PikoBrandIcons.Glyph,
                            contentDescription = null,
                        )
                    },
                    supportingContent = { Text("版本 ${platform.appVersion}") },
                    content = { Text("Piko") },
                )
                if (updater != null) {
                    UpdateRow(
                        status = updater.status,
                        onClick = {
                            when (val current = updater.status) {
                                is UpdateStatus.Available -> updateInSheet = current.update
                                is UpdateStatus.Downloading -> updateInSheet = current.update
                                is UpdateStatus.Installing -> updateInSheet = current.update
                                is UpdateStatus.Failed -> current.update?.let { updateInSheet = it }
                                    ?: scope.launch { updater.check() }
                                else -> scope.launch { updater.check() }
                            }
                        },
                    )
                }
                SettingsNavigationRow(
                    index = aboutCount - 1, count = aboutCount,
                    icon = Icons.Outlined.Code,
                    title = "开源仓库",
                    supporting = "github.com/NihilDigit/piko",
                    trailingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = { platform.openUrl("https://github.com/NihilDigit/piko") },
                )
            }

        }
    }

    if (updater != null) {
        updateInSheet?.let { update ->
            UpdateSheet(updater = updater, update = update, onDismiss = { updateInSheet = null })
        }
    }

    if (showDownloadDirDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDirDialog = false },
            title = { Text("下载位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = downloadLocation.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = pickDownloadDir,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择文件夹")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDirDialog = false }) { Text("完成") }
            },
        )
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}

// 选中色与底色取同一值：开关行用 checked 重载拿开关语义，而它把 checked 当作选中，
// 开着的行会换成选中底色与选中形状，一组设置里亮一块暗一块。开关状态由 Switch 表达。
@Composable
internal fun settingsRowColors() =
    ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

/**
 * 不可点的分段行：外观与可点的 SegmentedListItem 一致，只是没有点击态。
 *
 * 不直接用 SegmentedListItem 的无点击重载：桌面端的 material3 停在 1.12.0-alpha03（原因见
 * libs.versions.toml），那一版的 SegmentedListItem 只有带 onClick 与带 checked 的两种。
 */
@Composable
private fun StaticSegmentedRow(
    shapes: ListItemShapes,
    leadingContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(shape = shapes.shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            headlineContent = content,
            leadingContent = leadingContent,
            supportingContent = supportingContent,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** 深色模式三选一，用 M3 Expressive 的连体按钮组，与播放器倍速选择的写法一致。 */
@Composable
private fun ThemeModeRow(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    StaticSegmentedRow(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
        leadingContent = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
        supportingContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                ThemeMode.entries.forEachIndexed { index, option ->
                    ToggleButton(
                        checked = option == mode,
                        onCheckedChange = { onModeChange(option) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            ThemeMode.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(option.label, maxLines = 1)
                    }
                }
            }
        },
        content = { Text("深色模式") },
    )
}

/**
 * 主题色：系统取色加内置主题，一排圆形色块，选中的打勾，名字写在标题下方。
 *
 * 色块显示的是该主题在当前深浅下的 primary，即选中后按钮与强调色的实际颜色，而非种子色：
 * 种子色经 TonalSpot 调和后会变淡，按种子色画会与结果对不上。
 * 触控区按 48dp 下限给，七个在窄屏上放不下一行，改为横向滚动。
 */
@Composable
private fun ThemeColorRow(appearance: Appearance, onSeedChange: (SeedTheme?) -> Unit) {
    val dark = appearance.isDark()
    val platform = LocalPikoPlatform.current
    val selectedLabel = appearance.effectiveSeed?.label ?: "系统取色"
    StaticSegmentedRow(
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
        leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        supportingContent = {
            Column {
                Text(selectedLabel)
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (platform.supportsDynamicColor) {
                        val scheme = platform.dynamicColorScheme(dark)
                        ColorSwatch(
                            color = scheme.primary,
                            onColor = scheme.onPrimary,
                            label = "系统取色",
                            selected = appearance.seed == null,
                            idleIcon = Icons.Outlined.Wallpaper,
                            onClick = { onSeedChange(null) },
                        )
                    }
                    SeedTheme.entries.forEach { theme ->
                        val scheme = if (dark) theme.dark else theme.light
                        ColorSwatch(
                            color = scheme.primary,
                            onColor = scheme.onPrimary,
                            label = theme.label,
                            selected = appearance.effectiveSeed == theme,
                            onClick = { onSeedChange(theme) },
                        )
                    }
                }
            }
        },
        content = { Text("主题色") },
    )
}

@Composable
private fun ColorSwatch(
    color: Color,
    onColor: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    idleIcon: ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (selected) Icons.Outlined.Check else idleIcon
            if (icon != null) Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(20.dp))
        }
    }
}

/** 检查更新。发现新版本时尾部亮一个圆点，点开是更新面板。 */
@Composable
private fun UpdateRow(status: UpdateStatus, onClick: () -> Unit) {
    val supporting = when (status) {
        UpdateStatus.Idle -> "点按检查"
        UpdateStatus.Checking -> "正在检查"
        UpdateStatus.UpToDate -> "已是最新版本"
        is UpdateStatus.Available -> "发现新版本 ${status.update.version}"
        is UpdateStatus.Downloading -> "正在下载 ${(status.progress * 100).toInt()}%"
        is UpdateStatus.Installing -> "等待安装确认"
        is UpdateStatus.Failed -> status.message
    }
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
        colors = settingsRowColors(),
        leadingContent = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
        trailingContent = {
            when (status) {
                UpdateStatus.Checking -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                is UpdateStatus.Available -> Badge()
                else -> Unit
            }
        },
        supportingContent = { Text(supporting) },
        content = { Text("检查更新") },
    )
}

@Composable
private fun SettingsSwitchRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count).let { it.copy(selectedShape = it.shape) },
        colors = settingsRowColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        // 开关只作指示，整行的 checked 语义已由列表项提供
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = { Text(supporting) },
        content = { Text(title) },
    )
}

@Composable
internal fun SettingsNavigationRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
    /** 两栏布局里这一行对应的页正显示在右侧。 */
    selected: Boolean = false,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = if (selected) {
            ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            settingsRowColors()
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(trailingIcon, contentDescription = null) },
        supportingContent = {
            Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        content = { Text(title) },
    )
}
