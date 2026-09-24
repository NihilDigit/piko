package dev.piko.ui.screens.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateStatus
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 「我的」页：账号与配额、浏览偏好、下载设置、关于。
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
    onLogout: () -> Unit,
    onNavigateToTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val clientManager = services.clientManager
    val driveRepo = services.driveRepository
    val accountRepo = services.accountRepository
    val session by sessionManager.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isConcurrentAccelerationEnabled by sessionManager.concurrentAccelerationFlow.collectAsStateWithLifecycle(initialValue = true)
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()

    // 网络回来之前先用上次存下的数字渲染，否则卡片整块缺席、刷新完再跳出来
    val liveQuota by driveRepo.quotaFlow.collectAsStateWithLifecycle()
    val cachedQuota by sessionManager.quotaSnapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val quota = liveQuota?.let { QuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes) } ?: cachedQuota
    var showLogoutDialog by remember { mutableStateOf(false) }
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
    // 进页面静默查一次：失败不打扰，手动点「检查更新」时才报错
    LaunchedEffect(updater) {
        if (updater?.status == UpdateStatus.Idle) updater.check(silent = true)
    }

    LaunchedEffect(Unit) {
        driveRepo.getQuota()
        // 昵称与头像不随登录态返回，每次进入本页取一次。
        // 失败不提示：头像本就有首字母兜底，为它弹一条错误反而扰人。
        accountRepo.refreshProfile()
    }

    // 不设顶栏：标题与底部导航选中的「我的」重复，本页也没有页面级动作。
    // Scaffold 的内容边距已含状态栏，账号卡片直接从状态栏下方开始
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            AccountCard(
                username = session?.username,
                accountLabel = session?.email?.ifBlank { null }
                    ?: session?.userId?.ifBlank { null }?.let { "UID $it" },
                avatarUrl = session?.avatarUrl,
                quota = quota,
            )

            SettingsGroup(title = "文件") {
                SettingsNavigationRow(
                    index = 0, count = 1,
                    icon = Icons.Outlined.Delete,
                    title = "回收站",
                    supporting = "恢复或彻底删除已移入回收站的文件",
                    onClick = onNavigateToTrash,
                )
            }

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

            SettingsGroup(title = "浏览") {
                SettingsSwitchRow(
                    index = 0, count = 3,
                    icon = Icons.Outlined.AutoFixHigh,
                    title = "启发式折叠",
                    supporting = "折叠疑似广告的小文件",
                    checked = isHeuristicFilterEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                )
                SettingsSwitchRow(
                    index = 1, count = 3,
                    icon = Icons.Outlined.Subtitles,
                    title = "字幕随视频",
                    supporting = "添加链接时同名字幕与视频合为一项",
                    checked = isBundleSubtitlesEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                )
                SettingsSwitchRow(
                    index = 2, count = 3,
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
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = aboutCount),
                    colors = settingsRowColors(),
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

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("退出登录")
            }
        }
    }

    if (updater != null) {
        updateInSheet?.let { update ->
            UpdateSheet(updater = updater, update = update, onDismiss = { updateInSheet = null })
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("退出登录") },
            text = { Text("退出后将清除本机保存的 PikPak 登录凭据。") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        scope.launch {
                            clientManager.logout()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            },
        )
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

/**
 * 账号与配额合为一张卡：原先两张卡各带 18dp 内边距，配额又拆成三栏数字，
 * 合起来约 220dp；这里一行进度条加一行文字说清楚同样的信息。
 */
@Composable
private fun AccountCard(
    username: String?,
    /** 邮箱，没有邮箱时退回 UID。 */
    accountLabel: String?,
    avatarUrl: String?,
    quota: QuotaSnapshot?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = username?.take(1)?.uppercase(Locale.getDefault()) ?: "P",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username?.ifEmpty { null } ?: "PikPak 用户",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (accountLabel != null) {
                        Text(
                            text = accountLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            quota?.let { q ->
                val fraction = if (q.limitBytes > 0) {
                    (q.usageBytes.toFloat() / q.limitBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val remaining = (q.limitBytes - q.usageBytes).coerceAtLeast(0L)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "已用 ${q.usageBytes.toReadableSize()} / ${q.limitBytes.toReadableSize()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "剩余 ${remaining.toReadableSize()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun settingsRowColors() =
    ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

/** 深色模式三选一，用 M3 Expressive 的连体按钮组，与播放器倍速选择的写法一致。 */
@Composable
private fun ThemeModeRow(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
        colors = settingsRowColors(),
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
                        colors = ToggleButtonDefaults.colors(),
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
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
        colors = settingsRowColors(),
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
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count).let { it.copy(selectedShape = it.shape) },
        colors = settingsRowColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        // 开关只作指示，整行的 checked 语义已由列表项提供
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = { Text(supporting) },
        content = { Text(title) },
    )
}

@Composable
private fun SettingsNavigationRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = settingsRowColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(trailingIcon, contentDescription = null) },
        supportingContent = {
            Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        content = { Text(title) },
    )
}
