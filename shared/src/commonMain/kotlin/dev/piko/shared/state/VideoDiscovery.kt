package dev.piko.shared.state

import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** 一次列目录的结果。失败仍交给调用方显示，不能把没有列到当成已经列完。 */
class VideoFolderBatch(
    val folderId: String,
    val files: List<FileStat> = emptyList(),
    val candidates: List<FileStat> = emptyList(),
    val workOf: Map<String, String> = emptyMap(),
    val failure: Throwable? = null,
)

/** 画廊保留短视频与未知时长的视频；信息流另行决定能否截出片段。 */
internal fun FileStat.isDiscoverableVideo(): Boolean = isPlayableVideo() && !trashed && !isUploading

internal fun FileStat.hasGalleryCover(): Boolean = thumbnailLink.isNotBlank() || hash.isNotBlank()

/** 显式选中的文件也能只借用作品归属，不必套用信息流的样片过滤。 */
suspend fun analyzeVideoFolder(folderId: String, files: List<FileStat>): VideoFolderBatch = withContext(Dispatchers.Default) {
    currentCoroutineContext().ensureActive()
    val structure = runCatching { DriveAnalysisCache.analyze(files) }
        .onFailure { if (it is CancellationException) throw it }
        .logFailure("VideoDiscovery", "解析视频目录失败，不折叠")
        .getOrNull()
    currentCoroutineContext().ensureActive()
    val workOf = buildMap {
        structure?.blocks?.forEach { block ->
            val key = block.workKey?.let { if (block.workTitle == null) "$folderId/$it" else it } ?: return@forEach
            block.fileIds.forEach { put(it, key) }
        }
    }
    VideoFolderBatch(
        folderId = folderId,
        files = files,
        candidates = files.filter { it.id !in structure?.foldedIds.orEmpty() && it.isDiscoverableVideo() },
        workOf = workOf,
    )
}

/**
 * 按层遍历，只有 next 才列下一个目录，不自建后台扫描。调用方在自己的 owner scope 内串行调用。
 * 每一层打乱；同一目录只入队一次，错误保留到显式重试。仓库边界固定打开时的 client，
 * 仍使用 listBrowsable 的清单投影、内部目录过滤和共享缓存。
 */
class VideoDiscovery internal constructor(
    rootId: String,
    private var readFolder: suspend (String) -> Result<List<FileStat>>,
    private val random: Random = Random,
) {
    constructor(driveRepo: PikoDriveRepository, account: String, rootId: String, random: Random = Random) : this(
        rootId,
        driveRepo.browsableReader(account) ?: { throw CancellationException("Account changed") },
        random,
    )

    private val level = ArrayDeque<String>().apply { add(rootId) }
    private val nextLevel = mutableListOf<String>()
    private val enqueued = hashSetOf(rootId)
    private val failures = linkedMapOf<String, Throwable>()

    val hasPending: Boolean get() = level.isNotEmpty() || nextLevel.isNotEmpty()
    internal val folderIds: Set<String> get() = enqueued.toSet()
    val failedFolders: Map<String, Throwable> get() = failures.toMap()
    val isComplete: Boolean get() = !hasPending && failures.isEmpty()

    suspend fun next(): VideoFolderBatch? {
        currentCoroutineContext().ensureActive()
        if (level.isEmpty()) {
            level.addAll(nextLevel.shuffled(random))
            nextLevel.clear()
        }
        val id = level.firstOrNull() ?: return null
        val batch = readBatch(id)
        level.removeFirst()
        return batch
    }

    /** 重列不改变已探索位置；新出现的子目录接在仍未探索的下一层。 */
    suspend fun refreshFolder(folderId: String): VideoFolderBatch = readBatch(folderId)

    /** 调用方先结束旧请求；同账号重新登录可换 reader，仍保留探索位置。 */
    internal fun rebind(driveRepo: PikoDriveRepository, account: String) {
        readFolder = driveRepo.browsableReader(account) ?: throw CancellationException("Account changed")
    }

    private suspend fun readBatch(id: String): VideoFolderBatch {
        currentCoroutineContext().ensureActive()
        val result = readFolder(id)
        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            failures[id] = error
            return VideoFolderBatch(id, failure = error)
        }
        val batch = analyzeVideoFolder(id, result.getOrThrow())
        currentCoroutineContext().ensureActive()
        failures.remove(id)
        batch.files.filter { it.isFolder && !it.trashed }.forEach { folder ->
            if (enqueued.add(folder.id)) nextLevel += folder.id
        }
        return batch
    }

    fun forgetFolders(ids: Set<String>) {
        level.removeAll(ids)
        nextLevel.removeAll(ids)
        enqueued.removeAll(ids)
        failures.keys.removeAll(ids)
    }

    fun retryFailed() {
        val waiting = level.toSet() + nextLevel
        failures.keys.filter { it !in waiting }.forEach(level::addLast)
    }
}
