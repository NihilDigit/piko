package dev.piko.shared.media

import dev.piko.shared.data.PikoClientProvider
import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.ResolvedVariant
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.resolveVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PlayableMediaKind { Video, Image, UnsupportedImage }

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

data class PikoPlayableMediaInfo(
    val fileId: String,
    val name: String,
    val uri: String,
    val durationSeconds: Long,
    val availableVariants: List<MediaVariant>,
)

class PikoMediaRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val detail = client.getFile(fileId)
                val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                playableMediaInfo(detail, resolved, fileId)
            }
        }

    suspend fun createMediaData(
        fileId: String,
        preferredResolution: String? = null,
        concurrency: Int = 8,
    ): Result<Pair<PikoPlayableMediaInfo, PikoSeekableMediaData>> = withContext(Dispatchers.Default) {
        try {
            Result.success(createMediaDataInternal(fileId, preferredResolution, concurrency))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun savePlaybackPosition(fileId: String, positionMillis: Long) {
        preferences?.savePlaybackPosition(fileId, positionMillis)
    }

    suspend fun getPlaybackPosition(fileId: String): Long =
        preferences?.getPlaybackPosition(fileId) ?: 0L

    private suspend fun createMediaDataInternal(
        fileId: String,
        preferredResolution: String?,
        concurrency: Int,
    ): Pair<PikoPlayableMediaInfo, PikoSeekableMediaData> {
        val detail = client.getFile(fileId)
        val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
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
                parentCoroutineContext = Dispatchers.Default,
            )
        } catch (e: Throwable) {
            handle.close()
            throw e
        }
        val info = PikoPlayableMediaInfo(
            fileId = fileId,
            name = detail.name,
            uri = resolved.link.url,
            durationSeconds = resolved.video?.duration ?: 0L,
            availableVariants = detail.medias,
        )
        return info to PikoSeekableMediaData(resolved.link.url, reader, handle)
    }

    private fun preferenceFor(resolution: String?): VariantPreference =
        if (resolution.isNullOrBlank() || resolution == "Original") {
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
            currentResolution = resolved.video?.height?.toString() ?: "Original",
            availableVariants = detail.medias,
            mediaId = resolved.mediaId,
            sizeBytes = resolved.sizeBytes ?: detail.sizeBytes,
        )

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

fun mediaKindOf(name: String): PlayableMediaKind = when (name.substringAfterLast('.', "").lowercase()) {
    "avif", "bmp", "heic", "heif", "jpeg", "jpg", "png", "webp" -> PlayableMediaKind.Image
    "gif" -> PlayableMediaKind.UnsupportedImage
    else -> PlayableMediaKind.Video
}
