package dev.piko.shared.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.ChildFile
import dev.piko.shared.i18n.PikoLocale
import io.github.nihildigit.pikpak.FileStat
import dev.piko.shared.memory.cacheBudget

/**
 * 进程内复用纯计算结果，浏览上下文自己的展开与揭示状态不进入缓存。
 * 缓存访问可以来自不同上下文的协程；计算在锁之外进行，同一内容的并发请求只算一次。
 */
internal object DriveAnalysisCache {
    private val structureLock = Mutex()
    private val folderLock = structureLock
    private val generationState = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val generation: Long get() = generationState.value
    private var structureBudget = 4L * 1024 * 1024
    private var folderBudget = 2L * 1024 * 1024
    private val structures = LinkedHashMap<String, DriveStructure>()
    private val structureRequests = mutableMapOf<String, CompletableDeferred<DriveStructure>>()
    private val folderViews = LinkedHashMap<String, DriveFolderView>()

    init {
        dev.piko.shared.memory.PikoMemoryGovernor.Shared.register(object : dev.piko.shared.memory.MemoryParticipant {
            override val id = "drive-analysis"
            override suspend fun applyPolicy(policy: dev.piko.shared.memory.MemoryPolicy) {
                structureLock.withLock {
                    structureBudget = policy.cacheBudget(4L * 1024 * 1024)
                    folderBudget = policy.cacheBudget(2L * 1024 * 1024)
                    if (policy != dev.piko.shared.memory.MemoryPolicy.Normal) {
                        generationState.value++
                        structures.clear()
                        folderViews.clear()
                        structureRequests.clear()
                    }
                }
            }
        })
    }

    suspend fun clear() = structureLock.withLock {
        generationState.value++
        structures.clear()
        folderViews.clear()
        structureRequests.clear()
    }

    /**
     * 按内容而不是目录 id 缓存：同一目录刷新后内容没变就直接命中，改名或增删则自然失效。
     * 顺序也进指纹，未识别的文件按用户选的排序排列。
     */
    fun fingerprint(files: List<FileStat>, language: AppLanguage = PikoLocale.effectiveLanguage): String {
        var hash = 17L
        files.forEach { file ->
            hash = hash * 31 + file.id.hashCode()
            hash = hash * 31 + file.name.hashCode()
            hash = hash * 31 + file.size.hashCode()
            hash = hash * 31 + file.isFolder.hashCode()
            hash = hash * 31 + file.mimeType.hashCode()
        }
        return "${language.name}:${files.size}:$hash"
    }

    suspend fun analyze(
        files: List<FileStat>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        compute: suspend (List<FileStat>) -> DriveStructure = ::analyzeDriveFolder,
    ): DriveStructure = withContext(dispatcher) { analyzeShared(files, compute) }

    private suspend fun analyzeShared(
        files: List<FileStat>,
        compute: suspend (List<FileStat>) -> DriveStructure,
    ): DriveStructure {
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = PikoLocale.revision
            val language = PikoLocale.effectiveLanguage
            val key = fingerprint(files, language)
            val pending = CompletableDeferred<DriveStructure>()
            var startedGeneration = 0L
            val request = structureLock.withLock {
                startedGeneration = generation
                structures.remove(key)?.let {
                    structures[key] = it
                    return it
                }
                structureRequests.getOrPut(key) { pending }
            }
            if (request !== pending) {
                try {
                    return request.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val value = compute(files)
                currentCoroutineContext().ensureActive()
                val sameLanguage = PikoLocale.revision == revision && PikoLocale.effectiveLanguage == language
                structureLock.withLock {
                    if (sameLanguage && startedGeneration == generation) putBounded(structures, key, value, MAX_STRUCTURES, structureBudget, ::structureWeight)
                    if (structureRequests[key] === pending) structureRequests.remove(key)
                }
                if (!sameLanguage) {
                    pending.cancel()
                    continue
                }
                pending.complete(value)
                return value
            } catch (error: Throwable) {
                // 先移除失败请求，再唤醒等待者，让仍有效的调用者接手计算。
                withContext(NonCancellable) {
                    structureLock.withLock { if (structureRequests[key] === pending) structureRequests.remove(key) }
                }
                pending.completeExceptionally(error)
                throw error
            }
        }
    }

    suspend fun structure(key: String): DriveStructure? = structureLock.withLock {
        structures.remove(key)?.also { structures[key] = it }
    }

    suspend fun putStructure(key: String, value: DriveStructure, expectedGeneration: Long = generation) = structureLock.withLock {
        if (expectedGeneration == generation) putBounded(structures, key, value, MAX_STRUCTURES, structureBudget, ::structureWeight)
    }

    /** 名称或类型变化就重新描述，项数相同也不能复用旧结果。 */
    fun folderKey(folder: FileStat, content: List<ChildFile>?, language: AppLanguage = PikoLocale.effectiveLanguage): String =
        "${language.name}|${folder.id}|${folder.name}|${content?.hashCode() ?: -1}"

    suspend fun folderView(key: String): DriveFolderView? = folderLock.withLock {
        folderViews.remove(key)?.also { folderViews[key] = it }
    }

    suspend fun putFolderView(key: String, value: DriveFolderView, expectedGeneration: Long = generation) = folderLock.withLock {
        if (expectedGeneration == generation) putBounded(folderViews, key, value, MAX_FOLDER_VIEWS, folderBudget, ::folderWeight)
    }

    // commonMain 的 LinkedHashMap 没有按访问排序的构造参数，命中时先删再插，最早插入的即最久未用
    private fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V, limit: Int, budget: Long, weigh: (V) -> Long) {
        map.remove(key)
        if (weigh(value) + key.length * 2L > budget) return
        map[key] = value
        var weight = map.entries.sumOf { it.key.length * 2L + weigh(it.value) }
        while (map.size > limit || weight > budget) {
            val oldest = map.entries.first()
            weight -= oldest.key.length * 2L + weigh(oldest.value)
            map.remove(oldest.key)
        }
    }

    private fun stringsWeight(values: Iterable<String>): Long = values.sumOf { 40L + it.length * 2L }
    private fun fieldsWeight(fields: List<DriveParsedField>): Long = fields.sumOf { 48L + (it.label.length + it.value.length) * 2L }
    private fun folderWeight(view: DriveFolderView): Long = 128L +
        stringsWeight(listOfNotNull(view.title, view.code, view.resolution) + view.tags) + fieldsWeight(view.fields)
    private fun structureWeight(value: DriveStructure): Long = 128L +
        stringsWeight(value.secondaryIds) + stringsWeight(value.foldedIds) +
        stringsWeight(value.attachedTo.keys) + stringsWeight(value.attachedTo.values) +
        value.blocks.sumOf { 128L + stringsWeight(listOfNotNull(it.id, it.label, it.menuLabel, it.workKey, it.workTitle) + it.workTags + it.fileIds) } +
        value.views.entries.sumOf { (id, view) -> 128L + stringsWeight(listOfNotNull(id, view.title, view.code, view.resolution, view.heading) + view.tags) + fieldsWeight(view.fields) }

    private const val MAX_STRUCTURES = 24
    private const val MAX_FOLDER_VIEWS = 4_000
}
