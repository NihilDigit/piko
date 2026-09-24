package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class NameGroupSummary(val selected: Int, val total: Int, val bytes: Long, val hasUnindexed: Boolean)

enum class InstantActionKind {
    /** 只选了一项且已收录，秒传。 */
    INSTANT_SAVE,

    /** 多于一项，或含未收录的：整条链离线，完成后删掉未选的文件。 */
    OFFLINE_PACK,

    /** 没有解析结果（非磁力链接，或云端未收录），整条输入交给离线任务。 */
    SUBMIT_OFFLINE,
}

/** [fileCount] 是实际要存的文件数，含随视频打包的字幕；整条提交离线时为 0。 */
data class InstantPrimaryAction(val kind: InstantActionKind, val fileCount: Int, val enabled: Boolean)

/** 一次保存的结果。导航与提示由调用方处理，这里只报告存到了哪里。 */
sealed interface InstantSaveOutcome {
    val target: PikoPathBreadcrumb

    data class InstantSaved(
        val createdIds: List<String>,
        override val target: PikoPathBreadcrumb,
    ) : InstantSaveOutcome

    data class OfflineTaskCreated(override val target: PikoPathBreadcrumb) : InstantSaveOutcome
}

/** 预览播放的请求：文件已秒传进 Piko-Temp，由视图交给播放器。 */
data class InstantPreviewRequest(val fileId: String, val fileName: String)

/**
 * 秒传与磁力解析的工作台状态，两端共用。
 *
 * 流程：粘上磁力链自动解析（防抖），按启发式预选主体文件，确定保存目标（记住的目标、
 * 失效则回退 My Packs），然后按 [planSave] 定的路线秒传或整包离线。
 *
 * 视频行可以预览：秒传进 Piko-Temp 再播放，同一会话内不重复秒传，保存时直接移过去。
 * 会话结束（作用域取消）时，用过 Piko-Temp 就把它整个删掉。
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
    private val previewFolder: PreviewTempFolder,
    private val packTracker: OfflinePackTracker,
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

    /** 多项保存时的文件夹名，解析成功后以资源名预填。整包离线完成后产出文件夹改成这个名字。 */
    var folderName by mutableStateOf("")
        private set

    /** 网盘剩余空间，解析成功后查一次。null 表示还没查到或查询失败，此时不拦整包离线。 */
    var remainingBytes by mutableStateOf<Long?>(null)
        private set

    /** 正在秒传进 Piko-Temp 的那一行。同一时刻只预览一个。 */
    var previewingIndex by mutableStateOf<Int?>(null)
        private set

    // gcid 到 Piko-Temp 里的文件 id。同一会话内再预览、或保存这一项时直接复用
    private val previewedIds = mutableMapOf<String, String>()
    private var usedPreviewFolder = false

    private val _outcomes = MutableSharedFlow<InstantSaveOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<InstantSaveOutcome> = _outcomes.asSharedFlow()

    private val _previewRequests = MutableSharedFlow<InstantPreviewRequest>(extraBufferCapacity = 1)
    val previewRequests: SharedFlow<InstantPreviewRequest> = _previewRequests.asSharedFlow()

    /** 预览失败等一次性提示。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
     * 这次保存是否存进新建的一层目录。只选一项时直接存进目标目录，不必再套一层。
     * 整包离线的目录是 PikPak 以种子名建的，完成后改成这里填的名字。
     */
    val willCreateFolder: Boolean by derivedStateOf { resolution != null && selectedEntryCount > 1 }

    val canSaveSelection: Boolean by derivedStateOf {
        !isSaving && selectedItems.isNotEmpty() && target != null &&
            !(willCreateFolder && folderName.isBlank())
    }

    /** 路线与代价，见 [planSave]。没勾任何一项时为 null。 */
    val savePlan: SavePlan? by derivedStateOf {
        planSave(items, selectedIndices, selectedEntryCount, remainingBytes)
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

    /** 视图上显示的层级：去掉了与资源名重复的顶层组，见 [withoutRedundantGroups]。 */
    val displayTree: List<NameNode> by derivedStateOf {
        nameTree.withoutRedundantGroups(resolution?.resource?.name.orEmpty())
    }

    // 用户手动展开或收起过的组；没记录的按 isGroupExpanded 的默认规则。换一次解析结果就清空
    private val expandedGroups = mutableStateMapOf<String, Boolean>()

    /**
     * 顶层只有一个组时默认展开它，其余一律收起：一个资源常分正片、剧场版、特典几组，
     * 全展开时第一屏只看得到第一组的头几行，收起时是一张目录，一组一行。
     * 放在状态里而不是视图里：面板收起再展开、两端各自的视图，看到的展开状态都一致。
     */
    fun isGroupExpanded(key: String, depth: Int): Boolean =
        expandedGroups[key] ?: (depth == 0 && displayTree.count { it is NameGroup } == 1)

    fun toggleGroupExpanded(key: String, depth: Int) {
        expandedGroups[key] = !isGroupExpanded(key, depth)
    }

    val treeRows: List<NameTreeRow> by derivedStateOf { flattenNameTree(displayTree, ::isGroupExpanded) }

    /** 组行上显示的统计。条目数不含打包进视频的字幕，与行对得上。 */
    fun summaryOf(group: NameGroup): NameGroupSummary = NameGroupSummary(
        selected = group.indices.count { it in selectedIndices },
        total = group.indices.size,
        bytes = group.indices.sumOf { items[it].file.size },
        hasUnindexed = group.indices.any { !items[it].isInstantReady },
    )

    /**
     * 面板底部唯一的主操作。原先顶部有「解析 / 提交离线」、底部又有「保存」，失败时两个同时
     * 出现，要读完两行文案才知道该点哪个；重新解析挪进了错误提示。
     * 为 null 表示眼下没有可提交的：输入为空，或磁力链还在解析。
     */
    val primaryAction: InstantPrimaryAction? by derivedStateOf {
        when {
            resolution != null -> {
                val plan = savePlan
                InstantPrimaryAction(
                    kind = if (plan?.route == SaveRoute.OFFLINE_PACK) InstantActionKind.OFFLINE_PACK else InstantActionKind.INSTANT_SAVE,
                    fileCount = plan?.fileCount ?: 0,
                    enabled = canSaveSelection && plan?.lacksSpace != true,
                )
            }
            input.isBlank() -> null
            normalizedMagnet != null && errorMessage == null -> null
            else -> InstantPrimaryAction(
                kind = InstantActionKind.SUBMIT_OFFLINE,
                fileCount = 0,
                enabled = target != null && !isSaving && !isResolving,
            )
        }
    }

    fun performPrimaryAction() {
        when (primaryAction?.kind) {
            InstantActionKind.INSTANT_SAVE, InstantActionKind.OFFLINE_PACK -> saveSelection()
            InstantActionKind.SUBMIT_OFFLINE -> submitOfflineTask()
            null -> Unit
        }
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
        // 面板关闭即会话结束，作用域随之取消。清理要在它之后跑完，交给 Piko-Temp 自己的作用域
        scope.coroutineContext[Job]?.invokeOnCompletion {
            if (usedPreviewFolder) previewFolder.clearInBackground()
        }
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

    /** 可以预览的行：已收录的视频。未收录的秒传不了，也就没法先放进网盘里播。 */
    fun canPreview(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        return item.isInstantReady && item.file.name.fileCategory() == FileCategory.VIDEO
    }

    /**
     * 秒传进 Piko-Temp 后交给播放器。本会话已放进去过的直接复用：秒传按大小的 15%
     * 扣上传额度，同一个文件看两次不该扣两次。
     */
    fun preview(index: Int) {
        val item = items.getOrNull(index) ?: return
        val gcid = item.file.gcid ?: return
        previewedIds[gcid]?.let { fileId ->
            _previewRequests.tryEmit(InstantPreviewRequest(fileId, item.file.name))
            return
        }
        if (previewingIndex != null) return
        previewingIndex = index
        // 请求发出去就可能已经建好了文件，哪怕随后被取消，所以在发请求之前记下
        usedPreviewFolder = true
        scope.launch {
            try {
                previewFolder.put(item.file)
                    .onSuccess { fileId ->
                        previewedIds[gcid] = fileId
                        _previewRequests.emit(InstantPreviewRequest(fileId, item.file.name))
                    }
                    .onFailure { _messages.emit("预览失败：${it.message}") }
            } finally {
                previewingIndex = null
            }
        }
    }

    /** 按 [savePlan] 的路线保存当前勾选。 */
    fun saveSelection() {
        val plan = savePlan ?: return
        if (isSaving || plan.lacksSpace) return
        val toSave = selectedItems
        isSaving = true
        scope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                when (plan.route) {
                    SaveRoute.INSTANT -> instantSave(targetBread, toSave, keepStructure = false)
                    SaveRoute.OFFLINE_PACK -> packSave(targetBread, toSave)
                }
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * 空间放不下整包时的退路：只秒传选中的文件，按种子里的目录结构存进新建的文件夹。
     * 未收录的文件没有 gcid，这条路存不了，保存栏已写明会跳过几个。
     */
    fun saveSelectionInstantly() {
        if (isSaving || savePlan?.fallback == null) return
        val toSave = selectedItems.filter { it.isInstantReady }
        isSaving = true
        scope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                val name = FileNameSanitizer.sanitize(folderName)
                val folderId = driveRepo.createFolder(targetBread.id, name).getOrElse { err ->
                    errorMessage = "新建文件夹失败：${err.message}"
                    return@launch
                }
                instantSave(PikoPathBreadcrumb(folderId, name), toSave, keepStructure = true)
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
                val targetBread = target ?: resolveTarget()
                instantRepo.enqueueOfflineTask(submittedUrl(), targetBread.id)
                    .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(targetBread)) }
                    .onFailure { errorMessage = "保存失败：${it.message}" }
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
        expandedGroups.clear()
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
        scope.launch { refreshRemainingBytes() }
    }

    /** 查一次网盘余量。limit 为 0 的账号当作不限；查询失败保留上一次的数。 */
    private suspend fun refreshRemainingBytes(): Long? {
        driveRepo.getQuota().onSuccess { response ->
            remainingBytes = response.quota.takeIf { it.limitBytes > 0 }?.remainingBytes
        }
        return remainingBytes
    }

    // 只粘了 infohash 的输入要补成磁力链再交给离线，createUrlFile 不认裸的 hash
    private fun submittedUrl(): String = normalizedMagnet ?: input.trim()

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

    private suspend fun instantSave(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        keepStructure: Boolean,
    ) {
        instantRepo.instantSave(toSave, target.id, reuse = previewedIds.toMap(), keepStructure = keepStructure)
            .onSuccess { createdIds ->
                // 移出 Piko-Temp 的不能再当作预览副本：下次预览会指向保存目录里的这份
                toSave.forEach { item -> item.file.gcid?.let(previewedIds::remove) }
                _outcomes.emit(InstantSaveOutcome.InstantSaved(createdIds, target))
            }
            .onFailure { errorMessage = "保存失败：${it.message}" }
    }

    private suspend fun packSave(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>) {
        val allItems = items
        val packBytes = allItems.sumOf { it.file.size }
        // 提交前再查一次：解析时查到的余量可能已经过时，而离线一旦提交就是整包落盘。
        // 放不下时 savePlan 随 remainingBytes 变为 lacksSpace，保存栏换成空间不足的说明
        val remaining = refreshRemainingBytes()
        if (remaining != null && packBytes > remaining) return
        packTracker.submit(
            url = submittedUrl(),
            targetId = target.id,
            folderName = FileNameSanitizer.sanitize(folderName),
            keep = toSave.map { it.file.path }.toSet(),
            totalFiles = allItems.size,
            totalBytes = packBytes,
        )
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
