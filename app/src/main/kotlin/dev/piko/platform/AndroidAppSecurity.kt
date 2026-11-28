package dev.piko.platform

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.AppSecurityController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 端应用锁与最近任务隐私保护控制器。
 *
 * 使用设备原生 KeyguardManager / BiometricPrompt（无额外第三方依赖），偏好独立保存在
 * 设备本地 SharedPreferences 中，不跨设备同步。
 */
class AndroidAppSecurity(
    private val context: Context,
) : AppSecurityController {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    override val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _lockOnScreenOffOnly = MutableStateFlow(prefs.getBoolean(KEY_LOCK_ON_SCREEN_OFF_ONLY, false))
    override val lockOnScreenOffOnly: StateFlow<Boolean> = _lockOnScreenOffOnly.asStateFlow()

    private val _hideInRecents = MutableStateFlow(prefs.getBoolean(KEY_HIDE_IN_RECENTS, false))
    override val hideInRecents: StateFlow<Boolean> = _hideInRecents.asStateFlow()

    private val _locked = MutableStateFlow(_appLockEnabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && _appLockEnabled.value) {
                _locked.value = true
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { err ->
            PikoLog.w(TAG, "Failed to register screen-off receiver for app lock", err)
        }
    }

    override fun canUseDeviceLock(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isDeviceSecure
    }

    override fun setAppLockEnabled(enabled: Boolean): Boolean {
        if (enabled && !canUseDeviceLock()) return false
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLockEnabled.value = enabled
        if (!enabled) {
            _locked.value = false
        }
        return true
    }

    override fun setLockOnScreenOffOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ON_SCREEN_OFF_ONLY, enabled).apply()
        _lockOnScreenOffOnly.value = enabled
    }

    override fun setHideInRecents(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_IN_RECENTS, enabled).apply()
        _hideInRecents.value = enabled
    }

    fun applyWindowSecurity(activity: Activity) {
        val secure = _hideInRecents.value || _appLockEnabled.value
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!secure)
        }
    }

    fun onAppBackgrounded() {
        if (_appLockEnabled.value && !_lockOnScreenOffOnly.value) {
            _locked.value = true
        }
    }

    fun onAppForegrounded() {
        if (!_appLockEnabled.value) {
            _locked.value = false
        }
    }

    fun markUnlocked() {
        _locked.value = false
    }

    /**
     * 触发系统生物识别或锁屏凭据解锁。
     * API 30+ 直接使用系统 BiometricPrompt（支持生物识别 + PIN/图案/密码），
     * API 26-29 返回 false 由 Activity 通过 KeyguardManager.createConfirmDeviceCredentialIntent 兜底。
     */
    fun promptBiometricUnlock(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val title = tr("解锁应用", "Unlock app", "ロックを解除", "앱 잠금 해제", "解鎖應用程式")
            val subtitle = tr(
                "请验证身份以继续访问",
                "Verify your identity to continue",
                "続行するには本人確認を行ってください",
                "계속하려면 본인 인증을 완료하세요",
                "請驗證身分以繼續存取",
            )
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(
                CancellationSignal(),
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        markUnlocked()
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString?.toString().orEmpty())
                        }
                    }
                },
            )
            true
        }.onFailure { err ->
            PikoLog.w(TAG, "BiometricPrompt failed; falling back to keyguard intent", err)
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "AppSecurity"
        const val PREFS_NAME = "piko_app_security"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_LOCK_ON_SCREEN_OFF_ONLY = "app_lock_on_screen_off_only"
        const val KEY_HIDE_IN_RECENTS = "hide_in_recents"
    }
}
