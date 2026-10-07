package dev.piko.shared.media

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.log.PikoLog
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import io.github.nihildigit.pikpak.leaseDetail
import dev.piko.shared.media.proxy.PikPakByteSource
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyStream
import dev.piko.shared.media.proxy.SlicedByteSource
import io.github.nihildigit.pikpak.BlockStore
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.download.DownloadTask
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.fileHandle
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listPlayHistory
import io.github.nihildigit.pikpak.reportPlay
import io.github.nihildigit.pikpak.resolveVariant
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class PlayableMediaKind { Video, Image, UnsupportedImage }

data class PlayableMediaInfo(
    val fileId: String,
    val name: String,
    val gcid: String,
    /** 签名直链，会过期。只适合立刻使用，播放请走 [PreparedPlayback.proxyUrl]。 */
    val currentUrl: String,
    val durationSeconds: Long,
    val currentResolution: String,
    val availableVariants: List<MediaVariant>,
    val mediaId: String? = null,
    val sizeBytes: Long = 0L,
    val kind: PlayableMediaKind = mediaKindOf(name),
    /** 画面尺寸，服务端没抽出元数据时为 0。 */
    val width: Int = 0,
    val height: Int = 0,
    val isOrigin: Boolean = true,
) {
    /** 横向画面。尺寸未知时为 null，界面据此决定要不要提示全屏。 */
    val isLandscapeVideo: Boolean?
        get() = if (width <= 0 || height <= 0) null else width > height
}

/**
 * 可读的转码流里画面最大的那个。空表示这个文件没有能用的转码——
 * medias 里既有还在转的（video 为空），也有转完却没给链接的。
 */
fun PlayableMediaInfo.bestTranscodeName(): String? = availableVariants.transcodeNameAtMost(Int.MAX_VALUE)

/** 眼下放的这一档的名字，原画为 null。与 [PlayableMediaInfo.availableVariants] 里的名字对得上，界面据此标出当前档。 */
val PlayableMediaInfo.transcodeName: String?
    get() = if (isOrigin) null else availableVariants.firstOrNull { it.mediaId == mediaId }?.qualityName

/**
 * 画面高度不超过 [maxHeight] 的可读转码里最大的一档。没有返回 null，调用方退回原画：
 * 上限是为了省流量，挑一档更高的转码违背本意，原画至少是用户本来就会看到的。
 */
fun List<MediaVariant>.transcodeNameAtMost(maxHeight: Int): String? = this
    .filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() && (it.video?.height ?: 0) <= maxHeight }
    .maxByOrNull { it.video?.height ?: 0 }
    ?.qualityName

private val MediaVariant.qualityName: String?
    get() = mediaName.ifBlank { resolutionName }.takeIf { it.isNotBlank() }

/**
 * 一次播放准备的结果。关闭它即释放代理会话、reader 与 handle。
 */
class PreparedPlayback internal constructor(
    val info: PlayableMediaInfo,
    private val stream: ProxyStream?,
) : AutoCloseable {
    /** 本机代理的地址。handle 建不起来（例如没有 gcid）时为 null，只能读直链。 */
    val proxyUrl: String? get() = stream?.url

    /** 有人正等着：出第一帧前、拖动后、卡在缓冲上。见 ProxyStream.urgent。 */
    var urgent: Boolean
        get() = stream?.urgent ?: false
        set(value) {
            stream?.urgent = value
        }

    override fun close() {
        stream?.close()
    }
}

/** [PikoMediaRepository.clipProbe] 的结果。时长取不到时为 null。 */
class ClipProbe(val hasTranscode: Boolean, val durationMs: Long?)

/**
 * 下载时可选的一档。原画的 [name] 与 [mediaId] 为 null。[sizeBytes] 是要下的字节数，转码档还没探到时为 null；
 * 转码档存下来是转封装后的 MP4，比这个数略小。[height] 是画面高度，不知道时为 0。
 */
data class DownloadQuality(
    val name: String?,
    val mediaId: String?,
    val height: Int,
    val sizeBytes: Long?,
    val durationMs: Long,
    /** 转码档探测时读不出字节（服务端坏了），不能选。 */
    val unreadable: Boolean = false,
)

/** 这一档转码在服务端读不出字节，见 [PikoMediaRepository.downloadQualities]。消息直接给用户看。 */
class UnreadableTranscodeException(cause: Throwable? = null) : IllegalStateException("该画质的转码文件无法读取，请改选其他画质", cause)

class PikoMediaRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
    /** 随机片段切片的磁盘缓存，见 [ClipCache]。没有就每次都从网上取。 */
    private val clipCache: ClipCache? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    /**
     * 打开归档条目时借用的文件对象放在哪个目录，返回它的 ID。PikoServices 组装好 Piko-Temp 之后设上：
     * 媒体仓库在各端入口建出来时，Piko-Temp 还不存在。
     */
    var leaseFolder: (suspend () -> String)? = null

    /** 下载调度器提供；原画播放与完整下载共用缓存，转码片段仍用 ClipCache。 */
    var fileCachePool: PikoFileCachePool? = null
    var partialDownload: ((fileId: String) -> DownloadTask?)? = null

    // 按磁盘记录重建的切片在这里提前取直链，见 cachedClip
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 随机片段挑段时查转码拿到的详情，紧接着建 handle 直接用：handle 连第一条直链都从详情里取，
    // 一段省下全部的详情查询。直链的有效期远长于这几分钟
    private val freshDetails = HashMap<String, Pair<FileDetail, TimeMark>>()
    private val freshDetailsLock = Mutex()

    // 进程内一个就够：端口按需绑定，没有会话时只占一个监听 socket
    private val proxy by lazy { PikoMediaProxy() }

    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val detail = detailOf(client, fileId)
                val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                playableMediaInfo(detail, resolved, fileId)
            }
        }

    /**
     * 取元数据并为视频开一个代理会话。
     *
     * 只调一次 getFile：元数据、直链与 handle 都出自同一份详情，失败回退用的直链
     * 与代理读的字节同源。
     */
    /**
     * [preferredResolution] 是用户在播放器里点选的档位；为 null 时按 [defaultMaxHeight] 挑，见 [transcodeNameAtMost]，
     * 0 表示原画。
     */
    suspend fun preparePlayback(
        fileId: String,
        preferredResolution: String? = null,
        defaultMaxHeight: Int = 0,
    ): Result<PreparedPlayback> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = client
                // 本机下了一部分的原画不费流量，照样先用，画质上限是为了省流量
                if (preferredResolution.isNullOrBlank() || preferredResolution == ORIGINAL_QUALITY) {
                    partialSource(client, fileId)?.let { (task, source) ->
                        PikoLog.d(TAG, "取流用本机未下完的下载缓存：${logFile(fileId, task.fileName)}，已有 ${task.downloadedBytes}/${task.totalBytes} 字节")
                        val info = PlayableMediaInfo(fileId, task.displayName.substringAfterLast('/'), task.gcid,
                            currentUrl = "", durationSeconds = 0, availableVariants = emptyList(),
                            currentResolution = ORIGINAL_QUALITY, sizeBytes = task.totalBytes)
                        val stream = registerProxy(source, task.fileName.substringAfterLast('/'), StreamRole.FOREGROUND)
                            ?: error("无法创建本地播放会话")
                        return@runSuspendCatching PreparedPlayback(info, stream)
                    }
                }
                val detail = detailOf(client, fileId)
                val resolution = preferredResolution
                    ?: defaultMaxHeight.takeIf { it > 0 }?.let { detail.medias.transcodeNameAtMost(it) }
                val resolved = detail.resolveVariant(preferenceFor(resolution))
                val info = playableMediaInfo(detail, resolved, fileId)
                val leased = LeasedFile.isLeased(fileId)
                val stream = if (info.kind == PlayableMediaKind.Video) openProxyStream(client, detail, resolved, leased = leased) else null
                PreparedPlayback(info, stream)
            }
        }

    /**
     * 为随机片段开一个代理会话：全片 [startMs] 起的一段，[videoDurationMs] 是全片时长。
     *
     * 有 [CLIP_RESOLUTION] 转码时只截转码流起点附近的一截交给播放器，见 [SlicedByteSource]。
     * PikPak 的转码流是 MPEG-TS，没有索引，从片中起播 mpv 只能按时间戳二分查找，一次起播十来处跳读，
     * 实测 4 到 18 秒；而 TS 本来就能从任意包边界接着播，截出来当一个短文件从头顺序读，不跳读，
     * 起播只要起点那几百 KB。转码的码率大致恒定，按时长比例折算的起点也比原画准。
     *
     * 没有这一档转码、或者转码不是 TS 时，退回原画整条，由播放器从起点 seek 过去。
     */
    suspend fun prepareClip(
        fileId: String,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole,
    ): Result<PreparedClip> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val key = clipKey(fileId, startMs)
                val client = client
                val leased = LeasedFile.isLeased(fileId)
                clipCache?.record(key)?.let { record ->
                    cachedClip(client, record, startMs, videoDurationMs, role, leased)?.let { return@runSuspendCatching it }
                }
                val detail = takeFreshDetail(fileId) ?: detailOf(client, fileId)
                val transcode = detail.resolveVariant(VariantPreference.Resolution(CLIP_RESOLUTION))
                if (!transcode.isOrigin && videoDurationMs > 0) {
                    openClipSlice(client, detail, transcode, key, startMs, videoDurationMs, role, leased)
                        ?.let { return@runSuspendCatching it }
                }
                val original = detail.resolveVariant(VariantPreference.Original)
                val info = playableMediaInfo(detail, original, fileId)
                val stream = openProxyStream(client, detail, original, role, leased)
                val bytesPerMs = if (videoDurationMs > 0) detail.sizeBytes.toDouble() / videoDurationMs else 0.0
                PreparedClip(stream, fallbackUrl = info.currentUrl, sliced = false, clipStartMs = startMs, bytesPerMs = bytesPerMs)
            }
        }

    /**
     * 这一段来过：按记录建 handle，不查详情就交出代理地址，开头与末尾从磁盘读。
     * 读到中间才要直链，所以在后台先取一条，播放器读完盘上那几秒之前多半已经取好。
     */
    private suspend fun cachedClip(
        client: PikPakClient,
        record: ClipRecord,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole,
        leased: Boolean,
    ): PreparedClip? {
        val bytesPerMs = if (videoDurationMs > 0) record.streamBytes.toDouble() / videoDurationMs else 0.0
        val handle = PikPakFileHandle(
            client = client,
            gcid = record.gcid,
            size = record.originalBytes,
            name = record.name,
            initialFileId = record.fileId,
            mediaId = record.mediaId,
            parentId = record.parentId,
            leased = leased,
            onRangeAttempt = ::logRangeAttempt,
            streamSize = record.streamBytes,
        )
        val cache = try {
            handle.openCache(
                blockStore = sliceStore(record.sliceOffset, record.sliceLength, bytesPerMs),
                coroutineContext = proxy.readerContext,
            )
        } catch (e: Throwable) {
            handle.close()
            throw e
        }
        backgroundScope.launch { runCatching { handle.prewarm() } }
        val source = SlicedByteSource(PikPakByteSource(handle, cache), record.sliceOffset, record.sliceLength)
        val stream = registerProxy(source, fileName = "clip.ts", role = role) ?: return null
        return PreparedClip(stream, fallbackUrl = null, sliced = true, clipStartMs = record.sliceStartMs, bytesPerMs = bytesPerMs, fromDisk = true)
    }

    private fun sliceStore(sliceOffset: Long, sliceLength: Long, bytesPerMs: Double) =
        clipCache?.let { RangeLimitedStore(it.blocks) }?.also { it.keepSlice(sliceOffset, sliceLength, bytesPerMs) }

    /** 只存切片开头与末尾的块，见 [RangeLimitedStore]。块的偏移是整条转码流的，这里换算过去。 */
    private fun RangeLimitedStore.keepSlice(sliceOffset: Long, sliceLength: Long, bytesPerMs: Double) {
        kept = PreparedClip.sliceRanges(sliceLength, bytesPerMs).map { it.first + sliceOffset..it.last + sliceOffset }
    }

    // 同一个视频、同一个起点才是同一截；换了清晰度截出来的字节就不同。
    // 归档条目的 ID 带着文件名，拼进磁盘上的文件名会超长，只用条目自己的 ID
    private fun clipKey(fileId: String, startMs: Long): String {
        val id = VaultEntry.entryIdOf(fileId)?.let { "vault-$it" } ?: fileId
        return "${id}_${startMs}_$CLIP_RESOLUTION"
    }

    /**
     * 这个文件有没有随机片段要的那档转码，以及详情里的时长。列目录不带转码信息，只能逐个查详情；
     * 查不到按没有算，信息流把它排到有转码的后面，轮到时放原画。
     *
     * 时长是给归档条目的：它们存自磁力解析，没有时长，而信息流的随机起点要按时长算。借一次对象的详情里
     * 两样都有；这份详情随即留给 [prepareClip]，挑中后不必再借。
     */
    suspend fun clipProbe(fileId: String): ClipProbe =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val detail = detailOf(client, fileId)
                freshDetailsLock.withLock {
                    freshDetails.entries.removeAll { it.value.second.elapsedNow() > FRESH_DETAIL_FOR }
                    freshDetails[fileId] = detail to TimeSource.Monotonic.markNow()
                }
                ClipProbe(
                    hasTranscode = detail.medias.any { !it.isOrigin && it.mediaName == CLIP_RESOLUTION && it.url != null },
                    durationMs = detail.params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                )
            }.onFailure { PikoLog.d(TAG, "信息流查详情失败，按无转码处理：${logFile(fileId, "")}，${it::class.simpleName}：${it.message}") }
                .getOrDefault(ClipProbe(hasTranscode = false, durationMs = null))
        }

    /**
     * 文件详情。归档条目在网盘里没有常驻的文件，按 gcid 借一个对象查完就删（SDK 的 leaseDetail）：
     * 详情里的直链照样能用，6 GB 的免费空间因此整块留给正在看的那一个文件。
     * 读这份详情建 handle 时要带 leased，直链过期后重建出的对象才会同样删掉。
     */
    private suspend fun detailOf(client: PikPakClient, fileId: String): FileDetail {
        val leased = LeasedFile.resolvedFileOf(fileId) ?: return client.getFile(fileId)
        val folder = leaseFolder ?: error("归档条目无法打开")
        return client.leaseDetail(leased, parentId = folder())
    }

    /** [clipProbe] 刚查过的详情，还新鲜就拿走，省一次查询。 */
    private suspend fun takeFreshDetail(fileId: String): FileDetail? = freshDetailsLock.withLock {
        freshDetails.remove(fileId)?.takeIf { it.second.elapsedNow() <= FRESH_DETAIL_FOR }?.first
    }

    private suspend fun openClipSlice(
        client: PikPakClient,
        detail: FileDetail,
        transcode: ResolvedVariant,
        key: String,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole,
        leased: Boolean,
    ): PreparedClip? {
        val mediaId = transcode.mediaId ?: return null
        val store = clipCache?.let { RangeLimitedStore(it.blocks) }
        val source = openByteSource(client, detail, transcode, blockStore = store, leased = leased) ?: return null
        // 长度是 188 的整数倍是 TS 的样子；不是 TS 截出来就放不了，退回原画
        if (source.size % TS_PACKET_BYTES != 0L) {
            PikoLog.d(TAG, "转码流不是 TS（${source.size} 字节），片段退回原画：${logFile(detail.id, detail.name)}")
            source.close()
            return null
        }
        val bytesPerMs = source.size.toDouble() / videoDurationMs
        val estimated = ((bytesPerMs * startMs).toLong() / TS_PACKET_BYTES * TS_PACKET_BYTES).coerceIn(0, source.size - TS_PACKET_BYTES)
        // 按平均码率折算，截得宽裕：码率高的场面一截装下的视频比平均短，截短了没放完这一段就到了切片末尾，
        // 画面停在最后一帧。截长不费什么，播放器只读放到的部分，多出来的只是末尾那一截估时长时读一下
        val wanted = (bytesPerMs * (CLIP_SLICE_MS + PreparedClip.KEYFRAME_INTERVAL_MS) * SLICE_BITRATE_MARGIN).toLong()
        val length = (wanted / TS_PACKET_BYTES + 1) * TS_PACKET_BYTES
        fun sliceLengthAt(offset: Long) = length.coerceAtMost(source.size - offset)
        // 找关键帧读到的块也要落盘：它们多半就是切片开头，存储在这之前设好范围，否则这些块只进内存缓存，
        // 之后预取从内存读到，不再经过存储，盘上就缺了开头
        store?.keepSlice(estimated, sliceLengthAt(estimated), bytesPerMs)
        // 切片从第一个关键帧起：从估的位置截，解码器要先丢掉一堆缺参数集的包，HEVC 逐包报错，
        // 一段刷几百行日志，还白下了那一截。估的位置之后五秒内（转码每 5 秒一个关键帧）必有一个
        val keyframeWindow = (bytesPerMs * PreparedClip.KEYFRAME_INTERVAL_MS * PreparedClip.BITRATE_MARGIN).toLong()
        val keyframe = source.findKeyframe(estimated, keyframeWindow, role)
        val offset = if (keyframe != null) estimated + keyframe.byteOffset else estimated
        val sliceLength = sliceLengthAt(offset)
        store?.keepSlice(offset, sliceLength, bytesPerMs)
        val slice = SlicedByteSource(source, offset, sliceLength)
        val stream = registerProxy(slice, fileName = "clip.ts", role = role) ?: return null
        fun record(sliceStartMs: Long) = ClipRecord(
            fileId = detail.id,
            gcid = detail.hash,
            name = detail.name,
            parentId = detail.parentId,
            originalBytes = detail.sizeBytes,
            mediaId = mediaId,
            streamBytes = source.size,
            sliceOffset = offset,
            sliceLength = sliceLength,
            sliceStartMs = sliceStartMs,
        )
        // 切片起点的时间戳已在找关键帧时读到；流开头的几十 KB 要另取一次，放到预取之后，不拖慢出会话
        return PreparedClip(
            stream,
            fallbackUrl = null,
            sliced = true,
            clipStartMs = startMs,
            bytesPerMs = bytesPerMs,
            resolveStart = keyframe?.let { sliceStart ->
                {
                    // 前台读：这一步在开头预取之后、算作取好之前，与预取一样急。以后台身份读，预取一结束这个文件
                    // 就没有前台需求，被限到两路、排在所有段的预取之后，取好一段要等别段取完一段（2026-09-28）
                    TsTimestamps.firstVideoPtsMs(source.readAt(0, STREAM_HEAD_BYTES, StreamRole.FOREGROUND))
                        ?.let { streamStart -> TsTimestamps.elapsedMs(streamStart, sliceStart.ptsMs) }
                }
            },
            // 开头与末尾都在盘上了才记，记录在就当它们在
            onWarmed = { actualStartMs -> clipCache?.remember(key, record(actualStartMs)) },
        )
    }

    /**
     * [offset] 起 [window] 字节内第一个视频关键帧，偏移相对 [offset]。按块读，找到就停：
     * 关键帧平均在半个间隔处，一次读满整个窗口要多下一倍。块长是 188 的倍数，TS 包不会跨块。
     */
    private suspend fun ProxyByteSource.findKeyframe(offset: Long, window: Long, role: StreamRole): TsTimestamps.VideoPes? {
        var scanned = 0L
        while (scanned < window && offset + scanned < size) {
            val chunk = readAt(offset + scanned, KEYFRAME_SCAN_CHUNK, role)
            if (chunk.isEmpty()) return null
            TsTimestamps.firstVideoKeyframe(chunk)?.let { return TsTimestamps.VideoPes(scanned.toInt() + it.byteOffset, it.ptsMs) }
            scanned += chunk.size
        }
        return null
    }

    /**
     * 读 [offset] 起的 [length] 字节，流尾之前读不满就交出读到的。另开一个 reader，不动播放器的读位置。
     *
     * reader 的预读压到最小：默认窗口 32 MB，为读几十 KB 开一个，会顺手往后要一串块，
     * 信息流冷开时十来段各开几个，白白占着连接（2026-09-28 的传输汇总里大半是这种请求）。
     */
    private suspend fun ProxyByteSource.readAt(offset: Long, length: Int, role: StreamRole): ByteArray {
        // 先在 Long 上取小再转 Int：超过 2 GB 的流，剩余长度直接转 Int 会溢出成负数
        val buffer = ByteArray(minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt())
        openReader(role).use { reader ->
            reader.readAheadLimit = 0
            reader.seekTo(offset)
            var filled = 0
            while (filled < buffer.size) {
                val read = reader.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == buffer.size) buffer else buffer.copyOf(filled)
        }
    }

    /**
     * 为外挂字幕开一个代理会话，与视频走同一个本机代理：播放器读的是 127.0.0.1，不碰会过期的直链，
     * 地址里带着文件名，mpv 按扩展名认格式。开不起来返回 null，调用方跳过这一条。
     */
    suspend fun prepareSubtitle(fileId: String): ProxyStream? =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = client
                val detail = detailOf(client, fileId)
                val resolved = detail.resolveVariant(VariantPreference.Original)
                openProxyStream(client, detail, resolved, leased = LeasedFile.isLeased(fileId))
            }.logFailure(TAG, "外挂字幕取流失败：${logFile(fileId, "")}").getOrNull()
        }

    private suspend fun isPlayHistorySynced(): Boolean = preferences?.syncPlayHistoryFlow?.first() ?: false

    /**
     * 把播放进度上报到 PikPak 的播放历史。同步关闭时不做事；失败不抛，上报是附带的，不能打断播放。
     * 服务端会悄悄丢掉同一文件间隔太短的上报（实测 1.5 秒丢、6 秒收），节流由调用方负责。
     */
    suspend fun reportPlay(fileId: String, positionMillis: Long, durationMillis: Long) {
        // 归档条目与压缩包里的文件在网盘里没有常驻的文件，播放历史记不上；续播位置照样记在本机
        if (fileId.isBlank() || LeasedFile.isLeased(fileId) || positionMillis <= 0L || durationMillis <= 0L) return
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (isPlayHistorySynced()) client.reportPlay(fileId, positionMillis / 1000, durationMillis / 1000)
            }.logFailure(TAG, "上报播放历史失败：${logFile(fileId, "")}")
        }
    }

    /**
     * PikPak 播放历史里这个文件的续播位置，毫秒。同步关闭、没有记录、已看到片尾或取不到时为 null。
     * 服务端不能按文件查，只看第一页（最近播放的 100 条）：从历史里点进来的一定在里面，更早的就算了。
     */
    suspend fun cloudPlaybackPosition(fileId: String): Long? {
        if (fileId.isBlank() || LeasedFile.isLeased(fileId)) return null
        // 读偏好也包在里面：这只是续播的参考，任何一步失败都不该挡住开播
        return withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (!isPlayHistorySynced()) return@runSuspendCatching null
                val event = client.listPlayHistory().events.firstOrNull { it.fileId == fileId } ?: return@runSuspendCatching null
                val seconds = event.playSeconds ?: return@runSuspendCatching null
                val duration = event.playDuration
                if (duration != null && duration > 0 && seconds * 1000 >= duration * 1000 - CLOUD_NEAR_END_MILLIS) null else seconds * 1000
            }.logFailure(TAG, "读取云端播放历史失败，退回本机续播记录").getOrNull()
        }
    }

    suspend fun savePlaybackPosition(fileId: String, positionMillis: Long) {
        preferences?.savePlaybackPosition(fileId, positionMillis)
    }

    suspend fun getPlaybackPosition(fileId: String): Long =
        preferences?.getPlaybackPosition(fileId) ?: 0L

    /** 开不起来返回 null，由调用方退回直链；取消照常抛出。 */
    private suspend fun openProxyStream(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
        role: StreamRole = StreamRole.FOREGROUND,
        leased: Boolean = false,
    ): ProxyStream? {
        val source = openByteSource(client, detail, resolved, leased = leased) ?: return null
        return registerProxy(source, fileName = detail.name.takeIf { resolved.isOrigin }, role = role)
    }

    /** 登记失败时关掉 [source] 并返回 null，由调用方退回直链；取消照常抛出。 */
    private suspend fun registerProxy(source: ProxyByteSource, fileName: String?, role: StreamRole): ProxyStream? {
        return try {
            proxy.register(source, fileName = fileName, role = role)
        } catch (e: CancellationException) {
            source.close()
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "代理注册失败，退回直链", e)
            source.close()
            null
        }
    }

    /**
     * 按偏移读取原画，不经本机代理。给片段截取用：Android 的原因见 [RandomAccessMediaSource]，桌面端的 FFmpeg
     * 经 AVIO 回调读它。调用方负责关闭。
     *
     * 给了 [retainedBy] 时，读过的块在关闭后仍留在下载暂存里，记在这个所有者名下，直到它经
     * PikoFileCachePool.discard 放手：片段截取中断后再来，已读的部分不必重下。
     *
     * [mediaId] 不为 null 时读那一档转码（MPEG-TS），同样经下载暂存，与原画的暂存分开；返回的来源长度是转码流的长度。
     */
    suspend fun openRandomAccess(fileId: String, retainedBy: String? = null, mediaId: String? = null): Result<RandomAccessMediaSource> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = client
                if (mediaId != null) return@runSuspendCatching ReaderRandomAccessSource(transcodeSource(client, fileId, mediaId, retainedBy))
                partialSource(client, fileId, retainedBy)?.let { return@runSuspendCatching ReaderRandomAccessSource(it.second) }
                val detail = detailOf(client, fileId)
                val resolved = detail.resolveVariant(VariantPreference.Original)
                val source = openByteSource(client, detail, resolved, leased = LeasedFile.isLeased(fileId), retainedBy = retainedBy)
                    ?: error("文件缺少内容哈希，无法读取")
                ReaderRandomAccessSource(source)
            }
        }

    private suspend fun partialSource(client: PikPakClient, fileId: String, retainedBy: String? = null): Pair<DownloadTask, PikPakByteSource>? {
        val task = partialDownload?.invoke(fileId) ?: return null
        val pool = fileCachePool ?: return null
        if (task.totalBytes <= 0 || task.gcid.isBlank() || !task.sparseCache) return null
        val lease = pool.acquire(client, task.fileId, task.gcid, task.totalBytes, task.fileName,
            parentId = task.parentId, leased = task.leasedSource || LeasedFile.isLeased(fileId),
            retained = retainedBy != null, owner = retainedBy ?: task.fileId)
        return task to PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
    }

    /**
     * 这个视频能下载的各档：原画在前，转码按画面从高到低。拿到详情先发一次，转码档的大小服务端不给，各档同时探测
     * （[probeTranscode]），探到一档发一次更新；没探到的 [DownloadQuality.sizeBytes] 为 null，读不出字节的标上
     * [DownloadQuality.unreadable]。探测全部结束时流结束，查不到详情时抛出。
     * 归档条目与压缩包里的文件只有原画：它们的对象是借来的，转码档挂在借出的对象上，取完就删了。
     *
     * 同一个文件的探测在 [QUALITY_PROBE_FOR] 内只做一次，对话框、片段面板、[prefetchDownloadQualities] 与
     * 下载开始时的 [downloadVariant] 共用，进行中的也一起等。
     */
    fun downloadQualities(fileId: String): Flow<List<DownloadQuality>> = flow {
        emitAll(
            qualityProbe(fileId).state.transformWhile { probe ->
                probe.error?.let { throw it }
                probe.options?.let { emit(it) }
                !probe.finished
            },
        )
    }

    /**
     * 提前开始 [downloadQualities] 的探测，结果留给随后打开的对话框与片段面板。在「选择画质下载」「下载指定段落」
     * 上按下或指针移入时调用：这时多半就要点，不点也只白发一次详情与每档一个 1 字节的请求。
     */
    fun prefetchDownloadQualities(fileId: String) {
        backgroundScope.launch { runSuspendCatching { qualityProbe(fileId) } }
    }

    private class QualityProbeState(
        val options: List<DownloadQuality>? = null,
        val finished: Boolean = false,
        val error: Throwable? = null,
    )

    private class QualityProbe(val state: MutableStateFlow<QualityProbeState>, val started: TimeMark)

    // 按账号与文件 ID；失败的不留，下次打开重查
    private val qualityProbes = HashMap<String, QualityProbe>()
    private val qualityProbesLock = Mutex()

    private suspend fun qualityProbe(fileId: String): QualityProbe {
        val client = client
        val key = "${client.account}/$fileId"
        return qualityProbesLock.withLock {
            qualityProbes.values.removeAll { it.state.value.finished && it.started.elapsedNow() > QUALITY_PROBE_FOR }
            qualityProbes.getOrPut(key) {
                QualityProbe(MutableStateFlow(QualityProbeState()), TimeSource.Monotonic.markNow()).also { probe ->
                    backgroundScope.launch {
                        runSuspendCatching { probeQualities(client, fileId, probe.state) }.onFailure { error ->
                            qualityProbesLock.withLock { if (qualityProbes[key] === probe) qualityProbes.remove(key) }
                            probe.state.value = QualityProbeState(finished = true, error = error)
                        }
                    }
                }
            }
        }
    }

    /** 已经探完、还没过期的那一份，没有为 null。不发起探测。 */
    private suspend fun finishedQualities(fileId: String): List<DownloadQuality>? {
        val key = "${client.account}/$fileId"
        return qualityProbesLock.withLock {
            qualityProbes[key]?.takeIf { it.started.elapsedNow() <= QUALITY_PROBE_FOR }?.state?.value
                ?.takeIf { it.finished && it.error == null }?.options
        }
    }

    private suspend fun probeQualities(client: PikPakClient, fileId: String, state: MutableStateFlow<QualityProbeState>) {
        val detail = detailOf(client, fileId)
        val durationMs = durationMsOf(detail)
        val original = DownloadQuality(null, null, detail.medias.firstOrNull { it.isOrigin }?.video?.height ?: 0, detail.sizeBytes, durationMs)
        val transcodes = if (LeasedFile.isLeased(fileId)) emptyList() else detail.downloadableTranscodes().sortedByDescending { it.video?.height ?: 0 }
        state.value = QualityProbeState(listOf(original) + transcodes.map { it.toDownloadQuality(durationMs) })
        coroutineScope {
            transcodes.forEach { media ->
                launch {
                    val probed = runSuspendCatching { probeTranscode(client, media) }
                    val error = probed.exceptionOrNull()
                    if (error is UnreadableTranscodeException) {
                        PikoLog.w(TAG, "转码档 ${media.qualityName} 读不出字节：${logFile(fileId, "")}", error)
                    } else if (error != null) {
                        PikoLog.w(TAG, "探测转码档大小失败：${logFile(fileId, "")}", error)
                        return@launch
                    }
                    state.update { current ->
                        QualityProbeState(current.options?.map { option ->
                            if (option.mediaId != media.mediaId) option
                            else probed.fold(onSuccess = { option.copy(sizeBytes = it) }, onFailure = { option.copy(unreadable = true) })
                        })
                    }
                }
            }
        }
        state.update { QualityProbeState(it.options, finished = true) }
    }

    /**
     * 下载时定下的那一档，连同它的大小。[name] 是用户选的档，读不出字节时以 [UnreadableTranscodeException] 失败，
     * 不换成别的档。[name] 为 null 时按 [maxHeight] 挑不高于它、读得出的最高一档。没有可下的转码（或文件只能借出）
     * 时为 null，调用方下原画：上限是为了省流量，挑一档更高的转码违背本意。
     */
    suspend fun downloadVariant(fileId: String, name: String?, maxHeight: Int): Result<DownloadQuality?> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (LeasedFile.isLeased(fileId)) return@runSuspendCatching null
                // 刚在对话框或片段面板里列过各档，就按那次的探测结果定，不再查详情、不再探
                finishedQualities(fileId)?.filter { it.mediaId != null }
                    ?.takeIf { known -> known.all { it.unreadable || it.sizeBytes != null } }
                    ?.let { known -> return@runSuspendCatching chooseVariant(known, name, maxHeight) }
                val client = client
                val detail = detailOf(client, fileId)
                val durationMs = durationMsOf(detail)
                val transcodes = detail.downloadableTranscodes()
                if (name != null) {
                    val media = transcodes.firstOrNull { it.qualityName == name } ?: return@runSuspendCatching null
                    return@runSuspendCatching media.toDownloadQuality(durationMs, probeTranscode(client, media))
                }
                val candidates = transcodes.filter { (it.video?.height ?: 0) <= maxHeight }.sortedByDescending { it.video?.height ?: 0 }
                for (media in candidates) {
                    val probed = runSuspendCatching { probeTranscode(client, media) }
                    val error = probed.exceptionOrNull()
                    if (error is UnreadableTranscodeException) {
                        PikoLog.w(TAG, "转码档 ${media.qualityName} 读不出字节，改挑下一档：${logFile(fileId, "")}", error)
                        continue
                    }
                    return@runSuspendCatching media.toDownloadQuality(durationMs, probed.getOrThrow())
                }
                null
            }
        }

    /** [downloadVariant] 的挑法，用在已探过的各档上。 */
    private fun chooseVariant(transcodes: List<DownloadQuality>, name: String?, maxHeight: Int): DownloadQuality? {
        if (name != null) {
            val chosen = transcodes.firstOrNull { it.name == name } ?: return null
            if (chosen.unreadable) throw UnreadableTranscodeException()
            return chosen
        }
        return transcodes.filter { !it.unreadable && it.height <= maxHeight }.maxByOrNull { it.height }
    }

    private fun FileDetail.downloadableTranscodes(): List<MediaVariant> =
        medias.filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() && it.qualityName != null }

    private fun MediaVariant.toDownloadQuality(fallbackDurationMs: Long, sizeBytes: Long? = null) =
        DownloadQuality(qualityName, mediaId, video?.height ?: 0, sizeBytes, video?.duration?.times(1000) ?: fallbackDurationMs)

    /**
     * 一档转码的长度，并确认它真能读出字节：一次 1 字节的 Range 请求，长度取 Content-Range。读不出时抛
     * [UnreadableTranscodeException]，连不上、回错误状态码一类照原样抛出。
     *
     * 实测有的转码档服务端是坏的：回 206、Content-Range 写着全长，正文却是空的，换主机、隔几分钟都一样
     * （2026-10-07，同一个视频的 1080P 正常，720P 与 480P 如此）。只看响应头的长度探测认不出，下载到第一个块才失败。
     * 用详情里刚取的直链直接发，不经 handle：handle 读不出要换主机重试几轮才放弃，坏档要等几秒才能从列表里去掉。
     */
    private suspend fun probeTranscode(client: PikPakClient, media: MediaVariant): Long =
        client.streamRangeFromUrl(media.link.url, start = 0L, length = 1L) { stream ->
            val size = stream.totalSize?.takeIf { it > 0 } ?: error("转码档没有给出长度")
            // 正文提前断开在 Ktor 里是异常，不是读到 -1，两种都算读不出
            val read = runSuspendCatching { stream.channel.readAvailable(ByteArray(1), 0, 1) }
            if ((read.getOrNull() ?: -1) < 1) throw UnreadableTranscodeException(read.exceptionOrNull())
            size
        }

    /**
     * 一档转码经下载暂存读。不走 [openByteSource]：那条路给播放用，转码只进内存缓存，
     * 换成暂存的话边看边往盘上写整条 TS。
     */
    private suspend fun transcodeSource(client: PikPakClient, fileId: String, mediaId: String, retainedBy: String?): PikPakByteSource {
        val detail = detailOf(client, fileId)
        if (detail.hash.isBlank()) error("文件缺少内容哈希，无法读取")
        if (detail.medias.none { it.mediaId == mediaId && it.link.url.isNotBlank() }) error("所选画质已不可用")
        val streamSize = streamSizeOf(client, detail, mediaId)
        val pool = fileCachePool ?: run {
            val handle = client.fileHandle(detail, mediaId = mediaId, streamSize = streamSize, onRangeAttempt = ::logRangeAttempt)
            return try {
                PikPakByteSource(handle, handle.openCache(coroutineContext = proxy.readerContext))
            } catch (e: Throwable) {
                handle.close()
                throw e
            }
        }
        val lease = pool.acquire(client, detail.id, detail.hash, detail.sizeBytes, detail.name,
            parentId = detail.parentId, leased = LeasedFile.isLeased(fileId), detail = detail,
            retained = retainedBy != null, owner = retainedBy ?: detail.id, mediaId = mediaId, streamSize = streamSize)
        return PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
    }

    private suspend fun streamSizeOf(client: PikPakClient, detail: FileDetail, mediaId: String): Long =
        client.fileHandle(detail, mediaId = mediaId, onRangeAttempt = ::logRangeAttempt).use { it.streamSize() }

    private fun durationMsOf(detail: FileDetail): Long =
        detail.params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L

    /**
     * 建 handle 与字节来源。handle 的内容哈希、文件对象与第一条直链都取自 [detail]，第一次读不必再查。
     * 没有 gcid 或拿不到大小时返回 null；取消照常抛出。
     */
    private suspend fun openByteSource(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
        blockStore: BlockStore? = null,
        leased: Boolean = false,
        /** 见 [openRandomAccess]。只对原画有效：只有原画经下载暂存。 */
        retainedBy: String? = null,
    ): PikPakByteSource? {
        // handle 在直链被拒时按 gcid 重建文件对象，没有 gcid 就失去了它存在的意义
        if (detail.hash.isBlank()) return null
        if (resolved.isOrigin && blockStore == null) {
            fileCachePool?.let { pool ->
                val lease = pool.acquire(client, detail.id, detail.hash, detail.sizeBytes, detail.name,
                    parentId = detail.parentId, leased = leased, detail = detail,
                    retained = retainedBy != null, owner = retainedBy ?: detail.id)
                return PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
            }
        }
        // 原文件被删后 handle 会按 gcid 秒传重建一份，落在原来的目录（fileHandle 默认取详情里的 parentId）。
        // 归档条目的 detail 出自 leaseDetail，对象已经删了：直链过期时重建出的那份，handle 取完链同样删掉
        val handle = client.fileHandle(
            detail,
            mediaId = resolved.mediaId,
            onRangeAttempt = ::logRangeAttempt,
            leased = leased,
        )
        return try {
            // 原画的大小已知；转码流没有，本进程头一回会发一次 1 字节探测
            PikPakByteSource(handle, handle.openCache(blockStore, proxy.readerContext))
        } catch (e: CancellationException) {
            handle.close()
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "打开字节来源失败（${if (resolved.isOrigin) "原画" else "转码"}），退回直链", e)
            handle.close()
            null
        }
    }

    private fun preferenceFor(resolution: String?): VariantPreference =
        if (resolution.isNullOrBlank() || resolution == ORIGINAL_QUALITY) {
            VariantPreference.Original
        } else {
            VariantPreference.Resolution(resolution)
        }

    private fun playableMediaInfo(detail: FileDetail, resolved: ResolvedVariant, fileId: String) =
        PlayableMediaInfo(
            fileId = fileId,
            name = detail.name,
            gcid = detail.hash,
            currentUrl = resolved.link.url,
            durationSeconds = resolved.video?.duration ?: 0L,
            currentResolution = resolved.video?.height?.toString() ?: ORIGINAL_QUALITY,
            availableVariants = detail.medias,
            mediaId = resolved.mediaId,
            sizeBytes = resolved.sizeBytes ?: detail.sizeBytes,
            width = resolved.video?.width ?: 0,
            height = resolved.video?.height ?: 0,
            isOrigin = resolved.isOrigin,
        )

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

private const val TAG = "Media"

/** 随机片段取的转码档位：数据量比原画小得多。 */
private const val CLIP_RESOLUTION = "720P"

private const val TS_PACKET_BYTES = 188L

// 转码流开头的这一截里必有第一个视频 PES：PAT、PMT 之后紧接着就是
private const val STREAM_HEAD_BYTES = 64 * 1024

// 188 的倍数，约 256 KiB，与 SDK 的块一样大
private const val KEYFRAME_SCAN_CHUNK = 188 * 1394

/** 切片按这么长截，远超一段的 30 秒，见 openClipSlice。 */
private const val CLIP_SLICE_MS = 90_000L

private const val SLICE_BITRATE_MARGIN = 2.0

private val FRESH_DETAIL_FOR = 5.minutes

// 各档的大小随内容而定，不会变；读不出的档有可能被服务端修好，所以也不留太久
private val QUALITY_PROBE_FOR = 10.minutes

/** 清晰度菜单里代表原画的那一项，也是 [PikoMediaRepository] 认的原画标识。 */
const val ORIGINAL_QUALITY = "Original"

// 云端记录停在片尾这么近时当作看完，从头播；与本机续播的判断一致
private const val CLOUD_NEAR_END_MILLIS = 10_000L

fun mediaKindOf(name: String): PlayableMediaKind = when (name.substringAfterLast('.', "").lowercase()) {
    "avif", "bmp", "heic", "heif", "jpeg", "jpg", "png", "webp" -> PlayableMediaKind.Image
    "gif" -> PlayableMediaKind.UnsupportedImage
    else -> PlayableMediaKind.Video
}
