package dev.piko.shared.rename

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.naming.av.hasAvCode
import dev.piko.shared.naming.av.matchAvFolder
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.scrape.MetaTubeService
import kotlinx.coroutines.flow.first
import io.github.nihildigit.pikpak.FileStat
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 批量重命名：编辑规则时逐行预览，用户确认后才逐个改名。
 *
 * 规则管线见 [runBatchRenamePipeline]。预览、冲突检查与执行只看管线算出的新名称（见 [RenameRule]），
 * 以后别的规则来源接进来只需在管线里加一种。
 *
 * 冲突检查要知道同目录里未选中的项叫什么，网盘页手上的列表可能是全盘搜索的结果，不代表所在目录的全部，
 * 所以打开时按所选项的 parentId 各列一次目录。列完之前与列失败时都不能执行。
 */
class BatchRenameState(
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    files: List<FileStat>,
    private val memory: BatchRenameMemory,
    initialTextMode: Boolean = false,
    initialAvNaming: Boolean = false,
    /** 用户配了 MetaTube 时可以从那里取片名；为 null 时没有这个选项。 */
    private val metaTube: MetaTubeService? = null,
) {
    enum class Phase { EDITING, RUNNING, DONE }

    val sources: List<RenameSource> = files.map {
        RenameSource(it.id, it.parentId, it.name, it.isFolder, it.createdTime, it.modifiedTime)
    }

    // region 按番号规范命名

    /** 所选里有带番号的文件，才给「按番号规范命名」的入口。 */
    val avNamingAvailable: Boolean = sources.any { !it.isFolder && hasAvCode(it.name) }

    /**
     * 按番号规范命名（[CanonicalAvRule]）是否开着。它排在查找替换之前，开着时查找替换在规范名上接着改。
     * 不记进偏好：下次打开时预览里不该已有改动。
     */
    var avNaming by mutableStateOf(false)
        private set

    /**
     * 换上「按番号规范命名」：积木与输入框清空，免得原先的查找替换接在规范名后面改出意外的结果。
     * 文件夹默认不勾：文件夹名常是用户自己的归类，规范名只在明确要改时才用。
     */
    fun startAvNaming() {
        pendingFindText = ""
        pendingReplaceText = ""
        findInsertAt = null
        replaceInsertAt = null
        updateFindBlocks(emptyList())
        updateReplaceBlocks(emptyList())
        if (textMode) options = options.copy(search = "", replacement = "")
        excludedIds = excludedIds + sources.filter { it.isFolder }.map { it.id }
        avNaming = true
    }

    fun stopAvNaming() {
        avNaming = false
    }

    /** 配了 MetaTube（地址不为空）。没配时界面上不出现「片名取自 MetaTube」。 */
    var metaTubeAvailable by mutableStateOf(false)
        private set

    /** 规范名的片名取自 MetaTube，查不到的用原名里的片名。 */
    var useMetaTubeTitles by mutableStateOf(false)
        private set

    private var metaTubeTitles by mutableStateOf(emptyMap<String, String>())

    /** 正在查片名时的进度（已完成，总数），不在查时为 null。查完之前不能执行，预览还会变。 */
    var metaTubeProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 上一次查询里因出错没查成的番号数，底栏据此说一句。 */
    var metaTubeFailed by mutableStateOf(0)
        private set

    private var metaTubeJob: Job? = null

    fun updateUseMetaTubeTitles(enabled: Boolean) {
        useMetaTubeTitles = enabled
        metaTubeJob?.cancel()
        metaTubeProgress = null
        metaTubeFailed = 0
        val service = metaTube ?: return
        if (!enabled) return
        // 文件夹名里的番号也查：文件夹的规范名同样带片名
        val infos = sources.mapNotNull { source ->
            if (source.isFolder) matchAvFolder(source.name) else parseMediaName(source.name).av
        }
        metaTubeProgress = 0 to infos.distinctBy { it.code }.size
        metaTubeJob = scope.launch {
            try {
                val result = service.titles(infos, onProgress = { done, total -> metaTubeProgress = done to total })
                metaTubeTitles = metaTubeTitles + result.titles
                metaTubeFailed = result.failed
            } finally {
                metaTubeProgress = null
            }
        }
    }

    // endregion

    // 默认关、也不从上次恢复：打开时预览里不该已有改动
    var stripPrefix by mutableStateOf(false)
    var stripSuffix by mutableStateOf(false)

    /**
     * 查找替换的全部选项，打开时恢复上次执行时的，查找与替换的文字、大小写格式除外（见 [BatchRenameMemory]）：
     * 这三项单独就会改名，恢复了打开时预览里就已有改动。其余选项不配上查找串不改任何名称。
     */
    var options by mutableStateOf(memory.options.withoutChanges().copy(useRegex = true))

    // region 积木与正则文本两种写法

    /**
     * 查找与替换写成正则文本（true）还是拼积木（false）。两种写法都交给正则模式的 [FindReplaceRule]：
     * 积木里的「文字」就是普通文本查找，不必再有一种不带正则的模式。
     */
    var textMode by mutableStateOf(initialTextMode)
        private set

    /** 想切回积木却切不回时的说明，如「这个正则无法图形化」。长驻到下一次切换或改动写法为止。 */
    var modeNote by mutableStateOf<String?>(null)
        private set

    var findBlocks by mutableStateOf(emptyList<FindBlock>())
        private set
    var replaceBlocks by mutableStateOf(emptyList<ReplaceBlock>())
        private set

    /**
     * 积木条末尾输入框里还没收成积木的文字。它照样参与预览，算作末尾的一块文字积木；
     * 不逐字收成积木，是因为输入法组字时把输入框清空会打断组字。
     */
    var pendingFindText by mutableStateOf("")
    var pendingReplaceText by mutableStateOf("")

    /**
     * 输入框停在第几块之前，即没收的文字插在哪里；null 是末尾。不固定在末尾，是因为「第 01 集」这类写法要在序号前面
     * 打字：点了「改为序号」后插入点停在序号前，输入框为空时左右方向键在块之间移动它。块被删掉时压回有效范围。
     */
    var findInsertAt by mutableStateOf<Int?>(null)
    var replaceInsertAt by mutableStateOf<Int?>(null)

    val findInsertIndex: Int get() = (findInsertAt ?: findBlocks.size).coerceIn(0, findBlocks.size)
    val replaceInsertIndex: Int get() = (replaceInsertAt ?: replaceBlocks.size).coerceIn(0, replaceBlocks.size)

    /** 积木条上显示的积木，含输入框里还在输入的文字。 */
    val effectiveFindBlocks: List<FindBlock> by derivedStateOf {
        normalizeFindBlocks(findBlocks.toMutableList().apply { add(findInsertIndex, FindBlock.Text(pendingFindText)) })
    }
    val effectiveReplaceBlocks: List<ReplaceBlock> by derivedStateOf {
        normalizeReplaceBlocks(replaceBlocks.toMutableList().apply { add(replaceInsertIndex, ReplaceBlock.Text(pendingReplaceText)) })
    }

    fun updateFindBlocks(blocks: List<FindBlock>) {
        findBlocks = normalizeFindBlocks(blocks)
        modeNote = null
    }

    fun updateReplaceBlocks(blocks: List<ReplaceBlock>) {
        replaceBlocks = normalizeReplaceBlocks(blocks)
        modeNote = null
    }

    /**
     * 换上起手式：查找与替换整组换掉，输入框里没收的文字一并清掉，否则它会接进起手式的积木里。
     * 按番号规范命名随之关掉，两者是两种起点。
     * 替换的插入点放到最前：添加前缀时替换条是空的，最前即末尾；改为序号时接着打的字落在序号前面。
     */
    fun applyPreset(preset: RenamePreset) {
        avNaming = false
        pendingFindText = ""
        pendingReplaceText = ""
        findInsertAt = null
        replaceInsertAt = 0
        updateFindBlocks(preset.find)
        updateReplaceBlocks(preset.replace)
    }

    /**
     * 把输入框里的文字收成积木，加别的积木或在输入框里回车时调用。插入点停在刚收下的文字后面，接着打的字跟在它后面。
     * 按插入点之后还剩几块来算新位置：收下的文字可能与相邻的文字积木合并，按下标加一会算错。
     */
    fun commitPendingText() {
        if (pendingFindText.isNotEmpty()) {
            val tail = findBlocks.size - findInsertIndex
            updateFindBlocks(findBlocks.toMutableList().apply { add(findInsertIndex, FindBlock.Text(pendingFindText)) })
            if (findInsertAt != null) findInsertAt = findBlocks.size - tail
        }
        if (pendingReplaceText.isNotEmpty() && isExpressibleText(pendingReplaceText)) {
            val tail = replaceBlocks.size - replaceInsertIndex
            updateReplaceBlocks(replaceBlocks.toMutableList().apply { add(replaceInsertIndex, ReplaceBlock.Text(pendingReplaceText)) })
            if (replaceInsertAt != null) replaceInsertAt = replaceBlocks.size - tail
        }
        pendingFindText = ""
        if (isExpressibleText(pendingReplaceText)) pendingReplaceText = ""
    }

    /** 实际生效的选项：积木模式下查找与替换串由积木生成。 */
    val effectiveOptions: FindReplaceOptions by derivedStateOf {
        if (textMode) {
            options
        } else {
            options.copy(
                search = findBlocksToRegex(effectiveFindBlocks),
                replacement = replaceBlocksToTemplate(effectiveReplaceBlocks),
                useRegex = true,
            )
        }
    }

    /** 用户切到正则文本：积木原样写成正则，接着改。记下这个选择。 */
    fun switchToTextMode() {
        val current = effectiveOptions
        options = options.copy(search = current.search, replacement = current.replacement, useRegex = true)
        textMode = true
        modeNote = null
        rememberMode(textMode = true)
    }

    /**
     * 用户切回积木：查找与替换都能解析才切，并记下这个选择；有一边认不出就留在文本模式、注明原因，
     * 不改记住的值，那一次不是用户的选择。
     */
    fun switchToBlockMode() {
        val find = regexToFindBlocks(options.search)
        val replace = templateToReplaceBlocks(options.replacement)
        if (find == null || replace == null) {
            modeNote = when {
                find == null && replace == null -> "查找和替换无法用块表示，将继续使用正则表达式"
                find == null -> "查找无法用块表示，将继续使用正则表达式"
                else -> "替换无法用块表示，将继续使用正则表达式"
            }
            return
        }
        findBlocks = find
        replaceBlocks = replace
        pendingFindText = ""
        pendingReplaceText = ""
        textMode = false
        modeNote = null
        rememberMode(textMode = false)
    }

    /** 从最近列表里选了一条查找串。积木模式下认不出时被迫换到文本模式，不改记住的值。 */
    fun useRecentSearch(search: String) {
        if (textMode) {
            options = options.copy(search = search)
            return
        }
        val blocks = regexToFindBlocks(search)
        if (blocks != null) {
            pendingFindText = ""
            updateFindBlocks(blocks)
        } else {
            forceTextMode(effectiveOptions.copy(search = search), "此查找无法用块表示，已切换到正则表达式")
        }
    }

    fun useRecentReplacement(replacement: String) {
        if (textMode) {
            options = options.copy(replacement = replacement)
            return
        }
        val blocks = templateToReplaceBlocks(replacement)
        if (blocks != null) {
            pendingReplaceText = ""
            updateReplaceBlocks(blocks)
        } else {
            forceTextMode(effectiveOptions.copy(replacement = replacement), "此替换无法用块表示，已切换到正则表达式")
        }
    }

    private fun forceTextMode(current: FindReplaceOptions, note: String) {
        options = current
        textMode = true
        modeNote = note
    }

    private fun rememberMode(textMode: Boolean) {
        scope.launch { preferences.setRenameRegexTextMode(textMode) }
    }

    /** 取出第 [number] 段（从 1 起）的查找积木在 [effectiveFindBlocks] 里的位置，界面据此给替换里的 ①② 配同一种颜色。 */
    fun captureBlockIndex(number: Int): Int? = captureNumbers(effectiveFindBlocks).indexOf(number).takeIf { it >= 0 }

    private val highlighter by derivedStateOf {
        runCatching { MatchHighlighter(effectiveOptions, if (textMode) null else effectiveFindBlocks) }.getOrNull()
    }

    /** [source] 原名里被查找匹配到的各段，按积木标号，供预览上色。正则写错时为空。 */
    fun highlights(source: RenameSource): List<MatchHighlight> = highlighter?.highlights(source).orEmpty()

    /** 原名里查找作用得到的一段，预览把其余部分调暗；这一项不参与时为 null，整个原名都调暗。 */
    fun searchRange(source: RenameSource): IntRange? = searchPart(source, effectiveOptions)

    // endregion

    /** 替换串里写了日期占位符，界面据此才给出取哪个时间的选项。 */
    val usesTime by derivedStateOf { ReplaceTemplate.parse(effectiveOptions.replacement).usesTime }

    val recentSearches: List<String> get() = memory.recentSearches
    val recentReplacements: List<String> get() = memory.recentReplacements

    /** 预览里取消勾选的项，原名不动，也不占计数器的号。 */
    var excludedIds by mutableStateOf(emptySet<String>())
        private set

    fun setIncluded(id: String, included: Boolean) {
        excludedIds = if (included) excludedIds - id else excludedIds + id
    }

    // 打开时取定，改选项时同一项的随机串不跟着变，预览与执行也是同一串
    private val randomSeed = Random.nextLong()

    private var siblingNames by mutableStateOf<Map<String, Set<String>>?>(null)

    /** 列同目录失败，冲突无从判断。长驻到重试成功为止。 */
    var siblingsFailed by mutableStateOf(false)
        private set

    val isCheckingSiblings by derivedStateOf { siblingNames == null && !siblingsFailed }

    private class Preview(val plan: RenamePlan, val affixes: CommonAffixes, val patternError: String?)

    private val preview by derivedStateOf {
        val included = sources.filter { it.id !in excludedIds }
        val (findReplace, error) = try {
            FindReplaceRule(effectiveOptions, randomSeed) to null
        } catch (error: InvalidPatternException) {
            null to error.message.orEmpty()
        }
        val base = if (avNaming) CanonicalAvRule(if (useMetaTubeTitles) metaTubeTitles else emptyMap()) else null
        val result = runBatchRenamePipeline(included, findReplace, stripPrefix, stripSuffix, base)
        val newNameById = included.map { it.id }.zip(result.names).toMap()
        val newNames = sources.map { newNameById[it.id] ?: it.name }
        Preview(planRenames(sources, newNames, siblingNames.orEmpty()), result.affixes, error)
    }

    val plan: RenamePlan get() = preview.plan

    /** 查找替换之后各名仍共有的开头与结尾，即打开开关时会去掉的部分。随查找替换的结果变。 */
    val detected: CommonAffixes get() = preview.affixes

    /** 正则编译失败时的说明。长驻到改对为止，此时不能执行。 */
    val patternError: String? get() = preview.patternError

    var phase by mutableStateOf(Phase.EDITING)
        private set

    private val run = RenameRun(driveRepo, TAG)

    /** 执行时要改名的项数，与已处理的项数（含失败）。 */
    val total: Int get() = run.total
    val processed: Int get() = run.processed

    /** 改名失败的项，成功的不回滚。 */
    val failures: List<RenameRow> get() = run.failures

    var wasStopped by mutableStateOf(false)
        private set

    val canRename by derivedStateOf {
        phase == Phase.EDITING && siblingNames != null && patternError == null && metaTubeProgress == null &&
            plan.problemCount == 0 && plan.steps.isNotEmpty()
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    init {
        loadSiblings()
        if (initialAvNaming && avNamingAvailable) startAvNaming()
        metaTube?.let { service -> scope.launch { metaTubeAvailable = service.enabled.first() } }
    }

    fun loadSiblings() {
        siblingsFailed = false
        val selectedIds = sources.map { it.id }.toSet()
        scope.launch {
            val names = mutableMapOf<String, Set<String>>()
            for (parentId in sources.map { it.parentId }.distinct()) {
                val listing = driveRepo.listAllFiles(parentId)
                    .logFailure(TAG, "列出同目录名称失败：文件夹 $parentId")
                    .getOrElse {
                        siblingsFailed = true
                        return@launch
                    }
                names[parentId] = listing.filter { it.id !in selectedIds }.map { it.name }.toSet()
            }
            siblingNames = names
        }
    }

    /** 按 [RenamePlan.steps] 逐个改名，见 [RenameRun]。 */
    fun rename() {
        if (!canRename) return
        val plan = plan
        scope.launch { BatchRenameMemory.save(preferences, memory.remember(effectiveOptions)) }
        wasStopped = false
        phase = Phase.RUNNING
        val started = TimeSource.Monotonic.markNow()
        PikoLog.i(TAG, "开始批量重命名：${plan.changeCount} 项，${plan.steps.size} 步（含经临时名称的 ${plan.steps.size - plan.changeCount} 步），" +
            "${sources.map { it.parentId }.distinct().size} 个目录，${if (textMode) "正则文本" else "积木"}模式${if (avNaming) "，按番号规范命名" else ""}")
        job = scope.launch {
            try {
                run.execute(plan)
            } finally {
                PikoLog.i(TAG, "批量重命名结束：${summary()}，成功 ${run.renamed.size} 步，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
                phase = Phase.DONE
                // 改成了的记进改动记录，提示带「撤销」；一项也没改成的只报结果
                if (run.renamed.isNotEmpty()) {
                    driveRepo.changes.record(DriveChangeJournal.Change.Rename(run.renamed.toList(), summary()))
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
            wasStopped -> "已停止，已重命名 $succeeded 项"
            failures.isEmpty() -> "已重命名 $succeeded 项"
            else -> "已重命名 $succeeded 项，${failures.size} 项失败"
        }
    }

    private companion object {
        const val TAG = "BatchRename"
    }
}
