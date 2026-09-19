package dev.piko.data.repository

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dev.piko.data.auth.SessionManager
import dev.piko.data.client.PikPakClientManager
import dev.piko.media.PikoRoutingDataSourceFactory
import dev.piko.media.PikoStreamDataSource
import dev.piko.media.PikoStreamSession
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.resolveVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PlayableMediaInfo(
    val fileId: String,
    val name: String,
    val gcid: String,
    val currentUrl: String,
    val durationSeconds: Long,
    val currentResolution: String,
    val availableVariants: List<MediaVariant>,
    val mediaId: String? = null,
    val sizeBytes: Long = 0L,
    val kind: PlayableMediaKind = mediaKindOf(name),
)

enum class PlayableMediaKind {
    Video,
    Image,
    UnsupportedImage,
}

class MediaRepository(
    private val clientManager: PikPakClientManager,
    private val sessionManager: SessionManager,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        sessionManager.savePlaybackPosition(fileId, positionMs)
    }

    suspend fun getPlaybackPosition(fileId: String): Long {
        return sessionManager.getPlaybackPosition(fileId)
    }

    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(withMediaRetries {
                    val detail = client.getFile(fileId)
                    val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                    playableMediaInfo(detail, resolved, fileId)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 构建带有独立取流会话的播放资源。
     *
     * 会话创建失败时必须关闭已经创建的 reader 和 handle，否则重试会累积连接与 worker。
     */
    @OptIn(UnstableApi::class)
    suspend fun createStreamSession(
        fileId: String,
        preferredResolution: String? = null,
        concurrency: Int = 8,
    ): Result<Pair<PlayableMediaInfo, PikoStreamSession>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(withMediaRetries {
                    val detail = client.getFile(fileId)
                    val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                    val mediaInfo = playableMediaInfo(detail, resolved, fileId)

                    val handle = PikPakFileHandle(
                        client = client,
                        gcid = detail.hash,
                        size = detail.sizeBytes,
                        name = detail.name,
                        initialFileId = detail.id,
                        mediaId = resolved.mediaId,
                        connectionBudget = concurrency,
                    )
                    val reader = try {
                        val streamSize = resolved.sizeBytes ?: handle.streamSize()
                        handle.openStream(
                            size = streamSize,
                            concurrency = concurrency,
                            parentCoroutineContext = Dispatchers.IO,
                        )
                    } catch (e: Throwable) {
                        handle.close()
                        throw e
                    }
                    try {
                        val pikoDataSourceFactory = DataSource.Factory {
                            PikoStreamDataSource(reader)
                        }
                        val routingFactory = PikoRoutingDataSourceFactory(
                            mediaUri = resolved.link.url,
                            pikoDataSourceFactory = pikoDataSourceFactory,
                            fallbackDataSourceFactory = DefaultHttpDataSource.Factory(),
                        )
                        val session = PikoStreamSession(
                            fileId = fileId,
                            mediaUri = resolved.link.url,
                            handle = handle,
                            reader = reader,
                            dataSourceFactory = routingFactory,
                        )
                        mediaInfo to session
                    } catch (e: Throwable) {
                        reader.close()
                        handle.close()
                        throw e
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshPlayUrl(fileId: String, mediaId: String?): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(withMediaRetries {
                    val detail = client.getFile(fileId)
                    if (mediaId.isNullOrEmpty()) {
                        detail.octetStream.url
                    } else {
                        detail.medias.firstOrNull { it.mediaId == mediaId }?.link?.url
                            ?: detail.octetStream.url
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun preferenceFor(preferredResolution: String?): VariantPreference =
        if (preferredResolution.isNullOrBlank() || preferredResolution == "Original") {
            VariantPreference.Original
        } else {
            VariantPreference.Resolution(preferredResolution)
        }

    private fun playableMediaInfo(
        detail: FileDetail,
        resolved: ResolvedVariant,
        fileId: String,
    ): PlayableMediaInfo {
        val duration = resolved.video?.duration ?: 0L
        return PlayableMediaInfo(
            fileId = fileId,
            name = detail.name,
            gcid = detail.hash,
            currentUrl = resolved.link.url,
            durationSeconds = duration,
            currentResolution = resolved.label,
            availableVariants = detail.medias,
            mediaId = resolved.mediaId,
            sizeBytes = resolved.sizeBytes ?: detail.sizeBytes,
        )
    }

    private suspend fun <T> withMediaRetries(block: suspend () -> T): T {
        var lastFailure: Exception? = null
        repeat(MEDIA_OPERATION_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is IllegalArgumentException || e is IllegalStateException) {
                    throw e
                }
                lastFailure = e
                if (attempt + 1 < MEDIA_OPERATION_ATTEMPTS) {
                    delay(MEDIA_RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw requireNotNull(lastFailure)
    }
}

private const val MEDIA_OPERATION_ATTEMPTS = 4
private val MEDIA_RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 4_000L)

internal fun mediaKindOf(name: String): PlayableMediaKind {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "avif", "bmp", "heic", "heif", "jpeg", "jpg", "png", "webp" -> PlayableMediaKind.Image
        "gif" -> PlayableMediaKind.UnsupportedImage
        else -> PlayableMediaKind.Video
    }
}
