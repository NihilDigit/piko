@file:JvmMultifileClass
@file:JvmName("PikoMediaRepositoryKt")

package dev.piko.shared.media

import androidx.compose.runtime.Stable
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.download.DownloadTask
import dev.piko.shared.data.ContentMetadata
import dev.piko.shared.data.DriveMetadataSink
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMounts
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.player.PlayHistoryReporter
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyStream
import dev.piko.shared.media.reader.DocumentByteResult
import dev.piko.shared.media.storyboard.StoryboardSource
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.listPlayHistory
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Stable
interface MediaPlaybackService {
    val loopbackProxy: PikoMediaProxy
    val playHistoryReporter: PlayHistoryReporter

    fun currentAccountClient(): PikPakClient?
    suspend fun localPath(fileId: String, subtitle: Boolean = false): String?
    fun knownContentMetadata(fileId: String): ContentMetadata?
    fun knownDurationMs(fileId: String, gcid: String = "", sizeBytes: Long = 0L): Long?

    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo>
    suspend fun fetchDownloadUrl(fileId: String): Result<String>
    suspend fun readDocumentBytes(fileId: String, maxBytes: Int, localPath: String? = null): Result<DocumentByteResult>
    fun openTransferDemand(initialIntent: ConsumerIntent = ConsumerIntent()): TransferDemand
    suspend fun preparePlayback(
        fileId: String,
        preferredResolution: String? = null,
        defaultMaxHeight: Int = 0,
        startPositionMillis: Long = 0L,
    ): Result<PreparedPlayback>
    suspend fun cachedRenditionCoverage(
        info: PlayableMediaInfo,
        positionMillis: Long,
        durationMillis: Long,
    ): Pair<Map<String, Float>, Set<String>>

    fun enqueuePlayHistory(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        urgent: Boolean = false,
        title: String = "",
        client: PikPakClient? = null,
    ): Boolean
    suspend fun reportPlay(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        urgent: Boolean = true,
        title: String = "",
        client: PikPakClient? = null,
    )
    suspend fun flushPlayHistory(timeoutMillis: Long = PlayHistoryReporter.DEFAULT_FLUSH_TIMEOUT_MILLIS)
    suspend fun cloudPlaybackPosition(fileId: String): Long?
    suspend fun savePlaybackPosition(fileId: String, positionMillis: Long)
    fun savePlaybackPositionAsync(fileId: String, positionMillis: Long)
    suspend fun getPlaybackPosition(fileId: String): Long
}

@Stable
interface MediaVariantProber {
    suspend fun openRandomAccess(fileId: String, retainedBy: String? = null, mediaId: String? = null): Result<RandomAccessMediaSource>
    fun knownUnreadableTranscodeIds(fileId: String, gcid: String, sizeBytes: Long): Set<String>
    fun knownTranscodeStreamSizeFor(fileId: String, gcid: String, sizeBytes: Long, mediaId: String): Long?
    fun downloadQualities(fileId: String): Flow<List<DownloadQuality>>
    fun prefetchDownloadQualities(fileId: String)
    suspend fun downloadVariant(fileId: String, name: String?, maxHeight: Int): Result<DownloadQuality?>
}

@Stable
interface SubtitleSourceService {
    suspend fun prepareSubtitle(fileId: String): ProxyStream?
    suspend fun prepareExternalSubtitle(fileId: String, knownLanguage: String? = null): PreparedSubtitle?
    suspend fun prepareOriginalEmbeddedSubtitles(fileId: String): PreparedOriginalSubtitles
}

@Stable
interface ClipMediaService {
    suspend fun prepareClip(
        fileId: String,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole = StreamRole.BACKGROUND,
        demand: TransferDemand? = null,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
    ): Result<PreparedClip>
    suspend fun clipProbe(fileId: String, urgent: Boolean = false): ClipProbe
    suspend fun batchClipProbe(fileIds: List<String>, urgent: Boolean = false): Map<String, ClipProbe>
    fun openStoryboardSession(source: StoryboardSource): StoryboardTransportSession
    suspend fun resolveStoryboardStreamSize(source: StoryboardSource): Long?
    suspend fun fetchStoryboardRange(source: StoryboardSource, streamSize: Long, offset: Long, length: Int): ByteArray?
    suspend fun fetchPreviewChunk(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        chunkBytes: Int = DEFAULT_PREVIEW_CHUNK_BYTES,
    ): ByteArray?
}

@Stable
interface MediaService : MediaPlaybackService, MediaVariantProber, SubtitleSourceService, ClipMediaService

@Stable
class PikoMediaRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
    /** 随机片段切片的磁盘缓存，见 [ClipCache]。没有就每次都从网上取。 */
    clipCache: ClipCache? = null,
) : MediaService {
    private val client: PikPakClient get() = clientManager.currentClient.value ?: error("Not logged in")

    override fun currentAccountClient(): PikPakClient? = clientManager.currentClient.value

    /** 打开归档条目时借用的文件对象放在哪个目录，返回它的 ID。 */
    var leaseFolder: (suspend (PikPakClient) -> String)? = null
    var localMounts: LocalMounts? = null
    var metadataSink: DriveMetadataSink? = null
    override suspend fun localPath(fileId: String, subtitle: Boolean): String? = localMounts?.resolve(fileId, subtitle)

    /** 下载调度器提供；原画播放与完整下载共用缓存，转码片段仍用 ClipCache。 */
    var fileCachePool: PikoFileCachePool? = null
    var partialDownload: ((fileId: String) -> DownloadTask?)? = null

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val loopbackProxy: PikoMediaProxy get() = sharedProxy

    /** 跨播放器窗口生命周期的云端播放历史上报队列，负责同文件冷却合并、账号捕获与失败退避重试。 */
    override val playHistoryReporter: PlayHistoryReporter = PlayHistoryReporter(
        scope = backgroundScope,
        isPlayHistorySynced = ::isPlayHistorySynced,
    )

    private sealed interface LocalPositionOp {
        data class Save(val key: String, val positionMillis: Long) : LocalPositionOp
        data class Flush(val done: CompletableDeferred<Unit>) : LocalPositionOp
    }

    private val localPositionOps = Channel<LocalPositionOp>(Channel.UNLIMITED)

    private val detailResolver = MediaDetailResolver(
        clientManager = clientManager,
        leaseFolderProvider = { leaseFolder },
        metadataSinkProvider = { metadataSink },
    )

    private val qualityProber = TranscodeQualityProber(
        requireClient = { client },
        currentClientOrNull = { clientManager.currentClient.value },
        metadataSinkProvider = { metadataSink },
        fileCachePoolProvider = { fileCachePool },
        proxy = sharedProxy,
        backgroundScope = backgroundScope,
        detailResolver = detailResolver,
    )

    private val sessionPreparer = PlaybackSessionPreparer(
        requireClient = { client },
        currentClientOrNull = { clientManager.currentClient.value },
        localMountsProvider = { localMounts },
        metadataSinkProvider = { metadataSink },
        fileCachePoolProvider = { fileCachePool },
        partialDownloadProvider = { partialDownload },
        proxy = sharedProxy,
        backgroundScope = backgroundScope,
        detailResolver = detailResolver,
        qualityProber = qualityProber,
    )

    private val subtitleService = MediaSubtitleService(
        requireClient = { client },
        currentClientOrNull = { clientManager.currentClient.value },
        fileCachePoolProvider = { fileCachePool },
        detailResolver = detailResolver,
        sessionPreparer = sessionPreparer,
    )

    private val clipAndStoryboard = ClipAndStoryboardMediaSource(
        requireClient = { client },
        currentClientOrNull = { clientManager.currentClient.value },
        localMountsProvider = { localMounts },
        metadataSinkProvider = { metadataSink },
        fileCachePoolProvider = { fileCachePool },
        leaseFolderProvider = { leaseFolder },
        clipCache = clipCache,
        proxy = sharedProxy,
        backgroundScope = backgroundScope,
        detailResolver = detailResolver,
        qualityProber = qualityProber,
        sessionPreparer = sessionPreparer,
    )

    init {
        backgroundScope.launch {
            for (op in localPositionOps) {
                when (op) {
                    is LocalPositionOp.Save -> {
                        runSuspendCatching {
                            preferences?.savePlaybackPosition(op.key, op.positionMillis)
                        }.logFailure(MEDIA_LOG_TAG, "Failed to save local playback position: ${logFile(op.key, "")}")
                    }
                    is LocalPositionOp.Flush -> op.done.complete(Unit)
                }
            }
        }
        backgroundScope.launch {
            clientManager.currentClient.collect { owner ->
                detailResolver.clearIfOwnerChanged(owner)
                subtitleService.clearIfOwnerChanged(owner)
            }
        }
    }

    override fun knownContentMetadata(fileId: String): ContentMetadata? =
        detailResolver.knownContentMetadata(fileId)

    override fun knownDurationMs(fileId: String, gcid: String, sizeBytes: Long): Long? =
        detailResolver.knownDurationMs(fileId, gcid, sizeBytes)

    override suspend fun prepareMedia(fileId: String, preferredResolution: String?): Result<PlayableMediaInfo> =
        sessionPreparer.prepareMedia(fileId, preferredResolution)

    override suspend fun fetchDownloadUrl(fileId: String): Result<String> =
        sessionPreparer.fetchDownloadUrl(fileId)

    override suspend fun readDocumentBytes(fileId: String, maxBytes: Int, localPath: String?): Result<DocumentByteResult> =
        sessionPreparer.readDocumentBytes(fileId, maxBytes, localPath)

    override fun openTransferDemand(initialIntent: ConsumerIntent): TransferDemand =
        client.openTransferDemand(initialIntent)

    override suspend fun preparePlayback(
        fileId: String,
        preferredResolution: String?,
        defaultMaxHeight: Int,
        startPositionMillis: Long,
    ): Result<PreparedPlayback> =
        sessionPreparer.preparePlayback(fileId, preferredResolution, defaultMaxHeight, startPositionMillis)

    internal fun playbackPrewarmRanges(
        streamSize: Long,
        isOrigin: Boolean,
        startPositionMillis: Long,
        durationMillis: Long,
    ): List<LongRange> =
        sessionPreparer.playbackPrewarmRanges(streamSize, isOrigin, startPositionMillis, durationMillis)

    override suspend fun openRandomAccess(fileId: String, retainedBy: String?, mediaId: String?): Result<RandomAccessMediaSource> =
        sessionPreparer.openRandomAccess(fileId, retainedBy, mediaId)

    override suspend fun cachedRenditionCoverage(
        info: PlayableMediaInfo,
        positionMillis: Long,
        durationMillis: Long,
    ): Pair<Map<String, Float>, Set<String>> =
        sessionPreparer.cachedRenditionCoverage(info, positionMillis, durationMillis)

    override fun knownUnreadableTranscodeIds(fileId: String, gcid: String, sizeBytes: Long): Set<String> =
        qualityProber.knownUnreadableTranscodeIds(fileId, gcid, sizeBytes)

    override fun knownTranscodeStreamSizeFor(fileId: String, gcid: String, sizeBytes: Long, mediaId: String): Long? =
        qualityProber.knownTranscodeStreamSizeFor(fileId, gcid, sizeBytes, mediaId)

    override fun downloadQualities(fileId: String): Flow<List<DownloadQuality>> =
        qualityProber.downloadQualities(fileId)

    override fun prefetchDownloadQualities(fileId: String) =
        qualityProber.prefetchDownloadQualities(fileId)

    override suspend fun downloadVariant(fileId: String, name: String?, maxHeight: Int): Result<DownloadQuality?> =
        qualityProber.downloadVariant(fileId, name, maxHeight)

    override suspend fun prepareSubtitle(fileId: String): ProxyStream? =
        subtitleService.prepareSubtitle(fileId)

    override suspend fun prepareExternalSubtitle(fileId: String, knownLanguage: String?): PreparedSubtitle? =
        subtitleService.prepareExternalSubtitle(fileId, knownLanguage)

    override suspend fun prepareOriginalEmbeddedSubtitles(fileId: String): PreparedOriginalSubtitles =
        subtitleService.prepareOriginalEmbeddedSubtitles(fileId)

    override suspend fun prepareClip(
        fileId: String,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole,
        demand: TransferDemand?,
        onByteSource: ((ProxyByteSource) -> Unit)?,
    ): Result<PreparedClip> =
        clipAndStoryboard.prepareClip(fileId, startMs, videoDurationMs, role, demand, onByteSource)

    override suspend fun clipProbe(fileId: String, urgent: Boolean): ClipProbe =
        clipAndStoryboard.clipProbe(fileId, urgent)

    override suspend fun batchClipProbe(fileIds: List<String>, urgent: Boolean): Map<String, ClipProbe> =
        clipAndStoryboard.batchClipProbe(fileIds, urgent)

    override fun openStoryboardSession(source: StoryboardSource): StoryboardTransportSession =
        clipAndStoryboard.openStoryboardSession(source)

    override suspend fun resolveStoryboardStreamSize(source: StoryboardSource): Long? =
        clipAndStoryboard.resolveStoryboardStreamSize(source)

    override suspend fun fetchStoryboardRange(source: StoryboardSource, streamSize: Long, offset: Long, length: Int): ByteArray? =
        clipAndStoryboard.fetchStoryboardRange(source, streamSize, offset, length)

    override suspend fun fetchPreviewChunk(fileId: String, positionMillis: Long, durationMillis: Long, chunkBytes: Int): ByteArray? =
        clipAndStoryboard.fetchPreviewChunk(fileId, positionMillis, durationMillis, chunkBytes)

    private suspend fun isPlayHistorySynced(): Boolean = preferences?.syncPlayHistoryFlow?.first() ?: false

    override fun enqueuePlayHistory(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        urgent: Boolean,
        title: String,
        client: PikPakClient?,
    ): Boolean {
        val owner = client ?: clientManager.currentClient.value
        if (owner == null) {
            PikoLog.d(MEDIA_LOG_TAG, "Skipped cloud play history report (not logged in): ${logFile(fileId, title)}")
            return false
        }
        return playHistoryReporter.submit(
            client = owner,
            fileId = fileId,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            urgent = urgent,
            title = title,
        )
    }

    override suspend fun reportPlay(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        urgent: Boolean,
        title: String,
        client: PikPakClient?,
    ) {
        if (enqueuePlayHistory(fileId, positionMillis, durationMillis, urgent = urgent, title = title, client = client)) {
            playHistoryReporter.flushAll()
        }
    }

    override suspend fun flushPlayHistory(timeoutMillis: Long) {
        val bounded = timeoutMillis.coerceAtLeast(0L)
        withTimeoutOrNull(bounded) {
            coroutineScope {
                launch {
                    val localDone = CompletableDeferred<Unit>()
                    if (localPositionOps.trySend(LocalPositionOp.Flush(localDone)).isSuccess) {
                        localDone.await()
                    }
                }
                launch {
                    playHistoryReporter.flushAll(bounded)
                }
            }
        }
    }

    override suspend fun cloudPlaybackPosition(fileId: String): Long? {
        if (LocalLocation.isLocal(fileId) || fileId.isBlank() || LeasedFile.isLeased(fileId)) return null
        return withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (!isPlayHistorySynced()) return@runSuspendCatching null
                val event = client.listPlayHistory().events.firstOrNull { it.fileId == fileId } ?: return@runSuspendCatching null
                val seconds = event.playSeconds ?: return@runSuspendCatching null
                val duration = event.playDuration
                if (duration != null && duration > 0 && seconds * 1000 >= duration * 1000 - CLOUD_NEAR_END_MILLIS) null else seconds * 1000
            }.logFailure(MEDIA_LOG_TAG, "Failed to read cloud play history for ${logFile(fileId, "")}, falling back to local resume position").getOrNull()
        }
    }

    override suspend fun savePlaybackPosition(fileId: String, positionMillis: Long) {
        preferences?.savePlaybackPosition(fileId, positionMillis)
    }

    override fun savePlaybackPositionAsync(fileId: String, positionMillis: Long) {
        if (fileId.isBlank() || preferences == null) return
        localPositionOps.trySend(LocalPositionOp.Save(fileId, positionMillis))
    }

    override suspend fun getPlaybackPosition(fileId: String): Long =
        preferences?.getPlaybackPosition(fileId) ?: 0L

    private companion object {
        val sharedProxy by lazy { PikoMediaProxy() }
        const val CLOUD_NEAR_END_MILLIS = 10_000L
    }
}

internal const val MEDIA_LOG_TAG = "Media"
