package dev.piko.ui.screens.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import dev.piko.ui.components.IslandTabBarHeight
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.frame
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.state.LoginState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.settings.Avatar
import dev.piko.ui.screens.settings.ProxySettingsDialog
import kotlinx.coroutines.launch

/**
 * 登录页。宽窗口左右分栏，左边是品牌，右边是表单；窄窗口只有表单，品牌缩成表单顶上的图标。
 *
 * 本机保存着账号时，它们列在表单之前（「继续使用」）：点一下直接进，会话失效的填好账号名、光标落到密码框。
 * 登录着一个账号再加一个时（[onCancel] 不为 null）不列它们，标题换成「添加账号」，左上角可以关掉回去。
 */
@Composable
fun LoginScreen(
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clientManager = LocalPikoServices.current.clientManager
    val preferences = LocalPikoServices.current.preferences
    val state = remember { LoginState(clientManager, scope, clientManager.addingPrefill.takeIf { onCancel != null }.orEmpty()) }
    val savedAccounts by clientManager.accounts.collectAsState()
    val proxySetting by preferences.proxySettingFlow.collectAsState(ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }
    if (onCancel != null) BackHandler(onBack = onCancel)

    val wide = currentWidthClass() == WidthClass.Expanded
    // 标题栏并进内容，与主界面相同：顶上一行画窗口按钮，空白处能拖。宽窗口里左边的品牌区是外框色，
    // 右边的表单是一块圆角的岛浮在上面（四角露出外框色），两块颜色相接处由浅的一方圆角压在深的一方上；
    // 原来两块直接拼接，接缝是一条硬直线，标题栏还是系统的一条
    val windowCaption = LocalWindowCaption.current
    windowCaption?.Host()
    val caption = rememberCaptionSlot()
    Surface(modifier = modifier.fillMaxSize(), color = if (wide) MaterialTheme.colorScheme.frame else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            // 只在接管了标题栏的桌面窗口里有这一行；手机上顶上是状态栏，表单自己让开
            if (windowCaption != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().then(caption.modifier).height(IslandTabBarHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f).fillMaxHeight().windowDragArea())
                    caption.buttons?.invoke()
                }
            } else if (wide) {
                // 平板上没有标题栏这一行，岛的上沿也离开边缘一截
                Spacer(Modifier.height(IslandGap))
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (wide) BrandPane(Modifier.weight(1f).fillMaxHeight())
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (wide) {
                                Modifier
                                    .padding(end = IslandGap, bottom = IslandGap)
                                    .clip(FrameCardShape)
                                    .background(MaterialTheme.colorScheme.surface)
                            } else {
                                Modifier
                            },
                        )
                        .safeDrawingPadding(),
                ) {
                    LoginForm(
                        state = state,
                        saved = if (onCancel == null) savedAccounts.accounts else emptyList(),
                        adding = onCancel != null,
                        showLogo = !wide,
                        proxySummary = proxySetting.summary(),
                        onOpenProxy = { showProxyDialog = true },
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (onCancel != null) {
                        TooltipIconButton(
                            Icons.Outlined.Close,
                            "取消",
                            onClick = onCancel,
                            enabled = !state.isLoggingIn,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    if (showProxyDialog) {
        ProxySettingsDialog(
            current = proxySetting,
            onSave = { setting ->
                showProxyDialog = false
                scope.launch { preferences.saveProxySetting(setting) }
            },
            onDismiss = { showProxyDialog = false },
        )
    }
}

/** 宽窗口左边的品牌区：图标、名字与一句定位，直接落在外框色上，右边的表单是浮在上面的岛。 */
@Composable
private fun BrandPane(modifier: Modifier) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = PikoBrandIcons.Logo,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(128.dp).clip(MaterialTheme.shapes.extraLarge),
            )
            Spacer(Modifier.height(28.dp))
            Text("Piko", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "高性能、多平台的 PikPak 客户端",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginForm(
    state: LoginState,
    saved: List<SavedAccount>,
    adding: Boolean,
    showLogo: Boolean,
    proxySummary: String,
    onOpenProxy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    // SecureTextField 只收 TextFieldState，而 shared 里的 LoginState 存的是 String，
    // 所以在这里单向同步过去，不把 TextFieldState 推进 shared
    val passwordField = rememberTextFieldState(state.password)
    LaunchedEffect(passwordField) {
        snapshotFlow { passwordField.text.toString() }.collect(state::updatePassword)
    }
    val isLoading = state.isLoggingIn
    val errorMessage = state.errorMessage
    val clientManager = LocalPikoServices.current.clientManager
    val credentialsEncrypted by produceState(false) { value = clientManager.credentialsEncrypted() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 平板与横屏上表单不铺满：一行输入框拉到上千 dp 宽，眼睛要来回扫
        Column(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth()) {
            if (showLogo) {
                Icon(
                    imageVector = PikoBrandIcons.Logo,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Spacer(Modifier.height(24.dp))
            }
            Text(
                text = if (adding) "添加账号" else "登录",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (adding) "当前账号保持登录，可随时切换" else "使用 PikPak 账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            if (saved.isNotEmpty()) {
                SectionLabel("继续使用")
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        saved.forEach { account ->
                            SavedAccountRow(
                                saved = account,
                                busy = state.usingSaved == account.account,
                                enabled = !isLoading,
                                onClick = { state.useSaved(account.account) { passwordFocus.requestFocus() } },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                SectionLabel("其他账号")
            }

            // contentType 让密码管理器认出这两个框，能自动填充，登录后也会提示保存
            OutlinedTextField(
                value = state.account,
                onValueChange = state::updateAccount,
                label = { Text("邮箱或用户名") },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                singleLine = true,
                enabled = !isLoading,
                isError = errorMessage != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                shape = MaterialTheme.shapes.largeIncreased,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            // 错误放在密码框的辅助文字里，不单独一行飘在框与按钮之间
            OutlinedSecureTextField(
                state = passwordField,
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                textObfuscationMode = when {
                    passwordVisible -> TextObfuscationMode.Visible
                    LocalPikoPlatform.current.revealsLastTypedPassword -> TextObfuscationMode.RevealLastTyped
                    else -> TextObfuscationMode.Hidden
                },
                enabled = !isLoading,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .semantics { contentType = ContentType.Password },
                shape = MaterialTheme.shapes.largeIncreased,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { state.login() },
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = state::login,
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (isLoading && state.usingSaved == null) {
                    InlineLoadingIndicator(color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("正在登录")
                } else {
                    Text("登录")
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            // 连不上 PikPak 的人往往卡在这一步，代理要在登录之前就能改
            TextButton(onClick = onOpenProxy, enabled = !isLoading) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("网络代理：$proxySummary", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 系统没有可用的凭据存储（Linux 上没有钥匙串服务）时凭据以明文文件存放，这句话就不成立，不写
            if (credentialsEncrypted) {
                Text(
                    "登录凭据经系统加密后仅保存于本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SavedAccountRow(saved: SavedAccount, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(saved.displayName, saved.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(saved.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = saved.email.ifBlank { saved.account }
            if (secondary != saved.displayName) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) InlineLoadingIndicator()
    }
}

private val FormMaxWidth = 400.dp
