package dev.piko.ui.screens.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.tr
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.state.BrowserAuthState
import dev.piko.shared.state.LoginAccountMode
import dev.piko.shared.state.LoginState
import dev.piko.shared.state.PhoneRegions
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.IslandTabBarHeight
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.*
import dev.piko.ui.screens.settings.ProxySettingsDialog
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.frame
import dev.piko.ui.theme.islandBorderColor
import dev.piko.ui.theme.islandSurface
import dev.piko.ui.theme.tabular
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PIKPAK_SIGN_UP_URL = "https://mypikpak.com/drive/login"
private const val PIKO_GITHUB_URL = "https://github.com/NihilDigit/piko"

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
    val initialSnapshot = remember(preferences) { preferences.currentSnapshot }
    val proxySetting by preferences.proxySettingFlow.collectAsState(initialSnapshot.proxySetting)
    val appLanguage by preferences.appLanguageFlow.collectAsState(initialSnapshot.appLanguage)
    var showProxyDialog by remember { mutableStateOf(false) }
    val allowWelcome = onCancel == null && state.reauthContext == null
    var showWelcome by remember(allowWelcome) {
        mutableStateOf(allowWelcome && state.browserAuth is BrowserAuthState.Idle)
    }
    LaunchedEffect(state.browserAuth) {
        if (state.browserAuth !is BrowserAuthState.Idle) showWelcome = false
    }
    val handleBack: (() -> Unit)? = when {
        state.browserAuth is BrowserAuthState.AwaitingConfirmation -> state::cancelGoogleLogin
        !showWelcome && allowWelcome -> {
            { showWelcome = true }
        }
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
    val colors = MaterialTheme.colorScheme
    val platform = LocalPikoPlatform.current
    val expressive = remember { MotionScheme.expressive() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colors.surface,
    ) {
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
            // 统一极简顶栏：左侧按需显示返回/取消，右侧收拢语言与代理入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (windowCaption == null) Modifier.safeDrawingPadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !showWelcome && allowWelcome && state.browserAuth !is BrowserAuthState.AwaitingConfirmation -> {
                        TooltipIconButton(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                            onClick = { showWelcome = true },
                            enabled = !state.isBusy,
                        )
                    }
                    onCancel != null -> {
                        TooltipIconButton(
                            icon = Icons.Outlined.Close,
                            label = tr("取消", "Cancel", "キャンセル", "취소", "取消"),
                            onClick = {
                                state.cancelGoogleLogin()
                                onCancel()
                            },
                            enabled = !state.isLoggingIn,
                        )
                    }
                    else -> Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                LanguageQuickPill(
                    current = appLanguage,
                    enabled = !state.isLoggingIn,
                    onSelect = { lang -> scope.launch { preferences.setAppLanguage(lang) } },
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { showProxyDialog = true },
                    enabled = !state.isLoggingIn,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (proxySetting.mode == ProxyMode.SYSTEM) {
                            tr("网络代理", "Proxy", "プロキシ", "프록시", "網路代理")
                        } else {
                            proxySetting.summary()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AnimatedContent(
                targetState = showWelcome,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val forward = !targetState
                    val slideIn = slideInHorizontally(expressive.defaultSpatialSpec()) { w -> if (forward) w / 6 else -w / 6 }
                    val slideOut = slideOutHorizontally(expressive.defaultSpatialSpec()) { w -> if (forward) -w / 6 else w / 6 }
                    (slideIn + fadeIn(expressive.fastEffectsSpec())) togetherWith
                        (slideOut + fadeOut(expressive.fastEffectsSpec()))
                },
                label = "login-onboarding-step",
            ) { welcoming ->
                if (welcoming) {
                    LoginWelcomeStep(
                        onGetStarted = { showWelcome = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LoginForm(
                        state = state,
                        adding = onCancel != null,
                        modifier = Modifier.fillMaxSize(),
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
private fun LoginWelcomeStep(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val compact = currentWidthClass() == WidthClass.Compact
    val reduced = LocalPikoMotion.current.reduced
    val expressive = remember { MotionScheme.expressive() }

    // 顺序编排：先看画布演完（投文件 -> 憋劲 -> Bang!），再依次浮现「标题 + Slogan」与「开始使用」
    var bangTriggered by remember(reduced) { mutableStateOf(reduced) }
    val copyAlpha = remember(reduced) { Animatable(if (reduced) 1f else 0f) }
    val copySlide = remember(reduced) { Animatable(if (reduced) 0f else 1f) }
    val ctaAlpha = remember(reduced) { Animatable(if (reduced) 1f else 0f) }
    val ctaSlide = remember(reduced) { Animatable(if (reduced) 0f else 1f) }

    LaunchedEffect(bangTriggered, reduced) {
        if (!bangTriggered || reduced) return@LaunchedEffect
        delay(220)
        launch { copyAlpha.animateTo(1f, expressive.defaultEffectsSpec()) }
        launch { copySlide.animateTo(0f, expressive.defaultSpatialSpec()) }
        delay(180)
        launch { ctaAlpha.animateTo(1f, expressive.defaultEffectsSpec()) }
        launch { ctaSlide.animateTo(0f, expressive.defaultSpatialSpec()) }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PikoOnboardingCanvas(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                onBang = { bangTriggered = true },
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = copyAlpha.value
                        translationY = copySlide.value * 18.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Piko",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(
                        "优雅、易用、高性能的 PikPak 客户端",
                        "An elegant, easy-to-use, high-performance PikPak client",
                        "洗練された、使いやすく高速な PikPak クライアント",
                        "세련되고 사용하기 쉬우며 고성능인 PikPak 클라이언트",
                        "優雅、易用、高效能的 PikPak 用戶端",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onGetStarted,
                modifier = (if (compact) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(min = 220.dp)
                })
                    .height(ButtonDefaults.MediumContainerHeight)
                    .graphicsLayer {
                        alpha = ctaAlpha.value
                        translationY = ctaSlide.value * 16.dp.toPx()
                        val s = 0.94f + 0.06f * (1f - ctaSlide.value).coerceIn(0f, 1.05f)
                        scaleX = s
                        scaleY = s
                    },
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(tr("开始使用", "Get started", "はじめる", "시작하기", "開始使用"))
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LanguageQuickPill(
    current: AppLanguage,
    enabled: Boolean,
    onSelect: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val systemLabel = tr("跟随系统", "System", "システムに従う", "시스템 설정", "跟隨系統")
    val currentLabel = if (current == AppLanguage.SYSTEM) systemLabel else current.nativeLabel
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(currentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val languages = AppLanguage.entries
            languages.forEachIndexed { index, language ->
                val selected = language == current
                val label = if (language == AppLanguage.SYSTEM) systemLabel else language.nativeLabel
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    trailingContent = if (selected) ({
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }) else null,
                    shape = menuItemShape(index, languages.size),
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginForm(
    state: LoginState,
    adding: Boolean,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPikoPlatform.current
    var passwordVisible by remember { mutableStateOf(false) }
    var showDialCodeDialog by remember { mutableStateOf(false) }
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
    LaunchedEffect(isBusy) { if (isBusy) showDialCodeDialog = false }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = when {
                        reauthContext != null -> tr("重新登录", "Sign in again", "再ログイン", "다시 로그인", "重新登入")
                        adding -> tr("添加账号", "Add account", "アカウントを追加", "계정 추가", "新增帳號")
                        else -> tr("登录", "Sign in", "ログイン", "로그인", "登入")
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
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
                    textAlign = TextAlign.Start,
                )
            }
            if (reauthContext != null && browserAuth is BrowserAuthState.Idle) {
                TextButton(
                    onClick = state::clearReauthTarget,
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.Start),
                ) {
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
                Spacer(Modifier.height(24.dp))
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
                val accountModes = remember { listOf(LoginAccountMode.EMAIL, LoginAccountMode.PHONE) }
                val toggleColors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    accountModes.forEachIndexed { index, mode ->
                        ToggleButton(
                            checked = state.accountMode == mode,
                            onCheckedChange = { state.selectAccountMode(mode) },
                            enabled = !isBusy,
                            shapes = connectedToggleShapes(index, accountModes.size),
                            colors = toggleColors,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = when (mode) {
                                    LoginAccountMode.EMAIL -> tr("邮箱", "Email", "メール", "이메일", "信箱")
                                    LoginAccountMode.PHONE -> tr("手机号", "Phone", "電話番号", "전화번호", "手機號碼")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                when (state.accountMode) {
                    LoginAccountMode.EMAIL -> {
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
                            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        )
                    }
                    LoginAccountMode.PHONE -> {
                        val contentAlpha = if (isBusy) 0.38f else 1f
                        OutlinedTextField(
                            value = state.phoneNumber,
                            onValueChange = state::updatePhoneNumber,
                            label = { Text(tr("手机号码", "Phone number", "電話番号", "휴대폰 번호", "手機號碼")) },
                            leadingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .heightIn(min = 38.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .clickable(
                                                enabled = !isBusy,
                                                role = Role.Button,
                                                onClickLabel = tr(
                                                    "选择国家或地区区号",
                                                    "Select country or region code",
                                                    "国、地域コードを選択",
                                                    "국가/지역 번호 선택",
                                                    "選擇國家或地區區碼",
                                                ),
                                            ) { showDialCodeDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = "+${state.dialCode}",
                                            style = MaterialTheme.typography.titleSmall.tabular,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                                            maxLines = 1,
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            imageVector = Icons.Outlined.ArrowDropDown,
                                            contentDescription = tr("选择区号", "Select country code", "国番号を選択", "국가 번호 선택", "選擇區碼"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Box(
                                        Modifier
                                            .width(1.dp)
                                            .height(22.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                            },
                            trailingIcon = if (state.phoneNumber.isNotEmpty() && !isBusy) {
                                {
                                    IconButton(onClick = { state.updatePhoneNumber("") }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = tr("清除", "Clear", "クリア", "지우기", "清除"),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            enabled = !isBusy,
                            isError = errorMessage != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentType = ContentType.PhoneNumber + ContentType.Username },
                            shape = MaterialTheme.shapes.largeIncreased,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        )
                    }
                }
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

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = tr("或", "or", "または", "또는", "或"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(16.dp))

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

                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = { platform.openUrl(PIKPAK_SIGN_UP_URL) },
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        tr(
                            "前往 PikPak 官网注册",
                            "Create an account on PikPak",
                            "PikPak 公式サイトで登録",
                            "PikPak 공식 사이트에서 가입",
                            "前往 PikPak 官網註冊",
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                onClick = { platform.openUrl(PIKO_GITHUB_URL) },
                enabled = !isBusy,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = tr(
                            "Piko 是开源的 PikPak 第三方客户端，与 PikPak 官方无关",
                            "Piko is an open-source third-party client, not affiliated with PikPak",
                            "Piko はオープンソースの非公式 PikPak クライアントであり、公式とは関係ありません",
                            "Piko는 오픈소스 비공식 PikPak 클라이언트로, 공식 서비스와 무관합니다",
                            "Piko 是開源的 PikPak 第三方用戶端，與 PikPak 官方無關",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }

    if (showDialCodeDialog) {
        DialCodePickerDialog(
            currentDialCode = state.dialCode,
            onSelect = { code ->
                state.updateDialCode(code)
                showDialCodeDialog = false
            },
            onDismiss = { showDialCodeDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DialCodePickerDialog(
    currentDialCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { PhoneRegions.filter(query) }
    val customCode = remember(query, filtered) {
        PhoneRegions.customDialCodeCandidate(query)?.takeUnless { candidate ->
            filtered.any { it.dialCode == candidate }
        }
    }
    val showCurrentCustom = query.isBlank() && PhoneRegions.all.none { it.dialCode == currentDialCode }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
        title = {
            Text(
                tr(
                    "选择国家或地区区号",
                    "Select country or region code",
                    "国、地域コードを選択",
                    "국가/지역 번호 선택",
                    "選擇國家或地區區碼",
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = tr("清除", "Clear", "クリア", "지우기", "清除"),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = {
                        Text(
                            text = tr(
                                "搜索地区或输入区号（如 31）",
                                "Search region or enter code (e.g. 31)",
                                "地域を検索または番号を入力（例: 31）",
                                "지역 검색 또는 번호 입력 (예: 31)",
                                "搜尋地區或輸入區碼（如 31）",
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = MaterialTheme.shapes.largeIncreased,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val target = when {
                                filtered.size == 1 && customCode == null -> filtered.single().dialCode
                                filtered.isEmpty() && customCode != null -> customCode
                                else -> null
                            }
                            if (target != null) onSelect(target)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    if (showCurrentCustom) {
                        DialCodeOptionRow(
                            label = tr(
                                "当前区号 +$currentDialCode",
                                "Current code +$currentDialCode",
                                "現在の国番号 +$currentDialCode",
                                "현재 국가 번호 +$currentDialCode",
                                "目前區碼 +$currentDialCode",
                            ),
                            dialCode = currentDialCode,
                            selected = true,
                            onClick = { onSelect(currentDialCode) },
                        )
                    }
                    filtered.forEach { region ->
                        DialCodeOptionRow(
                            label = region.localizedName,
                            dialCode = region.dialCode,
                            selected = region.dialCode == currentDialCode,
                            onClick = { onSelect(region.dialCode) },
                        )
                    }
                    if (customCode != null) {
                        DialCodeOptionRow(
                            label = tr(
                                "使用区号 +$customCode",
                                "Use dial code +$customCode",
                                "国番号 +$customCode を使用",
                                "국가 번호 +$customCode 사용",
                                "使用區碼 +$customCode",
                            ),
                            dialCode = customCode,
                            selected = customCode == currentDialCode,
                            onClick = { onSelect(customCode) },
                        )
                    }
                    if (filtered.isEmpty() && customCode == null && !showCurrentCustom) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tr(
                                    "没有匹配的地区；可直接输入 1~4 位国际区号",
                                    "No matching region. Enter a 1–4 digit dial code.",
                                    "一致する地域がありません。1〜4桁の国番号を入力できます",
                                    "일치하는 지역이 없습니다. 1~4자리 국가 번호를 직접 입력할 수 있습니다",
                                    "沒有符合的地區；可直接輸入 1~4 位國際區碼",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.common_close))
            }
        },
    )
}

@Composable
private fun DialCodeOptionRow(
    label: String,
    dialCode: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+$dialCode",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
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
                        "等待确认中，有效剩余 $mins:$secs",
                        "Waiting for confirmation ($mins:$secs left)",
                        "確認待機中（残り $mins:$secs）",
                        "확인 대기 중 (남은 시간 $mins:$secs)",
                        "等待確認中，有效剩餘 $mins:$secs",
                    ),
                    style = MaterialTheme.typography.labelMedium.tabular,
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
            TextButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) {
                Text(tr("取消等待并返回", "Cancel", "キャンセル", "취소", "取消等待並返回"))
            }
        }
    }
}

private val FormMaxWidth = 400.dp
