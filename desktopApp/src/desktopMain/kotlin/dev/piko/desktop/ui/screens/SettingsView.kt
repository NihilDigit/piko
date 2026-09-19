package dev.piko.desktop.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.data.PikoClientManager
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.surface.Card
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsView(
    manager: PikoClientManager,
    settingsStore: DesktopSettingsStore,
    onThemeChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var downloadDirInput by remember { mutableStateOf(settingsStore.downloadDirectory.absolutePath) }
    var dirSaveMessage by remember { mutableStateOf<String?>(null) }
    var testNotificationMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val accentColor = remember { WinRTSupport.getSystemAccentColor() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header
        Text(
            text = "设置",
            style = FluentTheme.typography.title,
        )

        dirSaveMessage?.let { msg ->
            InfoBar(
                title = { Text("提示") },
                message = { Text(msg) },
                severity = io.github.composefluent.component.InfoBarSeverity.Success,
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { dirSaveMessage = null })
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        testNotificationMessage?.let { msg ->
            InfoBar(
                title = { Text("通知") },
                message = { Text(msg) },
                severity = io.github.composefluent.component.InfoBarSeverity.Informational,
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { testNotificationMessage = null })
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Section: 下载设置
        SettingsCard(title = "下载与存储") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "下载保存路径",
                    style = FluentTheme.typography.bodyStrong,
                )
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
                            if (!file.exists()) {
                                file.mkdirs()
                            }
                            settingsStore.downloadDirectory = file
                            dirSaveMessage = "下载目录已更新为: ${file.absolutePath}"
                        },
                    ) {
                        Text("保存")
                    }
                    SubtleButton(
                        onClick = {
                            WinRTSupport.openFolder(File(downloadDirInput.trim()))
                        },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Regular.Folder, contentDescription = "打开", modifier = Modifier.size(16.dp))
                            Text("浏览")
                        }
                    }
                }
            }
        }

        // Section: 外观与个性化
        SettingsCard(title = "外观与个性化") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "应用主题",
                    style = FluentTheme.typography.bodyStrong,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentMode = settingsStore.themeMode
                    ThemeOptionButton(
                        label = "跟随系统",
                        selected = currentMode == "system",
                        onClick = {
                            settingsStore.themeMode = "system"
                            onThemeChanged()
                        },
                    )
                    ThemeOptionButton(
                        label = "浅色",
                        selected = currentMode == "light",
                        onClick = {
                            settingsStore.themeMode = "light"
                            onThemeChanged()
                        },
                    )
                    ThemeOptionButton(
                        label = "深色",
                        selected = currentMode == "dark",
                        onClick = {
                            settingsStore.themeMode = "dark"
                            onThemeChanged()
                        },
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Windows 系统重色 (Accent Color)",
                            style = FluentTheme.typography.bodyStrong,
                        )
                        Text(
                            text = if (accentColor != null) "已通过 WinRT UISettings 自动读取并应用至 Fluent 界面" else "使用 Fluent 默认主题色",
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    }

                    accentColor?.let { color ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }
        }

        // Section: Windows 现代特性状态
        SettingsCard(title = "Windows 现代集成特性") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureStatusRow(
                    name = "Mica (云母) 质感背景",
                    status = "已启用",
                    description = "为窗口提供 Windows 11 原生亚克力与云母材质透明质感",
                )
                FeatureStatusRow(
                    name = "WinRT 原生 Toast 通知",
                    status = if (WinRTSupport.isWindows) "已就绪" else "非 Windows 环境",
                    description = "下载完成与离线转存时向 Windows 操作中心推送系统通知",
                )
                FeatureStatusRow(
                    name = "DisplayRequest 播放防锁屏",
                    status = if (WinRTSupport.isWindows) "已就绪" else "非 Windows 环境",
                    description = "内置视频播放器播放时阻止屏幕休眠，暂停或退出时自动释放",
                )
                FeatureStatusRow(
                    name = "Windows.System.Launcher 原生打开",
                    status = if (WinRTSupport.isWindows) "已就绪" else "非 Windows 环境",
                    description = "直接唤起 Windows 默认应用与文件资源管理器定位",
                )

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        WinRTSupport.showNotification("Piko Windows", "这是一个测试通知！WinRT Toast Notification 正常工作中。")
                        testNotificationMessage = "已发送系统 Toast 通知，请查看屏幕右下角通知中心。"
                    },
                ) {
                    Text("发送测试系统通知")
                }
            }
        }

        // Section: 账号与退出
        SettingsCard(title = "账号") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "当前会话",
                        style = FluentTheme.typography.bodyStrong,
                    )
                    Text(
                        text = "已登录 PikPak 账号",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }

                Button(
                    onClick = { showLogoutDialog = true },
                ) {
                    Text("退出登录")
                }
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        ContentDialog(
            title = "确认退出登录",
            visible = showLogoutDialog,
            primaryButtonText = "退出登录",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        showLogoutDialog = false
                        scope.launch { manager.logout() }
                    }
                    ContentDialogButton.Close -> {
                        showLogoutDialog = false
                    }
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
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = FluentTheme.typography.subtitle,
            )
            content()
        }
    }
}

@Composable
private fun ThemeOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        AccentButton(onClick = onClick) {
            Text(label)
        }
    } else {
        SubtleButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun FeatureStatusRow(
    name: String,
    status: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = FluentTheme.typography.body)
            Text(description, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
        }
        Text(status, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
    }
}
