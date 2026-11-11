package dev.piko.shared.state

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 归档对话框的三项操作勾选与风险提示确认标记（共四个字段），按上次的选择打开。
 * 哪些文件进入归档由 [sourcedOnly] 与 [skipSmallFiles] 决定，原文件处置方式由 [toTrash] 决定，
 * [riskNoticeAcknowledged] 记录是否已确认风险提示。
 *
 * 默认只归档有来源记录的（磁力、分享链接）：其余的云端失效后没有链接可凭，找不回来。
 * 默认放入回收站：取消勾选即永久删除原文件，用户自己选过一次之后才这样记住。
 * 是偏好，勾选一变就存，不等归档。每台设备各自的，不同步。
 */
@Serializable
data class VaultArchiveOptions(
    val sourcedOnly: Boolean = true,
    val toTrash: Boolean = true,
    val skipSmallFiles: Boolean = false,
    val riskNoticeAcknowledged: Boolean = false,
) {
    companion object {
        private const val TAG = "VaultOptions"

        // 内容损坏时退回默认值，不让一份坏数据挡住归档
        suspend fun load(preferences: PikoUserPreferences): VaultArchiveOptions {
            val serialized = preferences.vaultArchiveOptionsFlow.first()
            if (serialized.isBlank()) return VaultArchiveOptions()
            return runSuspendCatching { json.decodeFromString(serializer(), serialized) }
                .logFailure(TAG, "Failed to decode vault archive options; falling back to defaults")
                .getOrDefault(VaultArchiveOptions())
        }

        suspend fun save(preferences: PikoUserPreferences, options: VaultArchiveOptions) {
            preferences.saveVaultArchiveOptions(json.encodeToString(serializer(), options))
        }

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
