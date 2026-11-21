@file:JvmMultifileClass
@file:JvmName("PikoMediaRepositoryKt")

package dev.piko.shared.media

import dev.piko.shared.data.BatchDriveDetailObserver
import dev.piko.shared.data.DriveMetadataSink
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMounts
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.proxy.PikPakByteSource
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.SlicedByteSource
import dev.piko.shared.media.storyboard.StoryboardFetchPriority
import dev.piko.shared.media.storyboard.StoryboardRangeProbe
import dev.piko.shared.media.storyboard.StoryboardSource
import dev.piko.shared.media.storyboard.StoryboardSourceResolver
import dev.piko.shared.media.storyboard.StoryboardStreamFetcher
import dev.piko.shared.state.localClipMaxWindowMs
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.DownloadLink
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.ReadEndReason
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.VariantLink
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.batchGetFileDetails
import io.github.nihildigit.pikpak.fileHandle
import io.github.nihildigit.pikpak.resolveVariant
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.withApiPriority
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [PikoMediaRepository.clipProbe] 的结果。时长取不到时为 null。 */
class ClipProbe(val hasTranscode: Boolean, val durationMs: Long?)

// 关键帧扫描打开 4 个连续 256 KiB 分块（headSlot..headSlot+3，共约 1 MiB）并发预读窗口，一次性条带化拉回
internal const val CLIP_SCAN_READ_AHEAD_BYTES = 3L * 256 * 1024L
internal const val DEFAULT_PREVIEW_CHUNK_BYTES = 256 * 1024

/**
 * 单个雪碧图引擎专属分层传输会话：
 * 优先按 256 KiB 分块零流量直读 [PikoFileCachePool]，未命中部分经 [PikPakFileHandle] 接入 SDK 正式优先级调度与多 CDN 续期。
 */
class StoryboardTransportSession internal constructor(
    private val source: StoryboardSource,
    private val currentClientOrNull: () -> PikPakClient?,
    private val fileCachePoolProvider: () -> PikoFileCachePool?,
    private val leaseFolderProvider: () -> (suspend (PikPakClient) -> String)?,
    private val detailResolver: MediaDetailResolver,
    private val qualityProber: TranscodeQualityProber,
) : StoryboardStreamFetcher, AutoCloseable {
    private val sessionClient: PikPakClient? = currentClientOrNull()
    private val handleLock = Mutex()
    private val errorLogLock = Mutex()
    private var loggedErrors = 0

    @Volatile
    private var handle: PikPakFileHandle? = null

    @Volatile
    private var yieldBackgroundGate = false

    @Volatile
    private var urgentAllowedGate = true

    @Volatile
    private var knownStreamSize: Long = source.streamSizeBytes?.takeIf { it > 0L }
        ?: qualityProber.knownTranscodeStreamSizeFor(source.fileId, source.gcid, 0L, source.mediaId)
        ?: 0L

    @Volatile
    private var closed = false

    fun updateTransportGate(yieldBackground: Boolean, urgentAllowed: Boolean) {
        if (closed) return
        yieldBackgroundGate = yieldBackground
        urgentAllowedGate = urgentAllowed
    }

    override suspend fun streamRange(
        offset: Long,
        maxLength: Int,
        priority: StoryboardFetchPriority,
        onChunk: (buffer: ByteArray, length: Int) -> Boolean,
    ): StoryboardRangeProbe? = withContext(Dispatchers.IO) {
        if (closed || offset < 0L || maxLength <= 0) return@withContext null
        val accountClient = sessionClient ?: currentClientOrNull() ?: return@withContext null
        val currentKnownSize = knownStreamSize
        if (currentKnownSize > 0L && offset >= currentKnownSize) return@withContext null
        val totalBudget = if (currentKnownSize > 0L) {
            minOf(maxLength.toLong(), currentKnownSize - offset).toInt()
        } else {
            maxLength
        }
        if (totalBudget <= 0) return@withContext null

        var effectiveOffset = offset
        var remaining = totalBudget
        var totalConsumed = 0

        val pool = fileCachePoolProvider()
        if (pool != null && currentKnownSize > 0L) {
            while (remaining > 0) {
                val sliceLen = minOf(remaining, STORYBOARD_LOCAL_CACHE_SLICE_BYTES)
                val cached = pool.readCachedBytes(
                    account = accountClient.account,
                    gcid = source.gcid,
                    totalBytes = currentKnownSize,
                    mediaId = source.mediaId,
                    offset = effectiveOffset,
                    length = sliceLen,
                ) ?: break
                if (cached.isEmpty()) break
                totalConsumed += cached.size
                effectiveOffset += cached.size
                remaining -= cached.size
                val stoppedEarly = onChunk(cached, cached.size)
                if (stoppedEarly) {
                    return@withContext StoryboardRangeProbe(
                        streamSizeBytes = currentKnownSize,
                        bytesConsumed = totalConsumed,
                        reachedEof = effectiveOffset >= currentKnownSize,
                    )
                }
                if (cached.size < sliceLen) break
            }
            if (remaining <= 0) {
                return@withContext StoryboardRangeProbe(
                    streamSizeBytes = currentKnownSize,
                    bytesConsumed = totalConsumed,
                    reachedEof = effectiveOffset >= currentKnownSize,
                )
            }
        }

        val fileHandle = ensureHandle(accountClient) ?: return@withContext null
        val readPriority = when (priority) {
            StoryboardFetchPriority.URGENT -> {
                if (urgentAllowedGate) STORYBOARD_URGENT_READ_PRIORITY else STORYBOARD_PAUSED_READ_PRIORITY
            }
            StoryboardFetchPriority.BACKGROUND -> {
                if (yieldBackgroundGate) STORYBOARD_PAUSED_READ_PRIORITY else STORYBOARD_BACKGROUND_READ_PRIORITY
            }
        }
        val timeoutMs = when (priority) {
            StoryboardFetchPriority.URGENT -> STORYBOARD_URGENT_FETCH_TIMEOUT_MILLIS
            StoryboardFetchPriority.BACKGROUND -> STORYBOARD_FETCH_TIMEOUT_MILLIS
        }

        var netRead = 0
        val streamResult = try {
            Result.success(
                withTimeoutOrNull(timeoutMs) {
                    fileHandle.read(
                        start = effectiveOffset,
                        length = remaining.toLong(),
                        priority = readPriority,
                    ) { channel ->
                        val buf = ByteArray(minOf(STORYBOARD_STREAM_CHUNK_BYTES, remaining))
                        var stoppedEarly = false
                        while (netRead < remaining) {
                            val toRead = minOf(buf.size, remaining - netRead)
                            val n = channel.readAvailable(buf, 0, toRead)
                            if (n <= 0) break
                            netRead += n
                            if (onChunk(buf, n)) {
                                stoppedEarly = true
                                break
                            }
                        }
                        if (knownStreamSize <= 0L) {
                            val discovered = runCatching { fileHandle.streamSize() }.getOrNull()?.takeIf { it > 0L }
                            if (discovered != null) {
                                knownStreamSize = discovered
                                recordDiscoveredStreamSize(accountClient, discovered)
                            }
                        }
                        val effectiveSize = knownStreamSize.takeIf { it > 0L }
                        val endOffset = effectiveOffset + netRead
                        val reachedEof = !stoppedEarly && (
                            (effectiveSize != null && endOffset >= effectiveSize) ||
                                (netRead < remaining && netRead > 0)
                            )
                        StoryboardRangeProbe(
                            streamSizeBytes = effectiveSize,
                            bytesConsumed = totalConsumed + netRead,
                            reachedEof = reachedEof,
                        )
                    }
                }.also { completed ->
                    if (completed == null) {
                        logSessionErrorThrottled(
                            "Storyboard stream range timed out (${timeoutMs}ms, offset=$effectiveOffset, maxLen=$remaining, priority=$priority/$readPriority): ${logFile(source.fileId, source.mediaId)}",
                        )
                    }
                },
            )
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (closed) throw ce
            logSessionErrorThrottled(
                "Storyboard stream range preempted by foreground transfer (offset=$effectiveOffset, priority=$priority/$readPriority): ${logFile(source.fileId, source.mediaId)}",
            )
            val consumedSoFar = totalConsumed + netRead
            return@withContext if (consumedSoFar > 0) {
                StoryboardRangeProbe(
                    streamSizeBytes = knownStreamSize.takeIf { it > 0L },
                    bytesConsumed = consumedSoFar,
                    reachedEof = false,
                )
            } else {
                null
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }

        val err = streamResult.exceptionOrNull()
        if (err != null) {
            logSessionErrorThrottled(
                "Storyboard stream range failed (offset=$effectiveOffset, maxLen=$remaining, priority=$priority/$readPriority, cause=${err::class.simpleName}: ${err.message}): ${logFile(source.fileId, source.mediaId)}",
            )
            return@withContext if (totalConsumed > 0) {
                StoryboardRangeProbe(
                    streamSizeBytes = knownStreamSize.takeIf { it > 0L },
                    bytesConsumed = totalConsumed,
                    reachedEof = false,
                )
            } else {
                null
            }
        }
        return@withContext streamResult.getOrNull() ?: if (totalConsumed > 0) {
            StoryboardRangeProbe(
                streamSizeBytes = knownStreamSize.takeIf { it > 0L },
                bytesConsumed = totalConsumed,
                reachedEof = false,
            )
        } else {
            null
        }
    }

    private suspend fun ensureHandle(accountClient: PikPakClient): PikPakFileHandle? {
        handle?.let { return it }
        return handleLock.withLock {
            if (closed) return@withLock null
            handle?.let { return@withLock it }
            val fresh = detailResolver.takeFreshDetail(accountClient, source.fileId)
            val matchedVariant = fresh?.medias?.firstOrNull { it.mediaId == source.mediaId && it.link.url.isNotBlank() }
            val isLeased = LeasedFile.isLeased(source.fileId)
            val leasedMeta = LeasedFile.resolvedFileOf(source.fileId)
            val created = if (fresh != null && matchedVariant != null) {
                val leaseParentId = if (isLeased) {
                    runCatching { leaseFolderProvider()?.invoke(accountClient).orEmpty() }.getOrDefault("")
                } else {
                    fresh.parentId
                }
                accountClient.fileHandle(
                    detail = fresh,
                    mediaId = source.mediaId,
                    parentId = leaseParentId,
                    leased = isLeased,
                    onRangeAttempt = ::logRangeAttempt,
                    streamSize = knownStreamSize.takeIf { it > 0L },
                )
            } else {
                val initialUrl = source.url.takeIf { it.isNotBlank() } ?: return@withLock null
                val originalBytes = fresh?.sizeBytes?.takeIf { it > 0L }
                    ?: leasedMeta?.size?.takeIf { it > 0L }
                    ?: 0L
                val leaseParentId = if (isLeased) {
                    runCatching { leaseFolderProvider()?.invoke(accountClient).orEmpty() }.getOrDefault("")
                } else {
                    fresh?.parentId.orEmpty()
                }
                PikPakFileHandle(
                    client = accountClient,
                    gcid = source.gcid,
                    size = originalBytes,
                    name = fresh?.name?.takeIf { it.isNotBlank() } ?: leasedMeta?.name ?: source.fileId,
                    initialFileId = if (isLeased) null else source.fileId,
                    mediaId = source.mediaId,
                    parentId = leaseParentId,
                    connectionBudget = 6,
                    leased = isLeased,
                    onRangeAttempt = ::logRangeAttempt,
                    initialLink = VariantLink(url = initialUrl, expiresAt = DownloadLink(url = initialUrl).expiresAt),
                    streamSize = knownStreamSize.takeIf { it > 0L },
                )
            }
            handle = created
            created
        }
    }

    private suspend fun recordDiscoveredStreamSize(accountClient: PikPakClient, size: Long) {
        if (size <= 0L) return
        if (source.gcid.isNotBlank() && source.mediaId.isNotBlank()) {
            accountClient.rememberStreamSize(source.gcid, source.mediaId, size)
        }
        val detail = detailResolver.takeFreshDetail(accountClient, source.fileId)
        if (detail != null) {
            qualityProber.recordTranscodeStreamSize(accountClient, detail, source.mediaId, size, unreadable = false)
        }
    }

    private suspend fun logSessionErrorThrottled(message: String) {
        val shouldLog = errorLogLock.withLock {
            if (loggedErrors < MAX_SESSION_ERROR_LOGS) {
                loggedErrors++
                true
            } else {
                false
            }
        }
        if (shouldLog) {
            PikoLog.d(MEDIA_LOG_TAG, message)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { handle?.close() }
    }

    private companion object {
        const val STORYBOARD_URGENT_FETCH_TIMEOUT_MILLIS = 8_000L
        const val STORYBOARD_FETCH_TIMEOUT_MILLIS = 10_000L
        const val STORYBOARD_URGENT_READ_PRIORITY = 20
        const val STORYBOARD_BACKGROUND_READ_PRIORITY = 6
        const val STORYBOARD_PAUSED_READ_PRIORITY = 1
        const val STORYBOARD_STREAM_CHUNK_BYTES = 32 * 1024
        const val STORYBOARD_LOCAL_CACHE_SLICE_BYTES = 256 * 1024
        const val MAX_SESSION_ERROR_LOGS = 4
    }
}

internal class ClipAndStoryboardMediaSource(
    private val requireClient: () -> PikPakClient,
    private val currentClientOrNull: () -> PikPakClient?,
    private val localMountsProvider: () -> LocalMounts?,
    private val metadataSinkProvider: () -> DriveMetadataSink?,
    private val fileCachePoolProvider: () -> PikoFileCachePool?,
    private val leaseFolderProvider: () -> (suspend (PikPakClient) -> String)?,
    private val clipCache: ClipCache?,
    private val proxy: PikoMediaProxy,
    private val backgroundScope: CoroutineScope,
    private val detailResolver: MediaDetailResolver,
    private val qualityProber: TranscodeQualityProber,
    private val sessionPreparer: PlaybackSessionPreparer,
) {
    suspend fun prepareClip(
        fileId: String,
        startMs: Long,
        videoDurationMs: Long,
        role: StreamRole = StreamRole.BACKGROUND,
        demand: TransferDemand? = null,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
    ): Result<PreparedClip> {
        val accountClient = requireClient()
        val ownsDemand = demand == null
        val owner = demand ?: accountClient.openTransferDemand(
            ConsumerIntent(
                service = if (role == StreamRole.FOREGROUND) ServiceIntent.WAITING else ServiceIntent.PREFETCH,
                bufferBudget = if (role == StreamRole.FOREGROUND) {
                    BufferBudget(maxReadAheadBytes = CLIP_SCAN_READ_AHEAD_BYTES)
                } else {
                    BufferBudget.NONE
                },
            ),
        )
        var created: PreparedClip? = null
        try {
            return withContext(owner) {
                prepareClipOwned(accountClient, fileId, startMs, videoDurationMs, onByteSource)
                    .onSuccess {
                        created = it
                        it.bindDemand(owner)
                    }
            }.onFailure { if (ownsDemand) owner.close() }
        } catch (e: Throwable) {
            created?.close()
            if (ownsDemand) owner.close()
            throw e
        }
    }

    private suspend fun prepareClipOwned(
        client: PikPakClient,
        fileId: String,
        startMs: Long,
        videoDurationMs: Long,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
    ): Result<PreparedClip> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (LocalLocation.isLocal(fileId)) {
                    val path = localMountsProvider()?.resolve(fileId)
                        ?: error(
                            tr(
                                "本机文件夹不可访问",
                                "Local folder is inaccessible",
                                "ローカルフォルダにアクセスできません",
                                "로컬 폴더에 접근할 수 없습니다",
                                "本機資料夾無法存取",
                            ),
                        )
                    return@runSuspendCatching PreparedClip(
                        stream = null,
                        fallbackUrl = path,
                        sliced = false,
                        clipStartMs = startMs,
                        bytesPerMs = 0.0,
                        maxWindowMs = localClipMaxWindowMs(startMs, videoDurationMs),
                        localPath = path,
                    )
                }
                val key = clipKey(client.account, fileId, startMs)
                val leased = LeasedFile.isLeased(fileId)
                clipCache?.record(key)?.let { record ->
                    cachedClip(client, record, videoDurationMs, leased, onByteSource)?.let { return@runSuspendCatching it }
                }
                val detail = detailResolver.takeFreshDetail(client, fileId)
                    ?: detailResolver.detailOf(client, fileId).also { detailResolver.rememberFreshDetail(client, fileId, it) }
                val transcode = detail.resolveVariant(VariantPreference.Resolution(CLIP_RESOLUTION))
                if (!transcode.isOrigin && videoDurationMs > 0) {
                    openClipSlice(client, detail, transcode, key, startMs, videoDurationMs, leased, onByteSource)
                        ?.let { return@runSuspendCatching it }
                }
                val original = detail.resolveVariant(VariantPreference.Original)
                val info = sessionPreparer.playableMediaInfo(detail, original, fileId)
                val stream = sessionPreparer.openProxyStream(client, detail, original, leased = leased, fastReactivation = true, onByteSource = onByteSource)
                val bytesPerMs = if (videoDurationMs > 0) detail.sizeBytes.toDouble() / videoDurationMs else 0.0
                PreparedClip(stream, fallbackUrl = info.currentUrl, sliced = false, clipStartMs = startMs, bytesPerMs = bytesPerMs)
            }
        }

    private suspend fun cachedClip(
        client: PikPakClient,
        record: ClipRecord,
        videoDurationMs: Long,
        leased: Boolean,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
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
            reactivationPollInterval = PlaybackSessionPreparer.FAST_REACTIVATION_POLL_INTERVAL,
            reactivationTimeout = PlaybackSessionPreparer.FAST_REACTIVATION_TIMEOUT,
        )
        val boundedLength = record.sliceLength.coerceIn(TS_PACKET_BYTES, (record.streamBytes - record.sliceOffset).coerceAtLeast(TS_PACKET_BYTES))
        val cache = try {
            handle.openCache(
                blockStore = sliceStore(record.sliceOffset, boundedLength, bytesPerMs),
                coroutineContext = proxy.readerContext,
            )
        } catch (e: Throwable) {
            handle.close()
            throw e
        }
        backgroundScope.launch {
            runCatching { handle.prewarm() }.onFailure {
                if (it !is CancellationException) {
                    PikoLog.d(MEDIA_LOG_TAG, "Cached clip URL prewarm failed: ${logFile(record.fileId, record.name)}, cause=${it::class.simpleName}: ${it.message}")
                }
            }
        }
        val source = SlicedByteSource(
            inner = PikPakByteSource(handle, cache),
            offset = record.sliceOffset,
            size = boundedLength,
            nullVideoBeforeOffset = (record.keyframeOffset - record.sliceOffset).coerceAtLeast(0L),
            nullVideoPid = record.videoPid,
        )
        onByteSource?.invoke(source)
        val stream = sessionPreparer.registerProxy(source, fileName = "clip.ts", fileLabel = logFile(record.fileId, record.name)) ?: return null
        return PreparedClip(
            stream = stream,
            fallbackUrl = null,
            sliced = true,
            clipStartMs = record.sliceStartMs,
            bytesPerMs = bytesPerMs,
            maxWindowMs = record.sliceDurationMs.takeIf { it > 0L }?.let { minOf(CLIP_SLICE_MS, it) } ?: CLIP_SLICE_MS,
            fromDisk = true,
        )
    }

    private fun sliceStore(sliceOffset: Long, sliceLength: Long, bytesPerMs: Double) =
        clipCache?.let { RangeLimitedStore(it.blocks) }?.also { it.keepSlice(sliceOffset, sliceLength, bytesPerMs) }

    private fun RangeLimitedStore.keepSlice(sliceOffset: Long, sliceLength: Long, bytesPerMs: Double) {
        kept = PreparedClip.sliceRanges(sliceLength, bytesPerMs).map { it.first + sliceOffset..it.last + sliceOffset }
    }

    private fun clipKey(account: String, fileId: String, startMs: Long): String {
        val id = VaultEntry.entryIdOf(fileId)?.let { "vault-$it" } ?: fileId
        return "${account}_${id}_${startMs}_${CLIP_RESOLUTION}_v5"
    }

    suspend fun clipProbe(fileId: String, urgent: Boolean = false): ClipProbe =
        clipProbeInternal(fileId, urgent = urgent, logOnFailure = true).first

    private suspend fun clipProbeInternal(
        fileId: String,
        urgent: Boolean,
        logOnFailure: Boolean,
    ): Pair<ClipProbe, Boolean> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (LocalLocation.isLocal(fileId)) {
                    val file = localMountsProvider()?.probe(fileId)
                    return@runSuspendCatching ClipProbe(false, file?.params?.get("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() })
                }
                val owner = requireClient()
                detailResolver.takeFreshDetail(owner, fileId)?.let { fresh ->
                    return@runSuspendCatching clipProbeOf(fresh)
                }
                val content = detailResolver.lookupCachedContent(owner, fileId)
                val transcodes = content?.transcodes
                val durationMs = content?.durationMillis
                if (transcodes != null && (transcodes.isNotEmpty() || content.qualityRank > 0) && (durationMs ?: 0L) > 0L) {
                    return@runSuspendCatching ClipProbe(
                        hasTranscode = transcodes.any { it.name == CLIP_RESOLUTION && !it.unreadable },
                        durationMs = durationMs,
                    )
                }
                val priority = if (urgent) ApiRequestPriority.INTERACTIVE else ApiRequestPriority.BACKGROUND
                val detail = withApiPriority(priority) { detailResolver.detailOf(owner, fileId) }
                detailResolver.rememberFreshDetail(owner, fileId, detail)
                clipProbeOf(detail)
            }.fold(
                onSuccess = { it to true },
                onFailure = {
                    if (logOnFailure) {
                        PikoLog.d(MEDIA_LOG_TAG, "Clip detail probe failed, treating as no transcode: ${logFile(fileId, "")}, cause=${it::class.simpleName}: ${it.message}")
                    }
                    ClipProbe(hasTranscode = false, durationMs = null) to false
                },
            )
        }

    suspend fun batchClipProbe(fileIds: List<String>, urgent: Boolean = false): Map<String, ClipProbe> =
        withContext(Dispatchers.Default) {
            if (fileIds.isEmpty()) return@withContext emptyMap()
            val distinctIds = fileIds.distinct()
            val result = LinkedHashMap<String, ClipProbe>(distinctIds.size)
            val owner = currentClientOrNull()
            val missingDriveIds = ArrayList<String>()
            val fallbackIds = ArrayList<String>()
            for (id in distinctIds) {
                if (LocalLocation.isLocal(id)) {
                    val file = runSuspendCatching { localMountsProvider()?.probe(id) }.getOrNull()
                    result[id] = ClipProbe(false, file?.params?.get("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() })
                    continue
                }
                if (owner == null) {
                    result[id] = ClipProbe(hasTranscode = false, durationMs = null)
                    continue
                }
                val fresh = detailResolver.takeFreshDetail(owner, id)
                if (fresh != null) {
                    result[id] = clipProbeOf(fresh)
                    continue
                }
                val cachedContent = detailResolver.lookupCachedContent(owner, id)
                val cachedTranscodes = cachedContent?.transcodes
                val cachedDurationMs = cachedContent?.durationMillis
                if (cachedTranscodes != null && (cachedTranscodes.isNotEmpty() || cachedContent.qualityRank > 0) && (cachedDurationMs ?: 0L) > 0L) {
                    result[id] = ClipProbe(
                        hasTranscode = cachedTranscodes.any { it.name == CLIP_RESOLUTION && !it.unreadable },
                        durationMs = cachedDurationMs,
                    )
                    continue
                }
                if (LeasedFile.isLeased(id)) {
                    fallbackIds += id
                } else {
                    missingDriveIds += id
                }
            }
            val priority = if (urgent) ApiRequestPriority.INTERACTIVE else ApiRequestPriority.BACKGROUND
            var batchFailed = false
            val sink = metadataSinkProvider()
            if (owner != null && missingDriveIds.isNotEmpty()) {
                val observer = sink?.capture(owner)
                val fetched = runSuspendCatching {
                    withApiPriority(priority) {
                        owner.batchGetFileDetails(missingDriveIds, usage = "PLAY")
                    }
                }.onFailure {
                    batchFailed = true
                    PikoLog.w(
                        MEDIA_LOG_TAG,
                        "Batch clip detail probe failed (count=${missingDriveIds.size}), falling back to single-item probes: ${it::class.simpleName}: ${it.message}",
                    )
                }.getOrDefault(emptyList())
                val validFetched = fetched.filter { it.id.isNotBlank() }
                if (validFetched.isNotEmpty()) {
                    if (observer is BatchDriveDetailObserver) {
                        observer.observeBatch(validFetched)
                    } else if (observer != null) {
                        for (detail in validFetched) observer.observe(detail.id, detail)
                    }
                    detailResolver.rememberFreshDetails(owner, validFetched)
                    for (detail in validFetched) {
                        result[detail.id] = clipProbeOf(detail)
                    }
                }
                for (id in missingDriveIds) {
                    if (id !in result) {
                        val content = sink?.lookupContentByFileId(owner, id)
                        val transcodes = content?.transcodes
                        val durationMs = content?.durationMillis
                        if (transcodes != null && (transcodes.isNotEmpty() || content.qualityRank > 0) && (durationMs ?: 0L) > 0L) {
                            result[id] = ClipProbe(
                                hasTranscode = transcodes.any { it.name == CLIP_RESOLUTION && !it.unreadable },
                                durationMs = durationMs,
                            )
                        } else {
                            fallbackIds += id
                        }
                    }
                }
            }
            if (fallbackIds.isNotEmpty()) {
                val batchFailedSet = if (batchFailed) missingDriveIds.toSet() else emptySet()
                val probed = coroutineScope {
                    fallbackIds.map { id ->
                        async { id to clipProbeInternal(id, urgent = urgent, logOnFailure = id !in batchFailedSet) }
                    }.awaitAll()
                }
                var suppressedFailures = 0
                for ((id, outcome) in probed) {
                    result[id] = outcome.first
                    if (!outcome.second && id in batchFailedSet) suppressedFailures++
                }
                if (suppressedFailures > 0) {
                    PikoLog.d(MEDIA_LOG_TAG, "Fallback single-item clip probes also failed after batch error: failed=$suppressedFailures/${batchFailedSet.size}")
                }
            }
            result
        }

    private fun clipProbeOf(detail: FileDetail): ClipProbe = ClipProbe(
        hasTranscode = detail.medias.any { !it.isOrigin && it.mediaName == CLIP_RESOLUTION && it.url != null },
        durationMs = detailResolver.durationMsOf(detail).takeIf { it > 0L },
    )

    private suspend fun openClipSlice(
        client: PikPakClient,
        detail: FileDetail,
        transcode: ResolvedVariant,
        key: String,
        startMs: Long,
        videoDurationMs: Long,
        leased: Boolean,
        onByteSource: ((ProxyByteSource) -> Unit)? = null,
    ): PreparedClip? {
        val mediaId = transcode.mediaId ?: return null
        val store = clipCache?.let { RangeLimitedStore(it.blocks) }
        val source = sessionPreparer.openByteSource(client, detail, transcode, blockStore = store, leased = leased, fastReactivation = true) ?: return null
        onByteSource?.invoke(source)
        val bytesPerMs: Double
        val verified: TsTimestamps.VerifiedSlice
        val offset: Long
        val sliceLength: Long
        val slice = try {
            if (source.size % TS_PACKET_BYTES != 0L) {
                PikoLog.d(MEDIA_LOG_TAG, "Transcode stream size (${source.size} B) is not TS-aligned, falling back to original for clip: ${logFile(detail.id, detail.name)}")
                source.close()
                return null
            }
            bytesPerMs = source.size.toDouble() / videoDurationMs
            val estimated = ((bytesPerMs * startMs).toLong() / TS_PACKET_BYTES * TS_PACKET_BYTES).coerceIn(0, source.size - TS_PACKET_BYTES)
            val wanted = (bytesPerMs * (CLIP_SLICE_MS + PreparedClip.KEYFRAME_INTERVAL_MS) * SLICE_BITRATE_MARGIN).toLong()
            val length = (wanted / TS_PACKET_BYTES + 1) * TS_PACKET_BYTES
            fun windowLengthAt(pos: Long) = length.coerceAtMost(source.size - pos)
            store?.keepSlice(estimated, windowLengthAt(estimated), bytesPerMs)
            val primaryWindow = maxOf(
                (bytesPerMs * VERIFIED_KEYFRAME_WINDOW_MS * PreparedClip.BITRATE_MARGIN).toLong(),
                MIN_VERIFIED_KEYFRAME_SCAN_BYTES,
            )
            val maxWindow = maxOf(primaryWindow * 2L, MAX_VERIFIED_KEYFRAME_SCAN_BYTES)
            val found = source.findVerifiedSlice(estimated, primaryWindow, maxWindow)
            if (found == null) {
                PikoLog.d(MEDIA_LOG_TAG, "No verified keyframe with parameter sets found in transcode stream, falling back to full stream for clip: ${logFile(detail.id, detail.name)}")
                source.close()
                return null
            }
            verified = found
            offset = verified.sliceStartOffset
            sliceLength = windowLengthAt(offset)
            store?.keepSlice(offset, sliceLength, bytesPerMs)
            SlicedByteSource(
                inner = source,
                offset = offset,
                size = sliceLength,
                nullVideoBeforeOffset = (verified.keyframeOffset - offset).coerceAtLeast(0L),
                nullVideoPid = verified.videoPid,
            )
        } catch (t: Throwable) {
            source.close()
            throw t
        }
        val stream = sessionPreparer.registerProxy(slice, fileName = "clip.ts", fileLabel = logFile(detail.id, detail.name)) ?: return null
        fun record(sliceStartMs: Long, sliceDurationMs: Long) = ClipRecord(
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
            sliceDurationMs = sliceDurationMs,
            keyframeOffset = verified.keyframeOffset,
            videoPid = verified.videoPid,
        )
        val calibratedStartMs = (verified.ptsMs - STREAM_START_PTS_MS).coerceAtLeast(0L).let {
            if (videoDurationMs > 0L) it.coerceAtMost((videoDurationMs - 1L).coerceAtLeast(0L)) else it
        }
        return PreparedClip(
            stream = stream,
            fallbackUrl = null,
            sliced = true,
            clipStartMs = calibratedStartMs,
            bytesPerMs = bytesPerMs,
            maxWindowMs = CLIP_SLICE_MS,
            resolveStart = null,
            resolveWindowDuration = null,
            onWarmed = { actualStartMs, actualWindowMs -> clipCache?.remember(key, record(actualStartMs, actualWindowMs)) },
        )
    }

    private suspend fun ProxyByteSource.findVerifiedSlice(
        offset: Long,
        primaryWindow: Long,
        maxWindow: Long,
    ): TsTimestamps.VerifiedSlice? {
        suspend fun scanFrom(startOffset: Long, limitBytes: Long): TsTimestamps.VerifiedSlice? {
            val maxAvailable = minOf(limitBytes, (size - startOffset).coerceAtLeast(0L))
            if (maxAvailable <= 0L) return null
            val scanner = TsTimestamps.VerifiedSliceScanner()
            val contextOwner = currentCoroutineContext()[TransferDemand]
            val tempOwner = if (contextOwner == null) {
                TransferDemand(
                    ConsumerIntent(
                        service = ServiceIntent.WAITING,
                        bufferBudget = BufferBudget(maxReadAheadBytes = CLIP_SCAN_READ_AHEAD_BYTES),
                    ),
                )
            } else {
                null
            }
            val owner = contextOwner ?: tempOwner!!
            val ticket = beginRead(owner, startOffset, startOffset + maxAvailable)
            var endReason = ReadEndReason.Cancelled
            try {
                var scanned = 0L
                val scratch = ByteArray(minOf(KEYFRAME_SCAN_CHUNK.toLong(), maxAvailable).toInt())
                while (scanned < maxAvailable) {
                    val toRead = minOf(scratch.size.toLong(), maxAvailable - scanned).toInt()
                    val filled = ticket.readSome(scratch, 0, toRead)
                    if (filled <= 0) break
                    val chunk = if (filled == scratch.size) scratch else scratch.copyOf(filled)
                    scanner.feed(chunk, startOffset + scanned)?.let { return it }
                    scanned += filled
                }
                endReason = ReadEndReason.RangeCompleted
            } catch (e: CancellationException) {
                endReason = ReadEndReason.Cancelled
                throw e
            } catch (e: Exception) {
                endReason = ReadEndReason.SourceFailed
                throw e
            } finally {
                ticket.endRead(endReason)
                tempOwner?.close()
            }
            return scanner.finish()
        }

        scanFrom(offset, maxWindow)?.let { return it }
        if (offset > 0L && size - offset < primaryWindow) {
            val rewindStart = ((offset - primaryWindow).coerceAtLeast(0L) / TS_PACKET_BYTES) * TS_PACKET_BYTES
            if (rewindStart < offset) {
                return scanFrom(rewindStart, offset - rewindStart)
            }
        }
        return null
    }

    fun openStoryboardSession(source: StoryboardSource): StoryboardTransportSession =
        StoryboardTransportSession(
            source = source,
            currentClientOrNull = currentClientOrNull,
            fileCachePoolProvider = fileCachePoolProvider,
            leaseFolderProvider = leaseFolderProvider,
            detailResolver = detailResolver,
            qualityProber = qualityProber,
        )

    suspend fun resolveStoryboardStreamSize(source: StoryboardSource): Long? = withContext(Dispatchers.IO) {
        val accountClient = currentClientOrNull() ?: return@withContext null
        source.streamSizeBytes?.takeIf { it > 0L }?.let { return@withContext it }
        qualityProber.knownTranscodeStreamSizeFor(source.fileId, source.gcid, 0L, source.mediaId)?.let { return@withContext it }
        val detail = detailResolver.takeFreshDetail(accountClient, source.fileId)
        val variant = detail?.medias?.firstOrNull { it.mediaId == source.mediaId && it.link.url.isNotBlank() }
        val targetUrl = variant?.link?.url ?: source.url
        if (targetUrl.isBlank()) return@withContext null
        val probed = runSuspendCatching {
            withTimeoutOrNull(PREVIEW_FETCH_TIMEOUT_MILLIS) {
                accountClient.streamRangeFromUrl(targetUrl, start = 0L, length = 1L) { stream ->
                    val size = stream.totalSize?.takeIf { it > 0L } ?: return@streamRangeFromUrl null
                    val read = runSuspendCatching { stream.channel.readAvailable(ByteArray(1), 0, 1) }.getOrNull() ?: -1
                    if (read < 1) null else size
                }
            }
        }.getOrNull()
        if (probed != null && detail != null) {
            qualityProber.recordTranscodeStreamSize(accountClient, detail, source.mediaId, probed, unreadable = false)
        }
        probed
    }

    suspend fun fetchStoryboardRange(
        source: StoryboardSource,
        streamSize: Long,
        offset: Long,
        length: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (streamSize <= TS_PACKET_BYTES || offset < 0L || length <= 0) return@withContext null
        val accountClient = currentClientOrNull() ?: return@withContext null
        val fetchLen = minOf(length.toLong(), streamSize - offset).toInt()
        if (fetchLen <= 0) return@withContext null

        fileCachePoolProvider()?.readCachedBytes(
            account = accountClient.account,
            gcid = source.gcid,
            totalBytes = streamSize,
            mediaId = source.mediaId,
            offset = offset,
            length = fetchLen,
        )?.let { return@withContext it }

        val freshUrl = detailResolver.takeFreshDetail(accountClient, source.fileId)
            ?.medias
            ?.firstOrNull { it.mediaId == source.mediaId && it.link.url.isNotBlank() }
            ?.link?.url
            ?: source.url
        if (freshUrl.isBlank()) return@withContext null

        withTimeoutOrNull(STORYBOARD_RANGE_TIMEOUT_MILLIS) {
            runSuspendCatching {
                accountClient.streamRangeFromUrl(freshUrl, start = offset, length = fetchLen.toLong()) { stream ->
                    val result = ByteArray(fetchLen)
                    var filled = 0
                    while (filled < fetchLen) {
                        val read = stream.channel.readAvailable(result, filled, fetchLen - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    if (filled == fetchLen) result else result.copyOf(filled)
                }
            }.getOrNull()
        }
    }

    suspend fun fetchPreviewChunk(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        chunkBytes: Int = DEFAULT_PREVIEW_CHUNK_BYTES,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (fileId.isBlank() || durationMillis <= 0L || LocalLocation.isLocal(fileId)) {
            return@withContext null
        }
        val accountClient = currentClientOrNull() ?: return@withContext null
        val detail = detailResolver.takeFreshDetail(accountClient, fileId) ?: return@withContext null
        val sink = metadataSinkProvider()
        val content = sink?.lookupContent(accountClient, detail.hash, detail.sizeBytes)
            ?: sink?.lookupContentByFileId(accountClient, fileId)
        val unreadableIds = content?.transcodes?.filter { it.unreadable }?.map { it.mediaId }?.toSet().orEmpty()
        val variant = detail.medias
            .mapNotNull { media ->
                val h = StoryboardSourceResolver.eligibleHeightOrNull(media, unreadableIds) ?: return@mapNotNull null
                media to h
            }
            .minByOrNull { (_, h) -> h }
            ?.first
            ?: return@withContext null
        val streamSize = qualityProber.knownTranscodeStreamSize(accountClient, detail, variant.mediaId)
            ?: runSuspendCatching {
                withTimeoutOrNull(PREVIEW_FETCH_TIMEOUT_MILLIS) {
                    qualityProber.probeTranscode(accountClient, variant)
                }
            }.getOrNull()?.also { probedSize ->
                qualityProber.recordTranscodeStreamSize(accountClient, detail, variant.mediaId, probedSize, unreadable = false)
            }
            ?: return@withContext null
        if (streamSize <= TS_PACKET_BYTES) return@withContext null
        val clampedPos = positionMillis.coerceIn(0L, durationMillis)
        val rawOffset = ((streamSize.toDouble() * clampedPos.toDouble() / durationMillis.toDouble()).toLong() / TS_PACKET_BYTES) * TS_PACKET_BYTES
        val offset = rawOffset.coerceIn(0L, (streamSize - TS_PACKET_BYTES).coerceAtLeast(0L))
        val fetchLen = minOf(chunkBytes.toLong(), streamSize - offset).coerceAtLeast(TS_PACKET_BYTES)
        fileCachePoolProvider()?.readCachedBytes(
            account = accountClient.account,
            gcid = detail.hash,
            totalBytes = streamSize,
            mediaId = variant.mediaId,
            offset = offset,
            length = fetchLen.toInt(),
        )?.let { cached -> return@withContext alignMpegTsSync(cached) }
        val bytes = withTimeoutOrNull(PREVIEW_FETCH_TIMEOUT_MILLIS) {
            runSuspendCatching {
                accountClient.streamRangeFromUrl(variant.link.url, start = offset, length = fetchLen) { stream ->
                    val target = fetchLen.toInt()
                    val result = ByteArray(target)
                    var filled = 0
                    while (filled < target) {
                        val read = stream.channel.readAvailable(result, filled, target - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    if (filled == target) result else result.copyOf(filled)
                }
            }.getOrNull()
        } ?: return@withContext null
        alignMpegTsSync(bytes)
    }

    private fun alignMpegTsSync(bytes: ByteArray): ByteArray? {
        if (bytes.size < TS_PACKET_BYTES.toInt() * 2) return null
        val limit = minOf(bytes.size - TS_PACKET_BYTES.toInt(), TS_PACKET_BYTES.toInt())
        for (i in 0 until limit) {
            if (bytes[i] == 0x47.toByte() && bytes[i + TS_PACKET_BYTES.toInt()] == 0x47.toByte()) {
                return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
            }
        }
        return null
    }

    private companion object {
        const val CLIP_RESOLUTION = "720P"
        const val TS_PACKET_BYTES = 188L
        const val STREAM_START_PTS_MS = 1_480L
        const val KEYFRAME_SCAN_CHUNK = 188 * 1394
        const val VERIFIED_KEYFRAME_WINDOW_MS = 15_000L
        const val MIN_VERIFIED_KEYFRAME_SCAN_BYTES = 2L * 1024 * 1024
        const val MAX_VERIFIED_KEYFRAME_SCAN_BYTES = 6L * 1024 * 1024
        const val CLIP_SLICE_MS = 180_000L
        const val SLICE_BITRATE_MARGIN = 1.5
        const val PREVIEW_FETCH_TIMEOUT_MILLIS = 6_000L
        const val STORYBOARD_RANGE_TIMEOUT_MILLIS = 10_000L
    }
}
