package dev.piko.update

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.piko.download.DownloadStatus
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*
import dev.piko.ui.format.remainingTime
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 新版本对话框：品牌徽标与版本跃迁页眉、可滚动且带渐隐边缘的更新说明、不跳高预留状态槽与非阻塞下载控制。
 *
 * 下载由 [AppUpdateService] 的应用级作用域持有：点击「后台下载」或点击外部/返回关闭对话框时，
 * 下载继续在后台进行并由侧边栏与关于页持续反馈；需终止下载时点击「取消下载」。
 * 任何失败态（含校验失败）均固定暴露「打开下载页」与「重试」，避免开屏模式下陷入无法前往下载页的死胡同。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(
    updater: AppUpdateService,
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    /** 为 null 时不给「忽略此版本」，改给「在浏览器中查看」。 */
    onIgnore: (() -> Unit)? = null,
) {
    val language by PikoLocale.effectiveLanguageState
    val platform = LocalPikoPlatform.current
    val downloadManager = LocalPikoServices.current.downloadManager
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    // 状态属于另一个版本时（例如设置页查到了更新的版本）按未开始处理
    val status = updater.status.takeIf { it.update?.version == update.version }
    val downloading = status is UpdateStatus.Downloading
    var confirmInterrupt by remember { mutableStateOf(false) }
    // 仅当更新器未自管应用级作用域时（如无头截图假实现）回退到局部协程
    var fallbackDownloadJob by remember { mutableStateOf<Job?>(null) }

    val startDownload: () -> Unit = {
        if (!updater.startDownload(update)) {
            fallbackDownloadJob?.cancel()
            fallbackDownloadJob = scope.launch { updater.downloadAndInstall(update) }
        }
    }
    val cancelDownload: () -> Unit = {
        fallbackDownloadJob?.cancel()
        fallbackDownloadJob = null
        updater.cancelDownload()
    }
    val restart: () -> Unit = {
        val hasActiveDownloads = downloadManager.tasks.value.values.any {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
        }
        if (hasActiveDownloads) {
            confirmInterrupt = true
        } else {
            scope.launch { updater.restartToInstall(update) }
        }
    }

    val activeModeLabel = (status as? UpdateStatus.Downloading)?.modeLabel ?: update.modeLabel
    val activeTotalBytes = (status as? UpdateStatus.Downloading)?.totalBytes?.takeIf { it > 0L } ?: update.downloadSize
    val notesScroll = rememberScrollState()
    val notesSurfaceColor = colors.surfaceContainerLow
    var moreActionsExpanded by remember { mutableStateOf(false) }

    PikoDialog(
        onDismissRequest = onDismiss,
        // 平台默认宽度是给一句话加两个按钮定的，更新说明按它排每行只剩十来个字
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PikoBrandIcons.Glyph,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.updater_dialog_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatVersionBadge(platform.appVersion)} → ${formatVersionBadge(update.version)}",
                            style = MaterialTheme.typography.labelMedium.tabular,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                        val pillParts = buildList {
                            activeModeLabel?.takeIf { it.isNotBlank() }?.let(::add)
                            if (activeTotalBytes > 0L) add(activeTotalBytes.toReadableSize())
                        }
                        if (pillParts.isNotEmpty()) {
                            Surface(
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer,
                                shape = CircleShape,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    pillParts.forEach { part ->
                                        Text(
                                            text = part,
                                            style = MaterialTheme.typography.labelSmall.tabular,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 放在 surfaceContainerLow 上，让行内代码的 surfaceContainerHighest 形成清晰层次，并在上下边缘绘制渐隐提示可滚动
                Surface(
                    color = notesSurfaceColor,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScrollFade(notesScroll, edgeColor = notesSurfaceColor, fadeHeight = 20.dp),
                    ) {
                        ReleaseNotes(
                            markdown = update.notesFor(language).ifBlank {
                                stringResource(Res.string.updater_no_release_notes)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(notesScroll)
                                .padding(16.dp),
                        )
                    }
                }
                // 预留固定最小高度（覆盖 44dp、2 行 bodyMedium 状态文与进度条+遥测行三者的最大值），
                // 避免 360dp 窄屏及大字号下 Android 对话框窗口随状态切换上下跳动
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    StatusHeightPlaceholder(modifier = Modifier.alpha(0f).clearAndSetSemantics {})
                    UpdateStatusContent(status, update)
                }
            }
        },
        confirmButton = {
            when {
                !update.canInstallInApp -> Button(
                    onClick = { platform.openUrl(update.pageUrl) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.text_go_to_download_page))
                }
                status is UpdateStatus.Downloading -> FilledTonalButton(
                    onClick = onDismiss,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.updater_download_in_background))
                }
                status is UpdateStatus.ReadyToRestart -> Button(
                    onClick = restart,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.text_restart_and_update))
                }
                status is UpdateStatus.Installing -> Button(
                    onClick = {},
                    enabled = false,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.text_waiting_to_install))
                }
                status is UpdateStatus.Failed -> Button(
                    onClick = startDownload,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.common_retry))
                }
                else -> {
                    val menuCount = if (onIgnore != null) 2 else 1
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.LeadingButton(onClick = startDownload) {
                                Text(stringResource(Res.string.text_download_and_install))
                            }
                        },
                        trailingButton = {
                            Box {
                                SplitButtonDefaults.TrailingButton(
                                    checked = moreActionsExpanded,
                                    onCheckedChange = { moreActionsExpanded = it },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"),
                                        modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                                    )
                                }
                                PikoDropdownMenu(
                                    expanded = moreActionsExpanded,
                                    onDismissRequest = { moreActionsExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.text_view_in_browser)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        },
                                        shape = menuItemShape(0, menuCount),
                                        onClick = {
                                            moreActionsExpanded = false
                                            platform.openUrl(update.pageUrl)
                                        },
                                    )
                                    if (onIgnore != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.text_ignore_this_version)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.VisibilityOff,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            shape = menuItemShape(1, menuCount),
                                            onClick = {
                                                moreActionsExpanded = false
                                                onIgnore()
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        },
        dismissButton = {
            when {
                downloading -> TextButton(onClick = cancelDownload, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.text_cancel_download))
                }
                status is UpdateStatus.ReadyToRestart -> TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.updater_later))
                }
                status is UpdateStatus.Installing -> TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.common_close))
                }
                // 失败（含 SHA-256 校验未通过）时，即使在开屏弹窗模式也始终给出「打开下载页」作为逃生通道
                status is UpdateStatus.Failed -> TextButton(onClick = { platform.openUrl(update.pageUrl) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.updater_open_download_page))
                }
                !update.canInstallInApp && onIgnore != null -> TextButton(onClick = onIgnore, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.text_ignore_this_version))
                }
                else -> TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.updater_later))
                }
            }
        },
    )

    if (confirmInterrupt) {
        UpdateInterruptDownloadsDialog(
            onConfirm = {
                confirmInterrupt = false
                scope.launch { updater.restartToInstall(update) }
            },
            onDismiss = { confirmInterrupt = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateInterruptDownloadsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.text_interrupt_downloads_and_update)) },
        text = { Text(stringResource(Res.string.text_restarting_to_update_interrupts_active_downloads)) },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("重启并更新", "Restart and update", "再起動して更新", "다시 시작 및 업데이트", "重新啟動並更新"),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

@Composable
private fun UpdateStatusContent(status: UpdateStatus?, update: AvailableUpdate) {
    when (status) {
        is UpdateStatus.Downloading -> UpdateDownloadProgressBlock(status = status)
        is UpdateStatus.ReadyToRestart -> Text(
            text = stringResource(Res.string.text_downloaded_piko_will_quit_and_reopen_after_updating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is UpdateStatus.Failed -> Text(
            text = status.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> if (!update.canInstallInApp) {
            Text(
                text = stringResource(Res.string.text_this_version_cannot_be_installed_in_app_use_the_download_page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusHeightPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(modifier = Modifier.fillMaxWidth().height(10.dp))
            Text(
                text = " ",
                style = MaterialTheme.typography.labelMedium.tabular,
                maxLines = 1,
            )
        }
        Text(
            text = " \n ",
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
            maxLines = 2,
        )
    }
}

@Composable
fun UpdateDownloadProgressBlock(
    status: UpdateStatus.Downloading,
    modifier: Modifier = Modifier,
) {
    val leftLabel = updateDownloadTelemetryLeft(status)
    val rightLabel = updateDownloadTelemetryRight(status)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UpdateLinearProgress(status = status)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = leftLabel,
                style = MaterialTheme.typography.labelMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rightLabel != null) {
                Text(
                    text = rightLabel,
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateLinearProgress(
    status: UpdateStatus.Downloading,
    modifier: Modifier = Modifier,
) {
    val staticMode = LocalPikoMotion.current.reduced || !LocalWindowVisible.current
    Box(
        modifier = modifier.fillMaxWidth().height(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (status.stage == UpdateStage.Downloading) {
            if (staticMode) {
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearWavyProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            if (staticMode) {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun updateDownloadTelemetryLeft(status: UpdateStatus.Downloading): String = when (status.stage) {
    UpdateStage.Downloading -> {
        val percent = (status.progress * 100).toInt().coerceIn(0, 100)
        if (status.totalBytes > 0L) {
            "$percent% (${status.downloadedBytes.toReadableSize()} / ${status.totalBytes.toReadableSize()})"
        } else {
            "${stringResource(Res.string.updater_stage_downloading)} (${status.downloadedBytes.toReadableSize()})"
        }
    }
    UpdateStage.Verifying -> stringResource(Res.string.updater_stage_verifying)
    UpdateStage.Staging -> stringResource(Res.string.updater_stage_staging)
}

@Composable
fun updateDownloadTelemetryRight(status: UpdateStatus.Downloading): String? {
    if (status.stage != UpdateStage.Downloading || status.bytesPerSec <= 0L) return null
    val speed = "${status.bytesPerSec.toReadableSize()}/s"
    val remaining = (status.totalBytes - status.downloadedBytes).coerceAtLeast(0L)
    val eta = remainingTime(remaining, status.bytesPerSec)
    return if (eta != null) {
        "$speed (${stringResource(Res.string.updater_remaining_eta, eta)})"
    } else {
        speed
    }
}

fun formatVersionBadge(version: String): String {
    val trimmed = version.trim()
    return if (trimmed.firstOrNull()?.isDigit() == true) "v$trimmed" else trimmed
}

private fun Modifier.verticalScrollFade(
    scrollState: ScrollState,
    edgeColor: Color,
    fadeHeight: Dp = 20.dp,
): Modifier = drawWithContent {
    drawContent()
    val fadePx = fadeHeight.toPx().coerceAtMost(size.height / 2f)
    if (fadePx <= 0f) return@drawWithContent
    if (scrollState.canScrollBackward) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(edgeColor, edgeColor.copy(alpha = 0f)),
                startY = 0f,
                endY = fadePx,
            ),
            size = Size(size.width, fadePx),
        )
    }
    if (scrollState.canScrollForward) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(edgeColor.copy(alpha = 0f), edgeColor),
                startY = size.height - fadePx,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fadePx),
            size = Size(size.width, fadePx),
        )
    }
}

private val UpdateStatus.update: AvailableUpdate?
    get() = when (this) {
        is UpdateStatus.Available -> update
        is UpdateStatus.Downloading -> update
        is UpdateStatus.ReadyToRestart -> update
        is UpdateStatus.Installing -> update
        is UpdateStatus.Failed -> update
        UpdateStatus.Idle, UpdateStatus.Checking, UpdateStatus.UpToDate -> null
    }
