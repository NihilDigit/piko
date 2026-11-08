package dev.piko.shared.data

import io.github.nihildigit.pikpak.UserProfile
import io.github.nihildigit.pikpak.getUserProfile
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账户资料。登录态只带回 token 与 user id，昵称、头像与邮箱要单独取一次；取回后与空间用量、流量额度一起记进
 * 账号列表（[PikoClientManager.accounts]），切换菜单与下次启动的账号卡片不必等网络。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PikoAccountRepository(
    private val clientManager: PikoClientManager,
    driveRepository: PikoDriveRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            driveRepository.quotaUpdates.collect { (account, response) ->
                clientManager.updateAccount(account) {
                    it.copy(
                        usageBytes = response.quota.usageBytes,
                        limitBytes = response.quota.limitBytes,
                        quota = response,
                    )
                }
            }
        }
        scope.launch {
            clientManager.currentClient.collectLatest { client ->
                if (client == null) return@collectLatest
                val account = client.account
                combine(
                    client.quotaFlow,
                    client.transferQuotaFlow,
                    client.userProfileFlow,
                ) { quota, transferQuota, profile ->
                    Triple(quota, transferQuota, profile)
                }.collect { (quota, transferQuota, profile) ->
                    if (quota == null && transferQuota == null && profile == null) return@collect
                    val confirmedQuota = quota?.takeUnless { driveRepository.hasPendingOptimisticWritesFor(account) }
                    clientManager.updateAccount(account) { saved ->
                        saved.copy(
                            name = profile?.name?.ifEmpty { saved.name } ?: saved.name,
                            avatarUrl = profile?.avatarUrl?.ifEmpty { null } ?: saved.avatarUrl,
                            email = profile?.email?.ifEmpty { saved.email } ?: saved.email,
                            usageBytes = confirmedQuota?.quota?.usageBytes ?: saved.usageBytes,
                            limitBytes = confirmedQuota?.quota?.limitBytes ?: saved.limitBytes,
                            quota = confirmedQuota ?: saved.quota,
                            transferQuota = transferQuota ?: saved.transferQuota,
                            userProfile = profile ?: saved.userProfile,
                        )
                    }
                }
            }
        }
    }

    suspend fun refreshProfile(maxAge: Duration = Duration.ZERO): Result<UserProfile> = withContext(Dispatchers.Default) {
        val client = clientManager.currentClient.value
            ?: return@withContext Result.failure(IllegalStateException("尚未登录"))
        runSuspendCatching {
            client.getUserProfile(maxAge).also { profile ->
                clientManager.updateAccount(client.account) {
                    it.copy(
                        name = profile.name.ifEmpty { it.name },
                        avatarUrl = profile.avatarUrl?.ifEmpty { null } ?: it.avatarUrl,
                        email = profile.email.ifEmpty { it.email },
                        userProfile = profile,
                    )
                }
            }
        }
    }
}
