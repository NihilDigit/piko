@file:JvmMultifileClass
@file:JvmName("PikoMediaRepositoryKt")

package dev.piko.shared.media

import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.InMemoryProxyByteSource
import dev.piko.shared.media.player.OriginalEmbeddedSubtitlesResult
import dev.piko.shared.media.player.OriginalSubtitleExtractor
import dev.piko.shared.media.player.OriginalSubtitleTrackInfo
import dev.piko.shared.media.player.SubtitleEncoding
import dev.piko.shared.media.player.detectSubtitleLanguageFromUtf8Bytes
import dev.piko.shared.media.player.inferSubtitleLanguageCode
import dev.piko.shared.media.player.isTextSubtitleName
import dev.piko.shared.media.proxy.ProxyStream
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.resolveVariant
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.ktor.utils.io.readAvailable
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 云端外挂字幕代理流及可选内容探测语言码。 */
class PreparedSubtitle(
    val stream: ProxyStream,
    val detectedLanguage: String? = null,
)

/** 转码流尝试提取原画内封字幕的会话结果。 */
class PreparedOriginalSubtitles(
    val attached: List<Pair<ExternalSubtitle, ProxyStream>> = emptyList(),
    val unextracted: List<OriginalSubtitleTrackInfo> = emptyList(),
)

internal class MediaSubtitleService(
    private val requireClient: () -> PikPakClient,
    private val currentClientOrNull: () -> PikPakClient?,
    private val fileCachePoolProvider: () -> PikoFileCachePool?,
    private val detailResolver: MediaDetailResolver,
    private val sessionPreparer: PlaybackSessionPreparer,
) {
    private val cachedOriginalSubtitles = HashMap<String, OriginalEmbeddedSubtitlesResult>()
    private val cachedOriginalSubtitlesLock = Mutex()
    private var cachedOriginalSubtitlesOwner: PikPakClient? = null

    suspend fun clearIfOwnerChanged(owner: PikPakClient?) {
        cachedOriginalSubtitlesLock.withLock {
            if (cachedOriginalSubtitlesOwner !== owner) {
                cachedOriginalSubtitles.clear()
                cachedOriginalSubtitlesOwner = owner
            }
        }
    }

    suspend fun prepareSubtitle(fileId: String): ProxyStream? =
        prepareExternalSubtitle(fileId)?.stream

    suspend fun prepareExternalSubtitle(
        fileId: String,
        knownLanguage: String? = null,
    ): PreparedSubtitle? {
        var resolvedName = ""
        val registeredStreams = ArrayList<ProxyStream>(1)
        return try {
            withContext(Dispatchers.Default) {
                runSuspendCatching {
                    val client = requireClient()
                    val detail = detailResolver.fetchOrReuseInteractiveDetail(client, fileId)
                    resolvedName = detail.name
                    val resolved = detail.resolveVariant(VariantPreference.Original)
                    val leased = LeasedFile.isLeased(fileId)
                    val expectedSize = (resolved.sizeBytes ?: detail.sizeBytes).coerceAtLeast(0L)
                    if (isTextSubtitleName(detail.name) && expectedSize in 1L..MAX_TEXT_SUBTITLE_BYTES) {
                        val transcoded = runSuspendCatching {
                            readAndNormalizeTextSubtitle(client, detail, resolved, leased, expectedSize.toInt())
                        }.getOrNull()
                        if (transcoded != null) {
                            val sniffedLang = knownLanguage ?: detectSubtitleLanguageFromUtf8Bytes(transcoded, detail.name)
                            val stream = sessionPreparer.registerProxy(
                                source = InMemoryProxyByteSource(transcoded),
                                fileName = detail.name,
                                fileLabel = logFile(detail.id, detail.name),
                            ) ?: return@runSuspendCatching null
                            registeredStreams += stream
                            return@runSuspendCatching PreparedSubtitle(
                                stream = stream,
                                detectedLanguage = sniffedLang,
                            )
                        }
                    }
                    sessionPreparer.openProxyStream(client, detail, resolved, leased = leased)?.let { stream ->
                        registeredStreams += stream
                        PreparedSubtitle(stream = stream, detectedLanguage = knownLanguage)
                    }
                }.logFailure(MEDIA_LOG_TAG, "Failed to prepare external subtitle stream: ${logFile(fileId, resolvedName)}").getOrNull()
            }
        } catch (t: Throwable) {
            registeredStreams.forEach { runCatching { it.close() } }
            throw t
        }
    }

    private suspend fun readAndNormalizeTextSubtitle(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
        leased: Boolean,
        expectedSize: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val source = sessionPreparer.openByteSource(client, detail, resolved, leased = leased) ?: return@withContext null
        try {
            val raw = source.openReader().use { reader ->
                val out = ByteArray(expectedSize)
                var filled = 0
                while (filled < expectedSize) {
                    val n = reader.read(out, filled, expectedSize - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == 0) return@use null
                if (filled == expectedSize) out else out.copyOf(filled)
            } ?: return@withContext null
            SubtitleEncoding.current.normalizeToUtf8(raw)
        } finally {
            runCatching { source.close() }
        }
    }

    suspend fun prepareOriginalEmbeddedSubtitles(fileId: String): PreparedOriginalSubtitles {
        val registeredStreams = ArrayList<ProxyStream>()
        return try {
            withContext(Dispatchers.IO) {
                if (fileId.isBlank() || LocalLocation.isLocal(fileId)) {
                    return@withContext PreparedOriginalSubtitles()
                }
                val accountClient = currentClientOrNull() ?: return@withContext PreparedOriginalSubtitles()
                val cached = cachedOriginalSubtitlesLock.withLock {
                    if (cachedOriginalSubtitlesOwner === accountClient) cachedOriginalSubtitles[fileId] else null
                }
                val result = if (cached != null) {
                    cached
                } else {
                    val detail = detailResolver.takeFreshDetail(accountClient, fileId) ?: return@withContext PreparedOriginalSubtitles()
                    val original = detail.resolveVariant(VariantPreference.Original)
                    val url = original.link.url.takeIf { it.isNotBlank() } ?: return@withContext PreparedOriginalSubtitles()
                    val fileSize = detail.sizeBytes
                    if (fileSize <= 0L) return@withContext PreparedOriginalSubtitles()
                    val pool = fileCachePoolProvider()
                    val fullyCached = if (pool != null && detail.hash.isNotBlank()) {
                        runSuspendCatching {
                            pool.cachedCoverage(
                                account = accountClient.account,
                                gcid = detail.hash,
                                totalBytes = fileSize,
                                mediaId = null,
                                windowStartByte = 0L,
                                windowLengthBytes = minOf(fileSize, ABR_CACHE_WINDOW_BYTES),
                            ).coverageRatio >= 1.0f
                        }.getOrDefault(false)
                    } else {
                        false
                    }
                    val extracted = runSuspendCatching {
                        OriginalSubtitleExtractor.extract(
                            fileSize = fileSize,
                            readRange = { offset, length ->
                                val fetchLen = minOf(length.toLong(), (fileSize - offset).coerceAtLeast(0L)).toInt()
                                if (fetchLen <= 0 || offset < 0L) {
                                    null
                                } else {
                                    val cachedRange = if (detail.hash.isNotBlank()) {
                                        pool?.readCachedBytes(
                                            account = accountClient.account,
                                            gcid = detail.hash,
                                            totalBytes = fileSize,
                                            mediaId = null,
                                            offset = offset,
                                            length = fetchLen,
                                        )
                                    } else {
                                        null
                                    }
                                    cachedRange ?: withTimeoutOrNull(ORIGINAL_SUB_RANGE_TIMEOUT_MILLIS) {
                                        runSuspendCatching {
                                            accountClient.streamRangeFromUrl(url, start = offset, length = fetchLen.toLong()) { stream ->
                                                val buf = ByteArray(fetchLen)
                                                var filled = 0
                                                while (filled < fetchLen) {
                                                    val n = stream.channel.readAvailable(buf, filled, fetchLen - filled)
                                                    if (n <= 0) break
                                                    filled += n
                                                }
                                                if (filled == fetchLen) buf else buf.copyOf(filled)
                                            }
                                        }.getOrNull()
                                    }
                                }
                            },
                            canScanFullMkv = fullyCached,
                        )
                    }.getOrElse {
                        PikoLog.d(MEDIA_LOG_TAG, "Original subtitle probe skipped for ${logFile(fileId, detail.name)}: ${it::class.simpleName}: ${it.message}")
                        OriginalEmbeddedSubtitlesResult()
                    }
                    cachedOriginalSubtitlesLock.withLock {
                        if (cachedOriginalSubtitlesOwner !== accountClient) {
                            cachedOriginalSubtitles.clear()
                            cachedOriginalSubtitlesOwner = accountClient
                        }
                        if (cachedOriginalSubtitles.size >= MAX_CACHED_ORIGINAL_SUB_FILES) {
                            cachedOriginalSubtitles.clear()
                        }
                        cachedOriginalSubtitles[fileId] = extracted
                    }
                    extracted
                }
                val attached = ArrayList<Pair<ExternalSubtitle, ProxyStream>>(result.extracted.size)
                for (sub in result.extracted) {
                    val stream = sessionPreparer.registerProxy(
                        source = InMemoryProxyByteSource(sub.bytes),
                        fileName = sub.fileName,
                        fileLabel = logFile(fileId, sub.fileName),
                    ) ?: continue
                    registeredStreams += stream
                    val lang = inferSubtitleLanguageCode(sub.info.language, sub.info.title ?: sub.fileName)
                        ?: detectSubtitleLanguageFromUtf8Bytes(sub.bytes, sub.fileName)
                    val title = sub.info.title
                        ?: sub.info.language?.uppercase()
                        ?: if (lang != null) "" else tr("原画字幕", "Original subtitles", "オリジナル字幕", "원본 자막", "原畫字幕")
                    attached += ExternalSubtitle(url = stream.url, title = title, language = lang) to stream
                }
                PreparedOriginalSubtitles(attached = attached, unextracted = result.unextracted)
            }
        } catch (t: Throwable) {
            registeredStreams.forEach { runCatching { it.close() } }
            throw t
        }
    }

    companion object {
        private const val ORIGINAL_SUB_RANGE_TIMEOUT_MILLIS = 8_000L
        private const val MAX_CACHED_ORIGINAL_SUB_FILES = 16
        private const val MAX_TEXT_SUBTITLE_BYTES = 8L * 1024L * 1024L
    }
}
