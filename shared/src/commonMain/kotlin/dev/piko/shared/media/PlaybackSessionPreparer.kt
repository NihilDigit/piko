@file:JvmMultifileClass
@file:JvmName("PikoMediaRepositoryKt")

package dev.piko.shared.media

import dev.piko.data.repository.isPlayableAudio
import dev.piko.download.DownloadTask
import dev.piko.shared.data.ContentMetadata
import dev.piko.shared.data.DriveMetadataSink
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMounts
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.player.PlaybackTransport
import dev.piko.shared.media.proxy.PikPakByteSource
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyStream
import dev.piko.shared.media.reader.DocumentByteResult
import dev.piko.shared.media.reader.DocumentTextDecoder
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.BlockStore
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.fileHandle
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.leaseDetail
import io.github.nihildigit.pikpak.resolveVariant
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.withApiPriority
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PlayableMediaKind { Video, Audio, Image, UnsupportedImage }

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
    /** 原画文件的真实字节大小；播放转码流时 [sizeBytes] 为转码流长度，此字段仍保留原画大小供 ABR 测算原画码率。 */
    val originalSizeBytes: Long = if (isOrigin) sizeBytes else 0L,
) {
    /** 横向画面。尺寸未知时为 null，界面据此决定要不要提示全屏。 */
    val isLandscapeVideo: Boolean?
        get() = if (width <= 0 || height <= 0) null else width > height
}

/**
 * 可读的转码流里画面最大的那个。空表示这个文件没有能用的转码：
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

private val InactivePlaybackReactivation: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * 一次播放准备的结果。关闭它即释放代理会话、需求凭据与 handle。
 */
class PreparedPlayback internal constructor(
    val info: PlayableMediaInfo,
    private val stream: ProxyStream?,
    /** 当前变体（原画或转码流）按其实际流长度或编码码率估算的平均字节消耗率（字节/秒）。 */
    val estimatedBytesPerSecond: Long? = null,
    val defaultBufferBudget: BufferBudget = playbackBufferBudget(),
    internal val demand: TransferDemand = TransferDemand(),
    /** 打开本次播放会话时归属的账号客户端，供关闭播放器或切账号竞态时准确上报历史。 */
    val client: PikPakClient? = null,
) : PlaybackTransport {
    @Volatile internal var prewarmJob: Job? = null
    @Volatile private var boundPlaybackEpoch: Long = -1L
    @Volatile private var prewarmCancelled: Boolean = false

    /** 本机代理的地址。handle 建不起来（例如没有 gcid）时为 null，只能读直链。 */
    val proxyUrl: String? get() = stream?.url

    /** 当前流底层缓存已从网络收到的累计字节数（单调递增，用于显示加载实时网速）。 */
    val receivedBytes: Long get() = stream?.receivedBytes ?: 0L

    /** 当前流活跃网络传输累计 `(activeBytes, activeNanos)`，供 ABR Dual-EWMA 估算真实链路吞吐。 */
    internal fun activeTransferSnapshot(): Pair<Long, Long> = stream?.activeTransferSnapshot() ?: (0L to 0L)

    internal fun setOnReadActivity(callback: (() -> Unit)?) {
        stream?.setOnReadActivity(callback)
    }

    /** 底层云端文件是否正处于冷存唤醒等待中。 */
    val isReactivating: StateFlow<Boolean> get() = stream?.isReactivating ?: InactivePlaybackReactivation

    internal fun attachPrewarmJob(job: Job) {
        if (prewarmCancelled || demand.isClosed) {
            job.cancel()
            return
        }
        prewarmJob = job
        if (prewarmCancelled || demand.isClosed) {
            cancelPrewarm()
        }
    }

    fun cancelPrewarm() {
        prewarmCancelled = true
        prewarmJob?.cancel()
        prewarmJob = null
    }

    private fun onIntentUpdated(intent: ConsumerIntent) {
        if (intent.service == ServiceIntent.PAUSED || intent.bufferBudget.maxReadAheadBytes <= 0L) {
            cancelPrewarm()
            return
        }
        if (intent.epoch > 0L) {
            val prevBound = boundPlaybackEpoch
            if (prevBound < 0L) {
                boundPlaybackEpoch = intent.epoch
            } else if (intent.epoch > prevBound) {
                boundPlaybackEpoch = intent.epoch
                cancelPrewarm()
            }
        }
    }

    fun submitIntent(intent: ConsumerIntent): Boolean {
        val updated = demand.submitIntent(intent)
        onIntentUpdated(demand.intent)
        return updated
    }

    fun updateIntent(transform: (ConsumerIntent) -> ConsumerIntent): Boolean {
        val updated = demand.updateIntent(transform)
        onIntentUpdated(demand.intent)
        return updated
    }

    fun reportPlaybackBuffer(snapshot: PlaybackBufferSnapshot): Boolean =
        demand.reportPlaybackBuffer(
            snapshot.copy(mediaBytesPerSecond = snapshot.mediaBytesPerSecond ?: estimatedBytesPerSecond),
        )

    fun withdrawSpeculation() {
        cancelPrewarm()
        demand.withdrawSpeculation()
    }

    override fun withdrawTransport() {
        cancelPrewarm()
        stream?.withdrawTransport()
        demand.close()
    }

    override fun close() {
        withdrawTransport()
        stream?.close()
    }
}

/** 清晰度菜单里代表「自动」动态码率档位的标识。 */
const val AUTO_QUALITY = "Auto"

/** 清晰度菜单里代表原画的那一项，也是 [PikoMediaRepository] 认的原画标识。 */
const val ORIGINAL_QUALITY = "Original"

fun mediaKindOf(name: String): PlayableMediaKind = when {
    name.substringAfterLast('.', "").lowercase() in setOf("avif", "bmp", "heic", "heif", "jpeg", "jpg", "png", "webp") -> PlayableMediaKind.Image
    name.substringAfterLast('.', "").lowercase() == "gif" -> PlayableMediaKind.UnsupportedImage
    name.isPlayableAudio() -> PlayableMediaKind.Audio
    else -> PlayableMediaKind.Video
}

internal const val PLAYBACK_WAITING_READ_AHEAD_FLOOR_BYTES = 10L * 256 * 1024L
internal const val PLAYBACK_WAITING_READ_AHEAD_MAX_BYTES = 4L * 1024 * 1024L
internal const val ABR_CACHE_WINDOW_BYTES = 1024L * 1024L

internal fun playbackBufferBudget(estimatedBytesPerSecond: Long? = null): BufferBudget =
    if (estimatedBytesPerSecond != null) {
        BufferBudget(
            maxReadAheadBytes = (estimatedBytesPerSecond * 8L).coerceIn(
                12L * 1024 * 1024,
                PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES,
            ),
            waitingReadAheadBytes = (estimatedBytesPerSecond * 3L).coerceIn(
                PLAYBACK_WAITING_READ_AHEAD_FLOOR_BYTES,
                PLAYBACK_WAITING_READ_AHEAD_MAX_BYTES,
            ),
        )
    } else {
        BufferBudget(
            maxReadAheadBytes = PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES,
            waitingReadAheadBytes = PLAYBACK_WAITING_READ_AHEAD_FLOOR_BYTES,
        )
    }

/**
 * 共享文件详情缓存与归档借出解析器：按账号隔离缓存近期详情与签名有效期内内容详情，避免重复触发 `leaseDetail`。
 */
internal class MediaDetailResolver(
    private val clientManager: PikoClientProvider,
    private val leaseFolderProvider: () -> (suspend (PikPakClient) -> String)?,
    private val metadataSinkProvider: () -> DriveMetadataSink?,
) {
    private class CachedFreshDetail(
        val detail: FileDetail,
        val savedAt: TimeMark,
        val ttl: Duration,
    ) {
        val isFresh: Boolean get() = savedAt.elapsedNow() <= ttl
    }

    private val freshDetails = HashMap<String, CachedFreshDetail>()
    private val freshContentDetails = LinkedHashMap<String, CachedFreshDetail>()
    private val freshDetailsLock = Mutex()
    private var freshDetailsOwner: PikPakClient? = null

    suspend fun clearIfOwnerChanged(owner: PikPakClient?) {
        freshDetailsLock.withLock {
            if (freshDetailsOwner !== owner) {
                freshDetails.clear()
                freshContentDetails.clear()
                freshDetailsOwner = owner
            }
        }
    }

    fun knownContentMetadata(fileId: String): ContentMetadata? {
        val owner = clientManager.currentClient.value ?: return null
        return metadataSinkProvider()?.lookupContentByFileId(owner, fileId)
    }

    fun knownDurationMs(fileId: String, gcid: String = "", sizeBytes: Long = 0L): Long? {
        val owner = clientManager.currentClient.value ?: return null
        val sink = metadataSinkProvider() ?: return null
        val content = sink.lookupContentByFileId(owner, fileId)
            ?: (if (gcid.isNotBlank() && sizeBytes >= 0L) sink.lookupContent(owner, gcid, sizeBytes) else null)
        return content?.durationMillis?.takeIf { it > 0L }
    }

    fun lookupCachedContent(owner: PikPakClient, fileId: String): ContentMetadata? {
        val sink = metadataSinkProvider() ?: return null
        sink.lookupContentByFileId(owner, fileId)?.let { return it }
        val leased = LeasedFile.resolvedFileOf(fileId) ?: return null
        val gcid = leased.gcid?.takeIf { it.isNotBlank() } ?: return null
        return if (leased.size >= 0L) {
            sink.lookupContent(owner, gcid, leased.size)
        } else {
            null
        }
    }

    suspend fun detailOf(client: PikPakClient, fileId: String): FileDetail {
        require(!LocalLocation.isLocal(fileId)) { "Cloud file detail requested for local fileId" }
        val observer = metadataSinkProvider()?.capture(client)
        val leased = LeasedFile.resolvedFileOf(fileId)
        val detail = if (leased == null) {
            client.getFile(fileId)
        } else {
            val folder = leaseFolderProvider() ?: error(
                tr(
                    "无法打开归档条目",
                    "Unable to open archived item",
                    "アーカイブ項目を開けません",
                    "보관 항목을 열 수 없습니다",
                    "無法開啟歸檔項目",
                ),
            )
            client.leaseDetail(leased, parentId = folder(client))
        }
        observer?.observe(fileId, detail)
        return detail
    }

    suspend fun fetchOrReuseInteractiveDetail(client: PikPakClient, fileId: String): FileDetail =
        takeFreshDetail(client, fileId)
            ?: withApiPriority(ApiRequestPriority.INTERACTIVE) { detailOf(client, fileId) }
                .also { rememberFreshDetail(client, fileId, it) }

    suspend fun takeFreshDetail(owner: PikPakClient, fileId: String): FileDetail? = freshDetailsLock.withLock {
        if (freshDetailsOwner !== owner) return@withLock null
        freshDetails[fileId]?.let { entry ->
            if (entry.isFresh) return@withLock entry.detail
            freshDetails.remove(fileId)
        }
        val leased = LeasedFile.resolvedFileOf(fileId) ?: return@withLock null
        val key = contentCacheKey(leased.gcid, leased.size) ?: return@withLock null
        val cached = freshContentDetails[key] ?: return@withLock null
        if (!cached.isFresh) {
            freshContentDetails.remove(key)
            return@withLock null
        }
        val resolvedName = leased.name.ifBlank { cached.detail.name }
        if (cached.detail.name == resolvedName) cached.detail else cached.detail.copy(name = resolvedName)
    }

    suspend fun rememberFreshDetail(owner: PikPakClient, fileId: String, detail: FileDetail) = freshDetailsLock.withLock {
        if (clientManager.currentClient.value === owner) {
            if (freshDetailsOwner !== owner) {
                freshDetails.clear()
                freshContentDetails.clear()
            }
            freshDetailsOwner = owner
            freshDetails.entries.removeAll { !it.value.isFresh }
            freshContentDetails.entries.removeAll { !it.value.isFresh }
            val mark = TimeSource.Monotonic.markNow()
            val idTtl = if (LeasedFile.isLeased(fileId)) signedDetailTtlOf(detail) else FRESH_DETAIL_FOR
            if (idTtl > Duration.ZERO) {
                freshDetails[fileId] = CachedFreshDetail(detail, mark, idTtl)
            }
            putContentFreshDetailLocked(detail, mark)
        }
    }

    suspend fun rememberFreshDetails(owner: PikPakClient, details: List<FileDetail>) = freshDetailsLock.withLock {
        if (clientManager.currentClient.value === owner) {
            if (freshDetailsOwner !== owner) {
                freshDetails.clear()
                freshContentDetails.clear()
            }
            freshDetailsOwner = owner
            freshDetails.entries.removeAll { !it.value.isFresh }
            freshContentDetails.entries.removeAll { !it.value.isFresh }
            val mark = TimeSource.Monotonic.markNow()
            for (detail in details) {
                freshDetails[detail.id] = CachedFreshDetail(detail, mark, FRESH_DETAIL_FOR)
                putContentFreshDetailLocked(detail, mark)
            }
        }
    }

    fun durationMsOf(detail: FileDetail): Long =
        detail.params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it > 0L }
            ?: detail.medias.firstOrNull { it.isOrigin }?.video?.duration?.takeIf { it > 0L }?.times(1000L)
            ?: detail.medias.firstNotNullOfOrNull { it.video?.duration?.takeIf { d -> d > 0L } }?.times(1000L)
            ?: 0L

    fun estimatedMediaBytesPerSecond(
        streamSize: Long,
        durationSeconds: Long,
        bitRateBitsPerSecond: Long? = null,
    ): Long? {
        val bySize = if (streamSize > 0L && durationSeconds > 0L) streamSize / durationSeconds else null
        val byBitRate = bitRateBitsPerSecond?.takeIf { it > 0L }?.let { it / 8L }
        return maxOf(bySize ?: 0L, byBitRate ?: 0L).takeIf { it > 0L }
    }

    private fun contentCacheKey(gcid: String?, sizeBytes: Long): String? {
        val clean = gcid?.trim().orEmpty()
        if (clean.isEmpty() || sizeBytes < 0L) return null
        return "${clean.uppercase()}:$sizeBytes"
    }

    private fun signedDetailTtlOf(detail: FileDetail): Duration {
        val expiries = buildList {
            detail.links.values.forEach { link -> if (link.url.isNotBlank()) link.expiresAt?.let(::add) }
            detail.medias.forEach { media -> if (media.link.url.isNotBlank()) media.link.expiresAt?.let(::add) }
        }
        val earliest = expiries.minOrNull() ?: return FRESH_DETAIL_FOR
        val remaining = earliest - Clock.System.now() - SIGNED_DETAIL_EXPIRY_MARGIN
        return remaining.coerceAtMost(MAX_SIGNED_DETAIL_TTL)
    }

    private fun putContentFreshDetailLocked(detail: FileDetail, mark: TimeMark) {
        val key = contentCacheKey(detail.hash, detail.sizeBytes) ?: return
        val ttl = signedDetailTtlOf(detail)
        if (ttl <= Duration.ZERO) {
            freshContentDetails.remove(key)
            return
        }
        freshContentDetails.remove(key)
        while (freshContentDetails.size >= MAX_CACHED_CONTENT_DETAILS) {
            val eldest = freshContentDetails.keys.firstOrNull() ?: break
            freshContentDetails.remove(eldest)
        }
        freshContentDetails[key] = CachedFreshDetail(detail, mark, ttl)
    }

    companion object {
        private val FRESH_DETAIL_FOR = 5.minutes
        private val SIGNED_DETAIL_EXPIRY_MARGIN = 15.minutes
        private val MAX_SIGNED_DETAIL_TTL = 23.hours + 45.minutes
        private const val MAX_CACHED_CONTENT_DETAILS = 256
    }
}

internal class PlaybackSessionPreparer(
    private val requireClient: () -> PikPakClient,
    private val currentClientOrNull: () -> PikPakClient?,
    private val localMountsProvider: () -> LocalMounts?,
    private val metadataSinkProvider: () -> DriveMetadataSink?,
    private val fileCachePoolProvider: () -> PikoFileCachePool?,
    private val partialDownloadProvider: () -> ((String) -> DownloadTask?)?,
    private val proxy: PikoMediaProxy,
    private val backgroundScope: CoroutineScope,
    private val detailResolver: MediaDetailResolver,
    private val qualityProber: TranscodeQualityProber,
    private val playbackCacheEnabled: () -> Boolean = { true },
) {
    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = requireClient()
                val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
                val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                playableMediaInfo(detail, resolved, fileId)
            }
        }

    suspend fun fetchDownloadUrl(fileId: String): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val detail = detailResolver.fetchOrReuseInteractiveDetail(requireClient(), fileId)
                detail.downloadUrl?.takeIf { it.isNotBlank() } ?: error(
                    tr(
                        "未获取到下载链接",
                        "Download link unavailable",
                        "ダウンロードリンクを取得できません",
                        "다운로드 링크를 가져올 수 없습니다",
                        "無法取得下載連結",
                    ),
                )
            }
        }

    suspend fun readDocumentBytes(
        fileId: String,
        maxBytes: Int,
        localPath: String? = null,
    ): Result<DocumentByteResult> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            require(maxBytes > 0) { "maxBytes must be positive" }
            if (!localPath.isNullOrBlank()) {
                DocumentTextDecoder.readLocalBounded(localPath, maxBytes)?.let { (bytes, total) ->
                    return@runSuspendCatching DocumentByteResult(
                        bytes = bytes,
                        totalBytes = total,
                        truncated = total > bytes.size.toLong(),
                    )
                }
            }
            if (LocalLocation.isLocal(fileId)) {
                val mounts = localMountsProvider()
                val read = mounts?.readBytes(fileId, maxBytes)
                    ?: mounts?.resolve(fileId)?.let { DocumentTextDecoder.readLocalBounded(it, maxBytes) }
                    ?: error(
                        tr(
                            "本机文件夹不可访问",
                            "Local folder is unavailable",
                            "ローカルフォルダーにアクセスできません",
                            "로컬 폴더에 액세스할 수 없습니다",
                            "本機資料夾無法存取",
                        ),
                    )
                return@runSuspendCatching DocumentByteResult(
                    bytes = read.first,
                    totalBytes = read.second,
                    truncated = read.second > read.first.size.toLong(),
                )
            }
            val accountClient = requireClient()
            val detail = detailResolver.fetchOrReuseInteractiveDetail(accountClient, fileId)
            val url = detail.downloadUrl?.takeIf { it.isNotBlank() }
                ?: detail.links.values.firstOrNull { it.url.isNotBlank() }?.url
                ?: detail.webContentLink.takeIf { it.isNotBlank() }
                ?: error(
                    tr(
                        "未获取到下载链接",
                        "Download link unavailable",
                        "ダウンロードリンクを取得できません",
                        "다운로드 링크를 가져올 수 없습니다",
                        "無法取得下載連結",
                    ),
                )
            val knownSize = detail.sizeBytes
            if (knownSize == 0L) {
                return@runSuspendCatching DocumentByteResult(
                    bytes = ByteArray(0),
                    totalBytes = 0L,
                    truncated = false,
                )
            }
            val requestLen = if (knownSize > 0L) minOf(knownSize, maxBytes.toLong()) else maxBytes.toLong()
            accountClient.streamRangeFromUrl(url, start = 0L, length = requestLen) { stream ->
                val cap = requestLen.toInt().coerceAtMost(maxBytes)
                val chunks = ArrayList<ByteArray>()
                val buf = ByteArray(minOf(cap, 64 * 1024).coerceAtLeast(1))
                var totalRead = 0
                while (totalRead < cap) {
                    val toRead = minOf(buf.size, cap - totalRead)
                    val n = stream.channel.readAvailable(buf, 0, toRead)
                    if (n <= 0) break
                    chunks += buf.copyOf(n)
                    totalRead += n
                }
                val out = ByteArray(totalRead)
                var pos = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, pos)
                    pos += chunk.size
                }
                val totalSize = stream.totalSize?.takeIf { it >= totalRead.toLong() }
                    ?: knownSize.takeIf { it >= totalRead.toLong() }
                    ?: totalRead.toLong()
                DocumentByteResult(
                    bytes = out,
                    totalBytes = totalSize,
                    truncated = totalSize > out.size.toLong(),
                )
            }
        }
    }

    suspend fun preparePlayback(
        fileId: String,
        preferredResolution: String? = null,
        defaultMaxHeight: Int = 0,
        startPositionMillis: Long = 0L,
    ): Result<PreparedPlayback> {
        val accountClient = requireClient()
        val initialBudget = playbackBufferBudget()
        val owner = accountClient.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = initialBudget,
            ),
        )
        var created: PreparedPlayback? = null
        try {
            return withContext(owner) {
                preparePlaybackOwned(accountClient, owner, fileId, preferredResolution, defaultMaxHeight, startPositionMillis)
                    .onSuccess { created = it }
            }.onFailure { owner.close() }
        } catch (e: Throwable) {
            created?.close()
            owner.close()
            throw e
        }
    }

    private suspend fun preparePlaybackOwned(
        client: PikPakClient,
        owner: TransferDemand,
        fileId: String,
        preferredResolution: String?,
        defaultMaxHeight: Int,
        startPositionMillis: Long,
    ): Result<PreparedPlayback> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (preferredResolution.isNullOrBlank() || preferredResolution == ORIGINAL_QUALITY) {
                    partialSource(client, fileId)?.let { (task, source) ->
                        val fileLabel = logFile(fileId, task.fileName)
                        PikoLog.d(MEDIA_LOG_TAG, "Reusing partial download cache for playback: $fileLabel, held=${task.downloadedBytes}/${task.totalBytes} B")
                        val info = PlayableMediaInfo(
                            fileId = fileId,
                            name = task.displayName.substringAfterLast('/'),
                            gcid = task.gcid,
                            currentUrl = "",
                            durationSeconds = 0,
                            availableVariants = emptyList(),
                            currentResolution = ORIGINAL_QUALITY,
                            sizeBytes = task.totalBytes,
                            originalSizeBytes = task.totalBytes,
                        )
                        val stream = registerProxy(source, task.fileName.substringAfterLast('/'), fileLabel, owner)
                            ?: error(
                                tr(
                                    "无法创建本地播放会话",
                                    "Unable to create local playback session",
                                    "ローカル再生セッションを作成できません",
                                    "로컬 재생 세션을 만들 수 없습니다",
                                    "無法建立本機播放工作階段",
                                ),
                            )
                        val partialBudget = playbackBufferBudget()
                        owner.updateIntent { it.copy(bufferBudget = partialBudget) }
                        return@runSuspendCatching PreparedPlayback(info, stream, defaultBufferBudget = partialBudget, demand = owner, client = client)
                    }
                }
                val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
                val resolution = preferredResolution
                    ?: defaultMaxHeight.takeIf { it > 0 }?.let { detail.medias.transcodeNameAtMost(it) }
                val resolved = detail.resolveVariant(preferenceFor(resolution))
                val info = playableMediaInfo(detail, resolved, fileId)
                val leased = LeasedFile.isLeased(fileId)
                val stream = if (info.kind == PlayableMediaKind.Video || info.kind == PlayableMediaKind.Audio) {
                    openProxyStream(client, detail, resolved, leased = leased)
                } else {
                    null
                }
                val actualStreamBytes = stream?.size ?: resolved.sizeBytes ?: detail.sizeBytes.takeIf { resolved.isOrigin } ?: 0L
                val resolvedInfo = if (actualStreamBytes > 0L && info.sizeBytes != actualStreamBytes) {
                    info.copy(sizeBytes = actualStreamBytes)
                } else {
                    info
                }
                val durationSeconds = resolved.video?.duration?.takeIf { it > 0L } ?: (detailResolver.durationMsOf(detail) / 1000L)
                val estimatedRate = detailResolver.estimatedMediaBytesPerSecond(
                    streamSize = actualStreamBytes,
                    durationSeconds = durationSeconds,
                    bitRateBitsPerSecond = resolved.video?.bitRate,
                )
                val defaultBudget = playbackBufferBudget(estimatedRate)
                owner.updateIntent { it.copy(bufferBudget = defaultBudget) }
                val prepared = PreparedPlayback(resolvedInfo, stream, estimatedRate, defaultBudget, owner, client = client)
                if (stream != null && stream.size > 0L) {
                    val prewarmRanges = playbackPrewarmRanges(
                        streamSize = stream.size,
                        isOrigin = resolved.isOrigin,
                        startPositionMillis = startPositionMillis,
                        durationMillis = durationSeconds * 1000L,
                    )
                    val prewarm = backgroundScope.launch {
                        runCatching { stream.prefetch(prewarmRanges, StreamRole.FOREGROUND, PikPakStreamReader.BLOCKING_PRIORITY) }
                            .onFailure {
                                if (it !is CancellationException) {
                                    PikoLog.d(MEDIA_LOG_TAG, "Playback prewarm skipped: ${logFile(fileId, detail.name)}, cause=${it::class.simpleName}: ${it.message}")
                                }
                            }
                    }
                    prepared.attachPrewarmJob(prewarm)
                }
                prepared
            }
        }

    fun playbackPrewarmRanges(
        streamSize: Long,
        isOrigin: Boolean,
        startPositionMillis: Long,
        durationMillis: Long,
    ): List<LongRange> {
        if (streamSize <= 0L) return emptyList()
        val blockBytes = 256L * 1024L
        if (isOrigin || startPositionMillis <= 0L) {
            return listOf(0L..(minOf(streamSize, blockBytes) - 1L))
        }
        val rawRanges = buildList {
            add(0L..(minOf(streamSize, 2L * blockBytes) - 1L))
            val tailStart = ((streamSize - blockBytes).coerceAtLeast(0L) / blockBytes) * blockBytes
            add(tailStart..(streamSize - 1L))
            if (durationMillis > 0L && startPositionMillis < durationMillis) {
                val estByte = ((streamSize.toDouble() * startPositionMillis.toDouble() / durationMillis.toDouble()).toLong())
                    .coerceIn(0L, streamSize - 1L)
                val midStart = (estByte / blockBytes) * blockBytes
                val midEnd = minOf(streamSize, midStart + blockBytes) - 1L
                add(midStart..midEnd)
            }
        }.sortedBy { it.first }
        val merged = ArrayList<LongRange>(rawRanges.size)
        for (range in rawRanges) {
            val prev = merged.lastOrNull()
            if (prev != null && range.first <= prev.last + 1L) {
                merged[merged.lastIndex] = prev.first..maxOf(prev.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    suspend fun openRandomAccess(
        fileId: String,
        retainedBy: String? = null,
        mediaId: String? = null,
    ): Result<RandomAccessMediaSource> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = requireClient()
                if (mediaId != null) return@runSuspendCatching ReaderRandomAccessSource(qualityProber.transcodeSource(client, fileId, mediaId, retainedBy))
                partialSource(client, fileId, retainedBy)?.let { return@runSuspendCatching ReaderRandomAccessSource(it.second) }
                val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
                if (detail.hash.isBlank()) {
                    error(
                        tr(
                            "文件缺少内容哈希，无法读取",
                            "File is missing content hash and cannot be read",
                            "ファイルにハッシュ情報がないため読み込めません",
                            "파일에 콘텐츠 해시가 없어 읽을 수 없습니다",
                            "檔案缺少內容雜湊值，無法讀取",
                        ),
                    )
                }
                val resolved = detail.resolveVariant(VariantPreference.Original)
                val source = openByteSource(client, detail, resolved, leased = LeasedFile.isLeased(fileId), retainedBy = retainedBy)
                    ?: error(
                        tr(
                            "无法打开媒体数据流",
                            "Unable to open media stream",
                            "メディアストリームを開けません",
                            "미디어 스트림을 열 수 없습니다",
                            "無法開啟媒體資料流",
                        ),
                    )
                ReaderRandomAccessSource(source)
            }
        }

    suspend fun cachedRenditionCoverage(
        info: PlayableMediaInfo,
        positionMillis: Long,
        durationMillis: Long,
    ): Pair<Map<String, Float>, Set<String>> {
        val pool = fileCachePoolProvider() ?: return emptyMap<String, Float>() to emptySet()
        val owner = currentClientOrNull() ?: return emptyMap<String, Float>() to emptySet()
        val account = owner.account
        val gcid = info.gcid.takeIf { it.isNotBlank() } ?: return emptyMap<String, Float>() to emptySet()
        val contentSizeBytes = info.originalSizeBytes.takeIf { it > 0L } ?: info.sizeBytes
        val sink = metadataSinkProvider()
        val content = sink?.lookupContentByFileId(owner, info.fileId)
            ?: sink?.lookupContent(owner, gcid, contentSizeBytes)
        val transcodeSizes = content?.transcodes?.associate { it.mediaId to it.streamSizeBytes }.orEmpty()
        val freshDetail = detailResolver.takeFreshDetail(owner, info.fileId)
        val origTotal = freshDetail?.sizeBytes?.takeIf { it > 0L }
            ?: info.originalSizeBytes.takeIf { it > 0L }
            ?: info.sizeBytes.takeIf { info.isOrigin && it > 0L }
            ?: 0L

        val coverage = LinkedHashMap<String, Float>()
        val windowCached = LinkedHashSet<String>()
        val posFraction = if (durationMillis > 0L) (positionMillis.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 0.99) else 0.0

        suspend fun probeOne(keyName: String, mediaId: String?, totalBytes: Long) {
            if (totalBytes <= 0L) return
            val windowStart = (totalBytes.toDouble() * posFraction).toLong().coerceIn(0L, (totalBytes - 1L).coerceAtLeast(0L))
            val windowLen = minOf(ABR_CACHE_WINDOW_BYTES, (totalBytes - windowStart).coerceAtLeast(1L))
            val stats = pool.cachedCoverage(
                account = account,
                gcid = gcid,
                totalBytes = totalBytes,
                mediaId = mediaId,
                windowStartByte = windowStart,
                windowLengthBytes = windowLen,
            )
            if (stats.coverageRatio > 0f) {
                if (mediaId != null) coverage[mediaId] = stats.coverageRatio
                coverage[keyName] = stats.coverageRatio
            }
            if (stats.windowCached) {
                if (mediaId != null) windowCached += mediaId
                windowCached += keyName
            }
        }

        if (origTotal > 0L) {
            probeOne(ORIGINAL_QUALITY, null, origTotal)
        }
        for (variant in info.availableVariants) {
            if (variant.isOrigin || variant.video == null || variant.mediaId.isBlank()) continue
            val name = variant.qualityName ?: continue
            val size = transcodeSizes[variant.mediaId]?.takeIf { it > 0L }
                ?: info.sizeBytes.takeIf { !info.isOrigin && info.mediaId == variant.mediaId && it > 0L }
                ?: continue
            probeOne(name, variant.mediaId, size)
        }
        return coverage to windowCached
    }

    suspend fun openProxyStream(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
        leased: Boolean = false,
        fastReactivation: Boolean = false,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
    ): ProxyStream? {
        val source = openByteSource(client, detail, resolved, leased = leased, fastReactivation = fastReactivation) ?: return null
        onByteSource?.invoke(source)
        return registerProxy(
            source = source,
            fileName = if (resolved.isOrigin) detail.name else "stream.ts",
            fileLabel = logFile(detail.id, detail.name),
        )
    }

    suspend fun registerProxy(
        source: ProxyByteSource,
        fileName: String?,
        fileLabel: String,
        demand: TransferDemand? = null,
    ): ProxyStream? {
        val owner = demand ?: currentCoroutineContext()[TransferDemand]
        return try {
            proxy.register(source, fileName = fileName, demand = owner)
        } catch (e: CancellationException) {
            source.close()
            throw e
        } catch (e: Exception) {
            PikoLog.w(MEDIA_LOG_TAG, "Failed to register loopback proxy session: $fileLabel", e)
            source.close()
            null
        }
    }

    private suspend fun partialSource(
        client: PikPakClient,
        fileId: String,
        retainedBy: String? = null,
    ): Pair<DownloadTask, PikPakByteSource>? {
        val task = partialDownloadProvider()?.invoke(fileId) ?: return null
        val pool = fileCachePoolProvider() ?: return null
        if (task.totalBytes <= 0 || task.gcid.isBlank() || !task.sparseCache) return null
        val lease = pool.acquire(
            client = client,
            fileId = task.fileId,
            gcid = task.gcid,
            size = task.totalBytes,
            name = task.fileName,
            parentId = task.parentId,
            leased = task.leasedSource || LeasedFile.isLeased(fileId),
            retained = retainedBy != null,
            owner = retainedBy ?: task.fileId,
        )
        return task to PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
    }

    suspend fun openByteSource(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
        blockStore: BlockStore? = null,
        leased: Boolean = false,
        retainedBy: String? = null,
        fastReactivation: Boolean = false,
    ): PikPakByteSource? {
        if (detail.hash.isBlank()) return null
        val pool = fileCachePoolProvider()
        val knownStreamSize = if (resolved.isOrigin) {
            detail.sizeBytes
        } else {
            resolved.sizeBytes?.takeIf { it > 0L }
                ?: resolved.mediaId?.let { qualityProber.knownTranscodeStreamSize(client, detail, it) }
                ?: if (blockStore == null && !fastReactivation && pool != null && resolved.mediaId != null) {
                    runSuspendCatching { qualityProber.streamSizeOf(client, detail, resolved.mediaId!!) }.getOrNull()
                } else {
                    null
                }
        }
        val mediaRate = detailResolver.estimatedMediaBytesPerSecond(
            streamSize = knownStreamSize ?: detail.sizeBytes.takeIf { resolved.isOrigin } ?: 0L,
            durationSeconds = resolved.video?.duration ?: (detailResolver.durationMsOf(detail) / 1000L),
            bitRateBitsPerSecond = resolved.video?.bitRate,
        )
        if (blockStore == null && !fastReactivation && knownStreamSize != null && knownStreamSize > 0L && pool != null) {
            val lease = pool.acquire(
                client = client,
                fileId = detail.id,
                gcid = detail.hash,
                size = detail.sizeBytes,
                name = detail.name,
                parentId = detail.parentId,
                leased = leased,
                detail = detail,
                retained = retainedBy != null,
                owner = retainedBy ?: detail.id,
                mediaId = if (resolved.isOrigin) null else resolved.mediaId,
                streamSize = knownStreamSize,
                mediaBytesPerSecond = mediaRate,
                retainForPlayback = retainedBy == null && playbackCacheEnabled(),
                qualityLabel = if (resolved.isOrigin) "Original" else resolved.video?.height?.let { "${it}P" } ?: resolved.mediaId.orEmpty(),
            )
            return PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
        }
        val handle = if (fastReactivation) {
            client.fileHandle(
                detail,
                mediaId = resolved.mediaId,
                streamSize = knownStreamSize,
                onRangeAttempt = ::logRangeAttempt,
                leased = leased,
                reactivationPollInterval = FAST_REACTIVATION_POLL_INTERVAL,
                reactivationTimeout = FAST_REACTIVATION_TIMEOUT,
            )
        } else {
            client.fileHandle(
                detail,
                mediaId = resolved.mediaId,
                streamSize = knownStreamSize,
                onRangeAttempt = ::logRangeAttempt,
                leased = leased,
            )
        }
        return try {
            val cache = handle.openCache(blockStore, proxy.readerContext)
            if (!resolved.isOrigin && resolved.mediaId != null && cache.size > 0L) {
                qualityProber.recordTranscodeStreamSize(client, detail, resolved.mediaId!!, cache.size, unreadable = false)
            }
            val effectiveRate = detailResolver.estimatedMediaBytesPerSecond(
                streamSize = cache.size,
                durationSeconds = resolved.video?.duration ?: (detailResolver.durationMsOf(detail) / 1000L),
                bitRateBitsPerSecond = resolved.video?.bitRate,
            ) ?: mediaRate
            if (effectiveRate != null) cache.updateMediaBytesPerSecond(effectiveRate)
            PikPakByteSource(handle, cache)
        } catch (e: CancellationException) {
            handle.close()
            throw e
        } catch (e: Exception) {
            val variantLabel = if (resolved.isOrigin) ORIGINAL_QUALITY else (resolved.mediaId ?: "transcode")
            PikoLog.w(MEDIA_LOG_TAG, "Failed to open byte source: ${logFile(detail.id, detail.name)}, variant=$variantLabel", e)
            handle.close()
            null
        }
    }

    fun playableMediaInfo(detail: FileDetail, resolved: ResolvedVariant, fileId: String) =
        PlayableMediaInfo(
            fileId = fileId,
            name = detail.name,
            gcid = detail.hash,
            currentUrl = resolved.link.url,
            durationSeconds = resolved.video?.duration?.takeIf { it > 0L } ?: (detailResolver.durationMsOf(detail) / 1000L),
            currentResolution = resolved.video?.height?.toString() ?: ORIGINAL_QUALITY,
            availableVariants = detail.medias,
            mediaId = resolved.mediaId,
            sizeBytes = resolved.sizeBytes ?: detail.sizeBytes,
            width = resolved.video?.width?.takeIf { it > 0 } ?: detail.params["width"]?.toIntOrNull() ?: 0,
            height = resolved.video?.height?.takeIf { it > 0 } ?: detail.params["height"]?.toIntOrNull() ?: 0,
            isOrigin = resolved.isOrigin,
            originalSizeBytes = detail.sizeBytes,
        )

    private fun preferenceFor(resolution: String?): VariantPreference =
        if (resolution.isNullOrBlank() || resolution == ORIGINAL_QUALITY || resolution == AUTO_QUALITY) {
            VariantPreference.Original
        } else {
            VariantPreference.Resolution(resolution)
        }

    companion object {
        internal val FAST_REACTIVATION_POLL_INTERVAL = 1.seconds
        internal val FAST_REACTIVATION_TIMEOUT = 1_500.milliseconds
    }
}
