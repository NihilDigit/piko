package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.reportFailure
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.MediaFileInput
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.naming.av.AvNamingItem
import dev.piko.shared.naming.av.canonicalAvNames
import dev.piko.shared.naming.av.canonicalResourceName
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.scrape.MetaTubeTitles
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.QuotaResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** [unindexed] 是组里含未收录文件的行数，与 [total] 一样按行计。 */
data class NameGroupSummary(val selected: Int, val total: Int, val bytes: Long, val unindexed: Int)

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

    /** [submittedCount] 是批量保存时提交的链接数，单条为 1。 */
    data class OfflineTaskCreated(
        override val target: PikoPathBreadcrumb,
        val submittedCount: Int = 1,
    ) : InstantSaveOutcome
}

/** 预览播放的请求：文件已秒传进 Piko-Temp，由视图交给播放器。 */
data class InstantPreviewRequest(val fileId: String, val fileName: String)

/** 一次会话里各条链接共用的部分。批量时每条链接各有一个 [InstantSheetState]，共用这一份。 */
internal class InstantSharedContext {
    /** 保存目标对全部链接生效，在任一处更换都改这一份。 */
    val target = mutableStateOf<PikoPathBreadcrumb?>(null)
    val targetNotice = mutableStateOf<String?>(null)

    /** 用户在面板里另选过目标，此后不再跟随网盘页的当前目录。 */
    var targetChosen = false

    // gcid 到 Piko-Temp 里的文件 id。同一会话内再预览、或保存这一项时直接复用；
    // 两条链接里的同一个文件也共用这一份
    val previewedIds = mutableMapOf<String, String>()
    var usedPreviewFolder = false

    // 一次粘几十条时不同时压给服务端；单条时只有一个请求，不受影响
    val resolvePermits = Semaphore(RESOLVE_CONCURRENCY)

    /** 各条链接按同一个账号定路线，随网盘余量一起查。 */
    val account = mutableStateOf(SaveAccount())

    /** 保存时按番号规范命名，对这次会话的全部链接生效。初值取设置（默认关），面板里改了不写回设置。 */
    val canonicalNames = mutableStateOf(false)

    /** 从一次余量查询里取账号约束。账号类型登录时已取，这里不另发请求。 */
    fun updateAccount(free: Boolean?, quota: QuotaResponse) {
        account.value = SaveAccount(free = free == true, offlineLeft = quota.quotas.cloudDownload.remaining)
    }

    private companion object {
        const val RESOLVE_CONCURRENCY = 3
    }
}

/**
 * 秒传与磁力解析的工作台状态，两端共用。
 *
 * 流程：粘上磁力链自动解析（防抖），按文件名解析器组织成「作品 → 分区 → 条目」并预选正片，
 * 确定保存目标（网盘页的当前目录，见 [followDriveFolder]），然后按 [planSave] 定的路线秒传或整包离线。
 * 秒传成功的另记一笔 [InstantSaveRecords]，传输页据此列出。
 *
 * 视频行可以预览：秒传进 Piko-Temp 再播放，同一会话内不重复秒传，保存时直接移过去。
 * 会话结束（作用域取消）时，用过 Piko-Temp 就把它整个删掉。
 *
 * 这里只保存、不导航：结果经 [outcomes] 交给调用方。秒传由它通过 DriveScreenState 切到
 * 目标目录，顺带清掉搜索与选中，在这里直接改仓库的目录栈会绕过那一步；离线由它切到传输页。
 *
 * 解析失败与目标失效是长驻的说明文字，用状态表达；保存结果是一次性事件，用事件流。
 *
 * 一次粘进两条以上链接时，这一个实例只作输入，列表在 [batch]：每条链接另有一个子实例，
 * 与单条时的工作台完全相同，勾选、文件夹名与预览各自保留；保存目标与预览副本经
 * [InstantSharedContext] 共用。
 */
class InstantSheetState private constructor(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val previewFolder: PreviewTempFolder,
    private val packTracker: OfflinePackTracker,
    private val saveRecords: InstantSaveRecords,
    private val scope: CoroutineScope,
    initialMagnet: String,
    private val shared: InstantSharedContext,
    /** 面板直接持有的那个实例；批量列表里各行的子实例为 false。 */
    private val isRoot: Boolean,
    /** 规范命名时取 MetaTube 片名、保存后补名；为 null 或没配地址时用原名里的片名。 */
    private val titleFill: InstantTitleFill?,
) {
    constructor(
        instantRepo: InstantMagnetRepository,
        driveRepo: PikoDriveRepository,
        preferences: PikoUserPreferences,
        previewFolder: PreviewTempFolder,
        packTracker: OfflinePackTracker,
        saveRecords: InstantSaveRecords,
        scope: CoroutineScope,
        initialMagnet: String = "",
        titleFill: InstantTitleFill? = null,
    ) : this(
        instantRepo, driveRepo, preferences, previewFolder, packTracker, saveRecords, scope, initialMagnet,
        InstantSharedContext(), isRoot = true, titleFill,
    )

    var input by mutableStateOf(initialMagnet)
        private set

    var isResolving by mutableStateOf(false)
        private set

    /** 云端已返回文件列表，正在后台按文件名整理。属于 [isResolving] 的后半段。 */
    var isAnalyzing by mutableStateOf(false)
        private set

    var isSaving by mutableStateOf(false)
        private set
    var resolution by mutableStateOf<MagnetResolutionResult?>(null)
        private set
    var selectedIndices by mutableStateOf<Set<Int>>(emptySet())
        private set

    // 解析后的默认勾选之外，人自己勾过或取消过。换一次解析结果就清掉
    private var selectionPicked by mutableStateOf(false)

    /**
     * 人亲手挑过、还勾着要保存的文件，批量时看各行。解析后的默认勾选不算：同一条链再粘一次就是同样的勾选，
     * 丢了不可惜；挑过的丢了要重新挑。全部取消了的也不算，没有要保存的东西。
     */
    val hasPickedFiles: Boolean
        get() = (selectionPicked && selectedIndices.isNotEmpty()) || batch?.rows?.any { it.state.hasPickedFiles } == true

    /** 解析或保存失败的原因，下一次解析开始时清空。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 解析成功但云端没有这个资源，只能整条离线。批量列表据此与解析失败区分开。 */
    var isUnindexed by mutableStateOf(false)
        private set

    /** 保存目标。为 null 表示还在确认，此时不拿 My Packs 顶替，免得闪一个可能是错的名字。 */
    var target: PikoPathBreadcrumb? by shared.target
        private set

    /** 当前目录已失效、已回退到 My Packs 时的说明。 */
    var targetNotice: String? by shared.targetNotice
        private set

    /** 粘进两条以上链接时的批量列表，为 null 时是单条链接的工作台。 */
    var batch by mutableStateOf<InstantBatchState?>(null)
        private set

    /** 多项保存时的文件夹名，解析成功后以主作品的标题预填。整包离线完成后产出文件夹改成这个名字。 */
    var folderName by mutableStateOf("")
        private set

    // region 按番号规范命名

    /** 保存时是否按番号规范命名，见 [InstantSharedContext.canonicalNames]。 */
    var useCanonicalNames: Boolean by shared.canonicalNames
        private set

    fun updateUseCanonicalNames(enabled: Boolean) {
        useCanonicalNames = enabled
    }

    /**
     * 各文件的两种规范名，与 [items] 一一对应：[titled] 带片名，[bare] 只有番号、旗标与分段，存进以这个番号命名的
     * 新建文件夹时用。[codes] 是各文件归入的番号，[videos] 是视频的番号信息，文件夹名据此取。
     */
    private class CanonicalNames(val titled: List<String>, val bare: List<String>, val codes: List<String?>, val videos: List<AvInfo?>)

    // 资源里没有会改名的番号文件时为 null，面板不给这个开关。解析成功时在后台一并算好，查到片名后重算
    private var canonical by mutableStateOf<CanonicalNames?>(null)

    /** 资源里有会改名的番号文件，面板据此给出「按番号规范命名」。 */
    val offersCanonicalNames: Boolean get() = canonical != null

    /** 从 MetaTube 查到的片名，番号到片名。没配 MetaTube 或还没查时为空。 */
    private var titles by mutableStateOf(emptyMap<String, String>())

    // 这个解析结果的片名查询，挂在进程级的 InstantTitleFill 上。保存时交给它补名（adopted），此后不随面板取消
    private var titleLookup: Deferred<MetaTubeTitles>? = null
    private var titleLookupAdopted = false

    // 用户改过文件夹名就以他写的为准，开关不再替换
    private var folderNameEdited by mutableStateOf(false)

    private val selectedVideos: List<AvInfo> by derivedStateOf {
        val videos = canonical?.videos ?: return@derivedStateOf emptyList()
        selectedIndices.sorted().mapNotNull { videos.getOrNull(it) }
    }

    /** 新建的文件夹以哪个番号命名：所选视频只有一个番号时是它，几个番号时文件夹照原名，里面的文件各自带片名。 */
    private val folderCode: String? by derivedStateOf {
        selectedVideos.map { it.code }.distinct().singleOrNull().takeIf { willCreateFolder }
    }

    /**
     * 存进网盘时的文件名。存进以这个番号命名的新建文件夹时只写番号、旗标与分段（ABC-123-CD1.mp4），
     * 片名已在文件夹名上；单个文件直接存进目标目录时带片名。
     */
    fun nameToSave(index: Int): String {
        val original = items[index].file.name
        val names = canonical?.takeIf { useCanonicalNames } ?: return original
        return fileNameIn(names, index, folderCode, original)
    }

    /** 面板行上改显示的名字：开着规范命名、且这一行确实会改名时才有，否则为 null，照常显示解析出的标签。 */
    fun renamedLabel(row: InstantRow): String? =
        nameToSave(row.index).takeIf { useCanonicalNames && it != items[row.index].file.name }

    // 新建文件夹以番号命名：开着规范命名、用户没改过文件夹名、所选视频只有一个番号
    private val folderNamedByCode: Boolean by derivedStateOf { useCanonicalNames && !folderNameEdited && folderCode != null }

    /**
     * 新建文件夹的名字：开着规范命名、用户没改过、所选视频只有一个番号时换成规范名（ABC-123 片名），
     * 不带分段与压制标记。输入框显示的也是它。整包离线完成后产出的文件夹同样改成它，里面的文件离线任务改不了名。
     */
    val folderNameToSave: String by derivedStateOf {
        if (folderNamedByCode) resourceFolderName(folderName, selectedVideos, titles) else folderName
    }

    /**
     * 保存那一刻的命名：落盘用它定名字，片名随后查到时也按它重算（见 [titleFillRequest]），
     * 保存途中或之后面板上再改开关、勾选与文件夹名，都不影响这一次。
     */
    private inner class SaveNaming(toSave: List<InstantFileItem>) {
        val data = resolution
        val names = canonical?.takeIf { useCanonicalNames }
        val folderCode = this@InstantSheetState.folderCode
        val folderNamedByCode = this@InstantSheetState.folderNamedByCode
        val folderName = this@InstantSheetState.folderName
        val folderVideos = selectedVideos
        val folderNameToSave = this@InstantSheetState.folderNameToSave
        private val indexOf = items.withIndex().associate { (index, item) -> item.file.path to index }

        /** 种子内路径到存进网盘时的名字，只列改了名的。 */
        val fileNames: Map<String, String> = names?.let { names ->
            toSave.mapNotNull { item ->
                val index = indexOf[item.file.path] ?: return@mapNotNull null
                fileNameIn(names, index, folderCode, item.file.name).takeIf { it != item.file.name }?.let { item.file.path to it }
            }.toMap()
        }.orEmpty()

        fun indexOf(item: InstantFileItem): Int? = indexOf[item.file.path]
    }

    // endregion

    /** 网盘剩余空间，解析成功后查一次。null 表示还没查到或查询失败，此时不拦整包离线。 */
    var remainingBytes by mutableStateOf<Long?>(null)
        private set

    /** 正在秒传进 Piko-Temp 的那一行。同一时刻只预览一个。 */
    var previewingIndex by mutableStateOf<Int?>(null)
        private set

    private val previewedIds get() = shared.previewedIds

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

    /** 还什么都没做：没粘链接、没有解析结果、没在解析或保存。这时收起面板等于没打开过，见 InstantSession.collapse。 */
    val isBlank: Boolean
        get() = input.isBlank() && resolution == null && batch == null && !isResolving && !isSaving

    val selectedItems: List<InstantFileItem> by derivedStateOf {
        selectedIndices.sorted().mapNotNull(items::getOrNull)
    }

    /** 「保存配套字幕」开关，读自偏好。 */
    private var saveAttachedSubtitles by mutableStateOf(true)

    /**
     * 实际要保存的文件。开关关闭时去掉挂在视频下的字幕：勾选和面板显示照旧，
     * 只在保存这一步生效，免得用户来回切开关时丢了自己的勾选。
     */
    private val itemsToSave: List<InstantFileItem> by derivedStateOf {
        if (saveAttachedSubtitles) {
            selectedItems
        } else {
            val attached = tree?.rows?.flatMap { it.subtitleIndices }?.toSet().orEmpty()
            selectedIndices.filter { it !in attached }.sorted().mapNotNull(items::getOrNull)
        }
    }

    /**
     * 这次保存是否存进新建的一层目录。只选一项时直接存进目标目录，不必再套一层。
     * 整包离线的目录是 PikPak 以种子名建的，完成后改成这里填的名字。
     */
    val willCreateFolder: Boolean by derivedStateOf { resolution != null && selectedEntryCount > 1 }

    val canSaveSelection: Boolean by derivedStateOf {
        !isSaving && selectedItems.isNotEmpty() && target != null &&
            !(willCreateFolder && folderNameToSave.isBlank())
    }

    /** 今天还能建几个离线任务，会员或未知时为 null。 */
    val offlineLeft: Int? get() = shared.account.value.offlineLeft

    /** 免费账号每建一个离线任务都先确认，见 [SaveAccount]。 */
    val confirmsOffline: Boolean get() = shared.account.value.free

    /**
     * 解析到了 gcid，秒传时云端却还没有内容（别人上传到一半）。会员自动改交离线；
     * 免费账号的离线一天只有几次，停下来改由主操作提交，走一遍确认。
     */
    var contentMissing by mutableStateOf(false)
        private set

    /** 路线与代价，见 [planSave]。没勾任何一项时为 null。 */
    val savePlan: SavePlan? by derivedStateOf {
        planSave(items, selectedIndices, selectedEntryCount, remainingBytes, shared.account.value)
    }

    val isAllSelected: Boolean by derivedStateOf {
        items.isNotEmpty() && selectedIndices.size == items.size
    }

    /** 解析结果的文件树，与 [resolution] 同时就位。 */
    var tree by mutableStateOf<InstantTree?>(null)
        private set

    /** 列表里的行数：字幕等附件随视频成一行，不单算，与视图对得上。 */
    val entryCount: Int by derivedStateOf { tree?.rows?.size ?: 0 }
    val selectedEntryCount: Int by derivedStateOf { tree?.rows?.count { it.index in selectedIndices } ?: 0 }

    // 用户手动展开或收起过的组；没记录的按组自带的默认值。换一次解析结果就清空
    private val expandedGroups = mutableStateMapOf<String, Boolean>()

    /**
     * 放在状态里而不是视图里：面板收起再展开、两端各自的视图，看到的展开状态都一致。
     * 默认值见 [buildInstantTree]：正片、SP、剧场版展开，PV、特典、菜单与「其他文件」收起。
     */
    fun isGroupExpanded(group: InstantGroup): Boolean = expandedGroups[group.key] ?: group.defaultExpanded

    fun toggleGroupExpanded(group: InstantGroup) {
        expandedGroups[group.key] = !isGroupExpanded(group)
    }

    /** 这一行连同随它保存的字幕、音轨里有没有收录的。与文件行上的标记同一口径。 */
    fun isUnindexed(row: InstantRow): Boolean = row.indices.any { !items[it].isInstantReady }

    /** 含未收录文件的行数，与「已选 x / y」一样按行计。这些行保存时要离线下载，慢。 */
    val unindexedEntryCount: Int by derivedStateOf { tree?.rows?.count(::isUnindexed) ?: 0 }

    /**
     * 只列出含未收录文件的行。它们多半散在默认收起的分区里，照常浏览找不到；
     * 用筛选而不是替人展开那几个组：展开会改掉用户自己的展开状态，筛选关掉就原样回来。
     */
    var showsOnlyUnindexed by mutableStateOf(false)
        private set

    /** 筛选此刻是否生效。未收录的行被全部取消勾选不影响；换了解析结果、已没有未收录的行时自然失效。 */
    val isUnindexedFilterActive: Boolean by derivedStateOf { showsOnlyUnindexed && unindexedEntryCount > 0 }

    fun toggleOnlyUnindexed() {
        showsOnlyUnindexed = !isUnindexedFilterActive
    }

    val treeRows: List<InstantTreeRow> by derivedStateOf {
        val tree = tree ?: return@derivedStateOf emptyList()
        if (isUnindexedFilterActive) tree.flattenMatching(::isUnindexed) else tree.flatten(::isGroupExpanded)
    }

    /** 组行上显示的统计。条目数按行计，不含随视频的字幕。 */
    fun summaryOf(group: InstantGroup): NameGroupSummary = NameGroupSummary(
        selected = group.rows.count { it.index in selectedIndices },
        total = group.rows.size,
        bytes = group.indices.sumOf { items[it].file.size },
        unindexed = group.rows.count(::isUnindexed),
    )

    /**
     * 面板底部唯一的主操作。原先顶部有「解析 / 提交离线」、底部又有「保存」，失败时两个同时
     * 出现，要读完两行文案才知道该点哪个；重新解析挪进了错误提示。
     * 为 null 表示眼下没有可提交的：输入为空，或磁力链还在解析。
     */
    val primaryAction: InstantPrimaryAction? by derivedStateOf {
        when {
            resolution != null && contentMissing -> InstantPrimaryAction(
                kind = InstantActionKind.SUBMIT_OFFLINE,
                fileCount = 0,
                enabled = target != null && !isSaving && shared.account.value.offlineLeft != 0,
            )
            resolution != null -> {
                val plan = savePlan
                InstantPrimaryAction(
                    kind = if (plan?.route == SaveRoute.OFFLINE_PACK) InstantActionKind.OFFLINE_PACK else InstantActionKind.INSTANT_SAVE,
                    fileCount = plan?.fileCount ?: 0,
                    enabled = canSaveSelection && plan?.blocked != true,
                )
            }
            input.isBlank() -> null
            normalizedMagnet != null && errorMessage == null -> null
            else -> InstantPrimaryAction(
                kind = InstantActionKind.SUBMIT_OFFLINE,
                fileCount = 0,
                enabled = target != null && !isSaving && !isResolving && shared.account.value.offlineLeft != 0,
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

    private var resolveJob: Job? = null
    private var resolvedKey: String? = null

    init {
        if (isRoot) {
            // 面板关闭即会话结束，作用域随之取消。清理要在它之后跑完，交给 Piko-Temp 自己的作用域。
            // 子实例不登记：移除一行只取消那一行，Piko-Temp 里可能还有别的行预览过的文件
            scope.coroutineContext[Job]?.invokeOnCompletion {
                if (shared.usedPreviewFolder) previewFolder.clearInBackground()
            }
            scope.launch { followDriveFolder() }
            // 账号约束要在解析之前就位：整条交给离线的链接不解析，免费账号也要先确认
            scope.launch { refreshRemainingBytes() }
            // 开关的初值取设置，面板里改了只管这一次
            scope.launch { useCanonicalNames = preferences.autoCanonicalNamesFlow.first() }
        }
        scope.launch { preferences.bundleSubtitlesFlow.collect { saveAttachedSubtitles = it } }
        scope.launch { fetchTitles() }
        // 没保存就结束的，片名不再有人要
        scope.coroutineContext[Job]?.invokeOnCompletion { releaseTitleLookup() }
        if (initialMagnet.isNotBlank() && !startBatchIfMany()) {
            if (normalizeMagnet(initialMagnet) == null) {
                // 外部唤起的链不合法时自动解析不会发生，说明一句它只能整条离线
                errorMessage = "非磁力链接，可离线下载"
            } else {
                scheduleResolve()
            }
        }
    }

    fun updateInput(value: String) {
        input = value
        // 多条链接时 normalizeMagnet 为 null，这一步顺带清掉单条的解析结果
        scheduleResolve()
        startBatchIfMany()
    }

    /**
     * 输入里有两条以上链接就换成批量列表，返回是否换了。分享链接仍走转存，不进列表。
     * 批量列表里没有输入框：要换一批链接就点 × 结束这一次，或把列表里的行删光回到空的输入框。
     */
    private fun startBatchIfMany(): Boolean {
        if (!isRoot || findShareLink(input) != null) return false
        val links = extractLinks(input)
        if (links.size < 2) return false
        // 逐字输入时每多识别出一条链接就会走到这里
        batch?.dispose()
        batch = InstantBatchState(
            links = links,
            newRow = ::newBatchRow,
            driveRepo = driveRepo,
            shared = shared,
            scope = scope,
            emitOutcome = { _outcomes.emit(it) },
            emitMessage = { _messages.emit(it) },
            onEmpty = ::leaveBatch,
            titleFill = titleFill,
        )
        return true
    }

    private fun newBatchRow(link: PastedLink, rowScope: CoroutineScope) = InstantSheetState(
        instantRepo, driveRepo, preferences, previewFolder, packTracker, saveRecords, rowScope, link.uri, shared, isRoot = false, titleFill,
    )

    /** 列表里的行删光了，回到空的输入框。 */
    private fun leaveBatch() {
        batch = null
        input = ""
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

    /** 整组勾选或取消，给作品、分区与「其他文件」用。 */
    fun setGroupSelected(group: InstantGroup, selected: Boolean) {
        setItemsSelected(group.indices, selected)
    }

    /** 勾视频就连同挂在它下面的字幕，取消亦然；视图里字幕不单列，没有别的途径碰到它们。 */
    private fun setItemsSelected(indices: Collection<Int>, selected: Boolean) {
        val affected = indices.flatMap { index -> tree?.rowOf(index)?.indices ?: listOf(index) }.toSet()
        selectedIndices = if (selected) selectedIndices + affected else selectedIndices - affected
        selectionPicked = true
    }

    fun toggleSelectAll() {
        selectedIndices = if (isAllSelected) emptySet() else items.indices.toSet()
        selectionPicked = true
    }

    fun updateFolderName(value: String) {
        folderName = value
        folderNameEdited = true
    }

    /** 更换本次的保存目标。只管这一次会话，下次仍默认存进网盘页的当前目录。 */
    fun changeTarget(breadcrumb: PikoPathBreadcrumb) {
        shared.targetChosen = true
        target = breadcrumb
        targetNotice = null
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
        previewFile(item.file, index)
    }

    var previewingSharedId by mutableStateOf<String?>(null)
        private set

    fun previewSharedFile(file: io.github.nihildigit.pikpak.FileStat) {
        if (file.isFolder || file.name.fileCategory() != FileCategory.VIDEO || file.hash.isBlank() || previewingIndex != null) return
        previewingSharedId = file.id
        previewFile(io.github.nihildigit.pikpak.ResolvedFile(file.name, file.sizeBytes, file.hash), -1)
        if (previewingIndex == null) previewingSharedId = null
    }

    private fun previewFile(file: io.github.nihildigit.pikpak.ResolvedFile, index: Int) {
        val gcid = file.gcid ?: return
        previewedIds[gcid]?.let { fileId ->
            _previewRequests.tryEmit(InstantPreviewRequest(fileId, file.name))
            return
        }
        if (previewingIndex != null) return
        previewingIndex = index
        // 请求发出去就可能已经建好了文件，哪怕随后被取消，所以在发请求之前记下
        shared.usedPreviewFolder = true
        scope.launch {
            try {
                previewFolder.put(file)
                    .onSuccess { fileId ->
                        previewedIds[gcid] = fileId
                        _previewRequests.emit(InstantPreviewRequest(fileId, file.name))
                    }
                    .logFailure(TAG, "预览失败")
                    .onFailure { _messages.emit("预览失败：${it.message}") }
            } finally {
                previewingIndex = null
                previewingSharedId = null
            }
        }
    }

    /** 按 [savePlan] 的路线保存当前勾选。 */
    fun saveSelection() {
        val plan = savePlan ?: return
        if (isSaving || plan.blocked) return
        val toSave = itemsToSave
        isSaving = true
        val fills = mutableListOf<InstantTitleFill.Request>()
        scope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                when (plan.route) {
                    SaveRoute.INSTANT -> if (willCreateFolder) {
                        saveIntoNewFolder(targetBread, toSave, fills).onSuccess { _outcomes.emit(it) }
                    } else {
                        saveInstantOrOffline(targetBread, toSave, fills).onSuccess { ids ->
                            _outcomes.emit(
                                if (ids != null) InstantSaveOutcome.InstantSaved(ids, targetBread) else InstantSaveOutcome.OfflineTaskCreated(targetBread),
                            )
                        }
                    }
                    SaveRoute.OFFLINE_PACK -> {
                        // 提交前再查一次：解析时查到的余量可能已经过时，而离线一旦提交就是整包落盘。
                        // 放不下时 savePlan 随 remainingBytes 变为 lacksSpace，保存栏换成空间不足的说明
                        val remaining = refreshRemainingBytes()
                        if (remaining != null && plan.packBytes > remaining) {
                            PikoLog.i(TAG, "空间不足，不提交整包离线：需要 ${plan.packBytes} 字节，剩余 $remaining 字节")
                            return@launch
                        }
                        if (shared.account.value.offlineLeft == 0) {
                            PikoLog.i(TAG, "今日离线次数已用完，不提交整包离线")
                            return@launch
                        }
                        packSave(targetBread, toSave)
                            .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(targetBread)) }
                    }
                }
            } finally {
                isSaving = false
                titleFill?.fill(fills)
            }
        }
    }

    /**
     * 整包离线走不通时的退路：只秒传选中的文件，按种子里的目录结构存进新建的文件夹。
     * 未收录的文件没有 gcid，这条路存不了，保存栏已写明会跳过几个。
     */
    fun saveSelectionInstantly() {
        if (isSaving || savePlan?.fallback == null) return
        val toSave = itemsToSave.filter { it.isInstantReady }
        isSaving = true
        val fills = mutableListOf<InstantTitleFill.Request>()
        scope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                val remaining = refreshRemainingBytes()
                if (remaining != null && toSave.sumOf { it.file.size } > remaining) {
                    PikoLog.i(TAG, "空间不足，不秒传：需要 ${toSave.sumOf { it.file.size }} 字节，剩余 $remaining 字节")
                    errorMessage = "网盘空间不足，无法保存所选文件"
                    return@launch
                }
                val saved = saveIntoNewFolder(targetBread, toSave, fills)
                saved.onSuccess { _outcomes.emit(it) }
            } finally {
                isSaving = false
                titleFill?.fill(fills)
            }
        }
    }

    /**
     * 秒传 [toSave]，按种子里的目录结构存进 [target] 下以 [folderNameToSave] 新建的文件夹。
     * 存成了而片名可能随后才查到的，往 [titleFills] 里添一项，由调用方交给 InstantTitleFill。
     */
    private suspend fun saveIntoNewFolder(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        titleFills: MutableList<InstantTitleFill.Request>,
    ): Result<InstantSaveOutcome.InstantSaved> {
        val naming = SaveNaming(toSave)
        val name = driveFolderName(naming.folderNameToSave)
        val folderId = driveRepo.createFolder(target.id, name).getOrElse { err ->
            PikoLog.w(TAG, "新建保存目录失败", err)
            errorMessage = "新建文件夹失败：${err.message}"
            return Result.failure(err)
        }
        val folder = PikoPathBreadcrumb(folderId, name)
        return instantSave(folder, toSave, naming, keepStructure = true).map { ids ->
            saveRecords.add(name, ids.size, toSave.sumOf { it.file.size }, target.name, locateId = folderId)
            titleFillRequest(naming, toSave, ids, folder)?.let(titleFills::add)
            InstantSaveOutcome.InstantSaved(ids, folder)
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
                submitWhole(targetBread)
                    .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(targetBread)) }
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * 批量保存时由 [InstantBatchState] 逐行调用，路线与单条时的主操作相同，只是结果交回给列表汇总，
     * 不发 [outcomes]。空间由列表按全部整包合计后检查，这里不再逐条查。
     * 秒传成功返回新文件的 id，离线返回 null。要补片名的添进 [titleFills]，各行合成一批再交给 InstantTitleFill。
     */
    internal suspend fun submitForBatch(target: PikoPathBreadcrumb, titleFills: MutableList<InstantTitleFill.Request>): Result<List<String>?> {
        val plan = savePlan
        val toSave = itemsToSave
        isSaving = true
        try {
            return when {
                resolution == null -> submitWhole(target).map { null }
                plan == null -> Result.failure(IllegalStateException("未勾选文件"))
                plan.route == SaveRoute.INSTANT && willCreateFolder -> saveIntoNewFolder(target, toSave, titleFills).map { it.createdIds }
                plan.route == SaveRoute.INSTANT -> saveInstantOrOffline(target, toSave, titleFills)
                else -> packSave(target, toSave).map { null }
            }
        } finally {
            isSaving = false
        }
    }

    private suspend fun submitWhole(target: PikoPathBreadcrumb): Result<Unit> =
        instantRepo.enqueueOfflineTask(submittedUrl(), target.id)
            .map { }
            .reportSaveFailure()

    private fun scheduleResolve(debounce: Boolean = true) {
        val magnet = normalizeMagnet(input)
        if (magnet == resolvedKey) return
        resolvedKey = magnet
        resolveJob?.cancel()
        resolution = null
        tree = null
        canonical = null
        titles = emptyMap()
        releaseTitleLookup()
        selectedIndices = emptySet()
        selectionPicked = false
        errorMessage = null
        isUnindexed = false
        contentMissing = false
        if (magnet == null) return
        resolveJob = scope.launch {
            // 防抖。粘贴一次就是一条完整的链，等待只为压掉手敲时中途的半条链接，所以取短值。
            if (debounce) delay(AUTO_RESOLVE_DEBOUNCE_MS)
            isResolving = true
            try {
                shared.resolvePermits.withPermit { instantRepo.resolve(magnet) }
                    .onSuccess { data -> applyResolution(data) }
                    .onFailure { err ->
                        PikoLog.w(TAG, "解析链接失败", err)
                        errorMessage = "解析失败：${err.message}"
                    }
            } finally {
                // 换链取消上一次解析时也要走到这里，否则指示器会一直转
                isResolving = false
                isAnalyzing = false
            }
        }
    }

    private suspend fun applyResolution(data: MagnetResolutionResult?) {
        if (data == null) {
            isUnindexed = true
            errorMessage = "云端未收录，可离线下载"
            return
        }
        isAnalyzing = true
        val inputs = data.items.map { MediaFileInput(it.file.path, it.file.size) }
        // 在这里现读而不是在 init 里订阅：打开面板时带着链接会立刻开始解析，订阅未必已经收到值
        val parse = preferences.nameParsingFlow.first()
        val built = withContext(Dispatchers.Default) {
            if (parse) buildInstantTree(inputs, data.resource.name) else buildRawInstantTree(inputs, data.resource.name)
        }
        // 解析关着时连番号也不认，规范命名的开关一并不给
        val names = if (parse) withContext(Dispatchers.Default) { canonicalNamesOf(data, titles = emptyMap()) } else null
        // 树与解析结果一起就位，面板不会先闪一个没有分组的列表
        tree = built
        canonical = names
        folderNameEdited = false
        resolution = data
        expandedGroups.clear()
        showsOnlyUnindexed = false
        folderName = built.folderName
        selectedIndices = built.defaultSelection
        selectionPicked = false
        // 批量时余量由列表按合计查，逐行查只是多发请求
        if (isRoot) scope.launch { refreshRemainingBytes() }
    }

    /**
     * 开着规范命名、配了 MetaTube 时查片名，查到后重算规范名。每个解析结果只查一次；查不到或出错的沿用原名里的片名。
     * 保存不等它：查询要等外部站点，几秒到十几秒，查到时已保存的由 InstantTitleFill 补改，见 [titleFillRequest]。
     */
    private suspend fun fetchTitles() {
        val fill = titleFill ?: return
        snapshotFlow { Triple(useCanonicalNames, canonical, resolution) }.collectLatest { (enabled, names, data) ->
            if (!enabled || names == null || data == null || titles.isNotEmpty()) return@collectLatest
            val lookup = titleLookup ?: fill.lookUp(names.videos.filterNotNull())?.also { titleLookup = it } ?: return@collectLatest
            val found = lookup.titlesOrEmpty()
            if (found.isEmpty() || resolution !== data) return@collectLatest
            val renamed = withContext(Dispatchers.Default) { canonicalNamesOf(data, found) }
            if (resolution !== data) return@collectLatest
            titles = found
            canonical = renamed
        }
    }

    /** 查一次网盘余量与今天剩下的离线次数。limit 为 0 的账号当作不限；查询失败保留上一次的数。 */
    private suspend fun refreshRemainingBytes(): Long? {
        driveRepo.getQuota().onSuccess { response ->
            remainingBytes = response.quota.takeIf { it.limitBytes > 0 }?.remainingBytes
            shared.updateAccount(driveRepo.isFreeAccount(), response)
        }.logFailure(TAG, "查询网盘余量失败，沿用上次的 $remainingBytes 字节")
        return remainingBytes
    }

    // 只粘了 infohash 的输入要补成磁力链再交给离线，createUrlFile 不认裸的 hash。
    // 夹在一段话里的单条链接只交链接本身
    private fun submittedUrl(): String = normalizedMagnet ?: extractLinks(input).singleOrNull()?.uri ?: input.trim()

    /**
     * 保存目标跟随网盘页的当前目录，直到用户在面板里另选。面板可以收起着留在后台，用户收起后
     * 进到想存的目录再展开，看到的就是眼前这个目录；从应用外打开的磁力链同样存进网盘页停着的位置。
     *
     * 原先默认沿用上一次选过的目标（记在偏好里），失效再退回 My Packs。改成当前目录后不再读写那项偏好：
     * 两者同时生效时，用户看着一个目录，东西却进了另一个，而当前目录恰是他此刻最可能想要的。
     */
    private suspend fun followDriveFolder() {
        driveRepo.folderStackFlow
            .map(::saveFolderIn)
            .distinctUntilChanged()
            .collectLatest { folder ->
                if (shared.targetChosen) return@collectLatest
                val resolved = targetFor(folder)
                if (!shared.targetChosen) target = resolved
            }
    }

    private suspend fun resolveTarget(): PikoPathBreadcrumb = targetFor(saveFolderIn(driveRepo.folderStackFlow.value))

    /**
     * 栈里最后一个真实目录。库、查重与压缩包的 ID 是虚拟的（piko:、piko-archive:），交给 [targetFor] 去验
     * 会被当成「已不存在」，改存 My Packs 并提示，而那个位置本来就不是目录；压缩包里取它所在的文件夹，
     * 库与查重的栈底就是虚拟 ID，取根目录。
     */
    private fun saveFolderIn(stack: List<PikoPathBreadcrumb>): PikoPathBreadcrumb =
        stack.lastOrNull { isDriveFolderId(it.id) } ?: PikoDriveRepository.ROOT_BREADCRUMB

    /**
     * 目录栈是持久化的，停着的目录可能已在别的客户端被删或进了回收站。不验的话要等保存时才暴露，
     * 报的还是一句原始 API 错误。根目录是空 id，没有对应的 FileDetail，不验。
     */
    private suspend fun targetFor(folder: PikoPathBreadcrumb): PikoPathBreadcrumb {
        if (folder.id.isEmpty() || !driveRepo.isFolderGone(folder.id)) {
            targetNotice = null
            return folder
        }
        PikoLog.i(TAG, "保存目标 ${folder.id} 已不存在，改存 My Packs")
        targetNotice = "当前目录已不存在，改存 My Packs"
        return driveRepo.getOrCreateMyPacksFolder().logFailure(TAG, "取 My Packs 失败，改存根目录")
            .getOrDefault(PikoPathBreadcrumb("", "My Packs"))
    }

    private suspend fun instantSave(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        naming: SaveNaming,
        keepStructure: Boolean,
    ): Result<List<String>> = rawInstantSave(target, toSave, naming, keepStructure).reportSaveFailure()

    private suspend fun rawInstantSave(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        naming: SaveNaming,
        keepStructure: Boolean,
    ): Result<List<String>> =
        instantRepo.instantSave(toSave, target.id, reuse = previewedIds.toMap(), keepStructure = keepStructure, names = naming.fileNames)
            .onSuccess {
                // 移出 Piko-Temp 的不能再当作预览副本：下次预览会指向保存目录里的这份
                toSave.forEach { item -> item.file.gcid?.let(previewedIds::remove) }
                // 服务端不给秒传的文件填来源，由 Piko 记下。只改内存，存盘与上传网盘随后在后台做，失败不连累这次保存
                resolvedKey?.let { magnet -> driveRepo.sourceLedger.record(magnet, toSave.map { it.file }) }
            }

    /**
     * 秒传；单文件资源的内容云端其实还没有时改交离线任务。秒传成功返回新文件的 id，改走离线返回 null。
     *
     * 解析结果带着 gcid 不代表云端存着内容：它可能还在别人上传的途中（PENDING），秒传只建得出一个
     * 等上传的占位，SDK 已把它删掉并抛出 InstantContentUnavailableException。只对单文件资源回退：
     * 离线任务只收整条磁力，多文件资源里一部分秒传、一部分离线必然存出重复的文件。
     */
    private suspend fun saveInstantOrOffline(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        titleFills: MutableList<InstantTitleFill.Request>,
    ): Result<List<String>?> {
        val naming = SaveNaming(toSave)
        val instant = rawInstantSave(target, toSave, naming, keepStructure = false)
        val missing = instant.exceptionOrNull() as? InstantContentUnavailableException
        if (items.size == 1 && missing != null) {
            if (shared.account.value.free) {
                PikoLog.i(TAG, "云端没有这个文件的内容，等用户确认离线")
                contentMissing = true
                errorMessage = "云端暂无该文件内容，需离线下载"
                return Result.failure(missing)
            }
            PikoLog.i(TAG, "云端没有这个文件的内容，改交离线任务")
            return submitWhole(target).map { null }
        }
        return instant.onSuccess { ids ->
            recordSingleEntry(target, toSave, ids)
            titleFillRequest(naming, toSave, ids, folder = null)?.let(titleFills::add)
        }.reportSaveFailure()
    }

    /**
     * 这次保存要等片名补改的项：开着规范命名、配了 MetaTube 时，存下的各个文件，与按番号命名的新建文件夹 [folder]。
     * 片名在保存前已查到的也照样交出去：结果相同时补名一项不改。保存时查询还没发起（开关刚打开、解析刚完）就现在发起。
     * [ids] 与 [toSave] 里带 gcid 的文件按顺序一一对应。
     */
    private suspend fun titleFillRequest(
        naming: SaveNaming,
        toSave: List<InstantFileItem>,
        ids: List<String>,
        folder: PikoPathBreadcrumb?,
    ): InstantTitleFill.Request? {
        val fill = titleFill ?: return null
        val names = naming.names ?: return null
        val data = naming.data ?: return null
        val account = fill.currentAccount() ?: return null
        val files = toSave.filter { it.file.gcid != null }.zip(ids).mapNotNull { (item, id) ->
            naming.indexOf(item)?.let { index -> Triple(item, id, index) }
        }
        val renamedFolder = folder?.takeIf { naming.folderNamedByCode }
        val savedVideos = files.mapNotNull { (_, _, index) -> names.videos.getOrNull(index) }
        val lookup = titleLookup ?: fill.lookUp(savedVideos)?.also { titleLookup = it } ?: return null
        titleLookupAdopted = true
        val saved = files.map { (item, id, _) -> InstantTitleFill.Saved(id, naming.fileNames[item.file.path] ?: item.file.name) } +
            listOfNotNull(renamedFolder?.let { InstantTitleFill.Saved(it.id, it.name) })
        // 只捕获保存那一刻的值，不捕获面板：补名时会话多半已结束
        val folderCode = naming.folderCode
        val folderName = naming.folderName
        val folderVideos = naming.folderVideos
        return InstantTitleFill.Request(account, saved, lookup) { titles ->
            val renamed = canonicalNamesOf(data, titles) ?: names
            files.map { (item, _, index) -> fileNameIn(renamed, index, folderCode, item.file.name) } +
                listOfNotNull(renamedFolder?.let { driveFolderName(resourceFolderName(folderName, folderVideos, titles)) })
        }
    }

    /** 不再需要的片名查询就取消；已交给 InstantTitleFill 补名的留着，它还在等。 */
    private fun releaseTitleLookup() {
        if (!titleLookupAdopted) titleLookup?.cancel()
        titleLookup = null
        titleLookupAdopted = false
    }

    /**
     * 秒传路线只存一项：一个视频连同它的字幕。记录以其中最大的那个命名，定位也指向它；
     * [ids] 与 [toSave] 里带 gcid 的文件按顺序一一对应，见 InstantMagnetRepository.instantSave。
     */
    private fun recordSingleEntry(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>, ids: List<String>) {
        val saved = toSave.filter { it.file.gcid != null }.zip(ids)
        val (main, mainId) = saved.maxByOrNull { (item, _) -> item.file.size } ?: return
        saveRecords.add(main.file.name, saved.size, saved.sumOf { (item, _) -> item.file.size }, target.name, locateId = mainId)
    }

    private fun <T> Result<T>.reportSaveFailure(): Result<T> = reportFailure(TAG, "保存") { errorMessage = it }

    private suspend fun packSave(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>): Result<Unit> {
        val allItems = items
        val packBytes = allItems.sumOf { it.file.size }
        return packTracker.submit(
            url = submittedUrl(),
            targetId = target.id,
            folderName = driveFolderName(folderNameToSave),
            keep = toSave.map { it.file.path }.toSet(),
            totalFiles = allItems.size,
            totalBytes = packBytes,
            keptBytes = toSave.sumOf { it.file.size },
        )
            .map { }
            .reportSaveFailure()
    }

    companion object {
        private const val TAG = "Instant"
        private const val AUTO_RESOLVE_DEBOUNCE_MS = 350L

        /**
         * 各文件的规范名。没有一个文件会改名时为 null。
         * 按种子里的目录分组，与网盘里同目录的规则一致：撞名、字幕跟随只在同一层里看。
         */
        private fun canonicalNamesOf(data: MagnetResolutionResult, titles: Map<String, String>): CanonicalNames? {
            val files = data.items.map { it.file }
            val inputs = files.map { AvNamingItem(it.name, group = it.path.substringBeforeLast('/', "")) }
            val titled = canonicalAvNames(inputs, titles)
            if (titled.indices.none { titled[it] != files[it].name }) return null
            val bare = canonicalAvNames(inputs, titles) { _, _ -> false }
            val codes = titled.map { parseMediaName(it).av?.code }
            val videos = files.map { file -> parseMediaName(file.name).takeIf { it.fileKind == FileKind.VIDEO }?.av }
            return CanonicalNames(titled, bare, codes, videos)
        }

        /** 第 [index] 个文件的规范名：归入以番号 [folderCode] 命名的新建文件夹时只写番号，否则带片名。 */
        private fun fileNameIn(names: CanonicalNames, index: Int, folderCode: String?, original: String): String {
            val inNamedFolder = names.codes.getOrNull(index)?.let { it == folderCode } == true
            return (if (inNamedFolder) names.bare else names.titled).getOrNull(index) ?: original
        }

        private fun resourceFolderName(folderName: String, videos: List<AvInfo>, titles: Map<String, String>): String =
            canonicalResourceName(folderName, videos, titles) ?: folderName

        /**
         * 输入框里的内容归一化成可解析的磁力链。文本里恰好只有一条链接且是磁力时返回它，
         * 否则返回 null，不解析也不报错；两条以上由批量列表处理。
         *
         * 只粘 infohash 的情况不少，所以补全一条磁力链；但限定 40 位十六进制或 32 位 Base32，
         * 否则随手敲的任意长串都会发一次请求。
         */
        fun normalizeMagnet(raw: String): String? = extractLinks(raw).singleOrNull()?.takeIf { it.isMagnet }?.uri

        /**
         * 一段文本里的 PikPak 分享链接。分享常以「链接：https://mypikpak.com/s/… 提取码：abcd」的整段话转发，
         * 链接不在开头，SDK 的 shareIdFromUrl 只认以链接开头的串，所以先在这里把它找出来。
         */
        fun findShareLink(text: String): String? = SHARE_LINK.find(text)?.value

        /** 与分享链接一起转发的提取码：「提取码：abcd」「密码 abcd」，或链接上的 ?pwd=abcd。 */
        fun findSharePassCode(text: String): String? = SHARE_PASS_CODE.find(text)?.groupValues?.get(1)

        private val SHARE_LINK = Regex("""https?://(?:www\.)?mypikpak\.com/s/[A-Za-z0-9_-]+""")
        private val SHARE_PASS_CODE = Regex("""(?:提取码|密码|访问码|pwd|passcode)\s*[:：=]?\s*([A-Za-z0-9]{4,10})""", RegexOption.IGNORE_CASE)
    }
}
