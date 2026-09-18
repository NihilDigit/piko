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
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.resolveVariant
import kotlinx.coroutines.Dispatchers
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
)

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
            runCatching {
                val detail = client.getFile(fileId)
                val preference = if (preferredResolution.isNullOrBlank() || preferredResolution == "Original") {
                    VariantPreference.Original
                } else {
                    VariantPreference.Resolution(preferredResolution)
                }

                val resolved = detail.resolveVariant(preference)
                val duration = resolved.video?.duration ?: 0L

                PlayableMediaInfo(
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
        }

    /**
     * 构建基于 8 连接并发分块预取的播放会话 (PikoStreamSession)
     * 遵循 Animeko 架构规范，为 ExoPlayer 提供底层多连接高吞吐取流
     */
    @OptIn(UnstableApi::class)
    suspend fun createStreamSession(
        fileId: String,
        preferredResolution: String? = null,
        concurrency: Int = 8,
    ): Result<Pair<PlayableMediaInfo, PikoStreamSession>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val detail = client.getFile(fileId)
                val preference = if (preferredResolution.isNullOrBlank() || preferredResolution == "Original") {
                    VariantPreference.Original
                } else {
                    VariantPreference.Resolution(preferredResolution)
                }

                val resolved = detail.resolveVariant(preference)
                val duration = resolved.video?.duration ?: 0L
                val mediaInfo = PlayableMediaInfo(
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

                val handle = PikPakFileHandle(
                    client = client,
                    gcid = detail.hash,
                    size = detail.sizeBytes,
                    name = detail.name,
                    initialFileId = detail.id,
                    mediaId = resolved.mediaId,
                    connectionBudget = concurrency,
                )

                val streamSize = resolved.sizeBytes ?: handle.streamSize()
                val reader = handle.openStream(
                    size = streamSize,
                    concurrency = concurrency,
                    parentCoroutineContext = Dispatchers.IO,
                )

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

                Pair(mediaInfo, session)
            }
        }

    suspend fun refreshPlayUrl(fileId: String, mediaId: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val detail = client.getFile(fileId)
                if (mediaId.isNullOrEmpty()) {
                    detail.octetStream.url
                } else {
                    detail.medias.firstOrNull { it.mediaId == mediaId }?.link?.url
                        ?: detail.octetStream.url
                }
            }
        }
}
