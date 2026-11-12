package dev.piko.shared.upload

import kotlinx.coroutines.IO
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
import dev.piko.shared.log.logFolder
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
import kotlinx.coroutines.CompletableDeferred
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
    private val restoreCompleted = CompletableDeferred<Unit>()
    private val persistLock = Mutex()
    /**
     * 保护来源授权引用计数与准备队列内存状态（`stateMutex` 角色）。
     *
     * 不变量：主线程同步入口（[request]、[clearRequest]、[enqueue]、[pauseBatch]、[resumeBatch]、[removeBatch]、[releaseIfUnused]）
     * 通过 `runBlocking` 获取这两把锁，因此持锁代码块内**严禁**执行任何挂起 I/O、网络调用或等待子协程，
     * 且需要同时加锁时禁止反转顺序，以免卡死主线程或发生死锁。
     */
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
    private val structuralRevision = MutableStateFlow(0L)
    private val hashers = Semaphore(policy.maxHashers)
    private val bufferedPartBytes = MutableStateFlow(0L)

    private fun bumpStructuralRevision() {
        structuralRevision.update { it + 1L }
    }

    /** 机密存储里眼下存着的凭据，按任务 ID。只在 [restore] 与保存任务表的那个协程里改。 */
    private var storedCredentials = mapOf<String, OssCredentials>()

    init {
        scope.launch {
            try { restore() } finally {
                _isRestoring.value = false
                restoreCompleted.complete(Unit)
            }
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
        if (selection.isEmpty || !scope.isActive) return UploadEnqueueResult.Rejected(tr("上传会话已结束，或未选择文件", "Upload session ended or no files selected", "アップロードセッションが終了したか、ファイルが選択されていません", "업로드 세션이 종료되었거나 파일을 선택하지 않았습니다", "上傳工作階段已結束，或未選取檔案"))
        val client = clientProvider.currentClient.value
        if (client == null) {
            PikoLog.w(TAG, "Upload enqueue rejected: not signed in")
            _messages.tryEmit(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
            return UploadEnqueueResult.Rejected(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        }
        preparationBlockReason(selection, parentId)?.let { return UploadEnqueueResult.Rejected(it) }
        val frozen = selection.copy(files = selection.files.distinct(), folders = selection.folders.distinct())
        val origins = frozen.files.mapIndexed { index, uri -> UploadPreparationSource(sourceKey(uri, false), tr("所选文件 ${index + 1}", "Selected file ${index + 1}", "選択したファイル ${index + 1}", "선택한 파일 ${index + 1}", "所選檔案 ${index + 1}"), false) } +
            frozen.folders.mapIndexed { index, uri -> UploadPreparationSource(sourceKey(uri, true), tr("所选文件夹 ${index + 1}", "Selected folder ${index + 1}", "選択したフォルダー ${index + 1}", "선택한 폴더 ${index + 1}", "所選資料夾 ${index + 1}"), true) }
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
        PikoLog.i(TAG, "Upload preparation ${preparation.id} accepted: items=${frozen.count}, target=${logFolder(parentId)}")
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
                work.copy(result = UploadPreparationResult(UploadPreparationCompletion.STOPPED, tr("上传准备已停止，请核对队列中的文件与目标文件夹", "Upload preparation stopped. Check queued files and destination folder", "アップロードの準備が停止しました。キュー内のファイルと保存先フォルダーを確認してください", "업로드 준비가 중지되었습니다. 대기열의 파일과 대상 폴더를 확인해 주세요", "上傳準備已停止，請核對佇列中的檔案與目標資料夾"), nowMs())) else work } }
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
            if (work.id !in preparationJobs) throw CancellationException(tr("上传准备已移除", "Upload preparation removed", "アップロードの準備を削除しました", "업로드 준비를 삭제했습니다", "上傳準備已移除"))
            if (batchName == null) {
                val firstName = work.sources.firstOrNull()?.title ?: task.fileName
                batchName = if (selection.files.isEmpty() && selection.folders.size == 1) firstName else tr("$firstName 等 ${selection.count} 项", "$firstName and ${enCount(selection.count, "item", "items")}", "$firstName ほか ${selection.count} 項目", "$firstName 등 ${selection.count}개 항목", "$firstName 等 ${selection.count} 項")
            }
            val queued = if (grouped) task.copy(batchId = work.id, batchName = batchName,
                batchParentId = work.parentId, batchParentName = work.parentName, batchIsFolder = selection.folders.isNotEmpty()) else task
            val accepted = if (work.id in pausedBatches) queued.copy(status = UploadStatus.PAUSED) else queued
            _tasks.update { it + (accepted.taskId to accepted) }
            bumpStructuralRevision()
            work = work.copy(files = work.files + UploadPreparedFile(key, path, task.taskId)); publish()
        }
        suspend fun readFile(key: String, path: String, uri: String, parent: String, name: String): UploadTask? {
            val task = runSuspendCatching { newTask(client.account, uri, parent, name) }
                .onFailure { PikoLog.w(TAG, "Upload preparation ${work.id}: failed to inspect source file (key=$key)", it) }
                .getOrNull()
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
                val folder = runSuspendCatching { sources.listFolder(uri) }
                    .onFailure { PikoLog.w(TAG, "Upload preparation ${work.id}: failed to enumerate source folder (key=$key)", it) }
                    .getOrNull()
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
                        check(id.isNotBlank()) { tr("新建文件夹响应缺少 ID", "Folder creation response missing ID", "フォルダー作成の応答にIDが含まれていません", "폴더 생성 응답에 ID가 없습니다", "新增資料夾回應缺少 ID") }
                        directories[path] = id
                        work = work.copy(directories = work.directories + UploadPreparedDirectory(key, path, id, parent)); publish()
                        driveRepository.applyWriteChange(client.account, DriveChange.Created(parent, id, name, isFolder = true))
                        return id
                    } catch (error: Exception) {
                        blocked += path
                        if (attempted) {
                            val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
                            issue(key, path, if (refused) UploadPreparationIssueKind.DIRECTORY_REFUSED else UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED)
                            if (error !is CancellationException) {
                                PikoLog.w(
                                    TAG,
                                    "Upload preparation ${work.id}: folder creation ${if (refused) "refused" else "unconfirmed"} in parent=${logFolder(parent)}, relDepth=${if (path.isEmpty()) 0 else path.count { it == '/' } + 1}",
                                    error,
                                )
                            }
                            driveRepository.applyWriteChange(client.account, DriveChange.ContentsChanged(setOf(parent)))
                        }
                        if (error is CancellationException) throw error
                        return null
                    }
                }
                for ((index, entry) in folder.files.withIndex()) {
                    val sourcePath = listOf(entry.relativeDir, tr("所选文件 ${index + 1}", "Selected file ${index + 1}", "選択したファイル ${index + 1}", "선택한 파일 ${index + 1}", "所選檔案 ${index + 1}")).filter { it.isNotEmpty() }.joinToString("/")
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
            PikoLog.w(TAG, "Upload preparation ${work.id} failed unexpectedly", error)
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
                add(tr("已入队 ${work.files.size} 个文件", "Queued ${enCount(work.files.size, "file", "files")}", "${work.files.size} ファイルをキューに追加しました", "파일 ${work.files.size}개 대기열에 추가", "已將 ${work.files.size} 個檔案加入佇列"))
                if (work.directories.isNotEmpty()) add(tr("已创建 ${work.directories.size} 个文件夹", "Created ${enCount(work.directories.size, "folder", "folders")}", "${work.directories.size} 個のフォルダーを作成しました", "폴더 ${work.directories.size}개 생성 완료", "已建立 ${work.directories.size} 個資料夾"))
                val unknown = work.issues.count { it.kind == UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED }
                if (unknown > 0) add(tr("$unknown 个文件夹创建结果待确认", "$unknown folder creations unconfirmed", "$unknown 個のフォルダー作成結果が未確認です", "폴더 ${unknown}개 생성 결과 미확인", "$unknown 個資料夾建立結果待確認"))
                val skipped = work.issues.count { it.kind == UploadPreparationIssueKind.NOT_ATTEMPTED }
                if (skipped > 0) add(tr("$skipped 个文件未入队", "${enCount(skipped, "file", "files")} not queued", "$skipped ファイルをキューに追加していません", "파일 ${skipped}개 대기열에 추가 안 됨", "$skipped 個檔案未加入佇列"))
                val failed = work.issues.size - unknown - skipped
                if (failed > 0) add(tr("$failed 项未准备成功", "${enCount(failed, "item", "items")} failed preparation", "$failed 項目の準備に失敗しました", "${failed}개 항목 준비 실패", "$failed 項未準備成功"))
                if (stopped) add(tr("准备已停止，请核对目标文件夹", "Preparation stopped. Check the destination folder", "準備を停止しました。保存先フォルダーを確認してください", "준비를 중지했습니다. 대상 폴더를 확인해 주세요", "準備已停止，請核對目標資料夾"))
                if (unexpected) add(tr("准备出错，请核对队列中的文件与目标文件夹", "Preparation failed. Check queued files and destination folder", "準備中にエラーが発生しました。キュー内のファイルと保存先フォルダーを確認してください", "준비 중 오류가 발생했습니다. 대기열의 파일과 대상 폴더를 확인해 주세요", "準備發生錯誤，請核對佇列中的檔案與目標資料夾"))
            }.joinToString("，")
            work = work.copy(result = UploadPreparationResult(completion, message, nowMs()))
            PikoLog.i(TAG, "Upload preparation ${work.id} finished: status=$completion, queuedFiles=${work.files.size}, createdDirs=${work.directories.size}, issues=${work.issues.size}")
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
        val account = clientProvider.currentClient.value?.account ?: return tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")
        if (_isRestoring.value) return tr("正在恢复上传任务，请稍候", "Restoring upload tasks. Please wait", "アップロードタスクを復元中です。しばらくお待ちください", "업로드 작업을 복원 중입니다. 잠시 기다려 주세요", "正在恢復上傳任務，請稍候")
        if (_tasks.value.values.any { it.account == account && it.parentId == parentId && it.sourceUri in selection.files && it.status != UploadStatus.COMPLETED })
            return tr("此文件已有未完成的上传任务，请在任务页继续或移除原任务", "An unfinished upload already exists for this file. Resume or remove it in Tasks", "このファイルには未完了のアップロードがあります。タスク画面で再開または削除してください", "이 파일에 미완료 업로드가 있습니다. 작업 페이지에서 계속하거나 삭제해 주세요", "此檔案已有未完成的上傳任務，請至任務頁繼續或移除原任務")
        val keys = selection.files.map { sourceKey(it, false) } + selection.folders.map { sourceKey(it, true) }
        return tr("此文件夹中已有相同来源的上传记录，请先在任务页核对", "An upload from this source already exists in this folder. Check Tasks first", "このフォルダーには同じソースのアップロード記録があります。先にタスク画面で確認してください", "이 폴더에 동일한 원본의 업로드 기록이 있습니다. 먼저 작업 페이지에서 확인해 주세요", "此資料夾中已有相同來源的上傳記錄，請先至任務頁核對").takeIf {
            snapshot.any { it.account == account && it.parentId == parentId && it.blockedSources.any { key -> key in keys } }
        }
    }
    private fun sourceKey(uri: String, folder: Boolean) = fileCacheName("$folder:$uri")

    /** 继续暂停的任务，或重试失败的任务：放回队尾，轮到它时从断点接着传。 */
    fun resume(taskId: String) = resumeInternal(taskId, log = true)

    private fun resumeInternal(taskId: String, log: Boolean) {
        var resumed = false
        update(taskId) {
            if (it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED) {
                resumed = true
                it.copy(status = UploadStatus.QUEUED, errorMessage = null, queuedAtMs = nowMs())
            } else {
                it
            }
        }
        if (log && resumed) {
            PikoLog.d(TAG, "Resumed upload task: task=$taskId")
        }
    }

    fun pause(taskId: String) {
        var paused = false
        var snapshot: UploadTask? = null
        update(taskId) {
            if (it.status.isActive) {
                paused = true
                snapshot = it
                it.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L)
            } else {
                it
            }
        }
        if (paused) {
            PikoLog.d(TAG, "Paused upload task: task=$taskId, prevStage=${snapshot?.status}, processed=${snapshot?.processedBytes}/${snapshot?.size} B")
        }
        cancelRunning(taskId)
    }

    fun pauseBatch(batchId: String) {
        var pausedCount = 0
        val ids = runBlocking { preparationLock.withLock {
            if (batchId in preparationJobs) pausedBatches += batchId
            val pair = pauseBatchTasks(batchId)
            pausedCount = pair.second
            pair.first
        } }
        if (pausedCount > 0) {
            PikoLog.i(TAG, "Paused upload batch: batch=$batchId, paused=$pausedCount")
        }
        ids.forEach(::cancelRunning)
    }

    private fun pauseBatchTasks(batchId: String): Pair<Set<String>, Int> {
        val ids = _tasks.value.values.filter { it.batchId == batchId }.mapTo(HashSet()) { it.taskId }
        if (ids.isEmpty()) return ids to 0
        var pausedCount = 0
        _tasks.update { tasks ->
            var localPaused = 0
            val updated = tasks.mapValues { (id, task) ->
                if (id in ids && task.status.isActive) {
                    localPaused++
                    task.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L)
                } else task
            }
            pausedCount = localPaused
            if (localPaused == 0) tasks else updated
        }
        if (pausedCount > 0) bumpStructuralRevision()
        return ids to pausedCount
    }

    fun resumeBatch(batchId: String) {
        var resumedCount = 0
        runBlocking { preparationLock.withLock {
            pausedBatches.remove(batchId)
            _tasks.value.values.filter { it.batchId == batchId }.forEach { task ->
                if (task.status == UploadStatus.PAUSED || task.status == UploadStatus.FAILED) {
                    resumedCount++
                    resumeInternal(task.taskId, log = false)
                }
            }
        } }
        if (resumedCount > 0) {
            PikoLog.i(TAG, "Resumed upload batch: batch=$batchId, resumed=$resumedCount")
        }
    }

    fun removeBatch(batchId: String) {
        val (preparing, ids) = runBlocking { preparationLock.withLock {
            pausedBatches.remove(batchId)
            preparationJobs.remove(batchId) to pauseBatchTasks(batchId).first
        } }
        PikoLog.i(TAG, "Removing upload batch: batch=$batchId, tasks=${ids.size}, wasPreparing=${preparing != null}")
        preparing?.cancel()
        ids.forEach { removeInternal(it, log = false) }
    }

    /** 把所有未完成的任务转为暂停。Android 前台服务被系统叫停时用。 */
    fun pauseAll() {
        var pausedCount = 0
        _tasks.update { tasks ->
            var localPaused = 0
            val updated = tasks.mapValues { (_, task) ->
                if (task.status.isActive) {
                    localPaused++
                    task.copy(status = UploadStatus.PAUSED, speedBytesPerSec = 0L)
                } else task
            }
            pausedCount = localPaused
            if (localPaused == 0) tasks else updated
        }
        if (pausedCount > 0) {
            bumpStructuralRevision()
            PikoLog.i(TAG, "Paused all active uploads: count=$pausedCount")
        }
        running.value.values.forEach { it.cancel() }
    }

    /**
     * 移除任务。未完成的一并放弃网盘里那个上传中的文件，否则它会一直挂在目录里；
     * 已完成的只删记录，文件留在网盘里。
     */
    fun remove(taskId: String) = removeInternal(taskId, log = true)

    private fun removeInternal(taskId: String, log: Boolean) {
        val task = _tasks.value[taskId] ?: return
        val job = cancelRunning(taskId)
        var removed = false
        _tasks.update { tasks ->
            if (taskId in tasks) {
                removed = true
                tasks - taskId
            } else {
                removed = false
                tasks
            }
        }
        if (removed) bumpStructuralRevision()
        releaseIfUnused(task.sourceUri)
        if (log) {
            PikoLog.d(TAG, "Removed upload task: task=$taskId, status=${task.status}, remoteFile=${task.session?.fileId ?: task.fileId ?: "none"}")
        }
        val session = task.session ?: return
        if (task.status == UploadStatus.COMPLETED) return
        scope.launch(Dispatchers.IO) {
            job?.join()
            val client = clientProvider.currentClient.value?.takeIf { it.account == task.account } ?: run {
                PikoLog.i(TAG, "Removed upload task $taskId while account is inactive; remote pending file ${session.fileId} left in ${logFolder(task.parentId)}")
                return@launch
            }
            runSuspendCatching { client.cancelUpload(session) }
                .logFailure(TAG, "Failed to cancel pending upload session on remove: task=$taskId, remoteFile=${session.fileId}")
            driveRepository.applyWriteChange(client.account, DriveChange.ContentsChanged(setOf(task.parentId)))
        }
    }

    /** 清掉当前账号全部已完成的记录。 */
    fun clearCompleted() {
        val account = clientProvider.currentClient.value?.account ?: return
        var cleared = 0
        _tasks.update { tasks ->
            val localCleared = tasks.values.count { it.account == account && it.status == UploadStatus.COMPLETED }
            cleared = localCleared
            if (localCleared == 0) tasks else tasks.filterValues { it.account != account || it.status != UploadStatus.COMPLETED }
        }
        if (cleared > 0) {
            bumpStructuralRevision()
            PikoLog.d(TAG, "Cleared $cleared completed upload task(s)")
        }
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
     * 读回上次保存的任务表。进行中与排队的重新入队，所属账号可用后自动续传；手动暂停的保持暂停。
     */
    private suspend fun restore() {
        val raw = preferences.loadUploadTasks()
        if (raw.isBlank()) return
        val saved = runSuspendCatching {
            json.decodeFromString(taskListSerializer, raw)
        }.logFailure(TAG, "Failed to decode persisted upload tasks, starting with empty queue").getOrDefault(emptyList())
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
                task.status.isActive -> task.copy(status = UploadStatus.QUEUED, processedBytes = 0L, speedBytesPerSec = 0L)
                else -> task.copy(processedBytes = 0L, speedBytesPerSec = 0L)
            }
        }
        PikoLog.i(
            TAG,
            "Restored ${restored.size} upload task(s): incomplete=${restored.count { it.status != UploadStatus.COMPLETED }}, " +
                "resumableSessions=${withCredentials.count { it.session?.credentials != null }}, " +
                "missingSessionCredentials=${withCredentials.count { it.session != null && it.session.credentials == null }}",
        )
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
        bumpStructuralRevision()
    }

    private data class UploadPersistenceKey(
        val status: UploadStatus,
        val gcid: String?,
        val uploadId: String?,
        val settledBytes: Long,
    )

    /**
     * 状态、gcid 或会话变化时保存整张表。后两者不等状态变化：会话一建好就要落盘，
     * 否则进程这时被杀，网盘里留下一个上传中的文件，谁也接不上它。
     *
     * 凭据先存、任务表后存：进程死在两步之间时，任务表里的会话不会缺凭据。
     */
    private suspend fun persistOnStructuralChange() {
        structuralRevision
            .distinctUntilChangedBy {
                _tasks.value.mapValues { (_, task) ->
                    UploadPersistenceKey(
                        status = task.status,
                        gcid = task.gcid,
                        uploadId = task.session?.uploadId,
                        settledBytes = if (task.status.isActive) 0L else task.processedBytes,
                    )
                }
            }
            .collect { flushPersistence() }
    }

    suspend fun flushPersistence() {
        restoreCompleted.await()
        withContext(Dispatchers.IO) {
            persistLock.withLock {
                val tasks = _tasks.value
                saveCredentials(tasks)
                val withoutCredentials = tasks.values.map { task -> task.copy(session = task.session?.withCredentials(null)) }
                val serialized = json.encodeToString(taskListSerializer, withoutCredentials)
                runSuspendCatching { preferences.saveUploadTasks(serialized) }.logFailure(TAG, "Failed to save upload task table")
            }
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
                .logFailure(TAG, "Failed to save upload session credentials for task=$taskId")
        }
        for (taskId in storedCredentials.keys - current.keys) {
            runSuspendCatching { preferences.clearUploadCredentials(taskId) }
                .onSuccess { storedCredentials -= taskId }
                .logFailure(TAG, "Failed to clear upload session credentials for task=$taskId")
        }
    }

    private suspend fun loadCredentials(taskId: String): OssCredentials? =
        runSuspendCatching { preferences.loadUploadCredentials(taskId)?.let { json.decodeFromString(OssCredentials.serializer(), it) } }
            .logFailure(TAG, "Failed to load upload session credentials for task=$taskId")
            .getOrNull()

    /**
     * 当前账号按入队顺序补满空位。换号或退出登录时 collectLatest 等全部子任务取消后再切换。
     */
    private suspend fun runQueue() {
        clientProvider.currentClient.collectLatest { client ->
            if (client == null) return@collectLatest
            coroutineScope {
                combine(structuralRevision, running) { _, _ -> Unit }.collect {
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
        val initialTask = _tasks.value[taskId]
        if (initialTask?.status != UploadStatus.QUEUED) return
        val initialQueuedAtMs = initialTask.queuedAtMs
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
            var autoPaused = false
            var prevStage: UploadStatus? = null
            var prevSize: Long? = null
            val latestProgress = progress.value
            update(taskId) {
                val syncedBytes = latestProgress.coerceIn(it.processedBytes, it.size)
                when {
                    it.status == UploadStatus.HASHING || it.status == UploadStatus.UPLOADING -> {
                        autoPaused = true
                        prevStage = it.status
                        prevSize = it.size
                        it.copy(status = UploadStatus.PAUSED, processedBytes = syncedBytes, speedBytesPerSec = 0L)
                    }
                    it.status == UploadStatus.PAUSED && it.queuedAtMs == initialQueuedAtMs && syncedBytes != it.processedBytes -> {
                        it.copy(processedBytes = syncedBytes, speedBytesPerSec = 0L)
                    }
                    else -> it
                }
            }
            if (autoPaused) {
                PikoLog.i(
                    TAG,
                    "Upload task auto-paused on session cancellation: task=$taskId, prevStage=$prevStage, processed=$latestProgress/$prevSize B",
                )
            }
            throw e
        } catch (e: Throwable) {
            // 分片会话建立前网盘尚无文件 ID，固定打本地 taskId，已有会话时一并附上远端 fileId
            val task = _tasks.value[taskId]
            val remoteRef = task?.session?.fileId?.let { ", remote=${logFile(it, task.fileName)}" }
                ?: ", ext=${task?.fileName?.substringAfterLast('.', "")?.lowercase()?.ifBlank { "none" } ?: "none"}"
            val latestProgress = progress.value
            val currentBytes = latestProgress.takeIf { it > 0L } ?: task?.processedBytes ?: 0L
            PikoLog.w(
                TAG,
                "Upload failed: task=$taskId$remoteRef, stage=${task?.status}, " +
                    "processed=$currentBytes/${task?.size} B${if (e is SourceUnavailableException) ", reason=source_unreadable" else ""}",
                e,
            )
            update(taskId) {
                it.copy(
                    status = UploadStatus.FAILED,
                    processedBytes = latestProgress.coerceIn(it.processedBytes, it.size),
                    speedBytesPerSec = 0L,
                    errorMessage = failureMessage(e),
                )
            }
        }
    }

    private suspend fun upload(client: PikPakClient, taskId: String, progress: MutableStateFlow<Long>) {
        var task = _tasks.value[taskId] ?: return
        val started = TimeSource.Monotonic.markNow()
        val info = sources.describe(task.sourceUri) ?: throw SourceUnavailableException()
        if (info.size != task.size || info.lastModifiedMs != task.lastModifiedMs) {
            // 文件改过：若此前已算过 gcid 或建过分片会话，它们都属于旧内容，必须作废
            val hadCachedState = task.gcid != null || task.session != null
            if (hadCachedState) {
                PikoLog.w(
                    TAG,
                    "Upload $taskId: source file changed (size=${task.size}->${info.size}, mtime=${task.lastModifiedMs}->${info.lastModifiedMs}); " +
                        "discarding cachedGcid=${task.gcid != null}, abandonedRemoteFile=${task.session?.fileId ?: "none"}",
                )
            } else {
                PikoLog.i(
                    TAG,
                    "Upload $taskId: source metadata updated before hashing (size=${task.size}->${info.size}, mtime=${task.lastModifiedMs}->${info.lastModifiedMs})",
                )
            }
            task.session?.let { abandon(client, taskId, it) }
            task = updated(taskId) {
                it.copy(size = info.size, lastModifiedMs = info.lastModifiedMs, gcid = null, session = null, processedBytes = 0L)
            }
        }

        var hashedThisRun = false
        var hashMs = 0L
        val gcid = task.gcid ?: run {
            hashedThisRun = true
            val hashStarted = TimeSource.Monotonic.markNow()
            update(taskId) { it.copy(status = UploadStatus.HASHING, processedBytes = 0L) }
            progress.value = 0L
            computeGcid(client, task, progress).also { computed ->
                hashMs = hashStarted.elapsedNow().inWholeMilliseconds
                update(taskId) { it.copy(gcid = computed) }
            }
        }

        // 凭据过期或没能从机密存储读回的会话只剩放弃一条路；OSS 以 403 拒绝时同理，再开一个会话重传一次
        var session = task.session?.takeIf { it.canContinue(taskId) } ?: run {
            task.session?.let {
                val reason = if (it.credentials == null) "missing_credentials" else "credentials_expired_or_expiring"
                PikoLog.i(TAG, "Upload $taskId: abandoning previous session (remoteFile=${it.fileId}, reason=$reason)")
                abandon(client, taskId, it)
            }
            when (val startedSession = startSession(client, taskId, task, gcid)) {
                is SessionStartResult.Instant -> {
                    PikoLog.i(
                        TAG,
                        "Upload completed (instant): task=$taskId, remote=${logFile(startedSession.fileId, task.fileName)}, " +
                            "size=${task.size} B, totalMs=${started.elapsedNow().inWholeMilliseconds}${if (hashedThisRun) ", hashMs=$hashMs" else ""}",
                    )
                    return
                }
                is SessionStartResult.Pending -> startedSession.session
            }
        }
        if (session === task.session) {
            PikoLog.d(TAG, "Upload $taskId: resuming existing multipart session (remote=${logFile(session.fileId, task.fileName)})")
        }
        val transmittedBytes = MutableStateFlow(0L)
        var uploadStarted = TimeSource.Monotonic.markNow()
        var retried = false
        while (true) {
            // 进度归零：此前的数值是校验读过的字节数，continueUpload 随即报出 OSS 已收下的量
            progress.value = 0L
            transmittedBytes.value = 0L
            uploadStarted = TimeSource.Monotonic.markNow()
            update(taskId) { it.copy(status = UploadStatus.UPLOADING, session = session, processedBytes = 0L) }
            try {
                withPartBuffer(session) {
                    client.continueUpload(
                        session,
                        open = { offset ->
                            ThrottledSource(sources.open(task.sourceUri, offset), limiter) { read ->
                                transmittedBytes.update { it + read }
                            }
                        },
                        onProgress = { progress.value = it },
                    )
                }
                break
            } catch (e: PikPakException) {
                if (e.httpStatus != 403 || retried) throw e
                PikoLog.w(TAG, "Upload $taskId: OSS rejected credentials with 403 for remoteFile=${session.fileId}, starting new session", e)
                retried = true
                abandon(client, taskId, session)
                session = when (val restarted = startSession(client, taskId, task, gcid)) {
                    is SessionStartResult.Instant -> {
                        PikoLog.i(
                            TAG,
                            "Upload completed (instant on session reopen): task=$taskId, remote=${logFile(restarted.fileId, task.fileName)}, " +
                                "size=${task.size} B, totalMs=${started.elapsedNow().inWholeMilliseconds}",
                        )
                        return
                    }
                    is SessionStartResult.Pending -> restarted.session
                }
            }
        }
        val uploadMs = uploadStarted.elapsedNow().inWholeMilliseconds
        val totalMs = started.elapsedNow().inWholeMilliseconds
        val uploadedNow = transmittedBytes.value
        val rateSummary = if (uploadedNow > 0L) "${uploadedNow * 1000L / uploadMs.coerceAtLeast(1L) / 1024L} KiB/s" else "all_parts_present"
        PikoLog.i(
            TAG,
            "Upload completed: task=$taskId, remote=${logFile(session.fileId, task.fileName)}, " +
                "size=${task.size} B, uploadedNow=$uploadedNow B, uploadMs=$uploadMs ($rateSummary), " +
                "totalMs=$totalMs${if (hashedThisRun) ", hashMs=$hashMs" else ""}",
        )
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

    private sealed interface SessionStartResult {
        data class Instant(val fileId: String) : SessionStartResult
        data class Pending(val session: UploadSession) : SessionStartResult
    }

    /** 开一个上传会话。服务端认得这个 gcid 时直接完成（秒传），否则落盘分片会话供后续传输。 */
    private suspend fun startSession(client: PikPakClient, taskId: String, task: UploadTask, gcid: String): SessionStartResult {
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
                return SessionStartResult.Instant(start.fileId)
            }
            is UploadStart.Pending -> {
                // 会话先落盘再开始传：这之后进程被杀，下次启动还能接上
                update(taskId) { it.copy(session = start.session) }
                return SessionStartResult.Pending(start.session)
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
                PikoLog.d(TAG, "Upload ${task.taskId}: Xunlei CID matched global index, skipped full GCID scan")
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
            .also { PikoLog.d(TAG, "Upload ${task.taskId}: computed full GCID for ${task.size} B in ${started.elapsedNow().inWholeMilliseconds} ms") }
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
            if (!stillNeeded) runCatching { sources.release(key) }.logFailure(TAG, "Failed to release upload source permission")
        } }
    }

    private fun retainSources(keys: Set<String>) {
        keys.forEach { key -> runCatching { sources.retain(key) }.onFailure { PikoLog.w(TAG, "Failed to retain upload source permission", it) } }
    }

    /** 放弃一个会话：尽力而为，失败只意味着网盘里多留一个上传中的文件。 */
    private suspend fun abandon(client: PikPakClient, taskId: String, session: UploadSession) {
        withContext(NonCancellable) {
            runSuspendCatching { client.cancelUpload(session) }
                .logFailure(TAG, "Failed to cancel abandoned upload session: task=$taskId, remoteFile=${session.fileId}")
        }
    }

    /**
     * 按固定间隔把进度与速度写进任务表（与 [dev.piko.shared.download.PikoDownloadCoordinator] 的定时采样循环一致），
     * 避免每次分片进度回调都触发任务表整表复制。速度按最近 [SPEED_WINDOW_MS] 滑动窗口计算：只看相邻两次采样的话，
     * 分片一块块发出去，读数会在 0 与峰值之间来回跳。
     */
    private suspend fun reportProgress(taskId: String, progress: StateFlow<Long>) {
        val clock = TimeSource.Monotonic
        val samples = ArrayDeque<Pair<TimeMark, Long>>()
        var lastReportedBytes = progress.value
        var lastReportedSpeed = 0L
        samples.addLast(clock.markNow() to lastReportedBytes)
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val bytes = progress.value
            // 校验转上传时进度从头算，窗口里的旧读数不再可比，从这一刻重新攒
            if (bytes < samples.last().second) {
                samples.clear()
                lastReportedBytes = -1L
            }
            samples.addLast(clock.markNow() to bytes)
            while (samples.size > 2 && samples.first().first.elapsedNow().inWholeMilliseconds > SPEED_WINDOW_MS) samples.removeFirst()
            val (oldestMark, oldestBytes) = samples.first()
            val elapsedMs = oldestMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
            val speed = ((bytes - oldestBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            if (bytes == lastReportedBytes) {
                if (speed == 0L && lastReportedSpeed != 0L) {
                    lastReportedSpeed = 0L
                    updateProgress(taskId, bytes, 0L)
                }
                continue
            }
            lastReportedBytes = bytes
            lastReportedSpeed = speed
            updateProgress(taskId, bytes, speed)
        }
    }

    private fun failureMessage(e: Throwable): String = when {
        e is SourceUnavailableException -> tr("无法读取源文件，请重新选择", "Unable to read source file. Select it again", "元のファイルを読み込めません。選択し直してください", "원본 파일을 읽을 수 없습니다. 다시 선택해 주세요", "無法讀取來源檔案，請重新選擇")
        e.isNetworkFailure() -> tr("网络中断", "Network interrupted", "ネットワークが切断されました", "네트워크 연결이 끊어졌습니다", "網路中斷")
        else -> dev.piko.shared.notice.PikoErrorMapper.failure(tr("上传", "Upload", "アップロード", "업로드", "上傳"), e)
    }

    private fun updateProgress(taskId: String, bytes: Long, speed: Long) {
        _tasks.update { tasks ->
            val current = tasks[taskId] ?: return@update tasks
            if (!current.status.isActive || (current.processedBytes == bytes && current.speedBytesPerSec == speed)) {
                tasks
            } else {
                tasks + (taskId to current.copy(processedBytes = bytes, speedBytesPerSec = speed))
            }
        }
    }

    private fun update(taskId: String, transform: (UploadTask) -> UploadTask) {
        var changed = false
        _tasks.update { tasks ->
            val current = tasks[taskId]
            if (current == null) {
                changed = false
                tasks
            } else {
                val next = transform(current)
                if (next == current) {
                    changed = false
                    tasks
                } else {
                    changed = true
                    tasks + (taskId to next)
                }
            }
        }
        if (changed) bumpStructuralRevision()
    }

    /** 任务在途中被删除时抛出取消，[run] 随之结束。 */
    private fun updated(taskId: String, transform: (UploadTask) -> UploadTask): UploadTask {
        update(taskId, transform)
        return _tasks.value[taskId] ?: throw CancellationException(tr("任务已移除", "Task removed", "タスクを削除しました", "작업을 삭제했습니다", "任務已移除"))
    }

    private fun UploadSession.canContinue(taskId: String): Boolean {
        if (credentials == null) return false
        val expiresAt = runCatching { Instant.parse(expiration) }
            .onFailure { PikoLog.w(TAG, "Upload $taskId: invalid session expiration '$expiration', treating as expired", it) }
            .getOrNull() ?: return false
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
    private class ThrottledSource(
        private val inner: RawSource,
        private val limiter: BandwidthLimiter,
        private val onRead: (Long) -> Unit = {},
    ) : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            val read = inner.readAtMostTo(sink, minOf(byteCount, CHUNK))
            if (read > 0) {
                onRead(read)
                if (limiter.bytesPerSecond != null) runBlocking { limiter.acquire(read) }
            }
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
