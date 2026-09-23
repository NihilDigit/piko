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
) {
    var account by mutableStateOf("")
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
