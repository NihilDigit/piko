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
import io.github.nihildigit.pikpak.DeviceAuthorizationException
import io.github.nihildigit.pikpak.DeviceAuthorizationFailureReason
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    var reauthContext by mutableStateOf(initialContext)
        private set

    var account by mutableStateOf(initialContext?.loginAccount.orEmpty())
        private set

    var password by mutableStateOf("")
        private set

    var isLoggingIn by mutableStateOf(false)
        private set

    var browserAuth by mutableStateOf<BrowserAuthState>(BrowserAuthState.Idle)
        private set

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

    val canSubmit: Boolean by derivedStateOf {
        !isBusy && account.isNotBlank() && password.isNotEmpty()
    }

    fun updateAccount(value: String) {
        account = value
        val ctx = reauthContext
        if (ctx != null && ctx.loginAccount.isNotBlank() && !ctx.loginAccount.equals(value.trim(), ignoreCase = true)) {
            reauthContext = null
        }
    }

    fun updatePassword(value: String) {
        password = value
    }

    fun dismissError() {
        errorMessage = null
    }

    /** 解除续期目标账号锁定，允许改登其他账号。 */
    fun clearReauthTarget() {
        reauthContext = null
        errorMessage = null
    }

    fun login() {
        if (!canSubmit) return
        isLoggingIn = true
        errorMessage = null
        val submittedAccount = account.trim()
        val submittedPassword = password
        val expectedAccount = reauthContext?.storageKey
        scope.launch {
            try {
                clientManager.login(submittedAccount, expectedAccount = expectedAccount) { submittedPassword }
                    .onFailure {
                        errorMessage = it.message ?: tr(
                            "登录失败，请检查账号密码",
                            "Sign-in failed. Check your account and password",
                            "ログインに失敗しました。アカウントとパスワードを確認してください",
                            "로그인 실패. 계정과 비밀번호를 확인해 주세요",
                            "登入失敗，請檢查帳號密碼",
                        )
                    }
            } finally {
                isLoggingIn = false
            }
        }
    }

    /** 申请设备授权码并在系统默认浏览器中打开官方 Google 登录页，随后在后台轮询确认结果。 */
    fun startGoogleLogin(openUrl: (String) -> Unit) {
        if (isBusy) return
        errorMessage = null
        browserAuth = BrowserAuthState.Starting
        val expectedAccount = reauthContext?.storageKey
        googleAuthJob?.cancel()
        googleAuthJob = scope.launch {
            val tx = clientManager.startGoogleDeviceLogin(expectedAccount = expectedAccount).getOrElse { err ->
                if (err !is CancellationException) {
                    browserAuth = BrowserAuthState.Idle
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
