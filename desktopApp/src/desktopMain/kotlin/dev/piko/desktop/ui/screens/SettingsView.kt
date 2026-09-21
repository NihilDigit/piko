package dev.piko.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.ui.components.formatBytes
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.icons.regular.Code
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.FolderOpen
import io.github.composefluent.icons.regular.Gauge
import io.github.composefluent.icons.regular.SignOut
import io.github.composefluent.surface.Card
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 「我的」页：与 Android SettingsScreen 七段式对齐——
 * 用户卡片 / 配额 / 文件管理（回收站）/ 浏览与呈现 / 传输与加速 / 关于 / 退出登录。
 * 以前的「Windows 现代集成特性」状态列表是开发自检用的，普通用户看不懂，已删；
 * 通知自检按钮一并去掉，下载完成 Toast 本来就会弹。
 */
@Composable
fun SettingsView(
    manager: PikoClientManager,
    settingsStore: DesktopSettingsStore,
    preferences: PikoUserPreferences,
    driveRepository: PikoDriveRepository,
    onThemeChanged: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val session by preferences.sessionFlow.collectAsState(null)
    val isSpoilerBlurEnabled by preferences.spoilerBlurFlow.collectAsState(true)
    val isHeuristicFilterEnabled by preferences.heuristicFilterFlow.collectAsState(true)
    val isConcurrentAccelerationEnabled by preferences.concurrentAccelerationFlow.collectAsState(true)
    val liveQuota by driveRepository.quotaFlow.collectAsState()
    val cachedQuota by preferences.quotaSnapshotFlow.collectAsState(null)

    var downloadDirInput by remember { mutableStateOf(settingsStore.downloadDirectory.absolutePath) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 配额每次进页拉一次；断网时用上次存的数字顶着，卡片不缺席。
        runCatching {
            driveRepository.getQuota().onSuccess {
                preferences.saveQuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes)
            }
        }
    }

    val quota = liveQuota?.let { it.quota.usageBytes to it.quota.limitBytes }
        ?: cachedQuota?.let { it.usageBytes to it.limitBytes }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("我的", style = FluentTheme.typography.title)

        // 1. 用户信息卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 桌面侧没有图片加载器，首字头像与 Android 的加载失败兜底长一样。
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(FluentTheme.colors.fillAccent.default),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = session?.username?.take(1)?.uppercase(Locale.getDefault()) ?: "P",
                        style = FluentTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session?.username?.ifEmpty { "PikPak 用户" } ?: "PikPak 用户",
                        style = FluentTheme.typography.subtitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "已连接云端",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.fillAccent.default,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (!session?.userId.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "UID: ${session?.userId?.take(8)}…",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }
        }

        // 2. 云盘容量配额卡片
        quota?.let { (usage, limit) ->
            val fraction = if (limit > 0) (usage.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
            val remaining = (limit - usage).coerceAtLeast(0L)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Regular.Cloud,
                                contentDescription = null,
                                tint = FluentTheme.colors.fillAccent.default,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "存储空间配额",
                                style = FluentTheme.typography.subtitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = String.format(Locale.getDefault(), "%.1f%%", fraction * 100f),
                                style = FluentTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = FluentTheme.colors.fillAccent.default,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        QuotaCell("已使用", formatBytes(usage), null)
                        QuotaCell(
                            "剩余可用",
                            formatBytes(remaining),
                            FluentTheme.colors.fillAccent.default,
                            Alignment.CenterHorizontally,
                        )
                        QuotaCell(
                            "总容量",
                            formatBytes(limit),
                            null,
                            Alignment.End,
                        )
                    }
                }
            }
        }

        // 3. 文件管理
        SectionTitle("文件管理")
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToTrash)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Regular.Delete,
                        contentDescription = null,
                        tint = FluentTheme.colors.fillAccent.default,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("回收站", style = FluentTheme.typography.body, fontWeight = FontWeight.Medium)
                        Text(
                            "恢复误删的文件，或彻底清除以释放空间",
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    }
                }
                Icon(
                    Icons.Regular.ChevronRight,
                    contentDescription = null,
                    tint = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 4. 浏览与呈现
        SectionTitle("浏览与呈现")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingSwitchRow(
                    icon = Icons.Regular.Gauge,
                    title = "启发式内容筛选",
                    description = "若存在主体大文件，自动折叠附属小文件，专注核心资源。",
                    checked = isHeuristicFilterEnabled,
                    onCheckedChange = { scope.launch { preferences.setHeuristicFilterEnabled(it) } },
                )
                Spacer(Modifier.height(12.dp))
                SettingSwitchRow(
                    icon = Icons.Regular.FolderOpen,
                    title = "缩略图防剧透模糊",
                    description = "默认高斯模糊媒体预览图，点击即可查看清晰画面。",
                    checked = isSpoilerBlurEnabled,
                    onCheckedChange = { scope.launch { preferences.setSpoilerBlurEnabled(it) } },
                )
                Spacer(Modifier.height(12.dp))
                Text("应用主题", style = FluentTheme.typography.body, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentMode = settingsStore.themeMode
                    ThemeOptionButton("跟随系统", currentMode == "system") {
                        settingsStore.themeMode = "system"
                        onThemeChanged()
                    }
                    ThemeOptionButton("浅色", currentMode == "light") {
                        settingsStore.themeMode = "light"
                        onThemeChanged()
                    }
                    ThemeOptionButton("深色", currentMode == "dark") {
                        settingsStore.themeMode = "dark"
                        onThemeChanged()
                    }
                }
            }
        }

        // 5. 传输与加速
        SectionTitle("传输与加速")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingSwitchRow(
                    icon = Icons.Regular.Gauge,
                    title = "多连接并发加速",
                    description = if (isConcurrentAccelerationEnabled) {
                        "已开启多连接并发分块加速，充分利用网络吞吐量"
                    } else {
                        "已关闭并发加速，当前使用单连接标准下载"
                    },
                    checked = isConcurrentAccelerationEnabled,
                    onCheckedChange = { scope.launch { preferences.setConcurrentAccelerationEnabled(it) } },
                )
                Spacer(Modifier.height(12.dp))
                Text("下载保存路径", style = FluentTheme.typography.body, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = downloadDirInput,
                        onValueChange = { downloadDirInput = it },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val file = File(downloadDirInput.trim())
                            if (!file.exists()) file.mkdirs()
                            scope.launch { preferences.setDownloadDirPath(file.absolutePath) }
                            downloadDirInput = file.absolutePath
                        },
                    ) {
                        Text("保存")
                    }
                    SubtleButton(
                        onClick = { WinRTSupport.openFolder(File(downloadDirInput.trim())) },
                    ) {
                        Text("打开文件夹")
                    }
                }
            }
        }

        // 6. 关于
        SectionTitle("关于应用")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FluentTheme.colors.fillAccent.default),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "P",
                            style = FluentTheme.typography.title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Piko", style = FluentTheme.typography.subtitle, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "桌面版",
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.fillAccent.default,
                                )
                            }
                        }
                        Text(
                            "Fluent 风格云盘客户端",
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "基于 Kotlin Multiplatform 架构与 pikpak-kotlin SDK 构建，支持无损流式视频切片、并发分块加速与影音播放。",
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                java.awt.Desktop.getDesktop()
                                    .browse(java.net.URI("https://github.com/NihilDigit/piko"))
                            }
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Regular.Code,
                            contentDescription = null,
                            tint = FluentTheme.colors.fillAccent.default,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("开源仓库 (GitHub)", style = FluentTheme.typography.body, fontWeight = FontWeight.Medium)
                    }
                    Icon(
                        Icons.Regular.ChevronRight,
                        contentDescription = null,
                        tint = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 7. 退出登录
        Button(
            onClick = { showLogoutDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Regular.SignOut, contentDescription = null, modifier = Modifier.size(18.dp))
                // Fluent Button 没给 danger 槽，退出就用系统红， hover 照样有。
                Text("退出登录", color = Color(0xFFE81123))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showLogoutDialog) {
        ContentDialog(
            title = "确认退出登录",
            visible = true,
            primaryButtonText = "退出登录",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        showLogoutDialog = false
                        scope.launch { manager.logout() }
                    }
                    ContentDialogButton.Close -> showLogoutDialog = false
                    else -> {}
                }
            },
            content = {
                Text("退出登录后将返回登录界面，您需要重新输入密码或刷新凭据。")
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.bodyStrong,
        color = FluentTheme.colors.fillAccent.default,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun QuotaCell(
    label: String,
    value: String,
    valueColor: Color?,
    horizontal: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = horizontal) {
        Text(
            text = label,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
        )
        Text(
            text = value,
            style = FluentTheme.typography.bodyStrong,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: FluentTheme.colors.text.text.primary,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = FluentTheme.colors.fillAccent.default,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = FluentTheme.typography.body, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                description,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
        Switcher(
            checked = checked,
            onCheckStateChange = onCheckedChange,
        )
    }
}

@Composable
private fun ThemeOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        AccentButton(onClick = onClick) { Text(label) }
    } else {
        SubtleButton(onClick = onClick) { Text(label) }
    }
}
