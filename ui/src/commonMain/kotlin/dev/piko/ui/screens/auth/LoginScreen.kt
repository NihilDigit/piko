package dev.piko.ui.screens.auth

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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.state.BrowserAuthState
import dev.piko.shared.state.LoginState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.IslandTabBarHeight
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.*
import dev.piko.ui.screens.settings.ProxySettingsDialog
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页。整页一块，表单居中限宽，品牌是表单顶上的图标。
 *
 * 同时支持通过系统默认浏览器完成第一方 Google 登录（设备授权流 + `/drive/qr-login` 确认）与账号密码登录。
 * 因某个账号失效才来到这里时，按上次登录方式提示续期，且绝不将内部键 `pikpak:<sub>` 填入输入框。
 */
@Composable
fun LoginScreen(
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clientManager = LocalPikoServices.current.clientManager
    val preferences = LocalPikoServices.current.preferences
    val state = remember {
        LoginState(clientManager, scope, if (onCancel != null) clientManager.addingPrefill else clientManager.expiredPrefill)
    }
    DisposableEffect(state) {
        onDispose { state.cancelGoogleLogin() }
    }
    val proxySetting by preferences.proxySettingFlow.collectAsState(ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }
    val handleBack: (() -> Unit)? = when {
        state.browserAuth is BrowserAuthState.AwaitingConfirmation -> state::cancelGoogleLogin
        onCancel != null -> {
            {
                state.cancelGoogleLogin()
                onCancel()
            }
        }
        else -> null
    }
    if (handleBack != null) BackHandler(onBack = handleBack)

    val windowCaption = LocalWindowCaption.current
    windowCaption?.Host()
    val caption = rememberCaptionSlot()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (windowCaption != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().then(caption.modifier).height(IslandTabBarHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f).fillMaxHeight().windowDragArea())
                    caption.buttons?.invoke()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().safeDrawingPadding()) {
                LoginForm(
                    state = state,
                    adding = onCancel != null,
                    proxySummary = proxySetting.summary(),
                    onOpenProxy = { showProxyDialog = true },
                    modifier = Modifier.align(Alignment.Center),
                )
                if (onCancel != null) {
                    TooltipIconButton(
                        Icons.Outlined.Close,
                        tr("取消", "Cancel", "キャンセル", "취소", "取消"),
                        onClick = {
                            state.cancelGoogleLogin()
                            onCancel()
                        },
                        enabled = !state.isLoggingIn,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginForm(
    state: LoginState,
    adding: Boolean,
    proxySummary: String,
    onOpenProxy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPikoPlatform.current
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val passwordField = rememberTextFieldState(state.password)
    LaunchedEffect(passwordField) {
        snapshotFlow { passwordField.text.toString() }.collect(state::updatePassword)
    }
    val browserAuth = state.browserAuth
    val isBusy = state.isBusy
    val errorMessage = state.errorMessage
    val reauthContext = state.reauthContext
    LaunchedEffect(Unit) { if (state.needsPasswordOnly) passwordFocus.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = PikoBrandIcons.Logo,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = when {
                    reauthContext != null -> tr("重新登录", "Sign in again", "再ログイン", "다시 로그인", "重新登入")
                    adding -> tr("添加账号", "Add account", "アカウントを追加", "계정 추가", "新增帳號")
                    else -> tr("登录", "Sign in", "ログイン", "로그인", "登入")
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    reauthContext != null -> tr(
                        "目标账号：${reauthContext.displayName}",
                        "Account: ${reauthContext.displayName}",
                        "対象アカウント：${reauthContext.displayName}",
                        "대상 계정: ${reauthContext.displayName}",
                        "目標帳號：${reauthContext.displayName}",
                    )
                    adding -> tr(
                        "当前账号保持登录，可随时切换",
                        "Your current account stays signed in and can be switched anytime",
                        "現在のアカウントはログイン状態のまま、いつでも切り替えられます",
                        "현재 계정의 로그인은 유지되며 언제든 전환할 수 있습니다",
                        "目前帳號將保持登入，可隨時切換",
                    )
                    else -> tr(
                        "使用 PikPak 账号",
                        "Use your PikPak account",
                        "PikPakアカウントでログイン",
                        "PikPak 계정으로 로그인",
                        "使用 PikPak 帳號",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (reauthContext != null && browserAuth is BrowserAuthState.Idle) {
                TextButton(onClick = state::clearReauthTarget, enabled = !isBusy) {
                    Text(
                        tr(
                            "换用其他账号登录",
                            "Sign in with a different account",
                            "別のアカウントでログイン",
                            "다른 계정으로 로그인",
                            "換用其他帳號登入",
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(28.dp))
            }

            if (browserAuth is BrowserAuthState.AwaitingConfirmation) {
                GoogleConfirmationCard(
                    waiting = browserAuth,
                    errorMessage = errorMessage,
                    onReopenBrowser = { state.reopenBrowser(platform::openUrl) },
                    onCopyLink = {
                        platform.copyToClipboard(
                            label = "PikPak Google Sign-In",
                            text = browserAuth.googleAuthUrl,
                        )
                    },
                    onCancel = state::cancelGoogleLogin,
                )
            } else {
                val isStartingGoogle = browserAuth is BrowserAuthState.Starting
                OutlinedButton(
                    onClick = { state.startGoogleLogin(platform::openUrl) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (isStartingGoogle) {
                        InlineLoadingIndicator(color = LocalContentColor.current)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr(
                                "正在请求授权码…",
                                "Requesting sign-in code…",
                                "認証コードをリクエスト中…",
                                "인증 코드 요청 중…",
                                "正在請求授權碼…",
                            ),
                        )
                    } else {
                        Icon(
                            imageVector = PikoBrandIcons.Google,
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr(
                                "使用 Google 继续",
                                "Continue with Google",
                                "Google で続行",
                                "Google로 계속하기",
                                "使用 Google 繼續",
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = tr("或使用密码登录", "or sign in with password", "またはパスワードでログイン", "또는 비밀번호로 로그인", "或使用密碼登入"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = state.account,
                    onValueChange = state::updateAccount,
                    label = { Text(stringResource(Res.string.text_email_or_username)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = errorMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                    shape = MaterialTheme.shapes.largeIncreased,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedSecureTextField(
                    state = passwordField,
                    label = { Text(stringResource(Res.string.text_password)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = if (passwordVisible) tr("隐藏密码", "Hide password", "パスワードを隠す", "비밀번호 숨기기", "隱藏密碼") else tr("显示密码", "Show password", "パスワードを表示", "비밀번호 표시", "顯示密碼"),
                            )
                        }
                    },
                    textObfuscationMode = when {
                        passwordVisible -> TextObfuscationMode.Visible
                        platform.revealsLastTypedPassword -> TextObfuscationMode.RevealLastTyped
                        else -> TextObfuscationMode.Hidden
                    },
                    enabled = !isBusy,
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
                    if (state.isLoggingIn) {
                        InlineLoadingIndicator(color = LocalContentColor.current)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.text_signing_in))
                    } else {
                        Text(stringResource(Res.string.text_sign_in))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onOpenProxy, enabled = !state.isLoggingIn) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("网络代理：$proxySummary", "Proxy: $proxySummary", "プロキシ：$proxySummary", "프록시: $proxySummary", "網路代理：$proxySummary"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GoogleConfirmationCard(
    waiting: BrowserAuthState.AwaitingConfirmation,
    errorMessage: String?,
    onReopenBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onCancel: () -> Unit,
) {
    var remainingSeconds by remember(waiting.txId) {
        mutableLongStateOf((waiting.expiresAtEpochSec - Clock.System.now().epochSeconds).coerceAtLeast(0L))
    }
    var linkCopied by remember(waiting.txId) { mutableStateOf(false) }
    LaunchedEffect(waiting.txId) {
        while (true) {
            val left = (waiting.expiresAtEpochSec - Clock.System.now().epochSeconds).coerceAtLeast(0L)
            remainingSeconds = left
            if (left <= 0L) break
            delay(1_000L)
        }
    }
    LaunchedEffect(linkCopied) {
        if (linkCopied) {
            delay(2_000L)
            linkCopied = false
        }
    }

    val mins = (remainingSeconds / 60L).toString().padStart(2, '0')
    val secs = (remainingSeconds % 60L).toString().padStart(2, '0')

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = PikoBrandIcons.Google,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tr(
                        "请在浏览器中确认登录",
                        "Confirm sign-in in your browser",
                        "ブラウザでログインを確認してください",
                        "브라우저에서 로그인을 확인해 주세요",
                        "請在瀏覽器中確認登入",
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr(
                    "已在默认浏览器打开 Google 选号页。完成 Google 登录后，在跳回的 PikPak 页面点击「确认登录」即可自动进入 Piko。",
                    "Opened Google sign-in in your default browser. After choosing your Google account, click “Confirm” on the PikPak page to finish.",
                    "既定のブラウザで Google ログインを開きました。Google アカウントを選択後、PikPak の画面で「ログインを確認」を押すと自動で完了します。",
                    "기본 브라우저에서 Google 로그인을 열었습니다. Google 계정을 선택한 뒤 PikPak 페이지에서 '로그인 확인'을 누르면 자동으로 완료됩니다.",
                    "已在預設瀏覽器開啟 Google 選號頁。完成 Google 登入後，在跳回的 PikPak 頁面點選「確認登入」即可自動進入 Piko。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (waiting.userCode.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = tr("设备验证码", "Verification code", "確認コード", "인증 코드", "裝置驗證碼"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = waiting.userCode,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InlineLoadingIndicator()
                Text(
                    text = tr(
                        "等待确认中 · 有效剩余 $mins:$secs",
                        "Waiting for confirmation · $mins:$secs left",
                        "確認待機中 · 残り $mins:$secs",
                        "확인 대기 중 · 남은 시간 $mins:$secs",
                        "等待確認中 · 有效剩餘 $mins:$secs",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onReopenBrowser,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("重新打开浏览器", "Open browser again", "ブラウザをもう一度開く", "브라우저 다시 열기", "重新開啟瀏覽器"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onCopyLink()
                    linkCopied = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = if (linkCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (linkCopied) {
                        tr("已复制登录链接", "Sign-in link copied", "リンクをコピーしました", "로그인 링크 복사됨", "已複製登入連結")
                    } else {
                        tr("复制登录链接", "Copy sign-in link", "ログインリンクをコピー", "로그인 링크 복사", "複製登入連結")
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel) {
                Text(tr("取消等待并返回", "Cancel", "キャンセル", "취소", "取消等待並返回"))
            }
        }
    }
}

private val FormMaxWidth = 400.dp
