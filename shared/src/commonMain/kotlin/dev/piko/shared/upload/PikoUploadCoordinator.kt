package dev.piko.shared.upload

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.media.cache.fileCacheName
import dev.piko.shared.log.PikoLog
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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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

data class UploadConcurrencyPolicy(
    val maxFiles: Int = 4,
    val maxHashers: Int = 2,
    val maxBufferedPartBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxFiles > 0 && maxHashers > 0 && maxBufferedPartBytes > 0)
    }
}

/**
 * 本机文件上传到网盘，并发处理文件，单独限制整份校验的并发数。
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
    private val policy: UploadConcurrencyPolicy = UploadConcurrencyPolicy(),
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
    private val _preparations = MutableStateFlow<List<UploadPreparation>>(emptyList())
    val preparations = _preparations.asStateFlow()
    private val _isRestoring = MutableStateFlow(true)
    val isRestoring = _isRestoring.asStateFlow()
    private val sourceLock = Mutex()
    private val preparationLock = Mutex()
    private val preparationJobs = mutableMapOf<String, Job>()
    private val pausedBatches = mutableSetOf<String>()
    private val preparingSources = mutableMapOf<String, Set<String>>()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 入队时的失败，如所选文件夹读不出来。单个任务的失败体现在任务状态上。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _enqueued = MutableSharedFlow<UploadEnqueued>(extraBufferCapacity = 1)

    /**
     * 一次 [enqueue] 真的排进了任务，包含所属账号和新增任务数。界面据此切到传输页。发在任务入表之后而不是
     * 调用 enqueue 时：所选文件一个也读不出来的话只有一条失败提示，不该把人带到一个没有新任务的页面。
     */
    val enqueued: SharedFlow<UploadEnqueued> = _enqueued.asSharedFlow()
    private val _preparationOutcomes = MutableSharedFlow<UploadPreparation>(extraBufferCapacity = 16)
    val preparationOutcomes = _preparationOutcomes.asSharedFlow()

    private val _pendingRequest = MutableStateFlow<UploadSelection?>(null)

    /**
     * 从应用外进来、还没选好目标目录的上传：Android 的系统分享、桌面端拖进窗口的文件。
     * 界面据此弹出目标确认框，确认后调 [enqueue]。
     */
    val pendingRequest: StateFlow<UploadSelection?> = _pendingRequest.asStateFlow()

    /** 暂停与删除只取消对应文件，换号则取消当前调度器的全部子任务。 */
    private val running = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val hashers = Semaphore(policy.maxHashers)
    private val bufferedPartBytes = MutableStateFlow(0L)

    /** 机密存储里眼下存着的凭据，按任务 ID。只在 [restore] 与保存任务表的那个协程里改。 */
    private var storedCredentials = mapOf<String, OssCredentials>()

    init {
        scope.launch {
            try { restore() } finally { _isRestoring.value = false }
            launch { persistOnStructuralChange() }
            runQueue()
        }
    }

    fun request(selection: UploadSelection) {
        if (selection.isEmpty) return
        val previous = runBlocking { sourceLock.withLock {
            retainSources((selection.files + selection.folders).mapTo(HashSet(), sources::permissionKey))
            _pendingRequest.value.also { _pendingRequest.value = selection.copy(files = selection.files.distinct(), folders = selection.folders.distinct()) }
        } }
        previous?.let { (it.files + it.folders).mapTo(HashSet(), sources::permissionKey).forEach(::releaseIfUnused) }
    }

    fun clearRequest() {
        val previous = runBlocking { sourceLock.withLock { _pendingRequest.value.also { _pendingRequest.value = null } } }
        previous?.let { (it.files + it.folders).mapTo(HashSet(), sources::permissionKey).forEach(::releaseIfUnused) }
    }

    /**
     * 把 [selection] 排进队列，传到 [parentId]。文件夹在这里就把目录结构在网盘里建好，
     * 其下的文件各成一个任务，排进队列时已知道自己的上级目录。
     */
    fun enqueue(selection: UploadSelection, parentId: String, parentName: String): UploadEnqueueResult {
        if (selection.isEmpty || !scope.isActive) return UploadEnqueueResult.Rejected(tr("当前上传上下文已结束，或未选择文件", "Upload session ended or no files selected", "アップロードセッションが終了したか、ファイルが未選択です", "업로드 세션이 종료되었거나 파일을 선택하지 않았습니다"))
        val client = clientProvider.currentClient.value
        if (client == null) {
            PikoLog.w(TAG, "上传入队失败：未登录")
            _messages.tryEmit(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다"))
            return UploadEnqueueResult.Rejected(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다"))
        }
        preparationBlockReason(selection, parentId)?.let { return UploadEnqueueResult.Rejected(it) }
        val frozen = selection.copy(files = selection.files.distinct(), folders = selection.folders.distinct())
        val origins = frozen.files.mapIndexed { index, uri -> UploadPreparationSource(sourceKey(uri, false), tr("所选文件 ${index + 1}", "Selected file ${index + 1}", "選択したファイル ${index + 1}", "선택한 파일 ${index + 1}"), false) } +
            frozen.folders.mapIndexed { index, uri -> UploadPreparationSource(sourceKey(uri, true), tr("所选文件夹 ${index + 1}", "Selected folder ${index + 1}", "選択したフォルダー ${index + 1}", "선택한 폴더 ${index + 1}"), true) }
        val preparation = UploadPreparation("${nowMs()}-${Random.nextLong()}", client.account, parentId, parentName, frozen.count, nowMs(),
            origins)
        while (true) {
            val previous = _preparations.value
            preparationBlockReason(frozen, parentId, previous)?.let { return UploadEnqueueResult.Rejected(it) }
            if (_preparations.compareAndSet(previous, previous + preparation)) break
        }
        runBlocking { sourceLock.withLock {
            val keys = (frozen.files + frozen.folders).mapTo(HashSet(), sources::permissionKey)
            retainSources(keys)
            preparingSources[preparation.id] = keys
        } }
        PikoLog.i(TAG, "上传准备 ${preparation.id}：接受 ${frozen.count} 项，目标 $parentId")
        val preparing = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            prepare(client, frozen, preparation)
        }
        runBlocking { preparationLock.withLock { preparationJobs[preparation.id] = preparing } }
        preparing.invokeOnCompletion { error ->
            runBlocking { preparationLock.withLock {
                if (preparationJobs[preparation.id] === preparing) preparationJobs.remove(preparation.id)
                pausedBatches.remove(preparation.id)
            } }
            if (error != null) _preparations.update { all -> all.map { work -> if (work.id == preparation.id && work.isRunning)
                work.copy(result = UploadPreparationResult(UploadPreparationCompletion.STOPPED, tr("上传准备已结束，请检查已入队文件与目标目录", "Upload preparation ended. Check queued files and destination", "アップロードの準備が終了しました。キュー内のファイルと保存先を確認してください", "업로드 준비가 종료되었습니다. 대기열의 파일과 대상 폴더를 확인해 주세요"), nowMs())) else work } }
            val released = runBlocking { sourceLock.withLock { preparingSources.remove(preparation.id).orEmpty() } }
            released.forEach(::releaseIfUnused)
        }
        preparing.start()
        return UploadEnqueueResult.Accepted(preparation.id)
    }

    private suspend fun prepare(client: PikPakClient, selection: UploadSelection, initial: UploadPreparation) {
        var work = initial
        val grouped = selection.folders.isNotEmpty() || selection.files.size > 1
        var batchName: String? = null
        fun publish() { _preparations.update { all -> all.map { if (it.id == work.id) work else it } } }
        fun title(key: String, name: String) { work = work.copy(sources = work.sources.map { if (it.key == key) it.copy(title = name) else it }); publish() }
        fun issue(key: String, path: String, kind: UploadPreparationIssueKind) {
            work = work.copy(issues = work.issues + UploadPreparationIssue(key, path, kind)); publish()
        }
        suspend fun queue(key: String, path: String, task: UploadTask) = preparationLock.withLock {
            currentCoroutineContext().ensureActive()
            if (work.id !in preparationJobs) throw CancellationException(tr("上传准备已移除", "Upload preparation removed", "アップロードの準備を削除しました", "업로드 준비를 삭제했습니다"))
            if (batchName == null) {
                val firstName = work.sources.firstOrNull()?.title ?: task.fileName
                batchName = if (selection.files.isEmpty() && selection.folders.size == 1) firstName else tr("$firstName 等 ${selection.count} 项", "$firstName and ${enCount(selection.count, "item", "items")}", "$firstName ほか ${selection.count} 項目", "$firstName 등 ${selection.count}개 항목")
            }
            val queued = if (grouped) task.copy(batchId = work.id, batchName = batchName,
                batchParentId = work.parentId, batchParentName = work.parentName, batchIsFolder = selection.folders.isNotEmpty()) else task
            val accepted = if (work.id in pausedBatches) queued.copy(status = UploadStatus.PAUSED) else queued
            _tasks.update { it + (accepted.taskId to accepted) }
            work = work.copy(files = work.files + UploadPreparedFile(key, path, task.taskId)); publish()
        }
        suspend fun readFile(key: String, path: String, uri: String, parent: String, name: String): UploadTask? {
            val task = runSuspendCatching { newTask(client.account, uri, parent, name) }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (task == null) issue(key, path, UploadPreparationIssueKind.SOURCE_UNREADABLE)
            return task
        }
        var stopped = false
        var unexpected = false
        try {
            for (uri in selection.files) {
                val key = sourceKey(uri, false)
                val task = readFile(key, "", uri, work.parentId, work.parentName) ?: continue
                title(key, task.fileName)
                queue(key, task.fileName, task)
            }
            for (uri in selection.folders) {
                val key = sourceKey(uri, true)
                val folder = runSuspendCatching { sources.listFolder(uri) }.getOrNull()
                currentCoroutineContext().ensureActive()
                if (folder == null) { issue(key, "", UploadPreparationIssueKind.SOURCE_UNREADABLE); continue }
                title(key, folder.name)
                if (folder.files.isEmpty()) { issue(key, "", UploadPreparationIssueKind.EMPTY_FOLDER); continue }
                val directories = mutableMapOf<String, String>()
                val blocked = mutableSetOf<String>()
                suspend fun directory(path: String): String? {
                    directories[path]?.let { return it }
                    if (path in blocked) return null
                    val parent = if (path.isEmpty()) work.parentId else directory(path.substringBeforeLast('/', "")) ?: return null
                    val name = if (path.isEmpty()) folder.name else path.substringAfterLast('/')
                    var attempted = false
                    try {
                        currentCoroutineContext().ensureActive()
                        attempted = true
                        val id = client.createFolder(parent, name)
                        check(id.isNotBlank()) { tr("目录创建响应缺少 ID", "Folder creation response has no ID", "フォルダー作成の応答にIDがありません", "폴더 생성 응답에 ID가 없습니다") }
                        directories[path] = id
                        work = work.copy(directories = work.directories + UploadPreparedDirectory(key, path, id, parent)); publish()
                        driveRepository.applyWriteChange(client.account, DriveChange.Created(parent, id, name, isFolder = true))
                        return id
                    } catch (error: Exception) {
                        blocked += path
                        if (attempted) {
                            val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
                            issue(key, path, if (refused) UploadPreparationIssueKind.DIRECTORY_REFUSED else UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED)
                            PikoLog.w(TAG, "上传准备 ${work.id}：目录创建${if (refused) "被拒绝" else "待确认"}，父目录 $parent，${error::class.simpleName}")
                            driveRepository.applyWriteChange(client.account, DriveChange.ContentsChanged(setOf(parent)))
                        }
                        if (error is CancellationException) throw error
                        return null
                    }
                }
                for ((index, entry) in folder.files.withIndex()) {
                    val sourcePath = listOf(entry.relativeDir, tr("所选文件 ${index + 1}", "Selected file ${index + 1}", "選択したファイル ${index + 1}", "선택한 파일 ${index + 1}")).filter { it.isNotEmpty() }.joinToString("/")
                    val task = readFile(key, sourcePath, entry.uri, "", "") ?: continue
                    val path = listOf(entry.relativeDir, task.fileName).filter { it.isNotEmpty() }.joinToString("/")
                    val parent = directory(entry.relativeDir)
                    if (parent == null) { issue(key, path, UploadPreparationIssueKind.NOT_ATTEMPTED); continue }
                    queue(key, path, task.copy(parentId = parent, parentName = entry.relativeDir.substringAfterLast('/').ifEmpty { folder.name }))
                }
            }
        } catch (error: CancellationException) { stopped = true; throw error }
        catch (error: Exception) {
            unexpected = true
            PikoLog.w(TAG, "上传准备 ${work.id}异常结束：${error::class.simpleName}")
        }
        finally {
            val completion = when {
                stopped -> UploadPreparationCompletion.STOPPED
                unexpected && (work.files.isNotEmpty() || work.directories.isNotEmpty()) -> UploadPreparationCompletion.PARTIAL
                unexpected -> UploadPreparationCompletion.FAILED
                work.issues.isEmpty() -> UploadPreparationCompletion.SUCCEEDED
                work.files.isNotEmpty() || work.directories.isNotEmpty() -> UploadPreparationCompletion.PARTIAL
                work.hasUnconfirmedDirectory -> UploadPreparationCompletion.UNCONFIRMED
                else -> UploadPreparationCompletion.FAILED
            }
            val message = buildList {
                add(tr("已入队 ${work.files.size} 个文件", "Queued ${enCount(work.files.size, "file", "files")}", "${work.files.size} ファイルをキューに追加しました", "파일 ${work.files.size}개 대기열에 추가"))
                if (work.directories.isNotEmpty()) add(tr("已创建 ${work.directories.size} 个目录", "Created ${enCount(work.directories.size, "folder", "folders")}", "${work.directories.size} フォルダーを作成しました", "폴더 ${work.directories.size}개 생성 완료"))
                val unknown = work.issues.count { it.kind == UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED }
                if (unknown > 0) add(tr("$unknown 个目录创建结果待确认", "$unknown folder creations unconfirmed", "$unknown フォルダーの作成が未確認です", "폴더 ${unknown}개 생성 결과 미확인"))
                val skipped = work.issues.count { it.kind == UploadPreparationIssueKind.NOT_ATTEMPTED }
                if (skipped > 0) add(tr("$skipped 个文件未入队", "${enCount(skipped, "file", "files")} not queued", "$skipped ファイルをキューに追加していません", "파일 ${skipped}개 대기열에 추가 안 됨"))
                val failed = work.issues.size - unknown - skipped
                if (failed > 0) add(tr("$failed 项未准备成功", "${enCount(failed, "item", "items")} failed preparation", "$failed 項目の準備に失敗しました", "${failed}개 항목 준비 실패"))
                if (stopped) add(tr("准备已停止，请检查目标目录", "Preparation stopped. Check the destination folder", "準備を停止しました。保存先フォルダーを確認してください", "준비를 중지했습니다. 대상 폴더를 확인해 주세요"))
                if (unexpected) add(tr("准备发生异常，请检查已入队文件与目标目录", "Preparation failed. Check queued files and destination", "準備中にエラーが発生しました。キュー内のファイルと保存先を確認してください", "준비 중 오류가 발생했습니다. 대기열의 파일과 대상 폴더를 확인해 주세요"))
            }.joinToString("，")
            work = work.copy(result = UploadPreparationResult(completion, message, nowMs()))
            PikoLog.i(TAG, "上传准备 ${work.id}结束：$completion，入队 ${work.files.size} 个文件，创建 ${work.directories.size} 个目录，${work.issues.size} 项问题")
            if (completion == UploadPreparationCompletion.SUCCEEDED) _preparations.update { all -> all.filterNot { it.id == work.id } }
            else {
                publish()
                _preparations.update { all ->
                    val retained = all.filter { !it.isRunning && !it.hasUnconfirmedDirectory }.takeLast(100).mapTo(HashSet()) { it.id }
                    all.filter { it.isRunning || it.hasUnconfirmedDirectory || it.id in retained }
                }
                _preparationOutcomes.tryEmit(work)
            }
            if (work.files.isNotEmpty()) _enqueued.tryEmit(UploadEnqueued(work.account, work.files.size, work.id))
        }
    }

    fun forgetPreparation(id: String): Boolean {
        if (_preparations.value.any { it.id == id && it.isRunning }) return false
        _preparations.update { all -> all.filterNot { it.id == id } }
        return true
    }
    fun preparationBlockReason(selection: UploadSelection, parentId: String, snapshot: List<UploadPreparation> = _preparations.value): String? {
        val account = clientProvider.currentClient.value?.account ?: return tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다")
        if (_isRestoring.value) return tr("正在恢复上传任务，请稍后", "Restoring upload tasks. Please wait", "アップロードタスクを復元中です。お待ちください", "업로드 작업을 복원 중입니다. 잠시 기다려 주세요")
        if (_tasks.value.values.any { it.account == account && it.parentId == parentId && it.sourceUri in selection.files && it.status != UploadStatus.COMPLETED })
            return tr("此文件已有未完成的上传，请在任务页继续或先移除原任务", "This file has an unfinished upload. Resume or remove it in Tasks first", "このファイルには未完了のアップロードがあります。先にタスク画面で再開または削除してください", "이 파일에 미완료 업로드가 있습니다. 먼저 작업 페이지에서 계속하거나 삭제해 주세요")
        val keys = selection.files.map { sourceKey(it, false) } + selection.folders.map { sourceKey(it, true) }
        return tr("相同来源在此目录有准备中或已有副作用的上传，请先在任务页检查", "This source already has an upload in this folder. Check Tasks first", "同じソースのアップロードをこのフォルダーで準備中か処理済みです。先にタスク画面で確認してください", "이 폴더에 같은 원본의 업로드가 준비 중이거나 처리된 상태입니다. 먼저 작업 페이지에서 확인해 주세요").takeIf {
            snapshot.any { it.account == account && it.parentId == parentId && it.blockedSources.any { key -> key in keys } }
        }
    }
    private fun sourceKey(uri: String, folder: Boolean) = fileCacheName("$folder:$uri")

    /** 继续暂停的任务，或重试失败的任务：放回队尾，轮到它时从断点接着传。 */
    fun resume(taskId: String) {
        update(taskId) {
            if (it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED) {
                it.copy(status = UploadStatus.QUEUED, errorMessage = null, queuedAtMs = nowMs())
            } else {
                it
            }
        }
    }

    fun pause(taskId: String) {
        update(taskId) { if (it.status.isActive) it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else it }
        cancelRunning(taskId)
    }

    fun pauseBatch(batchId: String) {
        val ids = runBlocking { preparationLock.withLock {
            if (batchId in preparationJobs) pausedBatches += batchId
            pauseBatchTasks(batchId)
        } }
        ids.forEach(::cancelRunning)
    }

    private fun pauseBatchTasks(batchId: String): Set<String> {
        val ids = _tasks.value.values.filter { it.batchId == batchId }.mapTo(HashSet()) { it.taskId }
        _tasks.update { tasks -> tasks.mapValues { (id, task) ->
            if (id in ids && task.status.isActive) task.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else task
        } }
        return ids
    }

    fun resumeBatch(batchId: String) {
        runBlocking { preparationLock.withLock {
            pausedBatches.remove(batchId)
            _tasks.value.values.filter { it.batchId == batchId }.forEach { resume(it.taskId) }
        } }
    }

    fun removeBatch(batchId: String) {
        val (preparing, ids) = runBlocking { preparationLock.withLock {
            pausedBatches.remove(batchId)
            preparationJobs.remove(batchId) to pauseBatchTasks(batchId)
        } }
        preparing?.cancel()
        ids.forEach(::remove)
    }

    /** 把所有未完成的任务转为暂停。Android 前台服务被系统叫停时用。 */
    fun pauseAll() {
        _tasks.update { tasks -> tasks.mapValues { (_, task) ->
            if (task.status.isActive) task.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else task
        } }
        running.value.values.forEach { it.cancel() }
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
            driveRepository.applyWriteChange(client.account, DriveChange.ContentsChanged(setOf(task.parentId)))
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
     * 当前账号按入队顺序补满空位。换号或退出登录时 collectLatest 等全部子任务取消后再切换。
     */
    private suspend fun runQueue() {
        clientProvider.currentClient.collectLatest { client ->
            if (client == null) return@collectLatest
            coroutineScope {
                combine(_tasks, running) { _, _ -> Unit }.collect {
                    while (running.value.size < policy.maxFiles) {
                        val next = nextQueued(_tasks.value, client.account) ?: break
                        val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(client, next.taskId) }
                        running.update { it + (next.taskId to job) }
                        job.invokeOnCompletion {
                            running.update { if (it[next.taskId] === job) it - next.taskId else it }
                        }
                        job.start()
                    }
                }
            }
        }
    }

    private fun nextQueued(tasks: Map<String, UploadTask>, account: String): UploadTask? =
        tasks.values.filter { it.account == account && it.status == UploadStatus.QUEUED && it.taskId !in running.value }.minByOrNull { it.queuedAtMs }

    private fun cancelRunning(taskId: String): Job? {
        val job = running.value[taskId] ?: return null
        job.cancel()
        return job
    }

    private suspend fun run(client: PikPakClient, taskId: String) {
        if (_tasks.value[taskId]?.status != UploadStatus.QUEUED) return
        val progress = MutableStateFlow(0L)
        try {
            coroutineScope {
                val reporter = launch { reportProgress(taskId, progress) }
                upload(client, taskId, progress)
                reporter.cancel()
            }
            _tasks.value[taskId]?.let { task ->
                releaseIfUnused(task.sourceUri)
                driveRepository.applyWriteChange(client.account, DriveChange.ContentsChanged(setOf(task.parentId)))
            }
        } catch (e: CancellationException) {
            // 暂停后立即继续会重新入队；旧 worker 的取消不能覆盖这次继续。
            update(taskId) {
                if (it.status == UploadStatus.HASHING || it.status == UploadStatus.UPLOADING)
                    it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L) else it
            }
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
                withPartBuffer(session) {
                    client.continueUpload(
                        session,
                        open = { offset -> ThrottledSource(sources.open(task.sourceUri, offset), limiter) },
                        onProgress = { progress.value = it },
                    )
                }
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
        fun hash(): String = sources.open(task.sourceUri, 0L).buffered().use { source ->
            PikPakHash.fromSource(source, task.size) { hashed ->
                // 计算本身不挂起，暂停只能在这里生效
                context.ensureActive()
                progress.value = hashed
            }
        }
        return (if (task.size < 256L * 1024) hash() else hashers.withPermit { hash() })
            .also { PikoLog.d(TAG, "上传 ${task.taskId}：整份计算 gcid，${task.size} 字节，历时 ${started.elapsedNow().inWholeMilliseconds} ms") }
    }

    private suspend fun <T> withPartBuffer(session: UploadSession, block: suspend () -> T): T {
        // SDK 一次缓冲一个分片；超出预算的单片独占额度，避免与其他文件叠加。
        val bytes = minOf(session.size, session.partSize, policy.maxBufferedPartBytes)
        while (true) {
            val used = bufferedPartBytes.first { it <= policy.maxBufferedPartBytes - bytes }
            if (bufferedPartBytes.compareAndSet(used, used + bytes)) break
        }
        try { return block() } finally { bufferedPartBytes.update { it - bytes } }
    }

    /** 同一个文件可能排了不止一次，全部传完或移除后才交还读取授权。 */
    private fun releaseIfUnused(uri: String) {
        runBlocking { sourceLock.withLock {
            val key = sources.permissionKey(uri)
            val pending = _pendingRequest.value
            val stillNeeded = preparingSources.values.any { key in it } ||
                (pending?.files.orEmpty() + pending?.folders.orEmpty()).any { sources.permissionKey(it) == key } ||
                _tasks.value.values.any { it.status != UploadStatus.COMPLETED && sources.permissionKey(it.sourceUri) == key }
            if (!stillNeeded) runCatching { sources.release(key) }.logFailure(TAG, "交还源文件读取授权失败")
        } }
    }

    private fun retainSources(keys: Set<String>) {
        keys.forEach { key -> runCatching { sources.retain(key) }.onFailure { PikoLog.w(TAG, "保留来源读取授权失败：${it::class.simpleName}") } }
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
        e is SourceUnavailableException -> tr("无法读取源文件，请重新选择", "Unable to read source file. Select it again", "元のファイルを読み込めません。選択し直してください", "원본 파일을 읽을 수 없습니다. 다시 선택해 주세요")
        e.isNetworkFailure() -> tr("网络中断", "Network interrupted", "ネットワークが切断されました", "네트워크 연결이 끊어졌습니다")
        else -> dev.piko.shared.notice.PikoErrorMapper.failure(tr("上传", "Upload", "アップロード", "업로드"), e)
    }

    private fun update(taskId: String, transform: (UploadTask) -> UploadTask) {
        _tasks.update { tasks -> tasks[taskId]?.let { tasks + (taskId to transform(it)) } ?: tasks }
    }

    /** 任务在途中被删除时抛出取消，[run] 随之结束。 */
    private fun updated(taskId: String, transform: (UploadTask) -> UploadTask): UploadTask {
        update(taskId, transform)
        return _tasks.value[taskId] ?: throw CancellationException(tr("任务已移除", "Task removed", "タスクを削除しました", "작업을 삭제했습니다"))
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
