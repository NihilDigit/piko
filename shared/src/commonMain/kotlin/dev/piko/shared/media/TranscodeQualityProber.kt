@file:JvmMultifileClass
@file:JvmName("PikoMediaRepositoryKt")

package dev.piko.shared.media

import dev.piko.shared.data.DriveMetadataSink
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.proxy.PikPakByteSource
import dev.piko.shared.media.proxy.PikoMediaProxy
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.fileHandle
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.ktor.utils.io.readAvailable
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
) {
    val isOriginal: Boolean get() = mediaId == null
}

/**
 * 这一档转码在服务端读不出字节，见 [PikoMediaRepository.downloadQualities]。消息直接给用户看，点明是 PikPak：
 * 只写「无法读取」时用户会当成网络或客户端自身的问题，反复重试。
 */
class UnreadableTranscodeException(cause: Throwable? = null) : IllegalStateException(
    tr(
        "PikPak 云端转码流不可用，请选择其他画质",
        "PikPak cloud transcode is unavailable. Choose another quality",
        "PikPak クラウドの変換ストリームを利用できません。別の画質を選択してください",
        "PikPak 클라우드 변환 스트림을 사용할 수 없습니다. 다른 화질을 선택해 주세요",
        "PikPak 雲端轉碼串流無法使用，請選擇其他畫質",
    ),
    cause,
)

/** 选定的转码档在详情里已经没有了，给用户看的失败原因。 */
val TRANSCODE_GONE_MESSAGE: String
    get() = tr(
        "所选画质已不可用",
        "Selected quality is no longer available",
        "選択した画質は利用できなくなりました",
        "선택한 화질을 더 이상 사용할 수 없습니다",
        "所選畫質已無法使用",
    )

internal val MediaVariant.qualityName: String?
    get() = mediaName.ifBlank { resolutionName }.takeIf { it.isNotBlank() }

internal class TranscodeQualityProber(
    private val requireClient: () -> PikPakClient,
    private val currentClientOrNull: () -> PikPakClient?,
    private val metadataSinkProvider: () -> DriveMetadataSink?,
    private val fileCachePoolProvider: () -> PikoFileCachePool?,
    private val proxy: PikoMediaProxy,
    private val backgroundScope: CoroutineScope,
    private val detailResolver: MediaDetailResolver,
) {
    private class QualityProbeState(
        val options: List<DownloadQuality>? = null,
        val finished: Boolean = false,
        val error: Throwable? = null,
    )

    private class QualityProbe(
        val state: MutableStateFlow<QualityProbeState>,
        val started: TimeMark,
    )

    private val qualityProbes = HashMap<String, QualityProbe>()
    private val qualityProbesLock = Mutex()

    fun downloadQualities(fileId: String): Flow<List<DownloadQuality>> = flow {
        emitAll(
            qualityProbe(fileId).state.transformWhile { probe ->
                probe.error?.let { throw it }
                probe.options?.let { emit(it) }
                !probe.finished
            },
        )
    }

    fun prefetchDownloadQualities(fileId: String) {
        backgroundScope.launch {
            runSuspendCatching { qualityProbe(fileId) }
                .onFailure {
                    PikoLog.d(MEDIA_LOG_TAG, "Download quality prefetch skipped for ${logFile(fileId, "")}: ${it::class.simpleName}: ${it.message}")
                }
        }
    }

    private suspend fun qualityProbe(fileId: String): QualityProbe {
        val client = requireClient()
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

    private suspend fun finishedQualities(fileId: String): List<DownloadQuality>? =
        finishedQualitiesForClient(requireClient(), fileId)

    private suspend fun finishedQualitiesForClient(client: PikPakClient, fileId: String): List<DownloadQuality>? {
        val key = "${client.account}/$fileId"
        return qualityProbesLock.withLock {
            qualityProbes[key]?.takeIf { it.started.elapsedNow() <= QUALITY_PROBE_FOR }?.state?.value
                ?.takeIf { it.finished && it.error == null }?.options
        }
    }

    suspend fun knownTranscodeStreamSize(client: PikPakClient, detail: FileDetail, mediaId: String): Long? {
        if (detail.id.isNotBlank()) {
            finishedQualitiesForClient(client, detail.id)
                ?.firstOrNull { it.mediaId == mediaId && !it.unreadable }
                ?.sizeBytes
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        if (detail.hash.isNotBlank()) {
            client.knownStreamSize(detail.hash, mediaId)?.takeIf { it > 0L }?.let { return it }
            metadataSinkProvider()?.lookupContent(client, detail.hash, detail.sizeBytes)
                ?.transcodes
                ?.firstOrNull { it.mediaId == mediaId && !it.unreadable }
                ?.streamSizeBytes
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return null
    }

    fun recordTranscodeStreamSize(
        client: PikPakClient,
        detail: FileDetail,
        mediaId: String,
        streamSizeBytes: Long?,
        unreadable: Boolean = false,
    ) {
        if (detail.hash.isBlank() || mediaId.isBlank()) return
        if (streamSizeBytes != null && streamSizeBytes > 0L && !unreadable) {
            client.rememberStreamSize(detail.hash, mediaId, streamSizeBytes)
        }
        metadataSinkProvider()?.capture(client)?.observeTranscodeSize(
            gcid = detail.hash,
            fileSizeBytes = detail.sizeBytes,
            mediaId = mediaId,
            streamSizeBytes = streamSizeBytes?.takeIf { it > 0L },
            unreadable = unreadable,
        )
    }

    private suspend fun probeQualities(client: PikPakClient, fileId: String, state: MutableStateFlow<QualityProbeState>) {
        val sink = metadataSinkProvider()
        if (!LeasedFile.isLeased(fileId)) {
            val cachedFile = sink?.lookupFile(client, fileId)
            val cachedContent = sink?.lookupContentByFileId(client, fileId)
            val cachedTranscodes = cachedContent?.transcodes
            if (cachedFile != null && cachedTranscodes != null && (cachedTranscodes.isNotEmpty() || cachedContent.qualityRank > 0)) {
                val initialDurationMs = cachedContent.durationMillis?.takeIf { it > 0L }
                    ?: cachedFile.params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    ?: 0L
                val initialOriginal = DownloadQuality(
                    name = null,
                    mediaId = null,
                    height = cachedContent.height ?: cachedFile.params["height"]?.toIntOrNull() ?: 0,
                    sizeBytes = cachedFile.sizeBytes,
                    durationMs = initialDurationMs,
                )
                val initialTranscodes = cachedTranscodes
                    .filter { it.name.isNotBlank() && it.mediaId.isNotBlank() }
                    .sortedByDescending { it.height }
                    .map { t ->
                        val exactSize = client.knownStreamSize(cachedFile.hash, t.mediaId)?.takeIf { it > 0L }
                            ?: t.streamSizeBytes?.takeIf { it > 0L }
                        DownloadQuality(
                            name = t.name,
                            mediaId = t.mediaId,
                            height = t.height,
                            sizeBytes = if (t.unreadable) null else (exactSize ?: t.estimatedSizeBytes),
                            durationMs = t.durationSeconds.takeIf { it > 0L }?.times(1000L) ?: initialDurationMs,
                            unreadable = t.unreadable,
                        )
                    }
                state.value = QualityProbeState(listOf(initialOriginal) + initialTranscodes)
            }
        }
        val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
        val durationMs = detailResolver.durationMsOf(detail)
        val original = originalQuality(detail, durationMs)
        val transcodes = if (LeasedFile.isLeased(fileId)) {
            emptyList()
        } else {
            detail.downloadableTranscodes().sortedByDescending { it.video?.height ?: 0 }
        }
        val latestSink = metadataSinkProvider()
        val content = latestSink?.lookupContent(client, detail.hash, detail.sizeBytes)
            ?: latestSink?.lookupContentByFileId(client, fileId)
        val knownByMediaId = content?.transcodes?.associateBy { it.mediaId }.orEmpty()
        val initialOptions = transcodes.map { media ->
            val known = knownByMediaId[media.mediaId]
            val exactSize = client.knownStreamSize(detail.hash, media.mediaId)?.takeIf { it > 0L }
                ?: known?.streamSizeBytes?.takeIf { it > 0L }
            val unreadable = known?.unreadable == true
            media.toDownloadQuality(
                fallbackDurationMs = durationMs,
                sizeBytes = if (unreadable) null else (exactSize ?: known?.estimatedSizeBytes),
                unreadable = unreadable,
            )
        }
        state.value = QualityProbeState(listOf(original) + initialOptions)
        coroutineScope {
            transcodes.forEach { media ->
                val known = knownByMediaId[media.mediaId]
                val exactSize = client.knownStreamSize(detail.hash, media.mediaId)?.takeIf { it > 0L }
                    ?: known?.streamSizeBytes?.takeIf { it > 0L }
                if (known?.unreadable == true) return@forEach
                if (exactSize != null) {
                    client.rememberStreamSize(detail.hash, media.mediaId, exactSize)
                    return@forEach
                }
                launch {
                    val probed = runSuspendCatching { probeTranscode(client, media) }
                    val error = probed.exceptionOrNull()
                    if (error is UnreadableTranscodeException) {
                        PikoLog.w(MEDIA_LOG_TAG, "Transcode variant ${media.qualityName} returned unreadable stream: ${logFile(fileId, detail.name)}", error)
                        recordTranscodeStreamSize(client, detail, media.mediaId, null, unreadable = true)
                    } else if (error != null) {
                        PikoLog.w(MEDIA_LOG_TAG, "Failed to probe transcode variant ${media.qualityName} size: ${logFile(fileId, detail.name)}", error)
                        state.update { current ->
                            QualityProbeState(
                                current.options?.map { option ->
                                    if (option.mediaId != media.mediaId) option else option.copy(sizeBytes = null)
                                },
                            )
                        }
                        return@launch
                    } else {
                        probed.getOrNull()?.let { size ->
                            recordTranscodeStreamSize(client, detail, media.mediaId, size, unreadable = false)
                        }
                    }
                    state.update { current ->
                        QualityProbeState(
                            current.options?.map { option ->
                                if (option.mediaId != media.mediaId) {
                                    option
                                } else {
                                    probed.fold(
                                        onSuccess = { option.copy(sizeBytes = it, unreadable = false) },
                                        onFailure = { option.copy(sizeBytes = null, unreadable = true) },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        state.update { QualityProbeState(it.options, finished = true) }
    }

    suspend fun downloadVariant(fileId: String, name: String?, maxHeight: Int): Result<DownloadQuality?> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (LeasedFile.isLeased(fileId)) return@runSuspendCatching null
                finishedQualities(fileId)
                    ?.takeIf { known -> known.all { it.isOriginal || it.unreadable || it.sizeBytes != null } }
                    ?.let { known -> return@runSuspendCatching chooseVariant(known, name, maxHeight) }
                val client = requireClient()
                val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
                val durationMs = detailResolver.durationMsOf(detail)
                val transcodes = detail.downloadableTranscodes()
                val sink = metadataSinkProvider()
                val content = sink?.lookupContent(client, detail.hash, detail.sizeBytes)
                    ?: sink?.lookupContentByFileId(client, fileId)
                val knownByMediaId = content?.transcodes?.associateBy { it.mediaId }.orEmpty()
                suspend fun resolveTranscodeSize(media: MediaVariant): Long {
                    val known = knownByMediaId[media.mediaId]
                    if (known?.unreadable == true) throw UnreadableTranscodeException()
                    knownTranscodeStreamSize(client, detail, media.mediaId)?.let { return it }
                    return try {
                        probeTranscode(client, media).also { size ->
                            recordTranscodeStreamSize(client, detail, media.mediaId, size, unreadable = false)
                        }
                    } catch (e: UnreadableTranscodeException) {
                        recordTranscodeStreamSize(client, detail, media.mediaId, null, unreadable = true)
                        throw e
                    }
                }
                if (name != null) {
                    val media = transcodes.firstOrNull { it.qualityName == name } ?: return@runSuspendCatching null
                    return@runSuspendCatching media.toDownloadQuality(durationMs, resolveTranscodeSize(media))
                }
                val options = listOf(originalQuality(detail, durationMs)) + transcodes.map { media ->
                    val known = knownByMediaId[media.mediaId]
                    media.toDownloadQuality(durationMs, known?.streamSizeBytes, unreadable = known?.unreadable == true)
                }
                for (option in downloadQualityOrder(options, maxHeight)) {
                    if (option.isOriginal) return@runSuspendCatching null
                    val media = transcodes.first { it.mediaId == option.mediaId }
                    val probed = runSuspendCatching { resolveTranscodeSize(media) }
                    val error = probed.exceptionOrNull()
                    if (error is UnreadableTranscodeException) {
                        PikoLog.w(MEDIA_LOG_TAG, "Transcode variant ${media.qualityName} unreadable, trying next lower variant: ${logFile(fileId, detail.name)}", error)
                        continue
                    }
                    return@runSuspendCatching option.copy(sizeBytes = probed.getOrThrow())
                }
                null
            }
        }

    private fun chooseVariant(known: List<DownloadQuality>, name: String?, maxHeight: Int): DownloadQuality? {
        if (name != null) {
            val chosen = known.firstOrNull { !it.isOriginal && it.name == name } ?: return null
            if (chosen.unreadable) throw UnreadableTranscodeException()
            return chosen
        }
        return chooseDownloadQuality(known, maxHeight)?.takeUnless { it.isOriginal }
    }

    private fun originalQuality(detail: FileDetail, durationMs: Long) =
        DownloadQuality(null, null, detail.medias.firstOrNull { it.isOrigin }?.video?.height ?: 0, detail.sizeBytes, durationMs)

    private fun FileDetail.downloadableTranscodes(): List<MediaVariant> =
        medias.filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() && it.qualityName != null }

    private fun MediaVariant.toDownloadQuality(fallbackDurationMs: Long, sizeBytes: Long? = null, unreadable: Boolean = false) =
        DownloadQuality(qualityName, mediaId, video?.height ?: 0, sizeBytes, video?.duration?.times(1000) ?: fallbackDurationMs, unreadable = unreadable)

    suspend fun probeTranscode(client: PikPakClient, media: MediaVariant): Long =
        client.streamRangeFromUrl(media.link.url, start = 0L, length = 1L) { stream ->
            val size = stream.totalSize?.takeIf { it > 0 } ?: error(
                tr(
                    "服务端未返回转码文件大小",
                    "Server did not return transcoded file size",
                    "サーバーから変換ファイルのサイズが返されませんでした",
                    "서버에서 변환 파일 크기를 반환하지 않았습니다",
                    "伺服器未傳回轉碼檔案大小",
                ),
            )
            val read = runSuspendCatching { stream.channel.readAvailable(ByteArray(1), 0, 1) }
            if ((read.getOrNull() ?: -1) < 1) throw UnreadableTranscodeException(read.exceptionOrNull())
            size
        }

    suspend fun transcodeSource(
        client: PikPakClient,
        fileId: String,
        mediaId: String,
        retainedBy: String?,
    ): PikPakByteSource {
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
        val variant = detail.medias.firstOrNull { it.mediaId == mediaId && it.link.url.isNotBlank() }
            ?: error(TRANSCODE_GONE_MESSAGE)
        val streamSize = streamSizeOf(client, detail, mediaId)
        val mediaRate = detailResolver.estimatedMediaBytesPerSecond(
            streamSize = streamSize,
            durationSeconds = variant.video?.duration ?: (detailResolver.durationMsOf(detail) / 1000L),
            bitRateBitsPerSecond = variant.video?.bitRate,
        )
        val pool = fileCachePoolProvider() ?: run {
            val handle = client.fileHandle(detail, mediaId = mediaId, streamSize = streamSize, onRangeAttempt = ::logRangeAttempt)
            return try {
                val cache = handle.openCache(coroutineContext = proxy.readerContext)
                if (mediaRate != null) cache.updateMediaBytesPerSecond(mediaRate)
                PikPakByteSource(handle, cache)
            } catch (e: Throwable) {
                handle.close()
                throw e
            }
        }
        val lease = pool.acquire(
            client = client,
            fileId = detail.id,
            gcid = detail.hash,
            size = detail.sizeBytes,
            name = detail.name,
            parentId = detail.parentId,
            leased = LeasedFile.isLeased(fileId),
            detail = detail,
            retained = retainedBy != null,
            owner = retainedBy ?: detail.id,
            mediaId = mediaId,
            streamSize = streamSize,
            mediaBytesPerSecond = mediaRate,
        )
        return PikPakByteSource(lease.entry.handle, lease.entry.cache, lease::close)
    }

    suspend fun streamSizeOf(client: PikPakClient, detail: FileDetail, mediaId: String): Long {
        knownTranscodeStreamSize(client, detail, mediaId)?.let { return it }
        return client.fileHandle(detail, mediaId = mediaId, onRangeAttempt = ::logRangeAttempt)
            .use { it.streamSize() }
            .also { recordTranscodeStreamSize(client, detail, mediaId, it, unreadable = false) }
    }

    fun knownUnreadableTranscodeIds(fileId: String, gcid: String, sizeBytes: Long): Set<String> {
        val owner = currentClientOrNull() ?: return emptySet()
        val sink = metadataSinkProvider()
        val content = sink?.lookupContentByFileId(owner, fileId)
            ?: (if (gcid.isNotBlank() && sizeBytes >= 0L) sink?.lookupContent(owner, gcid, sizeBytes) else null)
        return content?.transcodes?.filter { it.unreadable }?.map { it.mediaId }?.toSet().orEmpty()
    }

    fun knownTranscodeStreamSizeFor(fileId: String, gcid: String, sizeBytes: Long, mediaId: String): Long? {
        val owner = currentClientOrNull() ?: return null
        val sink = metadataSinkProvider()
        val content = sink?.lookupContentByFileId(owner, fileId)
            ?: (if (gcid.isNotBlank() && sizeBytes >= 0L) sink?.lookupContent(owner, gcid, sizeBytes) else null)
        return content?.transcodes?.firstOrNull { it.mediaId == mediaId }?.streamSizeBytes?.takeIf { it > 0L }
    }

    companion object {
        private val QUALITY_PROBE_FOR = 10.minutes
    }
}
