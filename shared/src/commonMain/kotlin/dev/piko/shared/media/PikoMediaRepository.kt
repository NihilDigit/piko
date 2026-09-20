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
    /** 画面尺寸，服务端没抽出元数据时为 0。 */
    val width: Int = 0,
    val height: Int = 0,
    /** 原文件的容器与视频编码，取自服务端抽的元数据，未知为空。 */
    val originContainer: String = "",
    val originVideoCodec: String = "",
) {
    /** 横向画面。尺寸未知时为 null，界面据此决定要不要提示全屏。 */
    val isLandscapeVideo: Boolean?
        get() = if (width <= 0 || height <= 0) null else width > height
}

/**
 * 本机解码器拿不下的容器与编码。
 *
 * 判扩展名会漏：盘里有 mime 为 video/x-ms-asf、origin 编码是 vc1 却连扩展名都没有的文件。
 * 服务端抽元数据时已经把容器和编码写在 origin 那条 media 里，直接认它。
 */
private val UNDECODABLE_CONTAINERS = setOf("asf", "rm", "rmvb")
private val UNDECODABLE_VIDEO_CODECS = setOf("vc1", "wmv1", "wmv2", "wmv3", "rv30", "rv40", "msmpeg4v1", "msmpeg4v2")

/** 原文件本机放不了，只能改读转码流。元数据缺失时返回 false，交给实际播放去试。 */
fun PlayableMediaInfo.originNeedsTranscode(): Boolean =
    originContainer.split(',').any { it.trim().lowercase() in UNDECODABLE_CONTAINERS } ||
        originVideoCodec.lowercase() in UNDECODABLE_VIDEO_CODECS

/**
 * 可读的转码流里画面最大的那个。空表示这个文件没有能用的转码——
 * medias 里既有还在转的（video 为空），也有转完却没给链接的。
 */
fun PlayableMediaInfo.bestTranscodeName(): String? = availableVariants
    .filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }
    .maxByOrNull { it.video?.height ?: 0 }
    ?.let { it.mediaName.ifBlank { it.resolutionName } }
    ?.takeIf { it.isNotBlank() }

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
            width = resolved.video?.width ?: 0,
            height = resolved.video?.height ?: 0,
            // 固定取 origin 那条：resolved 可能已经是转码流，它的容器当然是 mpegts，
            // 而这里要回答的是「原文件本机放不放得了」
            originContainer = detail.medias.firstOrNull { it.isOrigin }?.video?.videoType.orEmpty(),
            originVideoCodec = detail.medias.firstOrNull { it.isOrigin }?.video?.videoCodec.orEmpty(),
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
