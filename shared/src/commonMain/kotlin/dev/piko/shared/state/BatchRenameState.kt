package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 批量重命名：编辑规则时逐行预览，用户确认后才逐个改名。
 *
 * 冲突检查要知道同目录里未选中的项叫什么，网盘页手上的列表可能是全盘搜索的结果，不代表所在目录的全部，
 * 所以打开时按所选项的 parentId 各列一次目录。列完之前与列失败时都不能执行。
 */
class BatchRenameState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    files: List<FileStat>,
) {
    enum class Phase { EDITING, RUNNING, DONE }

    val sources: List<RenameSource> = files.map { RenameSource(it.id, it.parentId, it.name, it.isFolder) }

    /** 自动识别出的共同前后缀，打开时算一次。 */
    val detected: CommonAffixes = commonAffixes(sources)

    var stripPrefix by mutableStateOf(detected.prefix.isNotEmpty())
    var stripSuffix by mutableStateOf(detected.suffix.isNotEmpty())
    var find by mutableStateOf("")
    var replacement by mutableStateOf("")

    private var siblingNames by mutableStateOf<Map<String, Set<String>>?>(null)

    /** 列同目录失败，冲突无从判断。长驻到重试成功为止。 */
    var siblingsFailed by mutableStateOf(false)
        private set

    val isCheckingSiblings by derivedStateOf { siblingNames == null && !siblingsFailed }

    private val rules by derivedStateOf {
        RenameRules(
            prefix = if (stripPrefix) detected.prefix else "",
            suffix = if (stripSuffix) detected.suffix else "",
            find = find,
            replacement = replacement,
        )
    }

    val plan by derivedStateOf { planRenames(sources, rules, siblingNames.orEmpty()) }

    var phase by mutableStateOf(Phase.EDITING)
        private set

    /** 执行时要改名的项数，与已处理的项数（含失败）。 */
    var total by mutableStateOf(0)
        private set
    var processed by mutableStateOf(0)
        private set

    // 改成了的，按执行顺序，撤销时倒着改回去
    private val renamed = mutableListOf<DriveChangeJournal.Renamed>()

    /** 改名失败的项，成功的不回滚。 */
    val failures = mutableStateListOf<RenameRow>()

    var wasStopped by mutableStateOf(false)
        private set

    val canRename by derivedStateOf {
        phase == Phase.EDITING && siblingNames != null && plan.problemCount == 0 && plan.order.isNotEmpty()
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    init {
        loadSiblings()
    }

    fun loadSiblings() {
        siblingsFailed = false
        val selectedIds = sources.map { it.id }.toSet()
        scope.launch {
            val names = mutableMapOf<String, Set<String>>()
            for (parentId in sources.map { it.parentId }.distinct()) {
                val listing = driveRepo.listAllFiles(parentId)
                    .logFailure(TAG, "列出同目录名称失败")
                    .getOrElse {
                        siblingsFailed = true
                        return@launch
                    }
                names[parentId] = listing.filter { it.id !in selectedIds }.map { it.name }.toSet()
            }
            siblingNames = names
        }
    }

    /**
     * 按 [RenamePlan.order] 逐个改名。一次一个：顺序本身就是为了避开 A 改成 B、B 改成 C 的中间冲突，
     * 并发会打乱它；改名请求也只是一次元数据修改，逐个执行的耗时可以接受。
     */
    fun rename() {
        if (!canRename) return
        val order = plan.order
        total = order.size
        processed = 0
        failures.clear()
        renamed.clear()
        wasStopped = false
        phase = Phase.RUNNING
        job = scope.launch {
            try {
                for (row in order) {
                    driveRepo.rename(row.source.id, row.newName)
                        .logFailure(TAG, "批量重命名失败：${logFile(row.source.id, row.source.name)}")
                        .onSuccess { renamed += DriveChangeJournal.Renamed(row.source.id, row.source.name, row.newName) }
                        .onFailure { failures += row }
                    processed++
                }
            } finally {
                phase = Phase.DONE
                driveRepo.requestRefresh()
                // 改成了的记进改动记录，提示带「撤销」；一项也没改成的只报结果
                if (renamed.isNotEmpty()) {
                    driveRepo.changes.record(DriveChangeJournal.Change.Rename(renamed.toList(), summary()))
                } else {
                    _messages.tryEmit(summary())
                }
            }
        }
    }

    /** 停在当前这一项之后。正在发出的请求可能已在服务端生效，以刷新后的列表为准。 */
    fun stop() {
        if (phase != Phase.RUNNING) return
        wasStopped = true
        job?.cancel()
    }

    private fun summary(): String {
        val succeeded = processed - failures.size
        return when {
            wasStopped -> "已停止，重命名了 $succeeded 项"
            failures.isEmpty() -> "已重命名 $succeeded 项"
            else -> "已重命名 $succeeded 项，${failures.size} 项失败"
        }
    }

    private companion object {
        const val TAG = "BatchRename"
    }
}
