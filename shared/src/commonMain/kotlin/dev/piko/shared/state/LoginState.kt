package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 账号密码登录，两端共用。
 *
 * 登录成功不需要回调：PikoClientManager.currentClient 随之变为非空，两端的根视图
 * 都据此切到主界面。错误是长驻的说明文字，改动输入后仍保留，直到下一次提交。
 */
class LoginState(
    private val clientManager: PikoClientManager,
    private val scope: CoroutineScope,
    initialAccount: String = "",
) {
    var account by mutableStateOf(initialAccount)
        private set
    var password by mutableStateOf("")
        private set
    var isLoggingIn by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val canSubmit: Boolean by derivedStateOf {
        !isLoggingIn && account.isNotBlank() && password.isNotEmpty()
    }

    fun updateAccount(value: String) {
        account = value
    }

    fun updatePassword(value: String) {
        password = value
    }

    fun dismissError() {
        errorMessage = null
    }

    /**
     * 登录页列出的已保存账号：会话还有效就直接进去；失效了又没存密码时填上账号名，等用户输密码。
     */
    fun useSaved(saved: String, onExpired: () -> Unit = {}) {
        if (isLoggingIn) return
        isLoggingIn = true
        usingSaved = saved
        errorMessage = null
        scope.launch {
            try {
                clientManager.switchTo(saved).onFailure {
                    account = saved
                    errorMessage = "登录已失效，请输入密码"
                    onExpired()
                }
            } finally {
                isLoggingIn = false
                usingSaved = null
            }
        }
    }

    /** 正在经 [useSaved] 进入的已保存账号，界面在那一行上转圈。 */
    var usingSaved by mutableStateOf<String?>(null)
        private set

    fun login() {
        if (!canSubmit) return
        isLoggingIn = true
        errorMessage = null
        val submittedPassword = password
        scope.launch {
            try {
                clientManager.login(account.trim()) { submittedPassword }
                    .onFailure { errorMessage = it.message ?: "登录失败，请检查账号密码" }
            } finally {
                isLoggingIn = false
            }
        }
    }
}
