package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.AccountAuthMethod
import dev.piko.shared.data.AccountMismatchException
import dev.piko.shared.data.ExpiredAuthContext
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.i18n.tr
import dev.piko.shared.notice.PikoErrorMapper
import io.github.nihildigit.pikpak.DeviceAuthorizationException
import io.github.nihildigit.pikpak.DeviceAuthorizationFailureReason
import io.github.nihildigit.pikpak.PikPakException
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.IOException

/** 系统默认浏览器 Google 设备授权流的界面阶段。 */
sealed interface BrowserAuthState {
    data object Idle : BrowserAuthState
    data object Starting : BrowserAuthState
    data class AwaitingConfirmation(
        val txId: String,
        val userCode: String,
        val googleAuthUrl: String,
        val qrLoginUrl: String,
        val expiresAtEpochSec: Long,
    ) : BrowserAuthState
}

/**
 * 登录页状态（账号密码与系统默认浏览器 Google 设备授权共用）。
 *
 * 登录成功不需要回调：`PikoClientManager.currentClient` 随之变为非空，两端的根视图
 * 都据此切到主界面。预填存储键若为内部键 `pikpak:<sub>`，不会填入用户名输入框，仅保留为续期校验上下文。
 */
class LoginState(
    private val clientManager: PikoClientManager,
    private val scope: CoroutineScope,
    initialAccount: String = "",
) {
    private val initialContext: ExpiredAuthContext? = clientManager.resolvePrefillContext(initialAccount)
    private val initialParsedPhone: ParsedPhone? = initialContext?.loginAccount
        ?.takeIf { it.isNotBlank() }
        ?.let(PhoneLoginFormat::splitInternationalPhone)

    var reauthContext by mutableStateOf(initialContext)
        private set

    var accountMode by mutableStateOf(
        if (initialParsedPhone != null) LoginAccountMode.PHONE else LoginAccountMode.EMAIL,
    )
        private set

    var account by mutableStateOf(
        if (initialParsedPhone == null) initialContext?.loginAccount.orEmpty() else "",
    )
        private set

    var dialCode by mutableStateOf(
        initialParsedPhone?.dialCode ?: PhoneLoginFormat.defaultDialCode(),
    )
        private set

    var phoneNumber by mutableStateOf(
        initialParsedPhone?.nationalNumber.orEmpty(),
    )
        private set

    var password by mutableStateOf("")
        private set

    var isLoggingIn by mutableStateOf(false)
        private set

    var browserAuth by mutableStateOf<BrowserAuthState>(BrowserAuthState.Idle)
        private set

    private var isReauthNotice = initialContext != null

    /** 带着失效的账号进来时一开始就按上次登录方式说明如何续期。 */
    var errorMessage by mutableStateOf(
        when {
            initialContext == null -> null
            initialContext.authMethod == AccountAuthMethod.Google -> expiredGoogleMessage(initialContext.displayName)
            else -> expiredPasswordMessage()
        },
    )
        private set

    private var googleAuthJob: Job? = null

    /** 预先填好了密码账号名，光标该落在密码框。 */
    val needsPasswordOnly: Boolean =
        initialContext != null && initialContext.authMethod == AccountAuthMethod.Password && initialContext.loginAccount.isNotBlank()

    val isBusy: Boolean by derivedStateOf {
        isLoggingIn || browserAuth !is BrowserAuthState.Idle
    }

    val effectiveAccount: String by derivedStateOf {
        when (accountMode) {
            LoginAccountMode.EMAIL -> PhoneLoginFormat.normalizeAccountInput(account)
            LoginAccountMode.PHONE -> {
                if (dialCode.isBlank() || phoneNumber.isBlank()) {
                    ""
                } else {
                    PhoneLoginFormat.formatE164(dialCode, phoneNumber)
                }
            }
        }
    }

    val canSubmit: Boolean by derivedStateOf {
        !isBusy && effectiveAccount.isNotBlank() && password.isNotEmpty()
    }

    fun selectAccountMode(mode: LoginAccountMode) {
        if (mode == accountMode) return
        if (mode == LoginAccountMode.PHONE && phoneNumber.isBlank() && account.isNotBlank()) {
            val parsed = PhoneLoginFormat.splitInternationalPhone(account)
            when {
                parsed != null -> {
                    dialCode = parsed.dialCode
                    phoneNumber = parsed.nationalNumber.take(15)
                    account = ""
                }
                PhoneLoginFormat.isDigitsAndSeparatorsOnly(account) -> {
                    phoneNumber = PhoneLoginFormat.sanitizeNationalInput(account)
                    account = ""
                }
            }
        }
        accountMode = mode
        clearNonReauthError()
        syncReauthTarget()
    }

    fun updateDialCode(code: String) {
        val cleaned = code.filter(Char::isDigit).dropWhile { it == '0' }.take(4)
        if (cleaned.isEmpty()) return
        dialCode = cleaned
        clearNonReauthError()
        syncReauthTarget()
    }

    fun updatePhoneNumber(value: String) {
        val parsed = PhoneLoginFormat.splitInternationalPhone(value)
        if (parsed != null) {
            dialCode = parsed.dialCode
            phoneNumber = parsed.nationalNumber.take(15)
        } else {
            phoneNumber = PhoneLoginFormat.sanitizeNationalInput(value)
        }
        clearNonReauthError()
        syncReauthTarget()
    }

    fun updateAccount(value: String) {
        account = value
        clearNonReauthError()
        syncReauthTarget()
    }

    fun updatePassword(value: String) {
        if (value == password) return
        password = value
        clearNonReauthError()
    }

    fun dismissError() {
        errorMessage = null
        isReauthNotice = false
    }

    /** 解除续期目标账号锁定，允许改登其他账号。 */
    fun clearReauthTarget() {
        reauthContext = null
        errorMessage = null
        isReauthNotice = false
    }

    private fun clearNonReauthError() {
        if (!isReauthNotice) {
            errorMessage = null
        }
    }

    private fun syncReauthTarget() {
        val ctx = reauthContext ?: return
        if (ctx.loginAccount.isBlank()) return
        val expected = PhoneLoginFormat.normalizeAccountInput(ctx.loginAccount)
        if (!expected.equals(effectiveAccount, ignoreCase = true)) {
            reauthContext = null
            errorMessage = null
            isReauthNotice = false
        }
    }

    fun login() {
        if (!canSubmit) return
        val currentMode = accountMode
        if (currentMode == LoginAccountMode.PHONE && !PhoneLoginFormat.isValidPhone(dialCode, phoneNumber)) {
            isReauthNotice = false
            errorMessage = tr(
                "请输入有效的手机号码",
                "Please enter a valid phone number",
                "有効な電話番号を入力してください",
                "유효한 전화번호를 입력해 주세요",
                "請輸入有效的手機號碼",
            )
            return
        }
        isLoggingIn = true
        isReauthNotice = false
        errorMessage = null
        val submittedAccount = effectiveAccount
        val submittedPassword = password
        val expectedAccount = reauthContext?.storageKey
        scope.launch {
            try {
                clientManager.login(submittedAccount, expectedAccount = expectedAccount) { submittedPassword }
                    .onFailure {
                        isReauthNotice = false
                        errorMessage = friendlyPasswordError(it, currentMode)
                    }
            } finally {
                isLoggingIn = false
            }
        }
    }

    /** 申请设备授权码并在系统默认浏览器中打开官方 Google 登录页，随后在后台轮询确认结果。 */
    fun startGoogleLogin(openUrl: (String) -> Unit) {
        if (isBusy) return
        isReauthNotice = false
        errorMessage = null
        browserAuth = BrowserAuthState.Starting
        val expectedAccount = reauthContext?.storageKey
        googleAuthJob?.cancel()
        googleAuthJob = scope.launch {
            val tx = clientManager.startGoogleDeviceLogin(expectedAccount = expectedAccount).getOrElse { err ->
                if (err !is CancellationException) {
                    browserAuth = BrowserAuthState.Idle
                    isReauthNotice = false
                    errorMessage = friendlyGoogleError(err)
                }
                return@launch
            }
            val nowSec = Clock.System.now().epochSeconds
            browserAuth = BrowserAuthState.AwaitingConfirmation(
                txId = tx.txId,
                userCode = tx.authorization.userCode,
                googleAuthUrl = tx.googleAuthUrl,
                qrLoginUrl = tx.qrLoginUrl,
                expiresAtEpochSec = nowSec + tx.authorization.expiresInSeconds,
            )
            runCatching { openUrl(tx.googleAuthUrl) }.onFailure {
                isReauthNotice = false
                errorMessage = tr(
                    "未能自动打开浏览器，请点击「复制登录链接」到系统浏览器中打开",
                    "Could not open the browser automatically. Copy the sign-in link and open it in your browser.",
                    "ブラウザを自動で開けませんでした。「ログインリンクをコピー」してブラウザで開いてください",
                    "브라우저를 자동으로 열지 못했습니다. '로그인 링크 복사'를 눌러 브라우저에서 열어 주세요",
                    "未能自動開啟瀏覽器，請點選「複製登入連結」至系統瀏覽器中開啟",
                )
            }
            clientManager.awaitGoogleDeviceLogin(tx)
                .onSuccess {
                    browserAuth = BrowserAuthState.Idle
                }
                .onFailure { err ->
                    if (err !is CancellationException) {
                        browserAuth = BrowserAuthState.Idle
                        isReauthNotice = false
                        errorMessage = friendlyGoogleError(err)
                    }
                }
        }
    }

    fun reopenBrowser(openUrl: (String) -> Unit) {
        val waiting = browserAuth as? BrowserAuthState.AwaitingConfirmation ?: return
        runCatching { openUrl(waiting.googleAuthUrl) }
    }

    fun cancelGoogleLogin() {
        val waiting = browserAuth as? BrowserAuthState.AwaitingConfirmation
        googleAuthJob?.cancel()
        googleAuthJob = null
        clientManager.cancelGoogleDeviceLogin(waiting?.txId)
        browserAuth = BrowserAuthState.Idle
    }

    private fun friendlyPasswordError(err: Throwable, mode: LoginAccountMode): String = when (err) {
        is AccountMismatchException, is IllegalArgumentException -> err.message ?: defaultPasswordError()
        is PikPakException -> {
            val serverMsg = err.errorMessage.lowercase()
            when {
                err.isCaptchaRequired || "captcha" in serverMsg -> tr(
                    "需要完成安全验证，请稍后重试或使用 Google 继续",
                    "Security verification is required. Try again shortly or continue with Google.",
                    "セキュリティ確認が必要です。しばらくしてから再試行するか Google で続行してください",
                    "보안 인증이 필요합니다. 잠시 후 다시 시도하거나 Google로 계속해 주세요",
                    "需要完成安全驗證，請稍後重試或使用 Google 繼續",
                )
                err.httpStatus == 429 || "too_many" in serverMsg || "rate_limit" in serverMsg || "frequent" in serverMsg -> tr(
                    "尝试次数过多，请稍后再试",
                    "Too many attempts. Please try again later.",
                    "試行回数が多すぎます。しばらくしてから再試行してください",
                    "시도 횟수가 너무 많습니다. 잠시 후 다시 시도해 주세요",
                    "嘗試次數過多，請稍後再試",
                )
                (err.httpStatus ?: 0) >= 500 -> tr(
                    "PikPak 服务暂时不可用，请稍后重试",
                    "PikPak service is temporarily unavailable. Please try again later.",
                    "PikPak サービスが一時的に利用できません。しばらくしてから再試行してください",
                    "PikPak 서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요",
                    "PikPak 服務暫時無法使用，請稍後重試",
                )
                err.errorCode == 4002 || serverMsg in CREDENTIAL_ERROR_CODES -> {
                    if (mode == LoginAccountMode.PHONE) {
                        tr(
                            "手机号或密码不正确；若该手机号尚未设置密码，请先在 PikPak 官方客户端设置密码或使用 Google 登录",
                            "Incorrect phone number or password. If this number has no password set yet, set one in the official PikPak app or continue with Google.",
                            "電話番号またはパスワードが正しくありません。パスワード未設定の場合は公式アプリで設定するか Google でログインしてください",
                            "전화번호 또는 비밀번호가 올바르지 않습니다. 비밀번호를 설정하지 않았다면 공식 앱에서 설정하거나 Google로 로그인해 주세요",
                            "手機號碼或密碼不正確；若該門號尚未設定密碼，請先於 PikPak 官方應用程式設定密碼或使用 Google 登入",
                        )
                    } else {
                        tr(
                            "账号或密码不正确，请核对后重试",
                            "Incorrect account or password. Please check and try again.",
                            "アカウントまたはパスワードが正しくありません",
                            "계정 또는 비밀번호가 올바르지 않습니다. 다시 확인해 주세요",
                            "帳號或密碼不正確，請核對後重試",
                        )
                    }
                }
                else -> PikoErrorMapper.reason(err.errorMessage) ?: defaultPasswordError()
            }
        }
        is IOException -> tr(
            "网络连接失败，请检查网络或代理设置后重试",
            "Network connection failed. Check your connection or proxy settings and try again.",
            "ネットワーク接続に失敗しました。接続またはプロキシ設定を確認して再試行してください",
            "네트워크 연결에 실패했습니다. 네트워크 또는 프록시 설정을 확인한 후 다시 시도해 주세요",
            "網路連線失敗，請檢查網路或代理設定後重試",
        )
        else -> defaultPasswordError()
    }

    private fun friendlyGoogleError(err: Throwable): String = when (err) {
        is AccountMismatchException -> err.message ?: defaultGoogleError()
        is DeviceAuthorizationException -> when (err.reason) {
            DeviceAuthorizationFailureReason.EXPIRED_TOKEN -> tr(
                "浏览器确认已超时，请重新点击「使用 Google 继续」",
                "Browser confirmation timed out. Please select “Continue with Google” again.",
                "ブラウザでの確認がタイムアウトしました。もう一度「Google で続行」を選択してください",
                "브라우저 확인 시간이 초과되었습니다. 'Google로 계속하기'를 다시 눌러 주세요",
                "瀏覽器確認已逾時，請重新點選「使用 Google 繼續」",
            )
            DeviceAuthorizationFailureReason.ACCESS_DENIED -> tr(
                "已在浏览器中取消登录授权",
                "Sign-in authorization was cancelled in the browser",
                "ブラウザでログイン承認がキャンセルされました",
                "브라우저에서 로그인 승인이 취소되었습니다",
                "已在瀏覽器中取消登入授權",
            )
            else -> err.message ?: defaultGoogleError()
        }
        else -> err.message ?: defaultGoogleError()
    }

    private companion object {
        val CREDENTIAL_ERROR_CODES = setOf(
            "invalid_account_or_password",
            "invalid_credentials",
            "password_error",
            "invalid_password",
            "account_not_found",
            "user_not_found",
        )

        fun defaultPasswordError() = tr(
            "登录失败，请检查账号密码",
            "Sign-in failed. Check your account and password",
            "ログインに失敗しました。アカウントとパスワードを確認してください",
            "로그인 실패. 계정과 비밀번호를 확인해 주세요",
            "登入失敗，請檢查帳號密碼",
        )

        fun expiredPasswordMessage() = tr(
            "登录已失效，请重新输入密码或使用 Google 登录",
            "Session expired. Enter your password again or continue with Google.",
            "セッションの有効期限が切れました。パスワードを再入力するか Google でログインしてください",
            "세션이 만료되었습니다. 비밀번호를 다시 입력하거나 Google로 로그인해 주세요",
            "登入已失效，請重新輸入密碼或使用 Google 登入",
        )

        fun expiredGoogleMessage(name: String) = tr(
            "「$name」登录已过期，请点击下方「使用 Google 继续」重新授权",
            "Session for “$name” expired. Select “Continue with Google” below to sign in again.",
            "「$name」のセッションが切れました。下の「Google で続行」から再認証してください",
            "“$name” 세션이 만료되었습니다. 아래 'Google로 계속하기'를 눌러 다시 인증해 주세요",
            "「$name」登入已過期，請點選下方「使用 Google 繼續」重新授權",
        )

        fun defaultGoogleError() = tr(
            "Google 登录未完成，请检查网络或代理后重试",
            "Google sign-in could not be completed. Check your connection or proxy and try again.",
            "Google ログインを完了できませんでした。通信環境やプロキシを確認して再試行してください",
            "Google 로그인을 완료하지 못했습니다. 네트워크 또는 프록시를 확인한 후 다시 시도해 주세요",
            "Google 登入未完成，請檢查網路或代理後重試",
        )
    }
}
