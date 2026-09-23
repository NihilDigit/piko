package dev.piko.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.BuildConfig
import dev.piko.PikoApplication
import dev.piko.R
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.launch
import java.util.Locale

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
    val context = LocalContext.current
    val sessionManager = PikoApplication.instance.sessionManager
    val clientManager = PikoApplication.instance.clientManager
    val driveRepo = PikoApplication.instance.driveRepository
    val accountRepo = PikoApplication.instance.accountRepository
    val session by sessionManager.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isConcurrentAccelerationEnabled by sessionManager.concurrentAccelerationFlow.collectAsStateWithLifecycle(initialValue = true)
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()

    // 网络回来之前先用上次存下的数字渲染，否则卡片整块缺席、刷新完再跳出来
    val liveQuota by driveRepo.quotaFlow.collectAsStateWithLifecycle()
    val cachedQuota by sessionManager.quotaSnapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val quota = liveQuota?.let { QuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes) } ?: cachedQuota
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDownloadDirDialog by remember { mutableStateOf(false) }
    val resolvedDownloadPath = remember(downloadDirPath) {
        displayDownloadPath(context, downloadDirPath)
    }
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { uri ->
        val selected = uri.data?.data
        if (selected != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(selected, flags) }
            val persistedTreeUri = selected.toString()
            scope.launch { sessionManager.setDownloadDirPath(persistedTreeUri) }
            showDownloadDirDialog = false
        }
    }

    LaunchedEffect(Unit) {
        driveRepo.getQuota()
        // 昵称与头像不随登录态返回，每次进入本页取一次。
        // 失败不提示：头像本就有首字母兜底，为它弹一条错误反而扰人。
        accountRepo.refreshProfile()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            PikoTopBar(title = "我的")
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            AccountCard(
                username = session?.username,
                userId = session?.userId,
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

            SettingsGroup(title = "浏览") {
                SettingsSwitchRow(
                    index = 0, count = 2,
                    icon = Icons.Outlined.AutoFixHigh,
                    title = "启发式折叠",
                    supporting = "存在主体大文件时，折叠样片、字幕等附属文件",
                    checked = isHeuristicFilterEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                )
                SettingsSwitchRow(
                    index = 1, count = 2,
                    icon = Icons.Outlined.VisibilityOff,
                    title = "缩略图防窥",
                    supporting = "缩略图默认遮蔽，点按后显示",
                    checked = isSpoilerBlurEnabled,
                    onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                )
            }

            SettingsGroup(title = "下载") {
                SettingsSwitchRow(
                    index = 0, count = 2,
                    icon = Icons.Outlined.Speed,
                    title = "并发加速",
                    supporting = if (isConcurrentAccelerationEnabled) "分 8 个连接并行下载" else "单连接下载",
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
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = settingsRowColors(),
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_piko_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(MaterialTheme.shapes.extraSmall),
                        )
                    },
                    supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
                    content = { Text("Piko") },
                )
                SettingsNavigationRow(
                    index = 1, count = 2,
                    icon = Icons.Outlined.Code,
                    title = "开源仓库",
                    supporting = "github.com/NihilDigit/piko",
                    trailingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NihilDigit/piko"))
                        runCatching { context.startActivity(intent) }
                    },
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

    if (showDownloadDirDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDirDialog = false },
            title = { Text("下载位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "默认保存到应用私有目录。选择公共目录后，文件保存到你授权的文件夹。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val initialUri = android.provider.DocumentsContract.buildTreeDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Download/Piko",
                            )
                            dirPickerLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                    putExtra("android.provider.extra.INITIAL_URI", initialUri)
                                    addFlags(
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                                    )
                                },
                            )
                        },
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
    userId: String?,
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
                    if (!userId.isNullOrBlank()) {
                        Text(
                            text = "UID $userId",
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

@Composable
private fun settingsRowColors() =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

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
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
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

private fun displayDownloadPath(context: android.content.Context, storedPath: String): String {
    if (storedPath.startsWith("content:")) {
        return DocumentFile.fromTreeUri(context, Uri.parse(storedPath))?.name ?: "已选择的文件夹"
    }
    return storedPath.ifBlank {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: context.filesDir.resolve("Piko").absolutePath
    }
}
