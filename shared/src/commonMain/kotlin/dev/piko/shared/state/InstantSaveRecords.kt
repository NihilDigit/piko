package dev.piko.shared.state

import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _attempts = MutableStateFlow<List<InstantSaveAttempt>>(emptyList())
    val attempts: StateFlow<List<InstantSaveAttempt>> = _attempts.asStateFlow()
    private val _outcomes = MutableSharedFlow<InstantSaveAttemptOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()

    fun beginAttempt(account: String, title: String, target: dev.piko.shared.data.PikoPathBreadcrumb,
        files: List<io.github.nihildigit.pikpak.ResolvedFile>, id: String? = null, containerName: String? = null,
        names: Map<String, String> = emptyMap(), keepStructure: Boolean = false): String? {
        if (clientProvider.currentClient.value?.account != account) return null
        val key = id ?: "${now()}-${Random.nextInt(0, Int.MAX_VALUE)}"
        _attempts.update { all ->
            val old = all.firstOrNull { it.id == key && it.account == account }
            val work = old?.copy(finishedAtMs = null) ?: InstantSaveAttempt(key, account, title, target, files.toList(), now(),
                containerName = containerName, names = names.toMap(), keepStructure = keepStructure)
            all.filterNot { it.id == key } + work
        }
        return key
    }

    fun finishAttempt(id: String, receipt: dev.piko.shared.data.InstantSaveReceipt) {
        if (clientProvider.currentClient.value?.account != receipt.account) return
        val work = _attempts.value.firstOrNull { it.id == id && it.account == receipt.account && it.isRunning } ?: return
        _attempts.update { all -> all.map { if (it.id == id && it.account == receipt.account) it.copy(receipt = receipt, finishedAtMs = now()) else it } }
        if (receipt.requiresInspection || receipt.failed.isNotEmpty() || receipt.notAttempted.isNotEmpty())
            _outcomes.tryEmit(InstantSaveAttemptOutcome(id, receipt.account, work.title, receipt.message))
    }

    fun forgetAttempt(id: String): Boolean {
        if (_attempts.value.any { it.id == id && it.isRunning }) return false
        _attempts.update { all -> all.filterNot { it.id == id } }
        return true
    }

    fun clearAttempts(account: String) { _attempts.update { all -> all.filterNot { it.account == account } } }

    fun observeContainer(id: String, account: String, folderId: String) {
        if (clientProvider.currentClient.value?.account != account || folderId.isBlank()) return
        _attempts.update { all -> all.map { work ->
            if (work.id == id && work.account == account && work.isRunning) work.copy(receipt =
                (work.receipt ?: dev.piko.shared.data.InstantSaveReceipt(account, folderId)).let { it.copy(folders = it.folders + ("" to folderId)) })
            else work
        } }
    }

    fun pendingConflict(account: String, targetId: String, files: List<io.github.nihildigit.pikpak.ResolvedFile>, excluding: String? = null,
        snapshot: List<InstantSaveAttempt> = _attempts.value, containerName: String? = null,
        names: Map<String, String> = emptyMap(), keepStructure: Boolean = false): Boolean {
        val outputs = files.mapTo(HashSet()) { outputOf(it, names, keepStructure) }
        return snapshot.any { work ->
            if (work.id == excluding || work.account != account) false else {
                val sameContainer = work.target.id == targetId && work.containerName == containerName
                val unknownContainer = sameContainer && containerName != null && work.receipt?.folders?.get("") == null &&
                    (work.isRunning || work.receipt?.unconfirmedFolder != null)
                unknownContainer || work.files.any { original ->
                    val blocked = work.isRunning || work.receipt?.unconfirmedFolder != null ||
                        original.path in work.receipt?.unconfirmed.orEmpty() || original.path in work.receipt?.namingUnconfirmed.orEmpty()
                    val output = outputOf(original, work.names, work.keepStructure)
                    blocked && ((sameContainer && output in outputs) || (containerName == null &&
                        physicalOutputs(work, output).any { (parent, physical) -> parent == targetId && physical in outputs }))
                }
            }
        }
    }

    private data class SaveOutput(val gcid: String?, val size: Long, val directory: String, val name: String)
    private fun outputOf(file: io.github.nihildigit.pikpak.ResolvedFile, names: Map<String, String>, keepStructure: Boolean) =
        SaveOutput(file.gcid, file.size, if (keepStructure) file.path.substringBeforeLast('/', "") else "", names[file.path] ?: file.name)

    // 逻辑容器 (父目录, 名称) 与已确认的物理目录是两种身份；进入新目录不能绕过未知回执保护。
    private fun physicalOutputs(work: InstantSaveAttempt, output: SaveOutput): List<Pair<String, SaveOutput>> {
        val folders = work.receipt?.folders.orEmpty() +
            if (work.containerName == null) mapOf("" to work.target.id) else emptyMap()
        return folders.filterKeys { it.isEmpty() || output.directory == it || output.directory.startsWith("$it/") }
            .map { (prefix, folder) -> folder to output.copy(directory = output.directory.removePrefix(prefix).trimStart('/')) }
    }

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

    fun add(name: String, fileCount: Int, totalBytes: Long, targetName: String, locateId: String,
        id: String? = null, expectedAccount: String? = clientProvider.currentClient.value?.account) {
        val account = expectedAccount?.takeIf { it == clientProvider.currentClient.value?.account } ?: return
        val record = InstantSaveRecord(
            id = id ?: "${now()}-${Random.nextInt(0, Int.MAX_VALUE)}",
            account = account,
            name = name,
            fileCount = fileCount,
            totalBytes = totalBytes,
            targetName = targetName,
            locateId = locateId,
            createdAtMs = now(),
        )
        mutate { listOf(record) + it.filterNot { old -> old.id == record.id } }
        if (id != null) _attempts.update { all -> all.filterNot { it.id == id && it.account == account } }
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
