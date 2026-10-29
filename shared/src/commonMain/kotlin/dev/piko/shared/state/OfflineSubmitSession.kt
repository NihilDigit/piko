package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.media.cache.fileCacheName
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.createUrlFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Clock

enum class OfflineSubmitStatus { SUBMITTING, COMPLETE, REFUSED, UNCONFIRMED }

/** 只记录提交回执；有远端任务引用后由云端任务列表或整包跟踪器接管。 */
data class OfflineSubmitWork(val id: Long, val account: String, val target: PikoPathBreadcrumb,
    val title: String, val createdAtMs: Long, internal val requestKey: String,
    val status: OfflineSubmitStatus = OfflineSubmitStatus.SUBMITTING, val message: String = tr("正在提交云端离线", "Submitting cloud download", "クラウドダウンロードを追加中", "클라우드 다운로드 추가 중"),
    val outputId: String? = null, val finishedAtMs: Long? = null) {
    val isRunning: Boolean get() = status == OfflineSubmitStatus.SUBMITTING
}

class OfflineSubmitException(val workId: Long, message: String, cause: Throwable? = null) : Exception(message, cause)

/** 已知云端工作的依赖身份，不复制任务进度，也不包含来源链接。 */
data class OfflineReceivingTarget(val account: String, val taskId: String, val parentId: String, val fileId: String, val phase: String)

class OfflineSubmitSession(private val clients: PikoClientProvider) {
    private val ids = MutableStateFlow(0L)
    private val _works = MutableStateFlow<List<OfflineSubmitWork>>(emptyList())
    val works = _works.asStateFlow()
    private val _outcomes = MutableSharedFlow<OfflineSubmitWork>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()
    private val _receivingTargets = MutableStateFlow<List<OfflineReceivingTarget>>(emptyList())
    val receivingTargets = _receivingTargets.asStateFlow()

    fun observeTasks(account: String, tasks: List<DriveTask>, fallbackParent: String? = null) {
        if (clients.currentClient.value?.account != account) return
        _receivingTargets.update { before ->
            val observed = tasks.mapTo(HashSet()) { it.id }
            before.filterNot { it.account == account && it.taskId in observed } + tasks.mapNotNull { task ->
                if (task.phase != TaskPhase.PENDING && task.phase != TaskPhase.RUNNING) return@mapNotNull null
                val parent = task.params["parent_folder_id"] ?: fallbackParent ?: before.firstOrNull { it.account == account && it.taskId == task.id }?.parentId
                parent?.let { OfflineReceivingTarget(account, task.id, it, task.fileId, task.phase) }
            }
        }
    }

    fun forgetTasks(account: String, ids: Set<String> = emptySet(), phases: Set<String> = emptySet()) {
        _receivingTargets.update { all -> all.filterNot { it.account == account && (it.taskId in ids || it.phase in phases) } }
    }

    fun conflict(account: String, url: String, targetId: String, snapshot: List<OfflineSubmitWork> = _works.value): OfflineSubmitWork? {
        val key = requestKey(url)
        return snapshot.firstOrNull { it.account == account && it.target.id == targetId && it.requestKey == key &&
            (it.isRunning || it.status == OfflineSubmitStatus.UNCONFIRMED) }
    }

    suspend fun submit(url: String, target: PikoPathBreadcrumb, title: String, expectedAccount: String?, requiresTask: Boolean = false): Result<CreateUrlResult> {
        val owner = clients.currentClient.value?.takeIf { expectedAccount == null || it.account == expectedAccount }
            ?: return Result.failure(IllegalStateException(tr("账号已改变，尚未提交离线任务", "Account changed; cloud download not submitted", "アカウントが変わったため、クラウドダウンロードは未送信です", "계정이 변경되어 클라우드 다운로드를 제출하지 않았습니다")))
        val work = OfflineSubmitWork(ids.getAndUpdate { it + 1 } + 1, owner.account, target, title.ifBlank { tr("提交云端离线", "Submit cloud download", "クラウドダウンロードを追加", "클라우드 다운로드 추가") }, now(), requestKey(url))
        while (true) {
            val before = _works.value
            val pending = conflict(owner.account, url, target.id, before)
            if (pending != null) return Result.failure(OfflineSubmitException(pending.id, tr("相同链接在此目录的提交结果待确认，请先在任务页检查", "This link’s submission in this folder is unconfirmed. Check Tasks first", "このフォルダーへの同じリンクの送信結果は未確認です。先にタスク画面で確認してください", "이 폴더에 같은 링크의 제출 결과가 미확인입니다. 먼저 작업 페이지에서 확인해 주세요")))
            if (_works.compareAndSet(before, before + work)) break
        }
        var sent = false
        fun finish(status: OfflineSubmitStatus, message: String, outputId: String? = null) {
            val result = work.copy(status = status, message = message, outputId = outputId, finishedAtMs = now())
            _works.update { all ->
                val updated = all.map { if (it.id == work.id) result else it }
                val retained = updated.filter { !it.isRunning && it.status != OfflineSubmitStatus.UNCONFIRMED }.takeLast(100).mapTo(HashSet()) { it.id }
                updated.filter { it.isRunning || it.status == OfflineSubmitStatus.UNCONFIRMED || it.id in retained }
            }
            if (clients.currentClient.value?.account == work.account && _works.value.any { it.id == work.id }) _outcomes.tryEmit(result)
        }
        try {
            val result = withContext(Dispatchers.Default) {
                sent = true
                owner.createUrlFile(parentId = target.id, url = url)
            }
            when (result) {
                is CreateUrlResult.Queued -> {
                    if (result.task.id.isBlank()) throw IllegalStateException(tr("离线响应缺少任务 ID", "Cloud download response has no task ID", "クラウドダウンロードの応答にタスクIDがありません", "클라우드 다운로드 응답에 작업 ID가 없습니다"))
                    observeTasks(owner.account, listOf(result.task), target.id)
                    _works.update { all -> all.filterNot { it.id == work.id } }
                }
                is CreateUrlResult.InstantComplete -> {
                    val file = result.file
                    // SDK 的无 task 分支并未固定协议；目录根或空响应不能证明整包已保存。
                    if (requiresTask || file == null || file.id.isBlank() || file.isFolder || file.trashed || file.phase != TaskPhase.COMPLETE || file.parentId != target.id) {
                        val message = if (requiresTask && file?.id?.isNotBlank() == true) tr("已收到云端产出，但缺少整包任务引用，请检查保存内容；未执行清理或改名", "Cloud files received without a batch task reference. Check saved files; no cleanup or renaming was performed", "クラウドのファイルは届きましたが、一括タスクの参照がありません。保存内容を確認してください。整理や改名は行っていません", "클라우드 파일을 받았지만 전체 작업 참조가 없습니다. 저장 내용을 확인해 주세요. 정리나 이름 변경은 수행하지 않았습니다")
                            else tr("离线提交未返回可确认的任务或完整文件，请检查云端任务与实际目录", "Cloud download returned no verifiable task or complete files. Check cloud tasks and destination", "クラウドダウンロードから確認可能なタスクや完全なファイルが返されませんでした。クラウドタスクと保存先を確認してください", "클라우드 다운로드에서 확인 가능한 작업이나 전체 파일이 반환되지 않았습니다. 클라우드 작업과 대상 폴더를 확인해 주세요")
                        finish(OfflineSubmitStatus.UNCONFIRMED, message, file?.id?.takeIf { it.isNotBlank() })
                        return Result.failure(OfflineSubmitException(work.id, message))
                    }
                    finish(OfflineSubmitStatus.COMPLETE, tr("云端保存已完成", "Saved to cloud", "クラウドに保存しました", "클라우드에 저장했습니다"), file.id)
                }
            }
            return Result.success(result)
        } catch (error: CancellationException) {
            finish(if (sent) OfflineSubmitStatus.UNCONFIRMED else OfflineSubmitStatus.REFUSED,
                if (sent) tr("离线提交结果待确认，请检查云端任务与实际目录", "Cloud download submission is unconfirmed. Check cloud tasks and destination", "クラウドダウンロードの送信結果は未確認です。クラウドタスクと保存先を確認してください", "클라우드 다운로드 제출 결과가 미확인입니다. 클라우드 작업과 대상 폴더를 확인해 주세요") else tr("离线请求尚未提交", "Cloud download not submitted", "クラウドダウンロードは未送信です", "클라우드 다운로드를 제출하지 않았습니다"))
            throw error
        } catch (error: Exception) {
            val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
            val message = if (refused) tr("服务端拒绝了离线提交", "Server rejected cloud download", "サーバーがクラウドダウンロードを拒否しました", "서버에서 클라우드 다운로드를 거부했습니다") else tr("离线提交结果待确认，请检查云端任务与实际目录", "Cloud download submission is unconfirmed. Check cloud tasks and destination", "クラウドダウンロードの送信結果は未確認です。クラウドタスクと保存先を確認してください", "클라우드 다운로드 제출 결과가 미확인입니다. 클라우드 작업과 대상 폴더를 확인해 주세요")
            finish(if (refused) OfflineSubmitStatus.REFUSED else OfflineSubmitStatus.UNCONFIRMED, message)
            return Result.failure(OfflineSubmitException(work.id, message, error))
        }
    }

    fun forget(id: Long): Boolean {
        if (_works.value.any { it.id == id && it.isRunning }) return false
        _works.update { all -> all.filterNot { it.id == id } }
        return true
    }
    fun clear(account: String) {
        _works.update { all -> all.filterNot { it.account == account } }
        _receivingTargets.update { all -> all.filterNot { it.account == account } }
    }
    private fun requestKey(url: String): String = fileCacheName(extractLinks(url).singleOrNull()?.infoHash?.let { "magnet:$it" } ?: "url:$url")
    private fun now() = Clock.System.now().toEpochMilliseconds()
}
