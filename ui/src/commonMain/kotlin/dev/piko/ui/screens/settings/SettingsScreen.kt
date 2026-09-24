package dev.piko.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
    // 选完不关对话框，让用户在卡片上看到新位置再点「完成」
    val pickDownloadDir = downloadLocation.rememberLauncher { picked ->
        scope.launch { sessionManager.setDownloadDirPath(picked) }
    }

    val updater = platform.updater
    var updateInSheet by remember { mutableStateOf<AvailableUpdate?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(updater) {
        updater?.messages?.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    val scrollState = rememberScrollState()
    // 各分组在滚动内容里的纵向位置，供左侧目录跳转与高亮
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    val currentSection by remember {
        derivedStateOf {
            // 滚到底时最后几组可能永远到不了顶端，此时高亮最后一组
            if (scrollState.value >= scrollState.maxValue && scrollState.maxValue > 0) {
                SettingsSection.entries.last()
            } else {
                sectionOffsets.entries.filter { it.value <= scrollState.value + SECTION_ACTIVATION_SLOP }
                    .maxByOrNull { it.value }?.key ?: SettingsSection.entries.first()
            }
        }
    }
    fun Modifier.trackSection(section: SettingsSection) =
        onGloballyPositioned { sectionOffsets[section] = it.positionInParent().y.toInt() }

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
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val showIndex = maxWidth >= SettingsIndexMinWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (showIndex) {
                    SettingsIndex(
                        current = currentSection,
                        onSelect = { section -> scope.launch { scrollState.animateScrollTo(sectionOffsets[section] ?: 0) } },
                        modifier = Modifier.width(SettingsIndexWidth).padding(start = 12.dp, top = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .readableWidth(),
                ) {
                    SettingsGroup(SettingsSection.Appearance.title, Modifier.trackSection(SettingsSection.Appearance)) {
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

                    SettingsGroup(SettingsSection.Drive.title, Modifier.trackSection(SettingsSection.Drive)) {
                        // 启发式折叠只在解析开着时有意义，关掉解析就收起这一项，不留一行灰掉的开关
                        val driveCount = if (isNameParsingEnabled) 3 else 2
                        SettingsSwitchRow(
                            index = 0, count = driveCount,
                            icon = Icons.Outlined.TextFields,
                            title = "文件名解析",
                            supporting = "按作品、分区与集数整理，标出发布组与清晰度",
                            checked = isNameParsingEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                        )
                        if (isNameParsingEnabled) {
                            SettingsSwitchRow(
                                index = 1, count = driveCount,
                                icon = Icons.Outlined.AutoFixHigh,
                                title = "启发式折叠",
                                supporting = "收起广告、样片、说明文件等次要项",
                                checked = isHeuristicFilterEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                                nested = true,
                            )
                        }
                        SettingsSwitchRow(
                            index = driveCount - 1, count = driveCount,
                            icon = Icons.Outlined.VisibilityOff,
                            title = "缩略图防窥",
                            supporting = "模糊显示缩略图与封面",
                            checked = isSpoilerBlurEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                        )
                    }

                    SettingsGroup(SettingsSection.Links.title, Modifier.trackSection(SettingsSection.Links)) {
                        // 字幕靠解析配到视频上。它在另一组，关掉解析时写明原因，而不是只把开关灰掉
                        SettingsSwitchRow(
                            index = 0, count = 1,
                            icon = Icons.Outlined.Subtitles,
                            title = "保存配套字幕",
                            supporting = if (isNameParsingEnabled) "保存视频时一并保存外挂字幕" else "需先开启文件名解析",
                            checked = isBundleSubtitlesEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                            enabled = isNameParsingEnabled,
                        )
                    }

                    SettingsGroup(SettingsSection.Download.title, Modifier.trackSection(SettingsSection.Download)) {
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

                    SettingsGroup(SettingsSection.About.title, Modifier.trackSection(SettingsSection.About)) {
                        AboutCard(
                            version = platform.appVersion,
                            updateStatus = updater?.status,
                            onUpdateClick = {
                                if (updater == null) return@AboutCard
                                when (val current = updater.status) {
                                    is UpdateStatus.Available -> updateInSheet = current.update
                                    is UpdateStatus.Downloading -> updateInSheet = current.update
                                    is UpdateStatus.Installing -> updateInSheet = current.update
                                    is UpdateStatus.Failed -> current.update?.let { updateInSheet = it }
                                        ?: scope.launch { updater.check() }
                                    else -> scope.launch { updater.check() }
                                }
                            },
                            onOpenRepository = { platform.openUrl(REPOSITORY_URL) },
                        )
                    }
                }
            }
        }
    }

    if (updater != null) {
        updateInSheet?.let { update ->
            UpdateSheet(updater = updater, update = update, onDismiss = { updateInSheet = null })
        }
    }

    if (showDownloadDirDialog) {
        val isDefaultDownloadDir = downloadDirPath.isEmpty()
        AlertDialog(
            onDismissRequest = { showDownloadDirDialog = false },
            title = { Text("下载位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DownloadLocationCard(path = resolvedDownloadPath, isDefault = isDefaultDownloadDir)
                    Text(
                        text = downloadLocation.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = pickDownloadDir) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("更改文件夹")
                        }
                        if (!isDefaultDownloadDir) {
                            TextButton(onClick = { scope.launch { sessionManager.setDownloadDirPath("") } }) {
                                Text("恢复默认")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDirDialog = false }) { Text("完成") }
            },
        )
    }
}

/**
 * 下载位置对话框里的当前位置：文件夹名作标题，完整路径可选中复制。
 * Android 选了 SAF 目录时 [path] 只有文件夹名，此时不再重复一行。
 */
@Composable
private fun DownloadLocationCard(path: String, isDefault: Boolean) {
    val folderName = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isDefault) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = "默认",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (folderName != path) {
                    SelectionContainer {
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 设置的分组，也是宽窗口左侧目录的条目。 */
private enum class SettingsSection(val title: String) {
    Appearance("外观"),
    Drive("网盘"),
    Links("添加链接"),
    Download("下载"),
    About("关于"),
}

private const val REPOSITORY_URL = "https://github.com/NihilDigit/piko"

// 设置页自身宽到这个程度才放左侧目录。expanded 窗口里设置页只是「我的」旁边的详情栏，多数时候放不下
private val SettingsIndexMinWidth = 900.dp
private val SettingsIndexWidth = 200.dp

// 分组顶端滚到离页顶这么近就算进入该组，否则点目录跳过去后高亮会停在上一组
private const val SECTION_ACTIVATION_SLOP = 8

@Composable
private fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier) {
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
}

/** 宽窗口左侧的分组目录，点击滚到对应分组，滚动时高亮当前所在的组。 */
@Composable
private fun SettingsIndex(current: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSection.entries.forEach { section ->
            NavigationDrawerItem(
                label = { Text(section.title) },
                selected = section == current,
                onClick = { onSelect(section) },
            )
        }
    }
}

/**
 * 关于：一张小卡片收在页尾，版本、更新状态与两个动作放在一起。
 * 原先是三行列表，版本号占一整行，「检查更新」的状态又要另读一行副标题。
 */
@Composable
private fun AboutCard(
    version: String,
    /** 没有应用内更新的平台为 null，不显示更新按钮。 */
    updateStatus: UpdateStatus?,
    onUpdateClick: () -> Unit,
    onOpenRepository: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PikoBrandIcons.Glyph, contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Piko", style = MaterialTheme.typography.titleMedium)
                    Text("版本 $version", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    updateStatus?.let(::updateStatusText)?.let { (text, emphasis) ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (emphasis) {
                                UpdateEmphasis.Normal -> colors.onSurfaceVariant
                                UpdateEmphasis.Positive -> colors.primary
                                UpdateEmphasis.Error -> colors.error
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (updateStatus != null) {
                    FilledTonalButton(onClick = onUpdateClick, enabled = updateStatus != UpdateStatus.Checking) {
                        if (updateStatus == UpdateStatus.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (updateStatus) {
                                is UpdateStatus.Available -> "查看更新"
                                is UpdateStatus.Downloading, is UpdateStatus.Installing -> "查看进度"
                                else -> "检查更新"
                            },
                        )
                        if (updateStatus is UpdateStatus.Available) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge()
                        }
                    }
                }
                OutlinedButton(onClick = onOpenRepository) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开源仓库")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private enum class UpdateEmphasis { Normal, Positive, Error }

/** 更新状态写在版本号下面一行；还没检查过时不写。 */
private fun updateStatusText(status: UpdateStatus): Pair<String, UpdateEmphasis>? = when (status) {
    UpdateStatus.Idle -> null
    UpdateStatus.Checking -> "正在检查更新" to UpdateEmphasis.Normal
    UpdateStatus.UpToDate -> "已是最新版本" to UpdateEmphasis.Normal
    is UpdateStatus.Available -> "发现新版本 ${status.update.version}" to UpdateEmphasis.Positive
    is UpdateStatus.Downloading -> "正在下载 ${(status.progress * 100).toInt()}%" to UpdateEmphasis.Normal
    is UpdateStatus.Installing -> "等待安装确认" to UpdateEmphasis.Normal
    is UpdateStatus.Failed -> status.message to UpdateEmphasis.Error
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

// 子项图标缩进到父项标题的起点附近
private val NestedIndent = 24.dp

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
    /** 从属于上一行的开关：图标缩进一级，读得出它跟着上一行走。 */
    nested: Boolean = false,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count).let { it.copy(selectedShape = it.shape) },
        colors = settingsRowColors(),
        leadingContent = {
            Row {
                if (nested) Spacer(modifier = Modifier.width(NestedIndent))
                Icon(icon, contentDescription = null)
            }
        },
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
