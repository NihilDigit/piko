package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 最近移动到过的目录，最近的在前。存完整路径而不只是目标：选择器点一下就要进到那一层，
 * 面包屑与底栏的「目标位置」都靠这条路径。
 *
 * 读出时按仓库的文件夹索引改正（见 FolderIndex.resolve）：改了名的换名字，移走的换上级，Piko 里删掉的不再列出。
 * 存着的路径不改写，只在索引还不认识时回落；为此每次打开都去服务端校验一遍不值得。
 *
 * 写入放在进程级的 [scope] 里：确认移动的同时对话框就关了，界面的协程作用域随之取消，
 * 在那里写偏好常常写不完。
 */
class MoveHistory(
    private val preferences: PikoUserPreferences,
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    val targets: Flow<List<List<PikoPathBreadcrumb>>> =
        combine(preferences.recentMoveTargetsFlow.map(::decodeTargets), driveRepo.folderIndex.flow) { targets, _ ->
            val index = driveRepo.folderIndex
            // 截短了的是停在删掉的文件夹里，那个目标已经去不了
            targets.mapNotNull { path -> index.resolve(path).takeIf { it.last().id == path.last().id } }
        }

    fun remember(path: List<PikoPathBreadcrumb>) {
        val target = path.lastOrNull() ?: return
        scope.launch {
            // 两次移动挨得很近时，读改写要串行，否则后一次会盖掉前一次
            lock.withLock { save(listOf(path) + current().filterNot { it.lastOrNull()?.id == target.id }) }
        }
    }

    private val lock = Mutex()

    private suspend fun current(): List<List<PikoPathBreadcrumb>> = decodeTargets(preferences.recentMoveTargetsFlow.first())

    private suspend fun save(targets: List<List<PikoPathBreadcrumb>>) {
        val stored = targets.take(MAX_TARGETS).map { path -> path.map { StoredCrumb(it.id, it.name) } }
        preferences.saveRecentMoveTargets(json.encodeToString(serializer, stored))
    }

    companion object {
        /** 一行 chip 放得下、横向滑一下能看完的数量。 */
        const val MAX_TARGETS = 8
    }
}

@Serializable
private data class StoredCrumb(val id: String, val name: String)

private val json = Json { ignoreUnknownKeys = true }
private val serializer = ListSerializer(ListSerializer(StoredCrumb.serializer()))

// 内容损坏时当作空表，不让一份坏数据挡住移动
private fun decodeTargets(serialized: String): List<List<PikoPathBreadcrumb>> =
    if (serialized.isBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(serializer, serialized) }.getOrDefault(emptyList())
            .map { path -> path.map { PikoPathBreadcrumb(it.id, it.name) } }
            .filter { it.isNotEmpty() }
    }
