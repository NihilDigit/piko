package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

/** 随机片段里的一段：某个视频从 [startMs] 起的 [CLIP_LENGTH_MS]。 */
@Serializable
data class Clip(
    val fileId: String,
    val name: String,
    val parentId: String,
    val startMs: Long,
    /** 整个视频的时长，界面据此标出片段在全片中的位置。 */
    val videoDurationMs: Long,
) {
    val file: FileStat get() = FileStat(id = fileId, name = name, parentId = parentId)
}

const val CLIP_LENGTH_MS = 30_000L

/**
 * 信息流：刷网盘页当前文件夹里的视频，子文件夹里的也算，每段从片中随机一处起放 30 秒。
 * 看的是内容本身，而不是片头片尾；随机整个视频时抽到的多半是没头没尾的东西。
 * 范围只由打开时所在的文件夹决定，界面在离开这个文件夹（及其子文件夹）时收起信息流，见 PikoMainScaffold。
 *
 * 与进程同寿，挂在 PikoServices 上：看到喜欢的一段会打开完整播放器，Android 上它压在信息流之上，
 * 底下的页可能被销毁，队列不能随页面走。队列按文件夹另外存盘，只存当前前后各 [KEPT_AROUND] 段：
 * 存下的段开头多半已在磁盘上（ClipCache），回到同一个文件夹时不必现取，见 [open]。
 * 内存里的历史不截：翻页按下标定位，截掉前面的，正在看的那一页会跳走，而一次会话的历史本来也不大。
 *
 * 候选边遍历边加：服务端按类型过滤只在全盘（parent_id=*）时有用，按文件夹圈定范围就只能逐层列目录。
 * 每个目录用网盘页同一套启发式折叠挑掉样片、广告、预告，找到头几个就能开播，不等遍历完。
 * 下一层的目录顺序打乱，免得先把第一个子目录挖到底，前面几十段全出自同一个合集。
 */
class ClipFeedSession(
    private val driveRepo: PikoDriveRepository,
    private val media: PikoMediaRepository,
    private val cacheStore: PikoCacheStore?,
    private val scope: CoroutineScope,
) {
    var root by mutableStateOf<PikoPathBreadcrumb?>(null)
        private set

    /**
     * 翻页器里的段：看过的、正在看的，和已经取好、翻过去就能放的。
     *
     * 还没取好的不在这里，在 [upcoming]。翻页器看不到它们，也就翻不到：没取到流的一段翻过去什么也看不到。
     * 候补取好一段就接到这里的末尾，谁先取好谁先上，落在慢主机上的自然排到后面，取不好的永远不出现。
     */
    var clips by mutableStateOf<List<Clip>>(emptyList())
        private set

    /** 挑好了、还在取流的段，由界面逐个取好后经 [promote] 接进 [clips]，取不好的经 [drop] 扔掉。 */
    var upcoming by mutableStateOf<List<Clip>>(emptyList())
        private set

    var currentIndex by mutableIntStateOf(0)
        private set

    /**
     * 到过的最远一段。够不够用从这里往后数，不从 [currentIndex]：往回翻时当前段往前挪，
     * 从它数，后面那一串看过的都算进去，预取与补候补就停了，翻回来越过原处时前面已经空了。
     */
    var furthestIndex by mutableIntStateOf(0)
        private set

    /** 还在遍历目录找视频。 */
    var isCollecting by mutableStateOf(false)
        private set

    /** 静音。放在会话上而不是页面上：打开完整播放器或换到独立窗口时页面会重建，静音要跟着走。 */
    var muted by mutableStateOf(false)

    /** 各段的代理会话与预取，同样不随页面走，见 [ClipStreams]。换文件夹或关掉信息流时清空。 */
    val streams = ClipStreams(media, onReady = ::promote, onDead = ::drop)

    // 段里只带着 ID、名字与所在目录，列目录时拿到的完整条目另外记下：缩略图、大小、gcid 与星标都在里面。
    // 存盘恢复的候选没有这些，要等后台这一轮遍历重新列到
    private val listedFiles = mutableStateMapOf<String, FileStat>()

    // 遍历到的目录名，界面据此标出一段出自哪个目录。随队列存盘，重开时不必等遍历
    private val folderNames = mutableStateMapOf<String, String>()

    /** 列目录时拿到的 [fileId] 的完整条目；存盘恢复、还没重新列到的为 null。 */
    fun listedFile(fileId: String): FileStat? = listedFiles[fileId]

    /** [folderId] 的名字；还没遍历到的为 null。 */
    fun folderName(folderId: String): String? = folderNames[folderId]

    // 还在查挑中的视频有没有转码
    private var isFilling by mutableStateOf(false)

    /** 遍历完了也没有一个能放的视频。 */
    val isEmpty: Boolean get() = !isCollecting && !isFilling && clips.isEmpty() && upcoming.isEmpty()

    private val pool = mutableListOf<FileStat>()
    private val poolIds = HashSet<String>()

    // 取不到流的，不再挑，值是扔掉的时刻；过了 [REJECT_TTL_MS] 再给一次机会
    private val rejected = HashMap<String, Long>()

    // 查过有转码的，下一轮不再查
    private val verified = HashSet<String>()

    // 查过没有转码的，值是查的时刻，过了 [REJECT_TTL_MS] 重查：转码可能是后来有人播过才生成的。
    // 它们照样能放，只是起播要读原画，排在有转码的之后，见 [ranked]
    private val untranscoded = HashMap<String, Long>()
    private var collectJob: Job? = null
    private var fillJob: Job? = null
    private var saveJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 刷 [folder]。正是眼前这一个时原样继续；换了文件夹就读那个文件夹存下的。
     *
     * 每个文件夹各存一份，最近用过的 [KEPT_FOLDERS] 个：队列从上次看到的地方接着，
     * 遍历到的视频、查过有没有转码的也一并存着，打开就能挑，不必等把目录重新走一遍、把详情重新查一遍。
     * 遍历照样在后台再走一遍，找新加进来的视频。
     */
    suspend fun open(folder: PikoPathBreadcrumb) {
        if (root?.id == folder.id && (clips.isNotEmpty() || upcoming.isNotEmpty() || isCollecting)) return
        collectJob?.cancel()
        fillJob?.cancel()
        saveJob?.cancel()
        // 旧文件夹里在取的段取好了也接不进新队列，留着只占连接
        streams.closeAll()
        pool.clear()
        poolIds.clear()
        rejected.clear()
        verified.clear()
        untranscoded.clear()
        listedFiles.clear()
        folderNames.clear()
        root = folder
        folderNames[folder.id] = folder.name
        val saved = load(folder.id)
        saved?.folders?.forEach { (id, name) -> folderNames.getOrPut(id) { name } }
        PikoLog.d(TAG, "打开随机片段：存下的队列 ${saved?.clips?.size ?: 0} 段，候选 ${saved?.pool?.size ?: 0} 个")
        val now = Clock.System.now().toEpochMilliseconds()
        saved?.rejected?.forEach { (id, at) -> if (now - at < REJECT_TTL_MS) rejected[id] = at }
        saved?.untranscoded?.forEach { (id, at) -> if (now - at < REJECT_TTL_MS) untranscoded[id] = at }
        saved?.verified?.let(verified::addAll)
        saved?.pool?.forEach { addToPool(it.toFileStat()) }
        val current = saved?.current?.coerceIn(0, (saved.clips.size - 1).coerceAtLeast(0)) ?: 0
        // 关掉信息流就是清空了队列，看过的不再摆回翻页器；还没看的那些接着用，它们的开头多半已在磁盘上，
        // 打开即有现成的段。存盘时取好的，这回的会话早关了，得重新取，所以都回到候补。
        // 上次正看的那段也回去：一关一开，它已经不算看过
        clips = emptyList()
        // 有转码的排前面：原画是手上的段见底时才收下的，重开时一上来就轮到它们，开头几段就慢
        upcoming = saved?.clips.orEmpty().drop(current).sortedBy { it.fileId in untranscoded }
        currentIndex = 0
        furthestIndex = 0
        // 存下的候选够挑就先挑，不等遍历
        fillAhead()
        collectJob = scope.launch { collect(folder.id) }
    }

    /**
     * 关掉信息流：清空队列，下次 [open] 同一个文件夹也从头来，不回到看过的段。
     * 跳去网盘看一个文件时信息流只是挂起，不调这个，队列与看到哪一段都留着。
     */
    fun close() {
        collectJob?.cancel()
        fillJob?.cancel()
        // 还没看的段与查过的转码留给下次打开，趁队列还在当场存下
        save(immediately = true)
        streams.closeAll()
        root = null
        clips = emptyList()
        upcoming = emptyList()
        currentIndex = 0
        furthestIndex = 0
        isCollecting = false
    }

    private fun addToPool(file: FileStat) {
        if (poolIds.add(file.id)) pool += file
    }

    /**
     * 候补里的 [clip] 取好了，接到翻页器的末尾。
     *
     * 不扣下几段留作候补位：原先取好的至多三段先不放进翻页器，翻到末尾的等待页才拿一段接上。
     * 扣着并不多出段来，只是每刷到翻页器末尾，滑进来的总是那张转圈的等待页，停稳才变成视频（2026-09-28）。
     */
    fun promote(clip: Clip) {
        if (clip !in upcoming) return
        upcoming = upcoming - clip
        clips = clips + clip
        save()
    }

    /**
     * 候补里的 [clip] 取不到流。头一次挪到候补末尾，轮到时重新备会话、拿一条新直链再取；
     * 第二次才扔掉，这个视频也不再挑。
     *
     * 一次就扔的话，CDN 一阵抖动就把好好的视频拉黑三天：实测一个原画的直链在五台主机上接连秒断，
     * 同一时刻别的视频照常（2026-09-28）。换过几台主机、隔一阵重取仍读不出来的，多半是内容本身坏了：
     * 有的短片的 720P 在每台主机上都断流。
     */
    fun drop(clip: Clip) {
        if (clip !in upcoming) return
        upcoming = upcoming - clip
        if (failedOnce.add(clip.fileId)) {
            PikoLog.d(TAG, "取不到，稍后重取 ${logFile(clip.fileId, clip.name)}")
            upcoming = upcoming + clip
        } else {
            rejected[clip.fileId] = Clock.System.now().toEpochMilliseconds()
            fillAhead()
        }
        save()
    }

    // 取流失败过一次的视频，见 drop。只在这次会话里记着：隔一天再打开时 CDN 早换了一批
    private val failedOnce = HashSet<String>()

    fun moveTo(index: Int) {
        if (index !in clips.indices || index == currentIndex) return
        currentIndex = index
        furthestIndex = maxOf(furthestIndex, index)
        fillAhead()
        save()
    }

    private suspend fun collect(rootId: String) {
        isCollecting = true
        val started = TimeSource.Monotonic.markNow()
        var listed = 0
        try {
            var level = listOf(rootId)
            while (level.isNotEmpty()) {
                val next = mutableListOf<String>()
                for (folderId in level) {
                    val files = driveRepo.listAllFiles(folderId).logFailure(TAG, "随机片段列目录失败，跳过").getOrNull() ?: continue
                    if (listed++ == 0) PikoLog.d(TAG, "列出第一个目录：${files.size} 项，${started.elapsedNow().inWholeMilliseconds} ms")
                    val folders = files.filter { it.isFolder }
                    next += folders.map { it.id }
                    folders.forEach { folderNames[it.id] = it.name }
                    // 解析整目录的文件名要花些时间，不放在主线程上。解析出错只少折叠这一个目录，不能让整个信息流崩掉
                    val folded = withContext(Dispatchers.Default) {
                        runCatching { analyzeDriveFolder(files).foldedIds }
                            .onFailure { if (it is CancellationException) throw it }
                            .logFailure(TAG, "随机片段解析目录失败，不折叠")
                            .getOrDefault(emptySet())
                    }
                    val candidates = files.filter { it.id !in folded && it.isClipCandidate() }
                    candidates.forEach { listedFiles[it.id] = it }
                    candidates.forEach(::addToPool)
                    fillAhead()
                }
                level = next.shuffled()
            }
            isCollecting = false
            PikoLog.d(TAG, "遍历完：$listed 个目录，候选 ${pool.size} 个，${started.elapsedNow().inWholeMilliseconds} ms")
            // 补队列时视频用完了会等遍历，遍历结束得再叫它一次，才开得了下一轮
            fillAhead()
            save()
        } finally {
            isCollecting = false
        }
    }

    /**
     * 当前之后补足 [KEPT_AROUND] 段，已取好的与候补合计，已在补就不重开。
     *
     * 一轮之内每个视频只出一段：同一部片子隔几段又冒出来，看着像是重复。视频用完了开下一轮，
     * 队列不到头；遍历还在进行时，先等后面列出的目录补上。
     *
     * 有 720P 转码的先挑：转码流能截一小块直接播，起播只要几百 KB；原画要先读索引再跳到起点，
     * 一段起播要 5 到 10 MB，刷快了必然卡。没有转码的排在后面，有转码的挑完了、或手上的段快见底时才用，
     * 一个都没有转码的文件夹因此也有得刷，只是慢些。列目录不带转码信息，所以挑中之后逐个查详情，
     * 每次并行查 [VERIFY_BATCH] 个；只查挑中的，不在遍历时把整个文件夹都查一遍。
     */
    private fun fillAhead() {
        if (fillJob?.isActive == true) return
        fillJob = scope.launch {
            isFilling = true
            try {
                while (true) {
                    val readyAhead = (clips.size - 1 - furthestIndex) + upcoming.size
                    val missing = KEPT_AROUND - readyAhead
                    if (missing <= 0) break
                    val used = (clips + upcoming).mapTo(HashSet()) { it.fileId }
                    val fresh = pool.filter { it.id !in used && it.id !in rejected }
                    // 都放过一轮了再开一轮，起点重新随机，只避开最近放过的；视频不多时至多避开一半，
                    // 否则一个几十个视频的文件夹第二轮就挑不出来。遍历还在进行时先等新的
                    val candidates = ranked(
                        fresh.ifEmpty {
                            if (isCollecting) return@launch
                            val playable = pool.filter { it.id !in rejected }
                            val recent = (clips + upcoming).takeLast(minOf(2 * KEPT_AROUND, playable.size / 2)).mapTo(HashSet()) { it.fileId }
                            playable.filter { it.id !in recent }
                        },
                    )
                    // 眼前只剩原画可挑时，遍历还没走完、手上又还有段可放，就先等：后面列出的目录里可能有转码的。
                    // 一段都没有了才不等，免得停在转圈上
                    val onlyOriginalsLeft = candidates.firstOrNull()?.let { it.id in untranscoded } == true
                    if (onlyOriginalsLeft && isCollecting && readyAhead > 0) return@launch
                    val batch = candidates.take(minOf(missing, VERIFY_BATCH))
                    if (batch.isEmpty()) break
                    val checkStarted = TimeSource.Monotonic.markNow()
                    val checked = coroutineScope {
                        batch.map { file -> async { file to transcodeOf(file.id) } }.awaitAll()
                    }
                    PikoLog.d(TAG, "查转码 ${batch.size} 个，有 ${checked.count { it.second == Transcode.Yes }} 个：${checkStarted.elapsedNow().inWholeMilliseconds} ms")
                    val now = Clock.System.now().toEpochMilliseconds()
                    checked.forEach { (file, transcode) ->
                        when (transcode) {
                            Transcode.Yes -> verified += file.id
                            Transcode.JustFoundMissing -> untranscoded[file.id] = now
                            Transcode.KnownMissing -> Unit
                        }
                    }
                    // 刚查出没有转码的这一回不收，下一圈它排到有转码的后面；排在最前还挑中了它，说明已经没有更好的。
                    // 手上的段快见底时照收：大半没有转码的文件夹里，等把候选查到只剩原画要几十秒，
                    // 其间一段一段地挤出有转码的，翻不了几下就停在等待页（2026-09-28，170 个候选查出 1 个）
                    val lowOnClips = readyAhead < LOW_ON_CLIPS
                    val added = checked.filter { lowOnClips || it.second != Transcode.JustFoundMissing }.map { clipOf(it.first) }
                    if (added.isNotEmpty()) {
                        upcoming = upcoming + added
                        save()
                    }
                }
            } finally {
                isFilling = false
            }
        }
    }

    private enum class Transcode { Yes, JustFoundMissing, KnownMissing }

    private suspend fun transcodeOf(fileId: String): Transcode = when {
        fileId in verified -> Transcode.Yes
        fileId in untranscoded -> Transcode.KnownMissing
        media.hasClipTranscode(fileId) -> Transcode.Yes
        else -> Transcode.JustFoundMissing
    }

    /**
     * 挑的先后：有转码的（与还没查过的）先于只有原画的；同一档里当前这一层先于子文件夹，
     * 站在一部番的目录里先刷这部番，子文件夹里的花絮、特典排后面。档内随机。
     */
    private fun ranked(files: List<FileStat>): List<FileStat> {
        val rootId = root?.id
        return files.shuffled().sortedWith(compareBy({ it.id in untranscoded }, { it.parentId != rootId }))
    }

    /**
     * 片中随机一处起。开头 10% 多是片头，末尾 15% 多是片尾，
     * 都不从那里起；片子短到放不下这个区间时从正中起。
     */
    private fun clipOf(file: FileStat): Clip {
        val durationMs = file.durationMs()
        val earliest = (durationMs * 0.10).toLong()
        val latest = (durationMs * 0.85).toLong() - CLIP_LENGTH_MS
        val start = if (latest > earliest) Random.nextLong(earliest, latest) else ((durationMs - CLIP_LENGTH_MS) / 2).coerceAtLeast(0)
        return Clip(file.id, file.name, file.parentId, start, durationMs)
    }

    /**
     * 存下这个文件夹的队列与候选。停下 [SAVE_DELAY_MS] 才真写：每翻一页都要存，而候选池能有上千条。
     * 存的是写那一刻的样子，不是叫存时的；[immediately] 时是叫存时的，[close] 接着就要清空队列。
     */
    private fun save(immediately: Boolean = false) {
        val store = cacheStore ?: return
        val folder = root ?: return
        saveJob?.cancel()
        val taken = if (immediately) snapshot() else null
        saveJob = scope.launch {
            val saved = taken ?: run {
                delay(SAVE_DELAY_MS)
                snapshot()
            }
            runCatching {
                store.write(keyOf(folder.id), json.encodeToString(SavedFeed.serializer(), saved))
                rememberFolder(store, folder)
            }
        }
    }

    private fun snapshot(): SavedFeed {
        val from = (currentIndex - KEPT_AROUND).coerceAtLeast(0).coerceAtMost(clips.size)
        // 候补接在后面一起存，读回来时上次正看的那段起都回到候补，见 open
        val window = clips.subList(from, clips.size) + upcoming
        // 只存段与候选所在的目录，遍历过的其余目录用不上
        val usedFolders = (window.map { it.parentId } + pool.map { it.parentId }).toSet()
        return SavedFeed(
            clips = window,
            current = currentIndex - from,
            pool = pool.map { SavedCandidate(it.id, it.name, it.parentId, it.durationMs()) },
            verified = verified.toList(),
            rejected = rejected.toMap(),
            untranscoded = untranscoded.toMap(),
            folders = folderNames.filterKeys { it in usedFolders },
        )
    }

    /** 把 [folder] 记为最近用过的；挤出 [KEPT_FOLDERS] 之外的连存盘一起删。 */
    private suspend fun rememberFolder(store: PikoCacheStore, folder: PikoPathBreadcrumb) {
        val recent = runCatching { store.read(INDEX_KEY)?.let { json.decodeFromString<List<String>>(it) } }.getOrNull().orEmpty()
        val updated = listOf(folder.id) + (recent - folder.id)
        updated.drop(KEPT_FOLDERS).forEach { store.delete(keyOf(it)) }
        store.write(INDEX_KEY, json.encodeToString(updated.take(KEPT_FOLDERS)))
    }

    private suspend fun load(folderId: String): SavedFeed? = runCatching {
        cacheStore?.read(keyOf(folderId))?.let { json.decodeFromString(SavedFeed.serializer(), it) }
    }.getOrNull()

    // 根目录的 ID 是空串
    private fun keyOf(folderId: String) = "clip-feed-${folderId.ifEmpty { "root" }}"

    @Serializable
    private class SavedFeed(
        val clips: List<Clip>,
        val current: Int,
        val pool: List<SavedCandidate> = emptyList(),
        val verified: List<String> = emptyList(),
        val rejected: Map<String, Long> = emptyMap(),
        val folders: Map<String, String> = emptyMap(),
        val untranscoded: Map<String, Long> = emptyMap(),
    )

    /** 候选池里的一个视频，只存挑段要用的几项：时长定随机起点，名字与所在目录带进段里。 */
    @Serializable
    private class SavedCandidate(val id: String, val name: String, val parentId: String, val durationMs: Long) {
        fun toFileStat() = FileStat(id = id, name = name, parentId = parentId, params = mapOf("duration" to (durationMs / 1000.0).toString()))
    }

    private companion object {
        const val TAG = "Clips"
        const val INDEX_KEY = "clip-feeds"
        const val KEPT_FOLDERS = 8
        const val KEPT_AROUND = 25
        // 查详情是 API 请求，不占 CDN 的连接；4 个一批约一秒，大半没有转码的文件夹里挑得太慢
        const val VERIFY_BATCH = 8

        /** 当前段之后挑好的不到这么多段时，没有转码的也当场收下，见 fillAhead。 */
        const val LOW_ON_CLIPS = 3
        const val SAVE_DELAY_MS = 1_000L
        const val REJECT_TTL_MS = 3L * 24 * 60 * 60 * 1000

        /** 短于一分钟的多是样片或花絮，放不满一段也没有意思。 */
        const val MIN_DURATION_MS = 60_000L
    }

    /**
     * 能挑出片段的视频。没有时长的跳过：随机起点要按时长算，先打开再量又要多等一次连接；
     * 服务端抽过元数据的视频列目录时就带着时长，没有的多半也放不了。
     */
    private fun FileStat.isClipCandidate(): Boolean =
        isPlayableVideo() && !isUploading && durationMs() >= MIN_DURATION_MS

    private fun FileStat.durationMs(): Long =
        ((params["duration"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}
