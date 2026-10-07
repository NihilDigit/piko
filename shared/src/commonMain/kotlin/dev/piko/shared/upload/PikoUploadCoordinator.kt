package dev.piko.shared.upload

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.failureText
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.update.isNetworkFailure
import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 本机文件上传到网盘。一次只传一个文件，其余排队：瓶颈是本机的上行带宽，并行只会互相抢。
 *
 * 每个文件先读三段 20 KB 算 CID 去查全局索引，命中即秒传；查不到才整份计算 gcid 再真传。
 * gcid 不能省也不能随便填：服务端不校验它，填错的内容会进秒传索引（SDK 的 upload 注释有实测）。
 *
 * 真传的 OSS 会话随任务一起存盘，暂停、失败或进程被杀后都从 OSS 已收下的分片之后接着传。
 * 其中的凭据按任务另存进平台的机密存储，任务表里只留抹去凭据的会话。
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

    // 蜗牛模式的上传额度，与下载的分开，照 FDM 上下行各一个上限
    private val limiter = BandwidthLimiter()

    init {
        scope.launch {
            preferences.snailModeFlow.collect { mode ->
                limiter.bytesPerSecond = if (mode.enabled) mode.uploadKiBps * 1024L else null
            }
        }
    }

    /** 全部账号的任务。界面按当前账号过滤。 */
    val tasks: StateFlow<Map<String, UploadTask>> = _tasks.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 入队时的失败，如所选文件夹读不出来。单个任务的失败体现在任务状态上。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _enqueued = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /**
     * 一次 [enqueue] 真的排进了任务，值是新增的任务数。界面据此切到传输页。发在任务入表之后而不是
     * 调用 enqueue 时：所选文件一个也读不出来的话只有一条失败提示，不该把人带到一个没有新任务的页面。
     */
    val enqueued: SharedFlow<Int> = _enqueued.asSharedFlow()

    private val _pendingRequest = MutableStateFlow<UploadSelection?>(null)

    /**
     * 从应用外进来、还没选好目标目录的上传：Android 的系统分享、桌面端拖进窗口的文件。
     * 界面据此弹出目标确认框，确认后调 [enqueue]。
     */
    val pendingRequest: StateFlow<UploadSelection?> = _pendingRequest.asStateFlow()

    /** 正在跑的那个任务。暂停与删除要取消的是它的协程，不是整条队列。 */
    private val running = MutableStateFlow<Pair<String, Job>?>(null)

    /** 机密存储里眼下存着的凭据，按任务 ID。只在 [restore] 与保存任务表的那个协程里改。 */
    private var storedCredentials = mapOf<String, OssCredentials>()

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
            PikoLog.w(TAG, "上传入队失败：未登录")
            _messages.tryEmit("未登录")
            return
        }
        scope.launch(Dispatchers.IO) {
            val added = mutableListOf<UploadTask>()
            var unreadable = 0
            selection.files.forEach { uri ->
                val task = newTask(client.account, uri, parentId, parentName)
                if (task == null) {
                    unreadable++
                    _messages.tryEmit("无法读取所选文件")
                } else {
                    added += task
                }
            }
            selection.folders.forEach { uri ->
                val folder = sources.listFolder(uri)
                if (folder == null) {
                    PikoLog.w(TAG, "上传入队：读不出所选文件夹")
                    _messages.tryEmit("无法读取所选文件夹")
                    return@forEach
                }
                runSuspendCatching { added += folderTasks(client, folder, parentId) }
                    .logFailure(TAG, "上传入队：在网盘文件夹 $parentId 下建立目录结构失败，本地 ${folder.files.size} 个文件")
                    .onFailure { _messages.tryEmit(failureText("建立文件夹", it)) }
                // 目录建好后网盘页该看到它，不必等文件传完。里面各层是新建的，没人列过，只算上传到的那一层
                driveRepository.applyChange(DriveChange.ContentsChanged(setOf(parentId)))
            }
            if (unreadable > 0) PikoLog.w(TAG, "上传入队：$unreadable 个所选文件读不出")
            if (added.isEmpty()) return@launch
            PikoLog.i(TAG, "上传入队 ${added.size} 个文件到文件夹 $parentId，共 ${added.sumOf { it.size }} 字节")
            _tasks.update { current -> current + added.associateBy { it.taskId } }
            _enqueued.tryEmit(added.size)
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
            val client = clientProvider.currentClient.value?.takeIf { it.account == task.account } ?: run {
                PikoLog.d(TAG, "移除上传任务 ${task.taskId}：所属账号不在用，网盘里上传中的文件 ${session.fileId} 留着")
                return@launch
            }
            runSuspendCatching { client.cancelUpload(session) }.logFailure(TAG, "移除上传任务 ${task.taskId}：放弃上传会话失败，文件 ${session.fileId}")
            driveRepository.applyChange(DriveChange.ContentsChanged(setOf(task.parentId)))
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
        }.logFailure(TAG, "读回上传任务表失败，按空表处理").getOrDefault(emptyList())
        if (saved.isEmpty()) return
        // 1.1.0 把凭据随任务表明文存着，读到的照原样用，随后第一次保存就把它们搬进机密存储、从任务表里抹掉
        val withCredentials = saved.map { task ->
            val session = task.session ?: return@map task
            if (session.credentials != null) return@map task
            val credentials = loadCredentials(task.taskId)?.also { storedCredentials += task.taskId to it }
            task.copy(session = session.withCredentials(credentials))
        }
        // 存下的进度只在状态变化时写，比实际落后，续传时 OSS 会给出准数，这之前不显示
        val restored = withCredentials.map { task ->
            when {
                task.status == UploadStatus.COMPLETED -> task.copy(speedBytesPerSec = 0L)
                task.status.isActive -> task.copy(status = UploadStatus.PAUSED, processedBytes = 0L, speedBytesPerSec = 0L)
                else -> task.copy(processedBytes = 0L, speedBytesPerSec = 0L)
            }
        }
        PikoLog.i(TAG, "恢复上传任务 ${restored.size} 个：未完成 ${restored.count { it.status != UploadStatus.COMPLETED }} 个，" +
            "带会话可续传 ${withCredentials.count { it.session?.credentials != null }} 个，会话凭据缺失 ${withCredentials.count { it.session != null && it.session.credentials == null }} 个")
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
    }

    /**
     * 状态、gcid 或会话变化时保存整张表。后两者不等状态变化：会话一建好就要落盘，
     * 否则进程这时被杀，网盘里留下一个上传中的文件，谁也接不上它。
     *
     * 凭据先存、任务表后存：进程死在两步之间时，任务表里的会话不会缺凭据。
     */
    private suspend fun persistOnStructuralChange() {
        _tasks
            .distinctUntilChangedBy { tasks -> tasks.mapValues { Triple(it.value.status, it.value.gcid, it.value.session?.uploadId) } }
            .collect { tasks ->
                saveCredentials(tasks)
                val withoutCredentials = tasks.values.map { task -> task.copy(session = task.session?.withCredentials(null)) }
                val serialized = json.encodeToString(taskListSerializer, withoutCredentials)
                runSuspendCatching { preferences.saveUploadTasks(serialized) }.logFailure(TAG, "保存上传任务表失败")
            }
    }

    /**
     * 让机密存储里的凭据与任务表一致：有会话的任务各存一份，完成、移除或换了会话的随之清掉或覆盖。
     * 存不下的下次保存再试；一直存不下，进程重启后这个会话按凭据过期处理，放弃已传的分片重新开始。
     */
    private suspend fun saveCredentials(tasks: Map<String, UploadTask>) {
        val current = tasks.mapNotNull { (taskId, task) -> task.session?.credentials?.let { taskId to it } }.toMap()
        for ((taskId, credentials) in current) {
            if (storedCredentials[taskId] == credentials) continue
            runSuspendCatching { preferences.saveUploadCredentials(taskId, json.encodeToString(OssCredentials.serializer(), credentials)) }
                .onSuccess { storedCredentials += taskId to credentials }
                .logFailure(TAG, "上传凭据未能保存")
        }
        for (taskId in storedCredentials.keys - current.keys) {
            runSuspendCatching { preferences.clearUploadCredentials(taskId) }
                .onSuccess { storedCredentials -= taskId }
                .logFailure(TAG, "上传凭据未能清除")
        }
    }

    private suspend fun loadCredentials(taskId: String): OssCredentials? =
        runSuspendCatching { preferences.loadUploadCredentials(taskId)?.let { json.decodeFromString(OssCredentials.serializer(), it) } }
            .logFailure(TAG, "上传凭据未能读取")
            .getOrNull()

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
            _tasks.value[taskId]?.let { task ->
                releaseIfUnused(task.sourceUri)
                driveRepository.applyChange(DriveChange.ContentsChanged(setOf(task.parentId)))
            }
        } catch (e: CancellationException) {
            update(taskId) { if (it.status.isActive) it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else it }
            throw e
        } catch (e: Throwable) {
            // 上传完成前网盘里还没有这个文件，用任务 ID 指代
            val task = _tasks.value[taskId]
            PikoLog.w(TAG, "上传失败：${logFile(taskId, task?.fileName.orEmpty())}，阶段 ${task?.status}，" +
                "已处理 ${task?.processedBytes}/${task?.size} 字节${if (e is SourceUnavailableException) "，源文件读不出" else ""}", e)
            update(taskId) { it.copy(status = UploadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = failureMessage(e)) }
        }
    }

    private suspend fun upload(client: PikPakClient, taskId: String, progress: MutableStateFlow<Long>) {
        var task = _tasks.value[taskId] ?: return
        val started = TimeSource.Monotonic.markNow()
        val info = sources.describe(task.sourceUri) ?: throw SourceUnavailableException()
        if (info.size != task.size || info.lastModifiedMs != task.lastModifiedMs) {
            // 文件改过：记下的 gcid 与已传的分片都属于旧内容
            PikoLog.w(TAG, "上传 $taskId：源文件已改动（${task.size} → ${info.size} 字节），弃用已算的 gcid 与会话")
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

        // 凭据过期或没能从机密存储读回的会话只剩放弃一条路；OSS 以 403 拒绝时同理，再开一个会话重传一次
        var session = task.session?.takeIf { it.canContinue() } ?: run {
            task.session?.let {
                PikoLog.i(TAG, "上传 $taskId：会话${if (it.credentials == null) "凭据缺失" else "凭据将过期"}，放弃已传分片，重开会话")
                abandon(client, it)
            }
            startSession(client, taskId, task, gcid) ?: run {
                PikoLog.i(TAG, "上传完成（秒传）：任务 $taskId，${task.size} 字节，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
                return
            }
        }
        if (session === task.session) PikoLog.d(TAG, "上传 $taskId：续用已有会话，网盘文件 ${session.fileId}")
        var retried = false
        while (true) {
            // 进度归零：此前的数值是校验读过的字节数，continueUpload 随即报出 OSS 已收下的量
            progress.value = 0L
            update(taskId) { it.copy(status = UploadStatus.UPLOADING, session = session, processedBytes = 0L) }
            try {
                client.continueUpload(
                    session,
                    open = { offset -> ThrottledSource(sources.open(task.sourceUri, offset), limiter) },
                    onProgress = { progress.value = it },
                )
                break
            } catch (e: PikPakException) {
                if (e.httpStatus != 403 || retried) throw e
                PikoLog.w(TAG, "OSS 拒绝上传凭据（403），换新会话重传", e)
                retried = true
                abandon(client, session)
                session = startSession(client, taskId, task, gcid) ?: run {
                    PikoLog.i(TAG, "上传完成（重开会话时秒传）：任务 $taskId，${task.size} 字节")
                    return
                }
            }
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds
        PikoLog.i(TAG, "上传完成：任务 $taskId → 文件 ${session.fileId}，${task.size} 字节，历时 $elapsed ms" +
            "（${task.size * 1000 / elapsed.coerceAtLeast(1) / 1024} KiB/s，含校验）")
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
            client.gcidByCid(cid, task.size)?.let {
                PikoLog.d(TAG, "上传 ${task.taskId}：CID 命中索引，免算整份 gcid")
                return it
            }
        }
        val context = currentCoroutineContext()
        val started = TimeSource.Monotonic.markNow()
        return sources.open(task.sourceUri, 0L).buffered().use { source ->
            PikPakHash.fromSource(source, task.size) { hashed ->
                // 计算本身不挂起，暂停只能在这里生效
                context.ensureActive()
                progress.value = hashed
            }
        }.also { PikoLog.d(TAG, "上传 ${task.taskId}：整份计算 gcid，${task.size} 字节，历时 ${started.elapsedNow().inWholeMilliseconds} ms") }
    }

    /** 同一个文件可能排了不止一次，全部传完或移除后才交还读取授权。 */
    private fun releaseIfUnused(uri: String) {
        val stillNeeded = _tasks.value.values.any { it.sourceUri == uri && it.status != UploadStatus.COMPLETED }
        if (!stillNeeded) runCatching { sources.release(uri) }.logFailure(TAG, "交还源文件读取授权失败")
    }

    /** 放弃一个会话：尽力而为，失败只意味着网盘里多留一个上传中的文件。 */
    private suspend fun abandon(client: PikPakClient, session: UploadSession) {
        withContext(NonCancellable) {
            runSuspendCatching { client.cancelUpload(session) }.logFailure(TAG, "放弃上传会话失败，网盘里留下上传中的文件 ${session.fileId}")
        }
    }

    /**
     * 按固定间隔把进度与速度写进任务表，理由同下载调度器的 reportProgress。速度也照它按最近
     * [SPEED_WINDOW_MS] 算：只看相邻两次采样的话，分片一块块发出去，读数在 0 与峰值之间来回跳。
     */
    private suspend fun reportProgress(taskId: String, progress: StateFlow<Long>) {
        val clock = TimeSource.Monotonic
        val samples = ArrayDeque<Pair<TimeMark, Long>>()
        samples.addLast(clock.markNow() to progress.value)
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val bytes = progress.value
            // 校验转上传时进度从头算，窗口里的旧读数不再可比，从这一刻重新攒
            if (bytes < samples.last().second) samples.clear()
            samples.addLast(clock.markNow() to bytes)
            while (samples.size > 2 && samples.first().first.elapsedNow().inWholeMilliseconds > SPEED_WINDOW_MS) samples.removeFirst()
            val (oldestMark, oldestBytes) = samples.first()
            val elapsedMs = oldestMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
            val speed = ((bytes - oldestBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
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

    private fun UploadSession.canContinue(): Boolean {
        if (credentials == null) return false
        val expiresAt = runCatching { Instant.parse(expiration) }.getOrNull() ?: return true
        return Clock.System.now() <= expiresAt - EXPIRY_MARGIN
    }

    private fun newTaskId(): String = "${nowMs()}-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private class SourceUnavailableException : Exception()

    /** 会话里的机密部分。其余字段（文件、分片、OSS 地址、到期时刻）不是机密，随任务表存。 */
    @Serializable
    private data class OssCredentials(val accessKeyId: String, val accessKeySecret: String, val securityToken: String)

    /** 抹去过凭据的会话（任务表里存的，或凭据没能读回）为 null。 */
    private val UploadSession.credentials: OssCredentials?
        get() = OssCredentials(accessKeyId, accessKeySecret, securityToken).takeIf { accessKeySecret.isNotEmpty() }

    private fun UploadSession.withCredentials(credentials: OssCredentials?): UploadSession = copy(
        accessKeyId = credentials?.accessKeyId.orEmpty(),
        accessKeySecret = credentials?.accessKeySecret.orEmpty(),
        securityToken = credentials?.securityToken.orEmpty(),
    )

    /**
     * 按蜗牛模式的额度读源文件。SDK 边读边往 OSS 写，读慢了请求体就跟着慢。
     * 每次至多读 [CHUNK] 再取额度：按整个分片扣的话，一个分片的请求体会停顿好几秒，容易撞上 OSS 的超时。
     * readAtMostTo 是阻塞调用，没法挂起，只能 runBlocking 等额度；读文件本来就阻塞在这个线程上。
     */
    private class ThrottledSource(private val inner: RawSource, private val limiter: BandwidthLimiter) : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            val read = inner.readAtMostTo(sink, minOf(byteCount, CHUNK))
            if (read > 0 && limiter.bytesPerSecond != null) runBlocking { limiter.acquire(read) }
            return read
        }

        override fun close() = inner.close()

        private companion object {
            const val CHUNK = 64L * 1024
        }
    }

    private companion object {
        const val TAG = "Upload"
        const val PROGRESS_INTERVAL_MS = 500L
        const val SPEED_WINDOW_MS = 3_000L

        /** 一个分片传到一半凭据到期也会被拒，留出余量。 */
        val EXPIRY_MARGIN = 10.minutes
        val json = Json { ignoreUnknownKeys = true }
        val taskListSerializer = ListSerializer(UploadTask.serializer())
    }
}
