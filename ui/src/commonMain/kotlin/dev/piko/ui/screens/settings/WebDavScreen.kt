package dev.piko.ui.screens.settings

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import dev.piko.ui.ObserveMessages

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

    }

    ObserveMessages(state.messages, snackbarHostState)

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WebDavClient?>(null) }
    var confirmRenew by remember { mutableStateOf<WebDavClient?>(null) }
    var confirmDelete by remember { mutableStateOf<WebDavClient?>(null) }

    fun copy(label: String, text: String) {
        platform.copyToClipboard(label, text)
        scope.launch { snackbarHostState.showSnackbar(tr("已复制$label", "Copied $label", "$label をコピーしました", "$label 복사 완료", "已複製$label"), withDismissAction = true) }
    }

    val framed = LocalFramed.current
    val loaded = state.settings != null
    val addAction = SheetAction(Icons.Outlined.Add, tr("添加应用", "Add app", "アプリを追加", "앱 추가", "新增應用程式"), onClick = { adding = true })

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        island = IslandPage(header = {
            // 设置的下一级，侧边栏上亮的仍是设置，回到设置只能经这里
            TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back", "戻る", "뒤로", "返回"), onBackClick)
            IslandTitle("WebDAV", Modifier.padding(start = 4.dp))
            IslandHeaderSpace()
            TooltipIconButton(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), { state.load(refresh = true) }, enabled = !state.isRefreshing)
            if (loaded) PrimaryActionButton(addAction, Modifier.padding(horizontal = 6.dp))
        }),
        topBar = {
            PikoTopBar(
                title = "WebDAV",
                onBackClick = onBackClick,
                actions = listOf(
                    iconBarItem(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), { state.load(refresh = true) }, priority = PinnedPriority, enabled = !state.isRefreshing),
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
                        supporting = tr("关闭后各应用均无法连接", "Disabling prevents all apps from connecting", "無効にすると各アプリから接続できなくなります", "끄면 모든 앱에서 연결할 수 없습니다", "關閉後所有應用程式皆無法連線"),
                        checked = settings.enabled,
                        onCheckedChange = state::setEnabled,
                        enabled = !state.isToggling,
                    )
                    AddressRow(tr("服务器地址", "Server address", "サーバーアドレス", "서버 주소", "伺服器位址"), WebDav.ENDPOINT, onCopy = { copy(tr("服务器地址", "Server address", "サーバーアドレス", "서버 주소", "伺服器位址"), WebDav.ENDPOINT) })
                    AddressRow(tr("备用地址", "Alternative address", "代替アドレス", "대체 주소", "備用位址"), WebDav.ALTERNATIVE_ENDPOINT, onCopy = { copy(tr("备用地址", "Alternative address", "代替アドレス", "대체 주소", "備用位址"), WebDav.ALTERNATIVE_ENDPOINT) })
                }
                SettingsGroup(tr("应用", "Apps", "アプリ", "앱", "應用程式")) {
                    val clients = state.webDavClients
                    if (clients.isEmpty()) {
                        Text(
                            stringResource(Res.string.text_no_apps_added_each_app_has_its_own_credentials_that_can_be_reset_or_deleted_),
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
            title = tr("添加应用", "Add app", "アプリを追加", "앱 추가", "新增應用程式"),
            confirmLabel = tr("添加", "Add", "追加", "추가", "新增"),
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
            title = tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"),
            confirmLabel = tr("保存", "Save", "保存", "저장", "儲存"),
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
            title = { Text(stringResource(Res.string.text_reset_password)) },
            text = { Text(tr("「${client.name}」的旧密码随即失效，使用它的应用需填入新密码。", "The old password for “${client.name}” will stop working. Update it in connected apps.", "「${client.name}」の古いパスワードは無効になります。利用中のアプリに新しいパスワードを入力してください。", "“${client.name}”의 이전 비밀번호는 즉시 무효화됩니다. 연결된 앱에서 새 비밀번호를 입력해야 합니다.", "「${client.name}」的舊密碼將立即失效，使用此密碼的應用程式需填入新密碼。")) },
            confirmButton = {
                PikoDialogConfirm(tr("重置", "Reset", "リセット", "재설정", "重設"), onClick = {
                    confirmRenew = null
                    state.renewPassword(client)
                })
            },
            dismissButton = { TextButton(onClick = { confirmRenew = null }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }

    confirmDelete?.let { client ->
        PikoDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(Res.string.text_delete_app)) },
            text = { Text(tr("以「${client.name}」的凭据登录的应用将无法再连接。", "Apps using “${client.name}” credentials will no longer connect.", "「${client.name}」の認証情報を使うアプリは接続できなくなります。", "“${client.name}”의 인증 정보를 사용하는 앱은 더 이상 연결할 수 없습니다.", "使用「${client.name}」憑證登入的應用程式將無法再連線。")) },
            confirmButton = {
                PikoDialogConfirm(tr("删除", "Delete", "削除", "삭제", "刪除"), destructive = true, onClick = {
                    confirmDelete = null
                    state.delete(client)
                })
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(Res.string.common_cancel)) } },
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
        trailing = { Icon(Icons.Outlined.ContentCopy, contentDescription = tr("复制", "Copy", "コピー", "복사", "複製")) },
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
                TooltipIconButton(Icons.Outlined.Edit, tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"), onEdit, enabled = !busy)
                TooltipIconButton(Icons.Outlined.LockReset, tr("重置密码", "Reset password", "パスワードをリセット", "비밀번호 재설정", "重設密碼"), onRenew, enabled = !busy)
                TooltipIconButton(Icons.Outlined.Delete, tr("删除", "Delete", "削除", "삭제", "刪除"), onDelete, enabled = !busy, tint = MaterialTheme.colorScheme.error)
            }
            CredentialLine(tr("用户名", "Username", "ユーザー名", "사용자 이름", "使用者名稱"), client.username) {
                TooltipIconButton(Icons.Outlined.ContentCopy, tr("复制用户名", "Copy username", "ユーザー名をコピー", "사용자 이름 복사", "複製使用者名稱"), { onCopy(tr("用户名", "Username", "ユーザー名", "사용자 이름", "使用者名稱"), client.username) })
            }
            CredentialLine(tr("密码", "Password", "パスワード", "비밀번호", "密碼"), if (revealed) client.password else PasswordMask) {
                TooltipIconButton(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (revealed) tr("隐藏密码", "Hide password", "パスワードを隠す", "비밀번호 숨기기", "隱藏密碼") else tr("显示密码", "Show password", "パスワードを表示", "비밀번호 표시", "顯示密碼"),
                    { revealed = !revealed },
                )
                TooltipIconButton(Icons.Outlined.ContentCopy, tr("复制密码", "Copy password", "パスワードをコピー", "비밀번호 복사", "複製密碼"), { onCopy(tr("密码", "Password", "パスワード", "비밀번호", "密碼"), client.password) })
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
                label = tr("名称", "Name", "名前", "이름", "名稱"),
                placeholder = tr("例如 Infuse", "For example, Infuse", "例：Infuse", "예: Infuse", "例如 Infuse"),
            )
        },
        confirmButton = {
            PikoDialogConfirm(confirmLabel, onClick = { onConfirm(name) }, enabled = name.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
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
        title = { Text(if (issued.renewed) tr("已重置「${client.name}」的密码", "Password reset for “${client.name}”", "「${client.name}」のパスワードをリセットしました", "“${client.name}” 비밀번호 재설정 완료", "已重設「${client.name}」的密碼") else tr("已添加「${client.name}」", "Added “${client.name}”", "「${client.name}」を追加しました", "“${client.name}” 추가 완료", "已新增「${client.name}」")) },
        text = {
            Column {
                CredentialLine(tr("地址", "Address", "アドレス", "주소", "位址"), WebDav.ENDPOINT) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, tr("复制地址", "Copy address", "アドレスをコピー", "주소 복사", "複製位址"), { onCopy(tr("服务器地址", "Server address", "サーバーアドレス", "서버 주소", "伺服器位址"), WebDav.ENDPOINT) })
                }
                CredentialLine(tr("用户名", "Username", "ユーザー名", "사용자 이름", "使用者名稱"), client.username) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, tr("复制用户名", "Copy username", "ユーザー名をコピー", "사용자 이름 복사", "複製使用者名稱"), { onCopy(tr("用户名", "Username", "ユーザー名", "사용자 이름", "使用者名稱"), client.username) })
                }
                CredentialLine(tr("密码", "Password", "パスワード", "비밀번호", "密碼"), client.password) {
                    TooltipIconButton(Icons.Outlined.ContentCopy, tr("复制密码", "Copy password", "パスワードをコピー", "비밀번호 복사", "複製密碼"), { onCopy(tr("密码", "Password", "パスワード", "비밀번호", "密碼"), client.password) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.text_you_can_view_it_on_this_page_anytime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) } },
    )
}

private const val PasswordMask = "••••••••"

private val CredentialLabelWidth = 56.dp
