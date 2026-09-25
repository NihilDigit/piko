package dev.piko.shared.upload

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.update.isNetworkFailure
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.PikPakHash
import io.github.nihildigit.pikpak.UploadSession
import io.github.nihildigit.pikpak.UploadStart
import io.github.nihildigit.pikpak.XunleiCid
import io.github.nihildigit.pikpak.cancelUpload
import io.github.nihildigit.pikpak.continueUpload
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.gcidByCid
import io.github.nihildigit.pikpak.startUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * 本机文件上传到网盘。一次只传一个文件，其余排队：瓶颈是本机的上行带宽，并行只会互相抢。
 *
 * 每个文件先读三段 20 KB 算 CID 去查全局索引，命中即秒传；查不到才整份计算 gcid 再真传。
 * gcid 不能省也不能随便填：服务端不校验它，填错的内容会进秒传索引（SDK 的 upload 注释有实测）。
 *
 * 真传的 OSS 会话随任务一起存盘，暂停、失败或进程被杀后都从 OSS 已收下的分片之后接着传。
 * 会话凭据 12 小时后失效，那时只能放弃已传的分片，从头再开一个会话。
 */
class PikoUploadCoordinator(
    private val clientProvider: PikoClientProvider,
    private val preferences: PikoUserPreferences,
    private val sources: PikoUploadSources,
    private val driveRepository: PikoDriveRepository,
    private val scope: CoroutineScope,
    /** Android 在这里拉起前台服务。 */
    private val onUploadStarted: (() -> Unit)? = null,
) {
    private val _tasks = MutableStateFlow<Map<String, UploadTask>>(emptyMap())

    /** 全部账号的任务。界面按当前账号过滤。 */
    val tasks: StateFlow<Map<String, UploadTask>> = _tasks.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 入队时的失败，如所选文件夹读不出来。单个任务的失败体现在任务状态上。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _pendingRequest = MutableStateFlow<UploadSelection?>(null)

    /**
     * 从应用外进来、还没选好目标目录的上传：Android 的系统分享、桌面端拖进窗口的文件。
     * 界面据此弹出目标确认框，确认后调 [enqueue]。
     */
    val pendingRequest: StateFlow<UploadSelection?> = _pendingRequest.asStateFlow()

    /** 正在跑的那个任务。暂停与删除要取消的是它的协程，不是整条队列。 */
    private val running = MutableStateFlow<Pair<String, Job>?>(null)

    init {
        scope.launch {
            restore()
            launch { persistOnStructuralChange() }
            runQueue()
        }
    }

    fun request(selection: UploadSelection) {
        if (!selection.isEmpty) _pendingRequest.value = selection
    }

    fun clearRequest() {
        _pendingRequest.value = null
    }

    /**
     * 把 [selection] 排进队列，传到 [parentId]。文件夹在这里就把目录结构在网盘里建好，
     * 其下的文件各成一个任务，排进队列时已知道自己的上级目录。
     */
    fun enqueue(selection: UploadSelection, parentId: String, parentName: String) {
        val client = clientProvider.currentClient.value
        if (client == null) {
            _messages.tryEmit("未登录")
            return
        }
        scope.launch(Dispatchers.IO) {
            val added = mutableListOf<UploadTask>()
            selection.files.forEach { uri ->
                val task = newTask(client.account, uri, parentId, parentName)
                if (task == null) _messages.tryEmit("无法读取所选文件") else added += task
            }
            selection.folders.forEach { uri ->
                val folder = sources.listFolder(uri)
                if (folder == null) {
                    _messages.tryEmit("无法读取所选文件夹")
                    return@forEach
                }
                runSuspendCatching { added += folderTasks(client, folder, parentId) }
                    .onFailure { _messages.tryEmit("未能在网盘里建立文件夹「${folder.name}」：${it.message}") }
                // 目录建好后网盘页该看到它，不必等文件传完
                driveRepository.requestRefresh()
            }
            if (added.isEmpty()) return@launch
            _tasks.update { current -> current + added.associateBy { it.taskId } }
            onUploadStarted?.invoke()
        }
    }

    /** 继续暂停的任务，或重试失败的任务：放回队尾，轮到它时从断点接着传。 */
    fun resume(taskId: String) {
        update(taskId) {
            if (it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED) {
                it.copy(status = UploadStatus.QUEUED, errorMessage = null, queuedAtMs = nowMs())
            } else {
                it
            }
        }
        onUploadStarted?.invoke()
    }

    fun pause(taskId: String) {
        cancelRunning(taskId)
        update(taskId) { if (it.status.isActive) it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else it }
    }

    /** 把所有未完成的任务转为暂停。Android 前台服务被系统叫停时用。 */
    fun pauseAll() {
        _tasks.value.values.filter { it.status.isActive }.forEach { pause(it.taskId) }
    }

    /**
     * 移除任务。未完成的一并放弃网盘里那个上传中的文件，否则它会一直挂在目录里；
     * 已完成的只删记录，文件留在网盘里。
     */
    fun remove(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        val job = cancelRunning(taskId)
        _tasks.update { it - taskId }
        releaseIfUnused(task.sourceUri)
        val session = task.session ?: return
        if (task.status == UploadStatus.COMPLETED) return
        scope.launch(Dispatchers.IO) {
            job?.join()
            val client = clientProvider.currentClient.value?.takeIf { it.account == task.account } ?: return@launch
            runSuspendCatching { client.cancelUpload(session) }
            driveRepository.requestRefresh()
        }
    }

    /** 清掉当前账号全部已完成的记录。 */
    fun clearCompleted() {
        val account = clientProvider.currentClient.value?.account ?: return
        _tasks.update { tasks -> tasks.filterValues { it.account != account || it.status != UploadStatus.COMPLETED } }
    }

    private fun newTask(account: String, uri: String, parentId: String, parentName: String): UploadTask? {
        val info = sources.describe(uri) ?: return null
        return UploadTask(
            taskId = newTaskId(),
            account = account,
            sourceUri = uri,
            fileName = info.name,
            size = info.size,
            lastModifiedMs = info.lastModifiedMs,
            parentId = parentId,
            parentName = parentName,
            createdAtMs = nowMs(),
        )
    }

    /**
     * 在 [parentId] 下按 [folder] 的结构建目录，返回其下每个文件的任务。同名目录已存在时服务端
     * 另建一个「名字(1)」，不会并进去：合并要先列出目标再逐层比对，而上传进一个新目录总是对的。
     */
    private suspend fun folderTasks(client: PikPakClient, folder: UploadFolder, parentId: String): List<UploadTask> {
        val rootId = client.createFolder(parentId, folder.name)
        val dirIds = mutableMapOf("" to rootId)
        suspend fun dirId(path: String): String = dirIds[path] ?: run {
            val parent = dirId(path.substringBeforeLast('/', ""))
            client.createFolder(parent, path.substringAfterLast('/')).also { dirIds[path] = it }
        }
        return folder.files.mapNotNull { entry ->
            val dirName = entry.relativeDir.substringAfterLast('/').ifEmpty { folder.name }
            newTask(client.account, entry.uri, dirId(entry.relativeDir), dirName)
        }
    }

    /**
     * 读回上次保存的任务表。进行中与排队的一律转为暂停，与下载一致：上次没传完的由用户决定
     * 何时继续，免得一开应用就占满上行。
     */
    private suspend fun restore() {
        val saved = runSuspendCatching {
            json.decodeFromString(taskListSerializer, preferences.loadUploadTasks())
        }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        val restored = saved.map { task ->
            if (task.status.isActive) task.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else task.copy(speedBytesPerSec = 0L)
        }
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
    }

    /**
     * 状态、gcid 或会话变化时保存整张表。后两者不等状态变化：会话一建好就要落盘，
     * 否则进程这时被杀，网盘里留下一个上传中的文件，谁也接不上它。
     */
    private suspend fun persistOnStructuralChange() {
        _tasks
            .distinctUntilChangedBy { tasks -> tasks.mapValues { Triple(it.value.status, it.value.gcid, it.value.session?.uploadId) } }
            .collect { tasks ->
                val serialized = json.encodeToString(taskListSerializer, tasks.values.toList())
                runSuspendCatching { preferences.saveUploadTasks(serialized) }
            }
    }

    /**
     * 队列：当前账号最早排进来的任务先传，传完或停下再取下一个。换号或退出登录时
     * collectLatest 取消正在传的那个，它转为暂停。
     */
    private suspend fun runQueue() {
        clientProvider.currentClient.collectLatest { client ->
            if (client == null) return@collectLatest
            while (true) {
                val next = _tasks.first { tasks -> nextQueued(tasks, client.account) != null }
                    .let { nextQueued(it, client.account)!! }
                coroutineScope {
                    val job = launch(Dispatchers.IO) { run(client, next.taskId) }
                    running.value = next.taskId to job
                    job.join()
                    running.update { if (it?.second === job) null else it }
                }
            }
        }
    }

    private fun nextQueued(tasks: Map<String, UploadTask>, account: String): UploadTask? =
        tasks.values.filter { it.account == account && it.status == UploadStatus.QUEUED }.minByOrNull { it.queuedAtMs }

    private fun cancelRunning(taskId: String): Job? {
        val (id, job) = running.value ?: return null
        if (id != taskId) return null
        job.cancel()
        return job
    }

    private suspend fun run(client: PikPakClient, taskId: String) {
        val progress = MutableStateFlow(0L)
        try {
            coroutineScope {
                val reporter = launch { reportProgress(taskId, progress) }
                upload(client, taskId, progress)
                reporter.cancel()
            }
            _tasks.value[taskId]?.let { releaseIfUnused(it.sourceUri) }
            driveRepository.requestRefresh()
        } catch (e: CancellationException) {
            update(taskId) { if (it.status.isActive) it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else it }
            throw e
        } catch (e: Throwable) {
            update(taskId) { it.copy(status = UploadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = failureMessage(e)) }
        }
    }

    private suspend fun upload(client: PikPakClient, taskId: String, progress: MutableStateFlow<Long>) {
        var task = _tasks.value[taskId] ?: return
        val info = sources.describe(task.sourceUri) ?: throw SourceUnavailableException()
        if (info.size != task.size || info.lastModifiedMs != task.lastModifiedMs) {
            // 文件改过：记下的 gcid 与已传的分片都属于旧内容
            task.session?.let { abandon(client, it) }
            task = updated(taskId) {
                it.copy(size = info.size, lastModifiedMs = info.lastModifiedMs, gcid = null, session = null, processedBytes = 0L)
            }
        }

        val gcid = task.gcid ?: run {
            update(taskId) { it.copy(status = UploadStatus.HASHING, processedBytes = 0L) }
            progress.value = 0L
            computeGcid(client, task, progress).also { gcid -> update(taskId) { it.copy(gcid = gcid) } }
        }

        // 凭据过期的会话只剩放弃一条路；OSS 以 403 拒绝时同理，再开一个会话重传一次
        var session = task.session?.takeUnless { it.isExpired() } ?: run {
            task.session?.let { abandon(client, it) }
            startSession(client, taskId, task, gcid) ?: return
        }
        var retried = false
        while (true) {
            update(taskId) { it.copy(status = UploadStatus.UPLOADING, session = session) }
            try {
                client.continueUpload(
                    session,
                    open = { offset -> sources.open(task.sourceUri, offset) },
                    onProgress = { progress.value = it },
                )
                break
            } catch (e: PikPakException) {
                if (e.httpStatus != 403 || retried) throw e
                retried = true
                abandon(client, session)
                session = startSession(client, taskId, task, gcid) ?: return
            }
        }
        update(taskId) {
            it.copy(
                status = UploadStatus.COMPLETED,
                session = null,
                fileId = session.fileId,
                processedBytes = it.size,
                speedBytesPerSec = 0L,
            )
        }
    }

    /** 开一个上传会话。服务端认得这个 gcid 时直接完成，返回 null。 */
    private suspend fun startSession(client: PikPakClient, taskId: String, task: UploadTask, gcid: String): UploadSession? {
        when (val start = client.startUpload(task.parentId, task.fileName, task.size, gcid)) {
            is UploadStart.Instant -> {
                update(taskId) {
                    it.copy(
                        status = UploadStatus.COMPLETED,
                        session = null,
                        fileId = start.fileId,
                        isInstant = true,
                        processedBytes = it.size,
                        speedBytesPerSec = 0L,
                    )
                }
                return null
            }
            is UploadStart.Pending -> {
                // 会话先落盘再开始传：这之后进程被杀，下次启动还能接上
                update(taskId) { it.copy(session = start.session) }
                return start.session
            }
        }
    }

    /**
     * CID 命中就用查到的 gcid，只读 60 KB；查不到才整份读一遍。空文件没有 CID 可查，
     * 服务端对 0 字节的查询回 400。
     */
    private suspend fun computeGcid(client: PikPakClient, task: UploadTask, progress: MutableStateFlow<Long>): String {
        if (task.size > 0) {
            val cid = XunleiCid.of(task.size) { offset, length -> sources.readAt(task.sourceUri, offset, length) }
            client.gcidByCid(cid, task.size)?.let { return it }
        }
        val context = currentCoroutineContext()
        return sources.open(task.sourceUri, 0L).buffered().use { source ->
            PikPakHash.fromSource(source, task.size) { hashed ->
                // 计算本身不挂起，暂停只能在这里生效
                context.ensureActive()
                progress.value = hashed
            }
        }
    }

    /** 同一个文件可能排了不止一次，全部传完或移除后才交还读取授权。 */
    private fun releaseIfUnused(uri: String) {
        val stillNeeded = _tasks.value.values.any { it.sourceUri == uri && it.status != UploadStatus.COMPLETED }
        if (!stillNeeded) runCatching { sources.release(uri) }
    }

    /** 放弃一个会话：尽力而为，失败只意味着网盘里多留一个上传中的文件。 */
    private suspend fun abandon(client: PikPakClient, session: UploadSession) {
        withContext(NonCancellable) { runSuspendCatching { client.cancelUpload(session) } }
    }

    /** 按固定间隔把进度与速度写进任务表，理由同下载调度器的 reportProgress。 */
    private suspend fun reportProgress(taskId: String, progress: StateFlow<Long>) {
        val clock = TimeSource.Monotonic
        var lastMark = clock.markNow()
        var lastBytes = progress.value
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val bytes = progress.value
            val elapsedMs = lastMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
            // 校验转上传时进度从头算，速度不能算成负数
            val speed = ((bytes - lastBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            lastMark = clock.markNow()
            lastBytes = bytes
            update(taskId) { if (it.status.isActive) it.copy(processedBytes = bytes, speedBytesPerSec = speed) else it }
        }
    }

    private fun failureMessage(e: Throwable): String = when {
        e is SourceUnavailableException -> "无法读取源文件，请重新选择"
        e.isNetworkFailure() -> "网络中断"
        else -> e.message ?: "上传未完成"
    }

    private fun update(taskId: String, transform: (UploadTask) -> UploadTask) {
        _tasks.update { tasks -> tasks[taskId]?.let { tasks + (taskId to transform(it)) } ?: tasks }
    }

    /** 任务在途中被删除时抛出取消，[run] 随之结束。 */
    private fun updated(taskId: String, transform: (UploadTask) -> UploadTask): UploadTask {
        update(taskId, transform)
        return _tasks.value[taskId] ?: throw CancellationException("任务已移除")
    }

    private fun UploadSession.isExpired(): Boolean {
        val expiresAt = runCatching { Instant.parse(expiration) }.getOrNull() ?: return false
        return Clock.System.now() > expiresAt - EXPIRY_MARGIN
    }

    private fun newTaskId(): String = "${nowMs()}-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private class SourceUnavailableException : Exception()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L

        /** 一个分片传到一半凭据到期也会被拒，留出余量。 */
        val EXPIRY_MARGIN = 10.minutes
        val json = Json { ignoreUnknownKeys = true }
        val taskListSerializer = ListSerializer(UploadTask.serializer())
    }
}
