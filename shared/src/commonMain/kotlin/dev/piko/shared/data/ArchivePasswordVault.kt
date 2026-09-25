package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 解压用过的密码，存在本机，最近用过的在前。
 *
 * 只供输密码时挑选，不拿去逐个试：多个密码逐个提交，每次都是一个真实的解压请求。
 * 无状态，设置页与解压会话各自建一个即可，数据都在偏好里。
 */
class ArchivePasswordVault(private val preferences: PikoUserPreferences) {
    val passwords: Flow<List<String>> = preferences.archivePasswordsFlow.map(::decodePasswords)

    suspend fun remember(password: String) {
        if (password.isEmpty()) return
        save(listOf(password) + current().filterNot { it == password })
    }

    suspend fun forget(password: String) {
        save(current().filterNot { it == password })
    }

    private suspend fun current(): List<String> = decodePasswords(preferences.archivePasswordsFlow.first())

    private suspend fun save(passwords: List<String>) {
        preferences.saveArchivePasswords(json.encodeToString(serializer, passwords.take(MAX_PASSWORDS)))
    }

    companion object {
        /** 挑选时一屏看得完；再旧的多半是一次性的，挤出去也无妨。 */
        const val MAX_PASSWORDS = 30
    }
}

private val json = Json { ignoreUnknownKeys = true }
private val serializer = ListSerializer(String.serializer())

// 内容损坏时当作空表，不让一份坏数据挡住解压
private fun decodePasswords(serialized: String): List<String> =
    if (serialized.isBlank()) emptyList() else runCatching { json.decodeFromString(serializer, serialized) }.getOrDefault(emptyList())
