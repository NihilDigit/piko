package dev.piko.shared.data

import io.github.nihildigit.pikpak.UserProfile
import io.github.nihildigit.pikpak.getUserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账户资料。登录态只带回 token 与 user id，昵称、头像与邮箱要单独取一次；取回后与空间用量一起记进
 * 账号列表（[PikoClientManager.accounts]），切换菜单与下次启动的账号卡片不必等网络。
 */
class PikoAccountRepository(
    private val clientManager: PikoClientManager,
    driveRepository: PikoDriveRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            driveRepository.quotaUpdates.collect { (account, response) ->
                clientManager.updateAccount(account) {
                    it.copy(usageBytes = response.quota.usageBytes, limitBytes = response.quota.limitBytes)
                }
            }
        }
    }

    suspend fun refreshProfile(): Result<UserProfile> = withContext(Dispatchers.Default) {
        val client = clientManager.currentClient.value
            ?: return@withContext Result.failure(IllegalStateException("尚未登录"))
        runSuspendCatching {
            client.getUserProfile().also { profile ->
                clientManager.updateAccount(client.account) {
                    it.copy(
                        name = profile.name.ifEmpty { it.name },
                        avatarUrl = profile.avatarUrl?.ifEmpty { null } ?: it.avatarUrl,
                        email = profile.email.ifEmpty { it.email },
                    )
                }
            }
        }
    }
}
