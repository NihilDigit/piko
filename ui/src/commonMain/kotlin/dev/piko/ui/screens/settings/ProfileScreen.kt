package dev.piko.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.update.UpdateStatus
import io.github.nihildigit.pikpak.TransferAllowances
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 「我的」页：账号卡片（含网盘空间与流量额度）、回收站与设置两个入口、退出登录。
 * 设置项另起一页，本页一屏放得下，不必滚动。
 *
 * [selectedPane] 是 expanded 窗口里右侧详情栏正在显示的页，对应的入口行高亮；其余宽度下为 null。
 */
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    selectedPane: Screen?,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val clientManager = services.clientManager
    val driveRepo = services.driveRepository
    val accountRepo = services.accountRepository
    val session by sessionManager.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    // 网络回来之前先用上次存下的数字渲染，否则卡片整块缺席、刷新完再跳出来
    val liveQuota by driveRepo.quotaFlow.collectAsStateWithLifecycle()
    val cachedQuota by sessionManager.quotaSnapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val quota = liveQuota?.let { QuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes) } ?: cachedQuota
    // 不落盘，只在本页存活期间保留；失败时留着上一次的值，只在旁边补一行错误文字
    val transferQuota by driveRepo.transferQuotaFlow.collectAsStateWithLifecycle()
    var transferQuotaError by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val updater = platform.updater
    // 进页面静默查一次：失败不打扰，在设置页手动点「检查更新」时才报错。放在这里而不是设置页，
    // 新版本才能写在「设置」入口上，不必点进去才知道
    LaunchedEffect(updater) {
        if (updater?.status == UpdateStatus.Idle) updater.check(silent = true)
    }
    val availableUpdate = (updater?.status as? UpdateStatus.Available)?.update

    LaunchedEffect(Unit) {
        driveRepo.getQuota()
        // 昵称与头像不随登录态返回，每次进入本页取一次。
        // 失败不提示：头像本就有首字母兜底，为它弹一条错误反而扰人。
        accountRepo.refreshProfile()
    }
    LaunchedEffect(Unit) {
        driveRepo.getTransferQuota()
            .onSuccess { transferQuotaError = null }
            .onFailure { transferQuotaError = "流量额度加载失败" }
    }

    // 不设顶栏：标题与导航栏选中的「我的」重复，本页也没有页面级动作。
    // Scaffold 的内容边距已含状态栏，账号卡片直接从状态栏下方开始
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
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
                allowances = transferQuota?.account,
                allowancesError = transferQuotaError,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsNavigationRow(
                    index = 0, count = 2,
                    icon = Icons.Outlined.Delete,
                    title = "回收站",
                    supporting = "恢复或彻底删除已移入回收站的文件",
                    onClick = onOpenTrash,
                    selected = selectedPane == Screen.Trash,
                )
                SettingsNavigationRow(
                    index = 1, count = 2,
                    icon = Icons.Outlined.Settings,
                    title = "设置",
                    supporting = availableUpdate?.let { "发现新版本 ${it.version}" } ?: "外观、文件名解析与下载",
                    onClick = onOpenSettings,
                    selected = selectedPane == Screen.Settings,
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
}

/** 账号、网盘空间与月度流量额度合为一张卡。 */
@Composable
private fun AccountCard(
    username: String?,
    /** 邮箱，没有邮箱时退回 UID。 */
    accountLabel: String?,
    avatarUrl: String?,
    quota: QuotaSnapshot?,
    allowances: TransferAllowances?,
    allowancesError: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            AccountHeader(
                username = username,
                accountLabel = accountLabel,
                avatarUrl = avatarUrl,
                memberUntil = allowances?.expireTime?.let(::formatExpireDate),
            )

            if (quota != null) {
                Spacer(modifier = Modifier.height(24.dp))
                StorageSection(quota)
            }

            if (allowances != null || allowancesError != null) {
                Spacer(modifier = Modifier.height(24.dp))
                TransferSection(allowances, allowancesError)
            }
        }
    }
}

@Composable
private fun AccountHeader(
    username: String?,
    accountLabel: String?,
    avatarUrl: String?,
    memberUntil: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = username?.take(1)?.uppercase(Locale.getDefault()) ?: "P",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = username?.ifEmpty { null } ?: "PikPak 用户",
                style = MaterialTheme.typography.titleLarge,
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
            if (memberUntil != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "会员至 $memberUntil",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageSection(quota: QuotaSnapshot) {
    val fraction = usedFraction(quota.usageBytes, quota.limitBytes)
    val nearlyFull = fraction >= NEARLY_FULL_FRACTION
    val accent = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column {
        SectionLabel("网盘空间")
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = quota.usageBytes.toReadableSize(),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "/ ${quota.limitBytes.toReadableSize()}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (quota.limitBytes > 0) {
                Text(
                    text = "剩余 ${(quota.limitBytes - quota.usageBytes).coerceAtLeast(0).toReadableSize()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (nearlyFull) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { fraction },
            color = accent,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
    }
}

@Composable
private fun TransferSection(allowances: TransferAllowances?, error: String?) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("本月流量", Modifier.weight(1f))
            if (allowances != null) {
                Text(
                    text = "${nextTransferQuotaReset()}重置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (allowances != null) {
            val usages = buildList {
                add(Usage("离线", allowances.offline.usedBytes, allowances.offline.limitBytes))
                add(Usage("下载", allowances.download.usedBytes, allowances.download.limitBytes))
                add(Usage("上传", allowances.upload.usedBytes, allowances.upload.limitBytes))
                if (allowances.downloadDaily.limitBytes > 0) {
                    add(Usage("每日下载", allowances.downloadDaily.usedBytes, allowances.downloadDaily.limitBytes))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            UsageGrid(usages)
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 小格按宽度换行，但各行格数取均匀：四格放不下一行时排成 2 + 2，而不是 3 + 1。
 * 不用 FlowRow：它只按剩余空间换行，末行那一格会被 weight 拉满整行，与上一行宽度对不上。
 */
@Composable
private fun UsageGrid(usages: List<Usage>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 8.dp
        val maxColumns = ((maxWidth + spacing) / (USAGE_TILE_MIN_WIDTH + spacing)).toInt().coerceIn(1, usages.size)
        val rows = (usages.size + maxColumns - 1) / maxColumns
        val columns = (usages.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            usages.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { UsageTile(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 月度流量额度（离线下载、下载、上传）的一格。流量额度是官方网页流量配额弹窗在客户端的对应物；
 * 第三方应用共享的 `connectedApps` 那 25% 不显示：piko 走的是账号自身额度，不占用那一份。
 */
private class Usage(val title: String, val usedBytes: Long, val limitBytes: Long)

private val USAGE_TILE_MIN_WIDTH = 88.dp

/** 空间用到这个比例起，进度条与剩余量改用 error 色。 */
private const val NEARLY_FULL_FRACTION = 0.95f

private fun usedFraction(usedBytes: Long, limitBytes: Long): Float =
    if (limitBytes > 0) (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f) else 0f

@Composable
private fun UsageTile(usage: Usage, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = usage.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = usage.usedBytes.toReadableSize(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "/ ${usage.limitBytes.toReadableSize()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { usedFraction(usage.usedBytes, usage.limitBytes) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 下一次月度重置的日期（每月 1 日 0 点，新加坡时间；接口不返回）。minSdk 26 起自带 java.time，不必再引入 kotlinx-datetime。 */
private fun nextTransferQuotaReset(): String {
    val nowInSingapore = OffsetDateTime.now(ZoneOffset.ofHours(8))
    val reset = nowInSingapore.toLocalDate().plusMonths(1).withDayOfMonth(1)
    return reset.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.getDefault()))
}

/** 非会员时 [TransferAllowances.expireTime] 为空字符串，解析失败也一并按「没有」处理。 */
private fun formatExpireDate(expireTime: String): String? {
    if (expireTime.isBlank()) return null
    return runCatching {
        OffsetDateTime.parse(expireTime).format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.getDefault()))
    }.getOrNull()
}

