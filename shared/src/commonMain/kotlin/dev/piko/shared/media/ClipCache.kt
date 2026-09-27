package dev.piko.shared.media

import io.github.nihildigit.pikpak.BlockStore
import kotlin.concurrent.Volatile

/**
 * 重建随机片段一段切片要的全部信息。有了它，同一段下次再来不必查详情：建 handle 要的内容哈希、
 * 文件对象与长度都在这里，开头与末尾的字节在 [ClipCache.blocks] 里，只有读到中间才连网。
 */
class ClipRecord(
    val fileId: String,
    val gcid: String,
    val name: String,
    val parentId: String,
    /** 原文件的字节数。文件对象失效时 SDK 按内容哈希重建要用它，与转码流的长度无关。 */
    val originalBytes: Long,
    val mediaId: String,
    /** 整条转码流的字节数，重建时不必再探。 */
    val streamBytes: Long,
    /** 切片在转码流里的起点与长度。 */
    val sliceOffset: Long,
    val sliceLength: Long,
    /** 切片实际从全片哪一刻开始，读自切片里的时间戳，见 PreparedClip。 */
    val sliceStartMs: Long,
)

/**
 * 随机片段的磁盘缓存：切片的块，外加每段的 [ClipRecord]。
 *
 * 字节是内容本身，不会过期，会过期的只是直链。块按内容哈希与档位存，不按文件，
 * 所以换了文件对象、换了直链都照样命中。
 */
interface ClipCache {
    val blocks: BlockStore

    suspend fun record(key: String): ClipRecord?

    suspend fun remember(key: String, record: ClipRecord)
}

/**
 * 只把落在 [kept] 里的块交给 [inner] 存，读照常。
 *
 * SDK 会把取到的每一块都交给存储，而一段放下去读到的远不止开头：只存开头与末尾，
 * 300 MB 能装两百来段；连着放过的部分也存，只装得下几十段。
 *
 * [kept] 可以事后设：切片的位置要等 handle 探出流长度才算得出，而存储在建 handle 时就得交进去。
 * 设好之前没有块可取，也就没有块会被漏存。
 */
internal class RangeLimitedStore(
    private val inner: BlockStore,
    @Volatile var kept: List<LongRange> = emptyList(),
) : BlockStore {
    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = inner.read(file, offset, length)

    override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
        val block = offset until offset + bytes.size
        if (kept.any { it.first <= block.last && block.first <= it.last }) inner.write(file, offset, bytes)
    }
}
