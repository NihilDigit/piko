package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.WebDavState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.FirstScreenState
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTextField
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.PrimaryActionButton
import dev.piko.ui.components.PrimaryActionFab
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalFramed
import io.github.nihildigit.pikpak.WebDav
import io.github.nihildigit.pikpak.WebDavClient
import kotlinx.coroutines.launch

/**
 * PikPak 官方的 WebDAV：总开关、服务器地址与各应用的登录凭据，从设置的「账号与同步」进入。
 *
 * 每个应用摊开成一张卡片，用户名与密码直接写在上面，旁边是复制。来这一页多半是为了把凭据填进别的应用，
 * 收进面板或详情里的话每次都要多点一下。密码默认遮住，点了才显示。
 */
@Composable
fun WebDavScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val state = remember { WebDavState(services.clientManager, scope) }

    LaunchedEffect(state) {
        launch { state.followAccount() }
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WebDavClient?>(null) }
    var confirmRenew by remember { mutableStateOf<WebDavClient?>(null) }
    var confirmDelete by remember { mutableStateOf<WebDavClient?>(null) }

    fun copy(label: String, text: String) {
        platform.copyToClipboard(label, text)
        scope.launch { snackbarHostState.showSnackbar("已复制$label", withDismissAction = true) }
    }

    val framed = LocalFramed.current
    val loaded = state.settings != null
    val addAction = SheetAction(Icons.Outlined.Add, "添加应用", onClick = { adding = true })

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        island = IslandPage(header = {
            // 设置的下一级，侧边栏上亮的仍是设置，回到设置只能经这里
            TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBackClick)
            IslandTitle("WebDAV", Modifier.padding(start = 4.dp))
            IslandHeaderSpace()
            TooltipIconButton(Icons.Outlined.Refresh, "刷新", { state.load(refresh = true) }, enabled = !state.isRefreshing)
            if (loaded) PrimaryActionButton(addAction, Modifier.padding(horizontal = 6.dp))
        }),
        topBar = {
            PikoTopBar(
                title = "WebDAV",
                onBackClick = onBackClick,
                actions = listOf(
                    iconBarItem(Icons.Outlined.Refresh, "刷新", { state.load(refresh = true) }, priority = PinnedPriority, enabled = !state.isRefreshing),
                ),
            )
        },
        floatingActionButton = {
            if (!framed && loaded) PrimaryActionFab(addAction)
        },
    ) { innerPadding ->
        FirstScreenState(
            isLoading = state.isLoading,
            error = state.loadError,
            isEmpty = !loaded,
            onRetry = { state.load() },
            skeleton = { FileListSkeleton(Modifier.padding(innerPadding).padding(horizontal = 8.dp), rows = 4) },
            modifier = Modifier.fillMaxSize(),
        ) {
            val settings = state.settings ?: return@FirstScreenState
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    // 留出扩展 FAB 的高度，最后一张卡片的按钮不被它盖住
                    .padding(bottom = 96.dp)
                    .readableWidth(),
            ) {
                SettingsGroup(null) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Lan,
                        title = "WebDAV",
                        supporting = "关闭后各应用均无法连接",
                        checked = settings.enabled,
                        onCheckedChange = state::setEnabled,
                        enabled = !state.isToggling,
                    )
                    AddressRow("服务器地址", WebDav.ENDPOINT, onCopy = { copy("服务器地址", WebDav.ENDPOINT) })
                    AddressRow("备用地址", WebDav.ALTERNATIVE_ENDPOINT, onCopy = { copy("备用地址", WebDav.ALTERNATIVE_ENDPOINT) })
                }
                SettingsGroup("应用") {
                    val clients = state.webDavClients
                    if (clients.isEmpty()) {
                        Text(
                            "尚未添加应用。每个应用各用一套用户名与密码，可单独重置或删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    clients.forEach { client ->
                        WebDavClientCard(
                            client = client,
                            busy = client.id in state.busyIds,
                            onCopy = ::copy,
                            onEdit = { editing = client },
                            onRenew = { confirmRenew = client },
                            onDelete = { confirmDelete = client },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        WebDavClientDialog(
            title = "添加应用",
            confirmLabel = "添加",
            initialName = "",
            onConfirm = { name ->
                adding = false
                state.create(name)
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { client ->
        WebDavClientDialog(
            title = "重命名",
            confirmLabel = "保存",
            initialName = client.name,
            onConfirm = { name ->
                editing = null
                state.rename(client, name)
            },
            onDismiss = { editing = null },
        )
    }

    confirmRenew?.let { client ->
        PikoDialog(
            onDismissRequest = { confirmRenew = null },
            icon = { Icon(Icons.Outlined.LockReset, contentDescription = null) },
            title = { Text("重置密码") },
            text = { Text("「${client.name}」的旧密码随即失效，使用它的应用需填入新密码。") },
            confirmButton = {
                PikoDialogConfirm("重置", onClick = {
                    confirmRenew = null
                    state.renewPassword(client)
                })
            },
            dismissButton = { TextButton(onClick = { confirmRenew = null }) { Text("取消") } },
        )
    }

    confirmDelete?.let { client ->
        PikoDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("删除应用") },
            text = { Text("以「${client.name}」的凭据登录的应用将无法再连接。") },
            confirmButton = {
                PikoDialogConfirm("删除", destructive = true, onClick = {
                    confirmDelete = null
                    state.delete(client)
                })
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    state.issued?.let { issued ->
        IssuedCredentialsDialog(
            issued = issued,
            onCopy = ::copy,
            onDismiss = state::dismissIssued,
        )
    }
}

@Composable
private fun AddressRow(title: String, url: String, onCopy: () -> Unit) {
    SettingsRow(
        title = title,
        icon = Icons.Outlined.Link,
        supporting = url,
        onClick = onCopy,
        trailing = { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制") },
    )
}

/**
 * 一个应用：名称与操作在首行，下面是用户名与密码。不用 SettingsRow：它只有标题与一行说明，
 * 两行凭据各带按钮放不进去。外观照设置的卡片，各应用连成一组。
 */
@Composable
private fun WebDavClientCard(
    client: WebDavClient,
    busy: Boolean,
    onCopy: (label: String, text: String) -> Unit,
    onEdit: () -> Unit,
    onRenew: () -> Unit,
    onDelete: () -> Unit,
) {
    // 按 ID 记，换了密码仍保持显示；账号切换后列表整份换掉，ID 对不上自然遮回去
    var revealed by remember(client.id) { mutableStateOf(false) }
    SettingsCard {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    client.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(Icons.Outlined.Edit, "重命名", onEdit, enabled = !busy)
                TooltipIconButton(Icons.Outlined.LockReset, "重置密码", onRenew, enabled = !busy)
                TooltipIconButton(Icons.Outlined.Delete, "删除", onDelete, enabled = !busy, tint = MaterialTheme.colorScheme.error)
            }
            CredentialLine("用户名", client.username) {
                TooltipIconButton(Icons.Outlined.ContentCopy, "复制用户名", { onCopy("用户名", client.username) })
            }
            CredentialLine("密码", if (revealed) client.password else PasswordMask) {
                TooltipIconButton(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (revealed) "隐藏密码" else "显示密码",
                    { revealed = !revealed },
                )
                TooltipIconButton(Icons.Outlined.ContentCopy, "复制密码", { onCopy("密码", client.password) })
            }
        }
    }
}

@Composable
private fun CredentialLine(label: String, value: String, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(CredentialLabelWidth),
        )
        // 等宽字体：生成的密码里 l、1、I 与 O、0 在比例字体下分不清，照着抄会抄错
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 添加与重命名共用，只填名称。 */
@Composable
private fun WebDavClientDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            PikoTextField(
                value = name,
                onValueChange = { name = it },
                label = "名称",
                placeholder = "例如 Infuse",
            )
        },
        confirmButton = {
            PikoDialogConfirm(confirmLabel, onClick = { onConfirm(name) }, enabled = name.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 刚添加或刚重置的凭据，当场写明并可复制。只是告知，按钮用文字按钮。 */
@Composable
private fun IssuedCredentialsDialog(
    issued: WebDavState.IssuedCredentials,
    onCopy: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val client = issued.client
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (issued.renewed) Icons.Outlined.LockReset else Icons.Outlined.Lan, contentDescription = null) },
        title = { Text(if (issued.renewed) "已重置「${client.name}」的密码" else "已添加「${client.name}」") },
        text = {
            Column {
                CredentialLine("地址", WebDav.ENDPOINT) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, "复制地址", { onCopy("服务器地址", WebDav.ENDPOINT) })
                }
                CredentialLine("用户名", client.username) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, "复制用户名", { onCopy("用户名", client.username) })
                }
                CredentialLine("密码", client.password) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, "复制密码", { onCopy("密码", client.password) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "也可随时在此页查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private const val PasswordMask = "••••••••"

private val CredentialLabelWidth = 56.dp
