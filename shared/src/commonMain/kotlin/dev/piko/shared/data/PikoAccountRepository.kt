package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.UserProfile
import io.github.nihildigit.pikpak.getUserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 账户资料。登录态只带回 token 与 user id，昵称、头像与邮箱要单独取一次。
 *
 * 取回后写进 [PikoUserPreferences.saveProfile] 而非 saveSession：后者会无条件
 * 重写 token，用它写资料等于每次刷新都覆盖一遍登录态。
 */
class PikoAccountRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences,
) {
    suspend fun refreshProfile(): Result<UserProfile> = withContext(Dispatchers.Default) {
        val client = clientManager.currentClient.value
            ?: return@withContext Result.failure(IllegalStateException("尚未登录"))
        try {
            val profile = client.getUserProfile()
            preferences.saveProfile(
                username = profile.name,
                avatarUrl = profile.avatarUrl.orEmpty(),
                email = profile.email,
            )
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
