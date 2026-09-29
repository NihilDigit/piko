package dev.piko.shared.rename

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 批量重命名记住的东西：上次执行时的选项，以及最近用过的查找串、替换串各 [MAX_RECENT] 条。
 * 照 PowerRename：只在真正执行时记，取消不记。与它不同，凡是单独就会改名的都不恢复（见 [withoutChanges]），
 * 查找与替换的文字只留在最近列表里：恢复了它们，一打开预览里就是上一批的改动，对眼前这一批多半不对。
 * PowerRename 的最近列表遇到已有的一条原地不动（源码里留着 TODO），这里把它提到最前。
 *
 * 每台设备各自的，不同步：查找串里常有具体的文件名，也只是个人的输入习惯。
 */
@Serializable
data class BatchRenameMemory(
    val options: FindReplaceOptions = FindReplaceOptions(),
    val recentSearches: List<String> = emptyList(),
    val recentReplacements: List<String> = emptyList(),
) {
    fun remember(used: FindReplaceOptions): BatchRenameMemory = BatchRenameMemory(
        options = used.withoutChanges(),
        recentSearches = pushRecent(recentSearches, used.search),
        recentReplacements = pushRecent(recentReplacements, used.replacement),
    )

    companion object {
        const val MAX_RECENT = 10

        // 内容损坏或字段改过时退回默认值，不让一份坏数据挡住重命名
        suspend fun load(preferences: PikoUserPreferences): BatchRenameMemory {
            val serialized = preferences.batchRenameFlow.first()
            if (serialized.isBlank()) return BatchRenameMemory()
            return runCatching { json.decodeFromString(serializer(), serialized) }.getOrDefault(BatchRenameMemory())
        }

        suspend fun save(preferences: PikoUserPreferences, memory: BatchRenameMemory) {
            preferences.saveBatchRename(json.encodeToString(serializer(), memory))
        }

        private fun pushRecent(list: List<String>, entry: String): List<String> =
            if (entry.isEmpty()) list else (listOf(entry) + list.filterNot { it == entry }).take(MAX_RECENT)

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}

/**
 * 去掉单独就会改名的选项：查找、替换与大小写格式。剩下的（正则、区分大小写、全部替换、范围、日期取自）
 * 不配上查找串或大小写格式就不改任何名称，恢复了也不会让预览一打开就有改动。
 */
fun FindReplaceOptions.withoutChanges(): FindReplaceOptions = copy(search = "", replacement = "", textCase = TextCase.NONE)
