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
    val status: OfflineSubmitStatus = OfflineSubmitStatus.SUBMITTING, val message: String = tr("正在提交离线下载", "Submitting cloud download", "クラウドダウンロードを送信中", "클라우드 다운로드 제출 중", "正在提交離線下載"),
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
            ?: return Result.failure(IllegalStateException(tr("账号已切换，未提交离线下载", "Account changed; cloud download not submitted", "アカウントが切り替わったため、クラウドダウンロードは送信されませんでした", "계정이 전환되어 클라우드 다운로드를 제출하지 않았습니다", "帳號已切換，未提交離線下載")))
        val work = OfflineSubmitWork(ids.getAndUpdate { it + 1 } + 1, owner.account, target, title.ifBlank { tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載") }, now(), requestKey(url))
        while (true) {
            val before = _works.value
            val pending = conflict(owner.account, url, target.id, before)
            if (pending != null) return Result.failure(OfflineSubmitException(pending.id, tr("此文件夹已有相同链接的待确认请求，请先在任务页核对", "A submission for this link in this folder is unconfirmed. Check Tasks first", "このフォルダーには同じリンクの未確認リクエストがあります。先にタスク画面で確認してください", "이 폴더에 동일한 링크의 미확인 요청이 있습니다. 먼저 작업 페이지에서 확인해 주세요", "此資料夾已有相同連結的待確認請求，請先至任務頁核對")))
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
                    if (result.task.id.isBlank()) throw IllegalStateException(tr("离线下载响应缺少任务 ID", "Cloud download response missing task ID", "クラウドダウンロードの応答にタスクIDが含まれていません", "클라우드 다운로드 응답에 작업 ID가 없습니다", "離線下載回應缺少任務 ID"))
                    observeTasks(owner.account, listOf(result.task), target.id)
                    _works.update { all -> all.filterNot { it.id == work.id } }
                }
                is CreateUrlResult.InstantComplete -> {
                    val file = result.file
                    // SDK 的无 task 分支并未固定协议；目录根或空响应不能证明整包已保存。
                    if (requiresTask || file == null || file.id.isBlank() || file.isFolder || file.trashed || file.phase != TaskPhase.COMPLETE || file.parentId != target.id) {
                        val message = if (requiresTask && file?.id?.isNotBlank() == true) tr("云端已保存文件，但未返回任务 ID，未执行自动裁剪与重命名", "Files saved in cloud without a task ID; automatic trimming and renaming skipped", "クラウドに保存されましたがタスクIDが返されなかったため、自動整理と名前変更をスキップしました", "클라우드에 파일이 저장되었으나 작업 ID가 반환되지 않아 자동 정리 및 이름 변경을 건너뛰었습니다", "雲端已儲存檔案，但未傳回任務 ID，未執行自動裁剪與重新命名")
                            else tr("离线下载未返回有效任务或文件，请核对云端任务与目标文件夹", "No verifiable task or file returned. Check cloud tasks and destination folder", "有効なタスクまたはファイルが返されませんでした。クラウドタスクと保存先フォルダーを確認してください", "확인 가능한 작업이나 파일이 반환되지 않았습니다. 클라우드 작업과 대상 폴더를 확인해 주세요", "離線下載未傳回有效任務或檔案，請核對雲端任務與目標資料夾")
                        finish(OfflineSubmitStatus.UNCONFIRMED, message, file?.id?.takeIf { it.isNotBlank() })
                        return Result.failure(OfflineSubmitException(work.id, message))
                    }
                    finish(OfflineSubmitStatus.COMPLETE, tr("云端保存已完成", "Saved to cloud", "クラウドに保存しました", "클라우드에 저장했습니다", "雲端儲存已完成"), file.id)
                }
            }
            return Result.success(result)
        } catch (error: CancellationException) {
            finish(if (sent) OfflineSubmitStatus.UNCONFIRMED else OfflineSubmitStatus.REFUSED,
                if (sent) tr("离线下载提交结果待确认，请核对云端任务与目标文件夹", "Cloud download submission unconfirmed. Check cloud tasks and destination folder", "クラウドダウンロードの送信結果が未確認です。クラウドタスクと保存先フォルダーを確認してください", "클라우드 다운로드 제출 결과가 미확인 상태입니다. 클라우드 작업과 대상 폴더를 확인해 주세요", "離線下載提交結果待確認，請核對雲端任務與目標資料夾") else tr("离线下载未提交", "Cloud download not submitted", "クラウドダウンロードは送信されませんでした", "클라우드 다운로드를 제출하지 않았습니다", "離線下載未提交"))
            throw error
        } catch (error: Exception) {
            val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
            val message = if (refused) tr("服务端拒绝了离线下载请求", "Server rejected cloud download request", "サーバーがクラウドダウンロードを拒否しました", "서버에서 클라우드 다운로드 요청을 거부했습니다", "伺服器拒絕了離線下載請求") else tr("离线下载提交结果待确认，请核对云端任务与目标文件夹", "Cloud download submission unconfirmed. Check cloud tasks and destination folder", "クラウドダウンロードの送信結果が未確認です。クラウドタスクと保存先フォルダーを確認してください", "클라우드 다운로드 제출 결과가 미확인 상태입니다. 클라우드 작업과 대상 폴더를 확인해 주세요", "離線下載提交結果待確認，請核對雲端任務與目標資料夾")
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
