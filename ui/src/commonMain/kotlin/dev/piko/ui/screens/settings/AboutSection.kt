package dev.piko.ui.screens.settings

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr
import org.jetbrains.compose.resources.getString

import dev.piko.ui.ObserveMessages

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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
    updater?.let { ObserveMessages(it.messages, snackbarHostState) }

    SettingsGroup(title = null, modifier = modifier) {
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
    }

    SettingsGroup(title = null, modifier = modifier) {
        // 没有应用内更新的平台不给这个开关
        if (updater != null) {
            val autoCheckUpdates by sessionManager.autoCheckUpdatesFlow.collectAsState(initial = true)
            SettingsSwitchRow(
                icon = Icons.Outlined.SystemUpdate,
                title = tr("自动检查更新", "Check for updates automatically", "更新を自動確認", "업데이트 자동 확인"),
                supporting = tr("启动时检查，有新版本时提示", "Check at startup and notify when an update is available", "起動時に確認し、新しいバージョンがあれば通知", "시작 시 확인하고 새 버전이 있으면 알림"),
                checked = autoCheckUpdates,
                onCheckedChange = { scope.launch { sessionManager.setAutoCheckUpdates(it) } },
            )
        }
        SettingsNavigationRow(
            icon = Icons.Outlined.BugReport,
            title = tr("导出日志", "Export logs", "ログをエクスポート", "로그 내보내기"),
            supporting = tr("反馈问题时附上，不含文件名与账号机密", "Attach when reporting issues; file names and account secrets are excluded", "問題報告時に添付してください。ファイル名やアカウントの機密情報は含みません", "문제 보고 시 첨부하세요. 파일 이름 및 계정 비밀 정보는 포함되지 않습니다"),
            onClick = { scope.launch { exportLogs(platform) } },
            trailingIcon = null,
        )
        // 日志只留七天，这里给的是复现之前手动清一次：导出的就只有这一次的经过
        SettingsNavigationRow(
            icon = Icons.Outlined.History,
            title = tr("清除日志", "Clear logs", "ログを消去", "로그 지우기"),
            supporting = tr("日志保留七天，复现问题前可先清空", "Logs are kept for seven days. Clear them before reproducing an issue", "ログは7日間保持されます。問題を再現する前に消去できます", "로그는 7일간 보관됩니다. 문제 재현 전에 지울 수 있습니다"),
            onClick = {
                scope.launch {
                    PikoLog.clear()
                    snackbarHostState.showSnackbar(getString(Res.string.text_logs_cleared), withDismissAction = true)
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
    val header = tr("Piko ${platform.appVersion}\n${platform.deviceSummary}\n导出于 $now\n\n", "Piko ${platform.appVersion}\n${platform.deviceSummary}\nExported at $now\n\n", "Piko ${platform.appVersion}\n${platform.deviceSummary}\nエクスポート日時 $now\n\n", "Piko ${platform.appVersion}\n${platform.deviceSummary}\n내보낸 시각 $now\n\n")
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
    SettingsCard {
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
                    Text(tr("版本 $version", "Version $version", "バージョン $version", "버전 $version"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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
                if (updateStatus != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledTonalButton(onClick = onUpdateClick, enabled = updateStatus != UpdateStatus.Checking) {
                        if (updateStatus == UpdateStatus.Checking) {
                            // 按钮在检查期间是禁用态，指示器跟着用禁用的内容色，不然一块亮色压在灰按钮上
                            InlineLoadingIndicator(color = LocalContentColor.current)
                        } else {
                            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when (updateStatus) {
                                is UpdateStatus.Available, is UpdateStatus.ReadyToRestart -> tr("查看更新", "View update", "更新を表示", "업데이트 보기")
                                is UpdateStatus.Downloading, is UpdateStatus.Installing -> tr("查看进度", "View progress", "進捗を表示", "진행 상황 보기")
                                else -> tr("检查更新", "Check for updates", "更新を確認", "업데이트 확인")
                            },
                            maxLines = 1,
                        )
                        if (updateStatus is UpdateStatus.Available) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onOpenRepository,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurface,
                    ),
                ) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.text_source_code), maxLines = 1)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                FilledTonalButton(
                    onClick = onOpenChannel,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurface,
                    ),
                ) {
                    Icon(Icons.Outlined.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.text_telegram_channel), maxLines = 1)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private enum class UpdateEmphasis { Normal, Positive, Error }

/** 更新状态写在版本号下面一行；还没检查过时不写。 */
private fun updateStatusText(status: UpdateStatus): Pair<String, UpdateEmphasis>? = when (status) {
    UpdateStatus.Idle -> null
    UpdateStatus.Checking -> tr("正在检查更新", "Checking for updates", "更新を確認中", "업데이트 확인 중") to UpdateEmphasis.Normal
    UpdateStatus.UpToDate -> tr("已是最新版本", "Up to date", "最新バージョンです", "최신 버전입니다") to UpdateEmphasis.Normal
    is UpdateStatus.Available -> tr("发现新版本 ${status.update.version}", "Update available: ${status.update.version}", "新バージョン ${status.update.version} があります", "새 버전 ${status.update.version} 사용 가능") to UpdateEmphasis.Positive
    is UpdateStatus.Downloading -> tr("正在下载 ${(status.progress * 100).toInt()}%", "Downloading ${(status.progress * 100).toInt()}%", "ダウンロード中 ${(status.progress * 100).toInt()}%", "다운로드 중 ${(status.progress * 100).toInt()}%") to UpdateEmphasis.Normal
    is UpdateStatus.Installing -> tr("等待安装确认", "Waiting for installation confirmation", "インストールの確認待ち", "설치 확인 대기 중") to UpdateEmphasis.Normal
    is UpdateStatus.ReadyToRestart -> tr("已下载，重启后完成更新", "Downloaded; restart to finish updating", "ダウンロード済み。再起動すると更新が完了します", "다운로드 완료. 다시 시작하면 업데이트됩니다") to UpdateEmphasis.Positive
    is UpdateStatus.Failed -> status.message to UpdateEmphasis.Error
}
