package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.naming.av.canonicalAvName
import dev.piko.shared.naming.av.canonicalAvNameOf
import dev.piko.shared.naming.av.matchAvFolder
import kotlinx.coroutines.isActive
import dev.piko.shared.presentation.ShareCommands
import dev.piko.shared.presentation.ShareCommandResult
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure

/**
 * 转存一个 PikPak 分享：逐层浏览，勾选当前这一层的条目，转存到目标目录。
 *
 * 勾选只在当前层有效，换层即清空：转存要带上条目所在的各级分享目录，跨层勾选得按目录拆成几次请求，
 * 而分享多半顶层只有一个文件夹，勾它就是整个分享。转存后文件直接落在目标目录下，不带分享里的上级目录。
 */
class ShareSaveState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val shareId: String,
    /** 随链接一起转发来的提取码，有就直接用上，省得再问一遍。 */
    initialPassCode: String = "",
    val session: ShareSaveSession = ShareSaveSession(driveRepo, scope),
    private val holdTarget: (Boolean) -> Unit = {},
    private val saveTarget: SaveTargetContext = SaveTargetContext.fixed(),
) {
    private val account = driveRepo.currentAccount
    private val commands = ShareCommands(driveRepo, session, account, saveTarget.availability) { scope.isActive && driveRepo.currentAccount == account }
    private var workId: Long? = null
    var activeWork by mutableStateOf<ShareSaveWork?>(null)
        private set
    private var handledResult: ShareSaveResult? = null
    private var targetRulesVersion by mutableStateOf(0L)
    fun targetBlockReason(id: String): String? {
        targetRulesVersion
        return (saveTarget.availability(id) as? dev.piko.shared.presentation.ActionAvailability.Blocked)?.reason
    }
    var info by mutableStateOf<ShareInfo?>(null)
        private set

    /** 分享设了提取码而还没给或给错了。给错时 [errorMessage] 另有说明。 */
    var needsPassCode by mutableStateOf(false)
        private set
    var passCode by mutableStateOf(initialPassCode)

    /** 进到的分享目录，不含顶层。 */
    val path = mutableStateListOf<PikoPathBreadcrumb>()
    var entries by mutableStateOf<List<FileStat>>(emptyList())
        private set
    val selectedIds = mutableStateListOf<String>()

    var isLoading by mutableStateOf(true)
        private set
    val isSaving: Boolean get() = activeWork?.isRunning == true
    val hasPendingResult: Boolean get() = activeWork?.let { it.result?.unconfirmedSources?.isNotEmpty() == true || it.pendingRenames.isNotEmpty() } == true
    val canEdit: Boolean get() = !isSaving && !hasPendingResult
    val canRecheck: Boolean get() = activeWork?.let { session.canRecheck(it.id) } == true
    fun recheck() { workId?.let(session::recheck) }
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 转存成功后的提示，界面显示后由它清空。 */
    var doneMessage by mutableStateOf<String?>(null)

    val selectedBytes: Long by derivedStateOf { entries.filter { it.id in selectedIds }.sumOf { it.sizeBytes } }

    private var loadJob: Job? = null

    init {
        scope.launch { saveTarget.actionChanges.collect { targetRulesVersion++ } }
        scope.launch {
            session.works.collect { works ->
                val current = workId?.let { id -> works.firstOrNull { it.id == id } }
                acceptWork(current)
            }
        }
        open()
    }

    private fun acceptWork(work: ShareSaveWork?) {
        activeWork = work
        holdTarget(work?.isRunning == true || hasPendingResult)
        val result = work?.result ?: return
        if (result === handledResult || driveRepo.currentAccount != account) return
        handledResult = result
        if (work.needsPassCode) needsPassCode = true
        selectedIds.removeAll(result.confirmedSources)
        doneMessage = result.message.takeIf { result.completion == ShareSaveCompletion.SUCCEEDED }
        errorMessage = result.message.takeUnless { result.completion == ShareSaveCompletion.SUCCEEDED }
    }

    /** 读顶层。带着 [passCode]；没设提取码的分享传空串即可。 */
    fun open() {
        if (!canEdit) return
        load {
            driveRepo.shareInfo(shareId, passCode.trim())
                .onSuccess { share ->
                    info = share
                    needsPassCode = false
                    path.clear()
                    entries = share.files
                }
                .onFailure { error ->
                    val unavailable = error as? ShareUnavailableException
                    // 分享 ID 与提取码合起来就能打开分享，都不进日志
                    if (unavailable?.needsPassCode == true) {
                        PikoLog.i(TAG, "打开分享：${if (passCode.isBlank()) "需要提取码" else "提取码错误"}")
                        needsPassCode = true
                        errorMessage = if (passCode.isBlank()) null else "提取码错误"
                    } else {
                        PikoLog.w(TAG, "打开分享失败${if (unavailable != null) "：分享已失效" else ""}", error)
                        // 服务端的 statusText 可能是英文，不直接显示；能回 200 却读不了的，是取消或过期了
                        errorMessage = if (unavailable != null) "分享已失效" else "无法读取分享"
                    }
                }
        }
    }

    fun enter(folder: FileStat) {
        if (!canEdit) return
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    path += PikoPathBreadcrumb(folder.id, folder.name)
                    entries = it
                }
                .logFailure(TAG, "列出分享里的文件夹失败：${folder.id}")
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    /** 回到第 [depth] 层；0 是顶层。 */
    fun goTo(depth: Int) {
        if (!canEdit) return
        if (depth >= path.size) return
        if (depth == 0) {
            val share = info ?: return
            path.clear()
            entries = share.files
            selectedIds.clear()
            return
        }
        val folder = path[depth - 1]
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    while (path.size > depth) path.removeAt(path.lastIndex)
                    entries = it
                }
                .logFailure(TAG, "列出分享里的文件夹失败：${folder.id}")
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    fun toggle(file: FileStat) {
        if (!canEdit) return
        if (!selectedIds.remove(file.id)) selectedIds += file.id
    }

    fun toggleAll() {
        if (!canEdit) return
        if (selectedIds.size == entries.size) selectedIds.clear() else {
            selectedIds.clear()
            selectedIds += entries.map { it.id }
        }
    }

    // region 按番号规范命名

    /**
     * 转存后这一项会改成的名字，不改时为 null，列表行据此预览。文件夹只看名字里的番号：里面有什么要转存后才列得出，
     * 实际以转存后按 [canonicalAvTree] 算出的为准。
     */
    fun canonicalPreview(file: FileStat): String? {
        val name = if (file.isFolder) {
            matchAvFolder(file.name)?.let { canonicalAvName(file.name, it, isFolder = true) }
        } else {
            canonicalAvNameOf(file.name)
        }
        return name?.takeIf { it != file.name }
    }

    /** 眼前这一层里有会改名的，面板据此给出「按番号规范命名」。 */
    val offersCanonicalNames: Boolean by derivedStateOf { entries.any { it.isFolder || canonicalPreview(it) != null } }

    // endregion

    /** 提交不可变参数；浏览页面不持有转存轮询和命名执行。 */
    fun save(target: PikoPathBreadcrumb, canonicalNames: Boolean = false) {
        val token = info?.passCodeToken ?: return
        if (!canEdit) return
        val sources = entries.filter { it.id in selectedIds }.map(ShareSource::of)
        if (sources.isEmpty()) return
        val command = ShareSaveCommand(shareId, sources, target, path.map { it.id }, canonicalNames)
        when (val result = commands.submit(command, token)) {
            is ShareCommandResult.Accepted -> {
                workId = result.workId
                handledResult = null
                errorMessage = null
                doneMessage = null
                acceptWork(session.works.value.firstOrNull { it.id == result.workId })
            }
            is ShareCommandResult.Rejected -> errorMessage = result.reason
        }
    }

    private companion object {
        const val TAG = "ShareSave"
    }

    private fun load(block: suspend () -> Unit) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        selectedIds.clear()
        loadJob = scope.launch {
            block()
            isLoading = false
        }
    }
}
