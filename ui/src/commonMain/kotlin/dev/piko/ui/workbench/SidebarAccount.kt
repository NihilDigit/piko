package dev.piko.ui.workbench

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.download.DownloadStatus
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.LocalSidebarTooltipsEnabled
import dev.piko.ui.screens.settings.AccountCard
import dev.piko.ui.screens.settings.AccountSwitcher
import dev.piko.ui.screens.settings.Avatar
import dev.piko.ui.screens.settings.rememberAccountSummary
import dev.piko.ui.theme.FrameBottomRowHeight
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalStatusColors
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.tabular
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateDialog
import dev.piko.update.UpdateInterruptDownloadsDialog
import dev.piko.update.UpdateStage
import dev.piko.update.UpdateStatus
import dev.piko.update.formatVersionBadge
import kotlinx.coroutines.launch

/**
 * 侧边栏左下角：更新常驻入口（下载进度胶囊 / 重启并更新药丸）+ 账号、配额进度条与设置入口。
 *
 * 完整订阅 [UpdateStatus]：
 * - `Available`：设置图标（展开态）或头像（收起态）挂轻量红点；
 * - `Downloading`：上方停靠紧凑圆形进度环胶囊，点击直接打开 [UpdateDialog] 查看或取消；
 * - `ReadyToRestart`：上方停靠带轻度阴影的 tonal「重启并更新」药丸（收起态为强调图标），一键重启更新。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SidebarAccountRow(selected: Boolean, onOpenSettings: () -> Unit, collapsed: Boolean = false) {
    val account = rememberAccountSummary()
    val saved = account.saved
    val platform = LocalPikoPlatform.current
    val updater = platform.updater
    val updateStatus = updater?.status
    val downloadManager = LocalPikoServices.current.downloadManager
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    val shortcut = platform.shortcutModifier.label(",")

    var dialogUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var confirmRestartUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }

    val triggerRestart: (AvailableUpdate) -> Unit = { update ->
        val hasActiveDownloads = downloadManager.tasks.value.values.any {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
        }
        if (hasActiveDownloads) {
            confirmRestartUpdate = update
        } else {
            scope.launch { updater?.restartToInstall(update) }
        }
    }

    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "sidebarAccountBg",
    )
    val clickLabel = tr("账号与设置", "Account and settings", "アカウントと設定", "계정 및 설정", "帳號與設定")
    val displayName = saved?.displayName ?: tr("PikPak 用户", "PikPak user", "PikPakユーザー", "PikPak 사용자", "PikPak 使用者")
    val availableUpdate = (updateStatus as? UpdateStatus.Available)?.update
    val failedUpdate = (updateStatus as? UpdateStatus.Failed)?.update
    // 已在设置页（selected == true）时，右侧关于卡片本身即呈现更新入口与进度，侧边栏不再重复显示第二个入口
    val showAccountBadge = !selected && (availableUpdate != null || failedUpdate != null)
    val collapsedSubtitle = if (collapsed) {
        account.quota?.let { quota ->
            "$displayName (${quota.usageBytes.toReadableSize()} / ${quota.limitBytes.toReadableSize()})"
        } ?: displayName
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 常驻更新动作/进度条停靠在账号行正上方（仅在离开设置页时显示，避免与关于卡片同时出现两个入口）
        if (!selected) {
            when (updateStatus) {
                is UpdateStatus.ReadyToRestart -> SidebarRestartUpdatePill(
                    update = updateStatus.update,
                    collapsed = collapsed,
                    onClick = { triggerRestart(updateStatus.update) },
                )
                is UpdateStatus.Downloading -> SidebarDownloadingChip(
                    status = updateStatus,
                    collapsed = collapsed,
                    onClick = { dialogUpdate = updateStatus.update },
                )
                else -> Unit
            }
        }

        val accountRowContent: @Composable () -> Unit = {
            if (collapsed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FrameBottomRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(containerColor)
                            .focusIndication(CircleShape, showPointerFocus = false)
                            .clickable(onClickLabel = clickLabel, onClick = onOpenSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        BadgedBox(
                            badge = {
                                if (showAccountBadge) {
                                    Badge(
                                        containerColor = if (failedUpdate != null) colors.error else colors.primary,
                                    )
                                }
                            },
                        ) {
                            Avatar(saved?.displayName, saved?.avatarUrl, size = 32.dp)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与传输页底栏同高起步，避免 CJK 行高或放大字号下裁切 3dp 配额条
                        .heightIn(min = FrameBottomRowHeight)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(containerColor)
                        .focusIndication(MaterialTheme.shapes.large, showPointerFocus = false)
                        .clickable(onClickLabel = clickLabel, onClick = onOpenSettings)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(saved?.displayName, saved?.avatarUrl, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        account.quota?.let { quota ->
                            val fraction = if (quota.limitBytes > 0L) {
                                (quota.usageBytes.toFloat() / quota.limitBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            val barColor = when {
                                fraction >= 0.95f -> colors.error
                                fraction >= 0.85f -> statusColors.warning
                                else -> colors.primary
                            }
                            val quotaColor = when {
                                fraction >= 0.95f -> colors.error
                                fraction >= 0.85f -> statusColors.warning
                                else -> colors.onSurfaceVariant
                            }
                            Text(
                                text = "${quota.usageBytes.toReadableSize()} / ${quota.limitBytes.toReadableSize()}",
                                style = MaterialTheme.typography.labelSmall.tabular,
                                color = quotaColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (quota.limitBytes > 0L) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(CircleShape)
                                        .background(colors.onSurfaceVariant.copy(alpha = 0.16f)),
                                ) {
                                    val visibleFraction = if (quota.usageBytes > 0L) fraction.coerceAtLeast(0.02f) else 0f
                                    if (visibleFraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(visibleFraction)
                                                .height(3.dp)
                                                .clip(CircleShape)
                                                .background(barColor),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    BadgedBox(
                        badge = {
                            if (showAccountBadge) {
                                Badge(
                                    containerColor = if (failedUpdate != null) colors.error else colors.primary,
                                )
                            }
                        },
                    ) {
                        Icon(
                            if (selected) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        if (LocalSidebarTooltipsEnabled.current) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    if (collapsed) TooltipAnchorPosition.End else TooltipAnchorPosition.Above,
                ),
                tooltip = {
                    PlainTooltip {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                availableUpdate?.let {
                                    tr(
                                        "账号与设置 ($shortcut，发现新版本 ${it.version})",
                                        "Account and settings ($shortcut, update ${it.version} available)",
                                        "アカウントと設定 ($shortcut、新バージョン ${it.version})",
                                        "계정 및 설정 ($shortcut, 새 버전 ${it.version})",
                                        "帳號與設定 ($shortcut，發現新版本 ${it.version})",
                                    )
                                } ?: "$clickLabel ($shortcut)",
                            )
                            if (!collapsedSubtitle.isNullOrBlank()) {
                                Text(
                                    text = collapsedSubtitle,
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    color = LocalContentColor.current.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                },
                state = rememberTooltipState(),
            ) {
                accountRowContent()
            }
        } else {
            accountRowContent()
        }
    }

    if (updater != null) {
        dialogUpdate?.let { update ->
            UpdateDialog(
                updater = updater,
                update = update,
                onDismiss = { dialogUpdate = null },
            )
        }
        confirmRestartUpdate?.let { update ->
            UpdateInterruptDownloadsDialog(
                onConfirm = {
                    confirmRestartUpdate = null
                    scope.launch { updater.restartToInstall(update) }
                },
                onDismiss = { confirmRestartUpdate = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SidebarRestartUpdatePill(
    update: AvailableUpdate,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(Res.string.text_restart_and_update)
    val versionTag = formatVersionBadge(update.version)

    if (collapsed) {
        val button: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    shape = CircleShape,
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .focusIndication(CircleShape, showPointerFocus = false)
                        .clickable(onClickLabel = label, onClick = onClick),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = label,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (LocalSidebarTooltipsEnabled.current) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
                tooltip = {
                    PlainTooltip {
                        Text("$label ($versionTag)", style = MaterialTheme.typography.labelMedium.tabular)
                    }
                },
                state = rememberTooltipState(),
            ) {
                button()
            }
        } else {
            button()
        }
    } else {
        Surface(
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                .clip(MaterialTheme.shapes.large)
                .focusIndication(MaterialTheme.shapes.large, showPointerFocus = false)
                .clickable(onClickLabel = label, onClick = onClick),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = versionTag,
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarDownloadingChip(
    status: UpdateStatus.Downloading,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val percent = (status.progress * 100).toInt().coerceIn(0, 100)
    val stageLabel = when (status.stage) {
        UpdateStage.Downloading -> "${stringResource(Res.string.updater_stage_downloading)} $percent%"
        UpdateStage.Verifying -> stringResource(Res.string.updater_stage_verifying)
        UpdateStage.Staging -> stringResource(Res.string.updater_stage_staging)
    }
    val speedLabel = if (status.stage == UpdateStage.Downloading && status.bytesPerSec > 0L) {
        "${status.bytesPerSec.toReadableSize()}/s"
    } else {
        null
    }

    if (collapsed) {
        val ringButton: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .focusIndication(CircleShape, showPointerFocus = false)
                        .clickable(onClickLabel = stageLabel, onClick = onClick),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CompactUpdateProgressRing(
                            status = status,
                            size = 22.dp,
                            color = colors.primary,
                            trackColor = colors.onSecondaryContainer.copy(alpha = 0.18f),
                        )
                    }
                }
            }
        }
        if (LocalSidebarTooltipsEnabled.current) {
            val tooltipTitle = when (status.stage) {
                UpdateStage.Downloading -> stringResource(Res.string.updater_background_downloading_tooltip, percent)
                UpdateStage.Verifying -> stringResource(Res.string.updater_stage_verifying)
                UpdateStage.Staging -> stringResource(Res.string.updater_stage_staging)
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
                tooltip = {
                    PlainTooltip {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tooltipTitle, style = MaterialTheme.typography.labelMedium.tabular)
                            if (speedLabel != null) {
                                Text(
                                    text = speedLabel,
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    color = LocalContentColor.current.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                },
                state = rememberTooltipState(),
            ) {
                ringButton()
            }
        } else {
            ringButton()
        }
    } else {
        Surface(
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                .clip(MaterialTheme.shapes.large)
                .focusIndication(MaterialTheme.shapes.large, showPointerFocus = false)
                .clickable(onClickLabel = stageLabel, onClick = onClick),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactUpdateProgressRing(
                    status = status,
                    size = 18.dp,
                    color = colors.primary,
                    trackColor = colors.onSecondaryContainer.copy(alpha = 0.18f),
                )
                Text(
                    text = stageLabel,
                    style = MaterialTheme.typography.labelMedium.tabular,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (speedLabel != null) {
                    Text(
                        text = speedLabel,
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = colors.onSecondaryContainer.copy(alpha = 0.78f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactUpdateProgressRing(
    status: UpdateStatus.Downloading,
    size: Dp,
    color: Color,
    trackColor: Color,
) {
    val staticMode = LocalPikoMotion.current.reduced || !LocalWindowVisible.current
    if (status.stage == UpdateStage.Downloading || staticMode) {
        val sweep = if (status.stage == UpdateStage.Downloading) {
            (status.progress.coerceIn(0f, 1f) * 360f).coerceAtLeast(12f)
        } else {
            260f
        }
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = 2.2.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    } else {
        InlineLoadingIndicator(color = color)
    }
}

/**
 * 设置页里「账号」一类的内容：账号卡片、切换与添加账号、退出登录。有整条侧边栏的宽窗口没有「我的」页，它们放在设置的最前；
 * 手机上它们在「我的」页。
 *
 * 账号卡片不放进侧边栏的下拉菜单：菜单按内容的固有尺寸定大小，卡片展开流量额度后的用量表格是
 * SubcomposeLayout，被问固有尺寸会直接抛异常。
 */
@Composable
internal fun ColumnScope.AccountSettings(
    onLogout: () -> Unit,
    onOpenSpaceMap: (() -> Unit)? = null,
) {
    // 与下面各组之间留同样的缝
    Spacer(Modifier.height(12.dp))
    // 宽窗口没有下拉刷新，另给按钮
    AccountCard(rememberAccountSummary(), showRefresh = true, onOpenSpaceMap = onOpenSpaceMap)
    // 退出与切换、添加同是对账号的操作，并成一组的末行，不再单独一个整行宽的描边按钮
    AccountSwitcher(onLoggedOut = onLogout)
}
