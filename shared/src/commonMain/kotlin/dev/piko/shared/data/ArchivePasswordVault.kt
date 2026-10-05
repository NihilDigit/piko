package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 解压用过的密码，存在本机的机密存储里，最近用过的在前；开着设置同步时经 ArchivePasswordSync 加密带到别的设备。
 *
 * 每个密码记着最后一次用到或删掉的时刻，删掉的留一条墓碑：两台设备合并时逐个比时刻，
 * 一台上删掉的不会被另一台的旧记录带回来。
 *
 * 只供输密码时挑选，不拿去逐个试：多个密码逐个提交，每次都是一个真实的解压请求。
 * 无状态，设置页与解压会话各自建一个即可，数据都在偏好里。
 */
class ArchivePasswordVault(
    private val preferences: PikoUserPreferences,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** 连同墓碑的全部记录，同步用。 */
    val entries: Flow<Map<String, ArchivePasswordEntry>> = preferences.archivePasswordsFlow.map(::decodeArchivePasswords)

    val passwords: Flow<List<String>> = entries.map { all ->
        all.filterValues { !it.removed }.entries.sortedByDescending { it.value.at }.map { it.key }
    }

    suspend fun remember(password: String) {
        if (password.isEmpty()) return
        update { it + (password to ArchivePasswordEntry(stampAfter(it))) }
    }

    suspend fun forget(password: String) {
        update { if (password in it) it + (password to ArchivePasswordEntry(stampAfter(it), removed = true)) else it }
    }

    // 同一毫秒里连着记两个时，后记的仍排在前面
    private fun stampAfter(entries: Map<String, ArchivePasswordEntry>): Long =
        maxOf(now(), (entries.values.maxOfOrNull { it.at } ?: 0L) + 1)

    suspend fun current(): Map<String, ArchivePasswordEntry> = entries.first()

    /** 换成合并后的整份记录，见 [mergeArchivePasswords]。 */
    suspend fun replace(entries: Map<String, ArchivePasswordEntry>) {
        preferences.saveArchivePasswords(encodeArchivePasswords(entries))
    }

    private suspend fun update(change: (Map<String, ArchivePasswordEntry>) -> Map<String, ArchivePasswordEntry>) {
        replace(pruneArchivePasswords(change(current())))
    }

    companion object {
        /** 挑选时一屏看得完；再旧的多半是一次性的，挤出去也无妨。墓碑另留同样多条。 */
        const val MAX_PASSWORDS = 30
    }
}

/** [at] 是最后一次用到或删掉的时刻，毫秒。 */
@Serializable
data class ArchivePasswordEntry(val at: Long, val removed: Boolean = false)

/** 逐个密码取较新的一条；同一时刻删掉的赢，两边才会收敛到同一份。结果已裁到上限。 */
fun mergeArchivePasswords(
    a: Map<String, ArchivePasswordEntry>,
    b: Map<String, ArchivePasswordEntry>,
): Map<String, ArchivePasswordEntry> {
    val merged = (a.keys + b.keys).associateWith { key ->
        listOfNotNull(a[key], b[key]).maxWith(compareBy({ it.at }, { it.removed }))
    }
    return pruneArchivePasswords(merged)
}

// 墓碑也有上限：被裁掉的墓碑若在别的设备上还是一条更旧的有效记录，会被带回来。只在删了三十个以上时发生，可以接受
private fun pruneArchivePasswords(entries: Map<String, ArchivePasswordEntry>): Map<String, ArchivePasswordEntry> {
    val (removed, live) = entries.entries.partition { it.value.removed }
    fun List<Map.Entry<String, ArchivePasswordEntry>>.newest() =
        sortedByDescending { it.value.at }.take(ArchivePasswordVault.MAX_PASSWORDS)
    return (live.newest() + removed.newest()).associate { it.key to it.value }
}

internal fun encodeArchivePasswords(entries: Map<String, ArchivePasswordEntry>): String =
    json.encodeToString(entriesSerializer, entries)

/**
 * 1.1.0 存的是最近的在前的密码列表，读成按先后递减的极小时刻：保住原来的顺序，又比之后任何一次真实的记录都旧。
 * 内容损坏时当作空表，不让一份坏数据挡住解压。
 */
internal fun decodeArchivePasswords(serialized: String): Map<String, ArchivePasswordEntry> {
    if (serialized.isBlank()) return emptyMap()
    runCatching { return json.decodeFromString(entriesSerializer, serialized) }
    val legacy = runCatching { json.decodeFromString(legacySerializer, serialized) }.getOrDefault(emptyList())
    return legacy.withIndex().associate { (index, password) -> password to ArchivePasswordEntry((legacy.size - index).toLong()) }
}

private val json = Json { ignoreUnknownKeys = true }
private val entriesSerializer = MapSerializer(String.serializer(), ArchivePasswordEntry.serializer())
private val legacySerializer = ListSerializer(String.serializer())
