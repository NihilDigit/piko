package dev.piko.platform

import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.AppDisguiseOption
import dev.piko.ui.platform.AppSecurityController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 端应用锁、最近任务隐私遮罩（FLAG_SECURE）与桌面图标伪装控制器。
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

    private val _hideInRecents = MutableStateFlow(prefs.getBoolean(KEY_HIDE_IN_RECENTS, false))
    override val hideInRecents: StateFlow<Boolean> = _hideInRecents.asStateFlow()

    private val _activeDisguiseId = MutableStateFlow(resolveActiveAliasId())
    override val activeDisguiseId: StateFlow<String> = _activeDisguiseId.asStateFlow()

    private val _locked = MutableStateFlow(_appLockEnabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAtElapsedMs: Long = 0L

    override val disguiseOptions: List<AppDisguiseOption>
        get() = ALIASES.map { alias ->
            AppDisguiseOption(
                id = alias.id,
                title = alias.title(),
                description = alias.description(),
            )
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
            backgroundedAtElapsedMs = 0L
        }
        return true
    }

    override fun setHideInRecents(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_IN_RECENTS, enabled).apply()
        _hideInRecents.value = enabled
    }

    override fun selectDisguise(id: String): Boolean {
        val target = ALIASES.firstOrNull { it.id == id } ?: return false
        val pm = context.packageManager
        val pkg = context.packageName
        return runCatching {
            // 先启用目标入口，再禁用其他入口，避免桌面图标短时全部消失。
            // Manifest 中组件类名按固定 namespace（dev.piko）解析，与 debug applicationId（dev.piko.debug）区分。
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "$COMPONENT_NAMESPACE.${target.aliasClassName}"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (alias in ALIASES) {
                if (alias.id == target.id) continue
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "$COMPONENT_NAMESPACE.${alias.aliasClassName}"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            prefs.edit().putString(KEY_DISGUISE_ID, target.id).apply()
            _activeDisguiseId.value = target.id
            true
        }.onFailure { err ->
            PikoLog.w(TAG, "Failed to switch launcher alias to $id", err)
        }.getOrDefault(false)
    }

    fun applyWindowSecurity(activity: Activity) {
        if (_hideInRecents.value || _appLockEnabled.value) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun onAppBackgrounded() {
        if (_appLockEnabled.value) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun onAppForegrounded() {
        if (!_appLockEnabled.value) {
            _locked.value = false
            return
        }
        if (backgroundedAtElapsedMs > 0L) {
            val awayMs = SystemClock.elapsedRealtime() - backgroundedAtElapsedMs
            if (awayMs >= RELOCK_GRACE_MS) {
                _locked.value = true
            }
            backgroundedAtElapsedMs = 0L
        }
    }

    fun markUnlocked() {
        _locked.value = false
        backgroundedAtElapsedMs = 0L
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

    private fun resolveActiveAliasId(): String {
        val pm = context.packageManager
        val pkg = context.packageName
        for (alias in ALIASES) {
            val state = runCatching {
                pm.getComponentEnabledSetting(ComponentName(pkg, "$COMPONENT_NAMESPACE.${alias.aliasClassName}"))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return alias.id
            }
        }
        return prefs.getString(KEY_DISGUISE_ID, "default") ?: "default"
    }

    private data class LauncherAlias(
        val id: String,
        val aliasClassName: String,
        val title: () -> String,
        val description: () -> String,
    )

    private companion object {
        const val TAG = "AppSecurity"
        const val COMPONENT_NAMESPACE = "dev.piko"
        const val PREFS_NAME = "piko_app_security"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_HIDE_IN_RECENTS = "hide_in_recents"
        const val KEY_DISGUISE_ID = "disguise_id"
        const val RELOCK_GRACE_MS = 15_000L

        val ALIASES = listOf(
            LauncherAlias(
                id = "default",
                aliasClassName = "MainActivityDefault",
                title = { tr("Piko（默认蓝）", "Piko (Default Blue)", "Piko（既定ブルー）", "Piko (기본 블루)", "Piko（預設藍）") },
                description = { tr("默认图标与名称", "Default icon and name", "既定のアイコンと名前", "기본 아이콘 및 이름", "預設圖示與名稱") },
            ),
            LauncherAlias(
                id = "emerald",
                aliasClassName = "MainActivityEmerald",
                title = { tr("Piko（常磐绿）", "Piko (Emerald)", "Piko（常磐グリーン）", "Piko (에메랄드)", "Piko（常磐綠）") },
                description = { tr("深绿色背景 Piko 图标", "Emerald background Piko icon", "深緑背景の Piko アイコン", "에메랄드 배경 Piko 아이콘", "深綠色背景 Piko 圖示") },
            ),
            LauncherAlias(
                id = "obsidian",
                aliasClassName = "MainActivityObsidian",
                title = { tr("Piko（墨曜灰）", "Piko (Obsidian)", "Piko（オブシディアン）", "Piko (옵시디언)", "Piko（墨曜灰）") },
                description = { tr("低调深灰背景 Piko 图标", "Muted dark gray Piko icon", "落ち着いたダークグレー背景の Piko アイコン", "차분한 다크 그레이 Piko 아이콘", "低調深灰背景 Piko 圖示") },
            ),
            LauncherAlias(
                id = "todo",
                aliasClassName = "MainActivityTodo",
                title = { tr("伪装：待办（Todo）", "Disguise: Todo", "偽装：Todo", "위장: Todo", "偽裝：待辦（Todo）") },
                description = { tr("显示为清单图标与名称 Todo", "Appears as a checklist icon named Todo", "チェックリストアイコンと名前「Todo」で表示", "체크리스트 아이콘과 이름 Todo로 표시", "顯示為清單圖示與名稱 Todo") },
            ),
            LauncherAlias(
                id = "notes",
                aliasClassName = "MainActivityNotes",
                title = { tr("伪装：笔记（Notes）", "Disguise: Notes", "偽装：Notes", "위장: Notes", "偽裝：筆記（Notes）") },
                description = { tr("显示为便签图标与名称 Notes", "Appears as a notebook icon named Notes", "ノートアイコンと名前「Notes」で表示", "노트 아이콘과 이름 Notes로 표시", "顯示為筆記圖示與名稱 Notes") },
            ),
        )
    }
}
