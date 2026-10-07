package dev.piko.shared.state

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.rename.RenameRun
import dev.piko.shared.rename.RenameSource
import dev.piko.shared.rename.planRenames
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.shared.scrape.MetaTubeTitles
import io.github.nihildigit.pikpak.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 添加链接按番号规范命名、配了 MetaTube 时，保存不等片名：先以原名里的片名落盘，片名随后查到再把这次存下的文件
 * （与按番号命名的新建文件夹）改成带 MetaTube 片名的规范名，整批记一条可撤销的改动。
 *
 * 进程级：保存成功会话即结束，面板与网盘页也可能已不在，补名不能挂在它们上面。片名查询也从这里发起
 * （[lookUp]），面板只是等它的结果，所以面板关了查询照样进行，保存后接着等同一次查询，不再另查。
 * 换号时 [endAccount] 取消全部补名与查询，上一个账号的文件 ID 不能拿到下一个账号上改。
 *
 * 尚未上线：PikoServices 建了实例，但没有交给 InstantSheetState，面板里既不查片名也不补名。
 * InstantTitleFillSmokeTest 通过，实机却不生效，原因未查明；接线前要做的事见 docs/development/av-naming.md 末节。
 */
class InstantTitleFill(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val metaTube: MetaTubeService?,
    private val scope: CoroutineScope,
    /** 整批片名迟迟查不完就放弃，照原名留着，不打扰用户。单次请求另有 15 秒超时，见 MetaTubeClient。 */
    private val giveUpAfter: Duration = 3.minutes,
) {
    /** 这次保存落盘的一项：[savedName] 是落盘时用的名字，补名前名字已不是它的，说明别处改过，不碰。 */
    class Saved(val id: String, val savedName: String)

    /**
     * 一次保存要补的项。[rename] 按查到的片名重算 [saved] 各项的名字，一一对应；查询所用的番号与重算的规则
     * 都在保存那一刻定下，之后面板上再怎么改都与它无关。
     */
    class Request internal constructor(
        val account: String,
        val saved: List<Saved>,
        internal val lookup: Deferred<MetaTubeTitles>,
        val rename: (titles: Map<String, String>) -> List<String>,
    )

    // 当前账号的补名与查询都挂在它下面，换号时整个取消
    private var accountJob = SupervisorJob(scope.coroutineContext[Job])

    /** 配了 MetaTube 时查这些番号的片名，否则为 null。查询挂在进程级的作用域上，调用方取消等待不会取消查询。 */
    suspend fun lookUp(videos: List<AvInfo>): Deferred<MetaTubeTitles>? {
        val service = metaTube ?: return null
        if (videos.isEmpty() || !service.enabled.first()) return null
        return scope.async(accountJob) { service.titles(videos) }
    }

    /** 当前账号，没登录时为 null。保存时记下，补名前核对。 */
    fun currentAccount(): String? = clients.currentClient.value?.account

    /**
     * 等片名查完，把 [requests] 里仍叫保存时那个名字的项改成带片名的规范名。几次保存（批量添加链接的各行）
     * 合成一批，只记一条改动、弹一次提示。返回补名的任务，测试据此等它结束。
     */
    fun fill(requests: List<Request>): Job? {
        if (requests.isEmpty()) return null
        return scope.launch(accountJob) {
            val titles = withTimeoutOrNull(giveUpAfter) { requests.map { it.lookup.titlesOrEmpty() } }
            if (titles == null) {
                PikoLog.i(TAG, "片名 ${giveUpAfter.inWholeSeconds} 秒内未查完，放弃补名")
                requests.forEach { it.lookup.cancel() }
                return@launch
            }
            val wanted = withContext(Dispatchers.Default) {
                requests.zip(titles).flatMap { (request, found) ->
                    if (found.isEmpty()) emptyList() else request.saved.zip(request.rename(found)).filter { (saved, name) -> name != saved.savedName }
                }
            }
            if (wanted.isEmpty()) return@launch
            val account = requests.map { it.account }.distinct().singleOrNull() ?: return@launch
            rename(account, wanted)
        }
    }

    /** 换号或退出登录时调用：取消上一个账号的补名与片名查询。 */
    fun endAccount() {
        accountJob.cancel()
        accountJob = SupervisorJob(scope.coroutineContext[Job])
    }

    /** 等当前账号的查询与补名都结束，测试用。 */
    internal suspend fun awaitIdle() {
        accountJob.children.toList().joinAll()
    }

    /**
     * 换号时 [endAccount] 会取消这里，但取消要等主线程上的账号收集者跑到，其间仓库已换成新账号的客户端，
     * 所以每次读写网盘之前再核对一次账号。
     */
    private suspend fun rename(account: String, wanted: List<Pair<Saved, String>>) {
        fun accountLeft() = (currentAccount() != account).also { if (it) PikoLog.i(TAG, "账号已变，不补片名") }
        if (accountLeft()) return
        // 现查名字与所在目录：用户可能已改名、移走或删掉，这些不碰
        val current = wanted.mapNotNull { (saved, name) ->
            val detail = driveRepo.getFileDetail(saved.id).logFailure(TAG, "补片名前查详情失败，跳过：${saved.id}").getOrNull()
                ?: return@mapNotNull null
            if (detail.trashed || detail.name != saved.savedName) return@mapNotNull null
            RenameSource(detail.id, detail.parentId, detail.name, isFolder = detail.kind == FileKind.FOLDER) to name
        }
        if (current.isEmpty()) {
            PikoLog.i(TAG, "补片名：${wanted.size} 项都已在别处改过，不改")
            return
        }
        val ids = current.map { it.first.id }.toSet()
        val siblings = current.map { it.first.parentId }.distinct().associateWith { parentId ->
            driveRepo.listAllFiles(parentId).logFailure(TAG, "补片名前列目录失败：$parentId").getOrElse { return }
                .filter { it.id !in ids }.map { it.name }.toSet()
        }
        // 与同目录已有的名字撞上的，planRenames 标为冲突，不在执行的步骤里
        val plan = planRenames(current.map { it.first }, current.map { it.second }, siblings)
        if (plan.steps.isEmpty() || accountLeft()) return
        val run = RenameRun(driveRepo, TAG)
        try {
            run.execute(plan)
        } finally {
            if (run.renamed.isNotEmpty()) {
                driveRepo.changes.record(DriveChangeJournal.Change.Rename(run.renamed.toList(), "已按 MetaTube 补全片名 ${run.succeeded} 项"))
            }
        }
        PikoLog.i(TAG, "补片名：${run.succeeded} 项，${plan.problemCount} 项有冲突未改，${run.failures.size} 项失败，${wanted.size - current.size} 项已在别处改过")
    }

    private companion object {
        const val TAG = "InstantTitleFill"
    }
}

/**
 * 查到的片名；查询出错或被取消（换号、放弃）时为空表，照原名留着。等待的一方自己被取消时照常抛出，
 * 不能吞掉：放弃补名的超时正是这样取消等待的。
 */
internal suspend fun Deferred<MetaTubeTitles>.titlesOrEmpty(): Map<String, String> = try {
    await().titles
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    emptyMap()
} catch (e: Exception) {
    PikoLog.w("InstantTitleFill", "查片名失败", e)
    emptyMap()
}
