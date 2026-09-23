package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一次保存的结果。导航与提示由调用方处理，这里只报告存到了哪里。 */
sealed interface InstantSaveOutcome {
    val target: PikoPathBreadcrumb

    data class InstantSaved(
        val createdIds: List<String>,
        override val target: PikoPathBreadcrumb,
    ) : InstantSaveOutcome

    data class OfflineTaskCreated(override val target: PikoPathBreadcrumb) : InstantSaveOutcome
}

/**
 * 秒传与磁力解析的工作台状态，两端共用。
 *
 * 流程：粘上磁力链自动解析（防抖），按启发式预选主体文件，确定保存目标（记住的目标、
 * 失效则回退 My Packs），然后整单秒传或整单交给离线任务。
 *
 * 这里只保存、不导航：结果经 [outcomes] 交给调用方，由它通过 DriveScreenState 切到
 * 目标目录，顺带清掉搜索与选中。在这里直接改仓库的目录栈会绕过那一步。
 *
 * 解析失败与目标失效是长驻的说明文字，用状态表达；保存结果是一次性事件，用事件流。
 */
class InstantSheetState(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    initialMagnet: String = "",
) {
    var input by mutableStateOf(initialMagnet)
        private set

    /**
     * 输入框是否展开。外部分享进来的磁力链已经在用户手上，输入框只是让他把同一件事
     * 再确认一遍，所以先收起；手动粘贴的链解析成功后同样收起，把高度让给文件列表。
     * 解析失败时再放出来，否则他既看不到那串链接，也没法改、没法重试。
     * 成功后不给重新展开的入口：换一条链关掉面板重开即可。
     */
    var isInputVisible by mutableStateOf(initialMagnet.isBlank())
        private set

    var isResolving by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var resolution by mutableStateOf<MagnetResolutionResult?>(null)
        private set
    var selectedIndices by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** 解析或保存失败的原因，下一次解析开始时清空。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 保存目标。为 null 表示还在确认，此时不拿 My Packs 顶替，免得闪一个可能是错的名字。 */
    var target by mutableStateOf<PikoPathBreadcrumb?>(null)
        private set

    /** 记住的目标已失效、已回退到默认目录时的说明。 */
    var targetNotice by mutableStateOf<String?>(null)
        private set

    /** 多文件秒传时新建目录的名字，解析成功后以资源名预填。 */
    var folderName by mutableStateOf("")
        private set

    private val _outcomes = MutableSharedFlow<InstantSaveOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<InstantSaveOutcome> = _outcomes.asSharedFlow()

    /**
     * 归一化后的磁力链，兼作解析的去重键：同一条链不会重复解析。非磁力的输入
     * （http 直链、ed2k）是 null，不自动解析，只能整条提交离线任务。
     */
    val normalizedMagnet: String? by derivedStateOf { normalizeMagnet(input) }

    val items: List<InstantFileItem> by derivedStateOf { resolution?.items.orEmpty() }

    val selectedItems: List<InstantFileItem> by derivedStateOf {
        selectedIndices.sorted().mapNotNull(items::getOrNull)
    }

    /**
     * 勾选项里只要有一项云端没收录，整单就走离线，不做「能秒传的先秒传、其余离线」。
     * createUrlFile 只收整条磁力 URL，ResolvedFile 也不带文件索引，离线任务没法只取
     * 选中的那几个；两者并用会把刚秒传的文件再下一遍，目录里留下重复项。取舍是用户
     * 定的：宁可放弃那几项的秒传，也不要重复。
     */
    val canInstantSaveAll: Boolean by derivedStateOf {
        selectedItems.isNotEmpty() && selectedItems.all { it.isInstantReady }
    }

    /**
     * 这次保存是否会新建一层目录。只有这时才让人改目录名：走离线那条路目录是 PikPak
     * 自己建的，摆一个可编辑的名字只会让人以为能生效。
     */
    val willCreateFolder: Boolean by derivedStateOf { canInstantSaveAll && selectedItems.size > 1 }

    val canSaveSelection: Boolean by derivedStateOf {
        !isSaving && selectedItems.isNotEmpty() && target != null &&
            !(willCreateFolder && folderName.isBlank())
    }

    val isAllSelected: Boolean by derivedStateOf {
        items.isNotEmpty() && selectedIndices.size == items.size
    }

    /** 偏好项，见 [subtitleBundles]。 */
    private var isBundleSubtitlesEnabled by mutableStateOf(true)

    /** 视频下标 → 随它打包的字幕下标。关掉偏好时为空，字幕照常单列。 */
    val subtitleBundles: Map<Int, List<Int>> by derivedStateOf {
        if (isBundleSubtitlesEnabled) subtitleBundles(items.map { it.file.name }) else emptyMap()
    }

    private val bundledSubtitles: Set<Int> by derivedStateOf { subtitleBundles.values.flatten().toSet() }

    /** 列表里的条目数：打包进视频的字幕不算，与视图里的行对得上。 */
    val entryCount: Int by derivedStateOf { items.size - bundledSubtitles.size }
    val selectedEntryCount: Int by derivedStateOf { selectedIndices.count { it !in bundledSubtitles } }

    /** 文件名按公共前缀折叠出的层级，见 [buildNameTree]。已随视频打包的字幕不单列。 */
    val nameTree: List<NameNode> by derivedStateOf {
        buildNameTree(items.map { it.file.name }.withIndex().filter { it.index !in bundledSubtitles })
    }

    /** 各大类的文件下标，按类整批勾选用。只有一类时没有可筛的，视图不必显示。 */
    val categoryIndices: Map<FileCategory, List<Int>> by derivedStateOf {
        items.indices.filter { it !in bundledSubtitles }.groupBy { items[it].file.name.fileCategory() }
            .toList()
            .sortedBy { (category, _) -> category.ordinal }
            .toMap()
    }

    private var resolveJob: Job? = null
    private var resolvedKey: String? = null

    init {
        scope.launch { target = resolveTarget() }
        scope.launch { preferences.bundleSubtitlesFlow.collect { isBundleSubtitlesEnabled = it } }
        if (initialMagnet.isNotBlank()) {
            if (normalizeMagnet(initialMagnet) == null) {
                // 外部唤起的链不合法时自动解析不会发生，而输入框又是收起的，不兜住就是一个空面板
                errorMessage = "非磁力链接，可离线下载"
                isInputVisible = true
            } else {
                scheduleResolve()
            }
        }
    }

    fun updateInput(value: String) {
        input = value
        scheduleResolve()
    }

    /** 对同一条链再解析一次。自动解析只在链接变化时触发，失败后的重试走这里。 */
    fun retryResolve() {
        resolvedKey = null
        scheduleResolve(debounce = false)
    }

    fun toggleItem(index: Int) {
        setItemSelected(index, index !in selectedIndices)
    }

    fun setItemSelected(index: Int, selected: Boolean) {
        setItemsSelected(listOf(index), selected)
    }

    /** 整批勾选或取消，给目录层级与大类用。 */
    fun setItemsSelected(indices: Collection<Int>, selected: Boolean) {
        val affected = withBundles(indices)
        selectedIndices = if (selected) selectedIndices + affected else selectedIndices - affected
    }

    /** 勾视频就连同它的字幕，取消亦然；视图里字幕不单列，没有别的途径碰到它们。 */
    private fun withBundles(indices: Collection<Int>): Set<Int> =
        indices.toSet() + indices.flatMap { subtitleBundles[it].orEmpty() }

    /** 这一类已全选就全部取消，否则补齐。 */
    fun toggleCategory(category: FileCategory) {
        val indices = categoryIndices[category].orEmpty()
        setItemsSelected(indices, selected = !selectedIndices.containsAll(indices))
    }

    fun toggleSelectAll() {
        selectedIndices = if (isAllSelected) emptySet() else items.indices.toSet()
    }

    fun updateFolderName(value: String) {
        folderName = value
    }

    /** 更换保存目标并记住，下次存资源不必重选。 */
    fun changeTarget(breadcrumb: PikoPathBreadcrumb) {
        target = breadcrumb
        targetNotice = null
        scope.launch { preferences.saveInstantTarget(breadcrumb.id, breadcrumb.name) }
    }

    /** 保存当前勾选：全部可秒传就秒传，否则整条链交给离线任务。 */
    fun saveSelection() {
        if (isSaving || selectedItems.isEmpty()) return
        val toSave = selectedItems
        val instantAll = canInstantSaveAll
        isSaving = true
        scope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                if (instantAll) instantSave(targetBread, toSave) else enqueueOffline(targetBread)
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * 把当前输入整条交给云端离线任务。磁力以外的链接只有这一条路：createUrlFile 收任意
     * URL，但 resolveMagnet 只认磁力，所以这些输入不会有文件列表可勾。
     */
    fun submitOfflineTask() {
        if (isSaving || input.isBlank()) return
        isSaving = true
        scope.launch {
            try {
                enqueueOffline(target ?: resolveTarget())
            } finally {
                isSaving = false
            }
        }
    }

    private fun scheduleResolve(debounce: Boolean = true) {
        val magnet = normalizeMagnet(input)
        if (magnet == resolvedKey) return
        resolvedKey = magnet
        resolveJob?.cancel()
        resolution = null
        selectedIndices = emptySet()
        errorMessage = null
        if (magnet == null) return
        resolveJob = scope.launch {
            // 防抖。粘贴一次就是一条完整的链，等待只为压掉手敲时中途的半条链接，所以取短值。
            if (debounce) delay(AUTO_RESOLVE_DEBOUNCE_MS)
            isResolving = true
            try {
                instantRepo.resolve(magnet)
                    .onSuccess { data -> applyResolution(data) }
                    .onFailure { err ->
                        errorMessage = "解析失败：${err.message}"
                        isInputVisible = true
                    }
            } finally {
                // 换链取消上一次解析时也要走到这里，否则指示器会一直转
                isResolving = false
            }
        }
    }

    private fun applyResolution(data: MagnetResolutionResult?) {
        if (data == null) {
            errorMessage = "云端未收录，可离线下载"
            isInputVisible = true
            return
        }
        resolution = data
        isInputVisible = false
        folderName = FileNameSanitizer.sanitize(data.resource.name)
        // 与网盘列表的启发式折叠同一套判据：剔掉 sample/subs 这类次要目录里的文件，
        // 再按最大文件的十分之一卡一道门槛。用户仍可手改。
        // 主体判据按大小卡门槛，字幕总会被筛掉，靠打包带回来
        selectedIndices = withBundles(
            mainContentIndices(
                data.items.map { it.file.path },
                data.items.map { it.file.size },
            ),
        )
    }

    /**
     * 记住过的目标优先，没配置过才退回 My Packs。只取一次而不是持续收集，否则用户在
     * 本次会话里改完目标，写回偏好的那次发射会再盖一遍。
     */
    private suspend fun resolveTarget(): PikoPathBreadcrumb {
        val saved = preferences.instantTargetFlow.first()
        if (saved != null) {
            // 记下的目录可能已经被删或进了回收站。不验的话要等保存时才暴露，报的还是一句
            // 原始 API 错误。回收站里的条目查详情返回 file_in_recycle_bin，与 trashed 同样
            // 视为失效。根目录是空 id，没有对应的 FileDetail，不验。
            val alive = saved.folderId.isEmpty() ||
                driveRepo.getFileDetail(saved.folderId).map { !it.trashed }.getOrDefault(false)
            if (alive) return PikoPathBreadcrumb(saved.folderId, saved.folderName)
            targetNotice = "原位置已不存在，改存 My Packs"
        }
        return driveRepo.getOrCreateMyPacksFolder().getOrDefault(PikoPathBreadcrumb("", "My Packs"))
    }

    private suspend fun instantSave(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>) {
        // 多个文件平铺进目标目录会把它和别的资源混在一起，先建一层再存。名字取自输入框，
        // 仍要过一遍 sanitize：用户可能敲进 / : * 这类建不出来的字符。
        val saveTarget = if (toSave.size > 1) {
            val name = FileNameSanitizer.sanitize(folderName)
            val folderId = driveRepo.createFolder(target.id, name).getOrElse { err ->
                errorMessage = "新建文件夹失败：${err.message}"
                return
            }
            PikoPathBreadcrumb(folderId, name)
        } else {
            target
        }
        instantRepo.instantSave(toSave, saveTarget.id)
            .onSuccess { createdIds -> _outcomes.emit(InstantSaveOutcome.InstantSaved(createdIds, saveTarget)) }
            .onFailure { errorMessage = "保存失败：${it.message}" }
    }

    private suspend fun enqueueOffline(target: PikoPathBreadcrumb) {
        instantRepo.enqueueOfflineTask(input.trim(), target.id)
            .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(target)) }
            .onFailure { errorMessage = "保存失败：${it.message}" }
    }

    companion object {
        private const val AUTO_RESOLVE_DEBOUNCE_MS = 350L

        /**
         * 输入框里的内容归一化成可解析的磁力链，不像磁力链就返回 null，不解析也不报错。
         *
         * 只粘 infohash 的情况不少，所以补全一条磁力链；但限定 40 位十六进制，否则随手敲的
         * 任意长串都会发一次请求。
         */
        fun normalizeMagnet(raw: String): String? {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("magnet:?xt=urn:btih:") -> trimmed
                trimmed.length == 40 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                    "magnet:?xt=urn:btih:$trimmed"
                else -> null
            }
        }
    }
}
