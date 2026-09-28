package dev.piko.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.piko.shared.log.PikoLog
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateDialog
import dev.piko.update.UpdateStatus
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * 关于：版本与更新、项目链接、自动检查更新与日志。有「我的」页时放在那里，设置入口的下面；
 * 有整条侧边栏的宽窗口里没有「我的」页，与账号一样收进设置。
 *
 * 更新对话框与更新器的提示随它走：更新器的一次性提示多由对话框里的操作引起，放在别处就没人接。
 * [snackbarHostState] 取宿主页面的，清除日志与更新器的提示都显示在那里。
 */
@Composable
internal fun AboutSection(snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    val sessionManager = LocalPikoServices.current.preferences
    val updater = platform.updater
    val scope = rememberCoroutineScope()
    var updateInSheet by remember { mutableStateOf<AvailableUpdate?>(null) }
    LaunchedEffect(updater, snackbarHostState) {
        updater?.messages?.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        AboutCard(
            version = platform.appVersion,
            updateStatus = updater?.status,
            onUpdateClick = {
                if (updater == null) return@AboutCard
                when (val current = updater.status) {
                    is UpdateStatus.Available -> updateInSheet = current.update
                    is UpdateStatus.Downloading -> updateInSheet = current.update
                    is UpdateStatus.Installing -> updateInSheet = current.update
                    is UpdateStatus.ReadyToRestart -> updateInSheet = current.update
                    is UpdateStatus.Failed -> current.update?.let { updateInSheet = it }
                        ?: scope.launch { updater.check() }
                    else -> scope.launch { updater.check() }
                }
            },
            onOpenRepository = { platform.openUrl(REPOSITORY_URL) },
            onOpenChannel = { platform.openUrl(CHANNEL_URL) },
        )
        // 没有应用内更新的平台不给这个开关
        val aboutCount = if (updater != null) 3 else 2
        val aboutOffset = aboutCount - 2
        if (updater != null) {
            val autoCheckUpdates by sessionManager.autoCheckUpdatesFlow.collectAsState(initial = true)
            SettingsSwitchRow(
                index = 0, count = aboutCount,
                icon = Icons.Outlined.SystemUpdate,
                title = "自动检查更新",
                supporting = "启动时检查一次，有新版本时提示",
                checked = autoCheckUpdates,
                onCheckedChange = { scope.launch { sessionManager.setAutoCheckUpdates(it) } },
            )
        }
        SettingsNavigationRow(
            index = aboutOffset,
            count = aboutCount,
            icon = Icons.Outlined.BugReport,
            title = "导出日志",
            supporting = "反馈问题时请附上。只记录操作经过，不含文件名、账号与密码",
            onClick = { scope.launch { exportLogs(platform) } },
            trailingIcon = null,
        )
        // 日志只留两天，这里给的是复现之前手动清一次：导出的就只有这一次的经过
        SettingsNavigationRow(
            index = aboutOffset + 1,
            count = aboutCount,
            icon = Icons.Outlined.History,
            title = "清除日志",
            supporting = "自动保留最近两天。复现问题之前清除一次，导出的内容更清楚",
            onClick = {
                scope.launch {
                    PikoLog.clear()
                    snackbarHostState.showSnackbar("已清除日志", withDismissAction = true)
                }
            },
            trailingIcon = null,
        )
    }

    if (updater != null) {
        updateInSheet?.let { update ->
            // 与开屏提示同一个对话框，这里不给「忽略此版本」：是用户自己点进来看的
            UpdateDialog(updater = updater, update = update, onDismiss = { updateInSheet = null })
        }
    }
}

private const val REPOSITORY_URL = "https://github.com/NihilDigit/piko"

// 更新与公告发在这个频道里，应用内不另做公告栏
private const val CHANNEL_URL = "https://t.me/piko_dev"

/** 开头写明版本与运行环境，收到的人不必再问；文件名带时间，多次导出不会互相覆盖。 */
private suspend fun exportLogs(platform: PikoPlatform) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.pad() = toString().padStart(2, '0')
    val stamp = "${now.year}${now.month.number.pad()}${now.day.pad()}-${now.hour.pad()}${now.minute.pad()}${now.second.pad()}"
    val header = "Piko ${platform.appVersion}\n${platform.deviceSummary}\n导出于 $now\n\n"
    platform.exportLog("piko-log-$stamp.txt", header + PikoLog.export())
}

/**
 * 版本、更新状态与两个动作放在一张小卡片里。
 * 原先是三行列表，版本号占一整行，「检查更新」的状态又要另读一行副标题。
 */
@Composable
private fun AboutCard(
    version: String,
    /** 没有应用内更新的平台为 null，不显示更新按钮。 */
    updateStatus: UpdateStatus?,
    onUpdateClick: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenChannel: () -> Unit,
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
                            // 按钮在检查期间是禁用态，指示器跟着用禁用的内容色，不然一块亮色压在灰按钮上
                            InlineLoadingIndicator(color = LocalContentColor.current)
                        } else {
                            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (updateStatus) {
                                is UpdateStatus.Available, is UpdateStatus.ReadyToRestart -> "查看更新"
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
                OutlinedButton(onClick = onOpenChannel) {
                    Icon(Icons.Outlined.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Telegram 频道")
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
    is UpdateStatus.ReadyToRestart -> "已下载，重启后完成更新" to UpdateEmphasis.Positive
    is UpdateStatus.Failed -> status.message to UpdateEmphasis.Error
}
