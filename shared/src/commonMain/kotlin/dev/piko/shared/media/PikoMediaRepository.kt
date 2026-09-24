package dev.piko.shared.media

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.media.proxy.PikPakByteSource
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyStream
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
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
fun PlayableMediaInfo.bestTranscodeName(): String? = availableVariants
    .filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }
    .maxByOrNull { it.video?.height ?: 0 }
    ?.let { it.mediaName.ifBlank { it.resolutionName } }
    ?.takeIf { it.isNotBlank() }

/**
 * 一次播放准备的结果。关闭它即释放代理会话、reader 与 handle。
 */
class PreparedPlayback internal constructor(
    val info: PlayableMediaInfo,
    private val stream: ProxyStream?,
) : AutoCloseable {
    /** 本机代理的地址。handle 建不起来（例如没有 gcid）时为 null，只能读直链。 */
    val proxyUrl: String? get() = stream?.url

    override fun close() {
        stream?.close()
    }
}

class PikoMediaRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    // 进程内一个就够：端口按需绑定，没有会话时只占一个监听 socket
    private val proxy by lazy { PikoMediaProxy() }

    suspend fun prepareMedia(fileId: String, preferredResolution: String? = null): Result<PlayableMediaInfo> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val detail = client.getFile(fileId)
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
    suspend fun preparePlayback(fileId: String, preferredResolution: String? = null): Result<PreparedPlayback> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = client
                val detail = client.getFile(fileId)
                val resolved = detail.resolveVariant(preferenceFor(preferredResolution))
                val info = playableMediaInfo(detail, resolved, fileId)
                val stream = if (info.kind == PlayableMediaKind.Video) openProxyStream(client, detail, resolved) else null
                PreparedPlayback(info, stream)
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
                val detail = client.getFile(fileId)
                val resolved = detail.resolveVariant(VariantPreference.Original)
                openProxyStream(client, detail, resolved)
            }.getOrNull()
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
    ): ProxyStream? {
        val source = openByteSource(client, detail, resolved) ?: return null
        return try {
            proxy.register(source, fileName = detail.name.takeIf { resolved.isOrigin })
        } catch (e: CancellationException) {
            source.close()
            throw e
        } catch (_: Exception) {
            source.close()
            null
        }
    }

    /**
     * 按偏移读取原画，不经本机代理。给 Android 的片段抽取用，原因见 [RandomAccessMediaSource]。
     * 调用方负责关闭。
     */
    suspend fun openRandomAccess(fileId: String): Result<RandomAccessMediaSource> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val client = client
                val detail = client.getFile(fileId)
                val resolved = detail.resolveVariant(VariantPreference.Original)
                val source = openByteSource(client, detail, resolved) ?: error("文件缺少内容哈希，无法读取")
                ReaderRandomAccessSource(source)
            }
        }

    /** 建 handle 与字节来源。没有 gcid 或拿不到大小时返回 null；取消照常抛出。 */
    private suspend fun openByteSource(
        client: PikPakClient,
        detail: FileDetail,
        resolved: ResolvedVariant,
    ): PikPakByteSource? {
        // handle 在直链被拒时按 gcid 重建文件对象，没有 gcid 就失去了它存在的意义
        if (detail.hash.isBlank()) return null
        val handle = PikPakFileHandle(
            client = client,
            gcid = detail.hash,
            size = detail.sizeBytes,
            name = detail.name,
            initialFileId = detail.id,
            mediaId = resolved.mediaId,
            // 原文件被删后 handle 会按 gcid 秒传重建一份，不给目录就落到网盘根目录
            parentId = detail.parentId,
        )
        return try {
            // 原画的大小已知；转码流没有，streamSize 会发一次 1 字节探测
            val size = resolved.sizeBytes ?: handle.streamSize()
            PikPakByteSource(handle, size, proxy.readerContext)
        } catch (e: CancellationException) {
            handle.close()
            throw e
        } catch (_: Exception) {
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

/** 清晰度菜单里代表原画的那一项，也是 [PikoMediaRepository] 认的原画标识。 */
const val ORIGINAL_QUALITY = "Original"

fun mediaKindOf(name: String): PlayableMediaKind = when (name.substringAfterLast('.', "").lowercase()) {
    "avif", "bmp", "heic", "heif", "jpeg", "jpg", "png", "webp" -> PlayableMediaKind.Image
    "gif" -> PlayableMediaKind.UnsupportedImage
    else -> PlayableMediaKind.Video
}
