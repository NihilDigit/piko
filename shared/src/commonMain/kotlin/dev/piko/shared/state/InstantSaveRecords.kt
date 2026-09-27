package dev.piko.shared.state

import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

/** 一次成功的秒传。 */
@Serializable
data class InstantSaveRecord(
    val id: String,
    val account: String,
    /** 只存一个视频（连同字幕）时是视频的文件名，存进新建文件夹时是文件夹名。 */
    val name: String,
    val fileCount: Int,
    val totalBytes: Long,
    val targetName: String,
    /** 点按时在网盘里定位的条目：那个视频，或新建的文件夹。 */
    val locateId: String,
    val createdAtMs: Long,
)

/**
 * 秒传的记录，给传输页列出。秒传当场完成，服务端不留任务，不记下来的话传输页里什么也看不到，
 * 与离线、上传的结果不在一处。
 *
 * 存在缓存里而不是偏好里：丢了只是传输页少几行历史，文件本身在网盘里，不值得为它在两端各加一项偏好。
 * 与云端已完成的任务一样只留最近 [RETENTION] 内的。
 */
class InstantSaveRecords(
    private val clientProvider: PikoClientProvider,
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _records = MutableStateFlow<List<InstantSaveRecord>>(emptyList())

    /** 全部账号的记录，新的在前。界面按当前账号过滤。 */
    val records: StateFlow<List<InstantSaveRecord>> = _records.asStateFlow()

    // 读回之前不写：先写的话，磁盘上的旧记录会被只含新记录的列表盖掉
    private val restored = CompletableDeferred<Unit>()
    private val writeLock = Mutex()

    init {
        scope.launch {
            try {
                restore()
            } finally {
                restored.complete(Unit)
            }
        }
    }

    fun add(name: String, fileCount: Int, totalBytes: Long, targetName: String, locateId: String) {
        val account = clientProvider.currentClient.value?.account ?: return
        val record = InstantSaveRecord(
            id = "${now()}-${Random.nextInt(0, Int.MAX_VALUE)}",
            account = account,
            name = name,
            fileCount = fileCount,
            totalBytes = totalBytes,
            targetName = targetName,
            locateId = locateId,
            createdAtMs = now(),
        )
        mutate { listOf(record) + it }
    }

    /** 只删记录，文件留在网盘里。 */
    fun remove(id: String) = mutate { records -> records.filterNot { it.id == id } }

    private fun mutate(transform: (List<InstantSaveRecord>) -> List<InstantSaveRecord>) {
        _records.update { transform(it).take(MAX_RECORDS) }
        val store = store ?: return
        scope.launch {
            restored.await()
            writeLock.withLock { store.write(KEY, json.encodeToString(serializer, _records.value)) }
        }
    }

    private suspend fun restore() {
        val raw = store?.read(KEY)?.takeIf { it.isNotBlank() } ?: return
        val saved = runSuspendCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        val cutoff = now() - RETENTION.inWholeMilliseconds
        _records.update { current ->
            val known = current.mapTo(HashSet()) { it.id }
            (current + saved.filter { it.createdAtMs >= cutoff && it.id !in known }).take(MAX_RECORDS)
        }
    }

    companion object {
        /** 与传输页里云端已完成任务的展示窗口相同。 */
        val RETENTION = TransfersState.COMPLETED_CLOUD_WINDOW
        private const val MAX_RECORDS = 200
        private const val KEY = "instant-saves.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(InstantSaveRecord.serializer())
    }
}
