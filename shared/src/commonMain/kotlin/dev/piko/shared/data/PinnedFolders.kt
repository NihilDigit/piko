package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 固定到快速访问的文件夹，照资源管理器的「固定到快速访问」：用户自己挑，按固定的先后排，不随浏览变动。
 * PikPak 没有这项能力，存在偏好里，经设置同步带到别的设备。
 *
 * 只存 ID 与名字，不存路径：文件夹被移走后路径就旧了，打开时再按 ID 查上级。
 * 名字在 Piko 里改名时跟着改，在别处改的等下次打开时由列表对上。
 */
internal class PinnedFolders(private val preferences: PikoUserPreferences?, private val scope: CoroutineScope) {
    val flow: Flow<List<PikoPathBreadcrumb>> = preferences?.pinnedFoldersFlow?.map(::decode) ?: emptyFlow()

    // 连着点几下时读改写要串行，否则后一次会盖掉前一次
    private val lock = Mutex()

    fun pin(folder: PikoPathBreadcrumb) = edit { list -> if (list.any { it.id == folder.id }) list else list + folder }

    fun unpin(folderId: String) = edit { list -> list.filterNot { it.id == folderId } }

    fun renamed(folderId: String, name: String) = edit { list ->
        list.map { if (it.id == folderId) PikoPathBreadcrumb(it.id, name) else it }
    }

    private fun edit(change: (List<PikoPathBreadcrumb>) -> List<PikoPathBreadcrumb>) {
        val preferences = preferences ?: return
        scope.launch {
            lock.withLock {
                val current = decode(preferences.pinnedFoldersFlow.first())
                val next = change(current)
                if (next != current) {
                    preferences.savePinnedFolders(json.encodeToString(serializer, next.map { StoredPin(it.id, it.name) }))
                }
            }
        }
    }
}

@Serializable
private data class StoredPin(val id: String, val name: String)

private val json = Json { ignoreUnknownKeys = true }
private val serializer = ListSerializer(StoredPin.serializer())

// 内容损坏时当作空表，快速访问空着总比打不开网盘页强
private fun decode(serialized: String): List<PikoPathBreadcrumb> =
    if (serialized.isBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(serializer, serialized) }.getOrDefault(emptyList())
            .map { PikoPathBreadcrumb(it.id, it.name) }
    }
