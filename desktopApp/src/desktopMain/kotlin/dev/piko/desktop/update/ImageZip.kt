package dev.piko.desktop.update

import dev.piko.shared.update.ChecksumMismatchException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * 整个应用目录的 zip（Release 附件 piko-windows-<架构>-<版本>-image.zip，packageReleaseUpdate 打出），给补丁对不上的便携版用：
 * 按 HTTP Range 先取中央目录，再只取本机缺的或不同的条目。JDK 升级时运行时几乎全变，按文件取约 34 MB，整个约 116 MB。
 *
 * 不把 app.zip 扩成全量：1.1.0 及更早的客户端整个下 app.zip，清单外的条目一律当成异常拒掉，它们的补丁更新就坏了。
 * 便携包（7z）给人手动下载，JDK 解不了，系统的 tar.exe 在旧系统上未必认得，所以应用内更新不碰它。
 */
internal class ImageCatalog(val entries: Map<String, ImageEntry>)

internal class ImageEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val nameLength: Int,
    /** 中央目录里的扩展字段长度。本地文件头的可能不同（JDK 本地写的时间戳字段比中央目录的长），取数据时按实际的算。 */
    val centralExtraLength: Int,
)

private const val EOCD_SIGNATURE = 0x06054b50
private const val CENTRAL_SIGNATURE = 0x02014b50
private const val LOCAL_SIGNATURE = 0x04034b50
private const val LOCAL_HEADER = 30
private const val STORED = 0
private const val DEFLATED = 8

/** 取 [size] 字节的 zip 的中央目录。[range] 取闭区间的字节。不认 ZIP64：应用目录远不到 4 GB、6 万个文件。 */
internal suspend fun readImageCatalog(size: Long, range: suspend (LongRange) -> ByteArray): ImageCatalog {
    // 结尾记录 22 字节，后面最多跟 64 KB 的注释
    val tailLength = minOf(size, 22L + 0xFFFF)
    val tailStart = size - tailLength
    val tail = range(tailStart until size)
    val eocd = (tail.size - 22 downTo 0).firstOrNull { tail.u32(it) == EOCD_SIGNATURE.toLong() }
        ?: throw IOException("image.zip 里找不到中央目录的结尾记录")
    val centralSize = tail.u32(eocd + 12)
    val centralOffset = tail.u32(eocd + 16)
    if (centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) throw IOException("image.zip 是 ZIP64，不支持")
    val central = if (centralOffset >= tailStart) {
        tail.copyOfRange((centralOffset - tailStart).toInt(), (centralOffset - tailStart + centralSize).toInt())
    } else {
        range(centralOffset until centralOffset + centralSize)
    }
    val entries = mutableMapOf<String, ImageEntry>()
    var at = 0
    while (at + 46 <= central.size && central.u32(at) == CENTRAL_SIGNATURE.toLong()) {
        val nameLength = central.u16(at + 28)
        val extraLength = central.u16(at + 30)
        val commentLength = central.u16(at + 32)
        val name = central.decodeToString(at + 46, at + 46 + nameLength).replace('\\', '/')
        if (!name.endsWith("/")) {
            entries[name] = ImageEntry(
                name = name,
                method = central.u16(at + 10),
                compressedSize = central.u32(at + 20),
                size = central.u32(at + 24),
                localHeaderOffset = central.u32(at + 42),
                nameLength = nameLength,
                centralExtraLength = extraLength,
            )
        }
        at += 46 + nameLength + extraLength + commentLength
    }
    return ImageCatalog(entries)
}

/**
 * 按 Range 取 [wanted] 的条目，写到 [target] 下，逐个对照清单校验并还原修改时间。[range] 把闭区间的字节分块交出；
 * [onBytes] 报告已收到的字节数，用于进度。
 */
internal suspend fun fetchImageEntries(
    catalog: ImageCatalog,
    wanted: List<ManifestFile>,
    target: File,
    range: suspend (LongRange, (ByteArray, Int) -> Unit) -> Unit,
    onBytes: (Long) -> Unit = {},
) {
    val root = target.canonicalFile
    for (spec in wanted) {
        val entry = catalog.entries[spec.path] ?: throw ChecksumMismatchException("image.zip 缺少：${spec.path}")
        val out = outputFor(root, spec)
        val receiver = EntryReceiver(entry, out.outputStream().buffered())
        receiver.use {
            // 先按中央目录的扩展字段长度估本地文件头；本地的更长时，第一段收完数据还差一截，再补取
            var next = entry.localHeaderOffset
            val end = entry.localHeaderOffset + LOCAL_HEADER + entry.nameLength + entry.centralExtraLength + entry.compressedSize
            range(next until end) { buffer, length -> receiver.accept(buffer, length); onBytes(length.toLong()) }
            next = end
            while (!receiver.complete) {
                val missing = receiver.dataMissing
                range(next until next + missing) { buffer, length -> receiver.accept(buffer, length); onBytes(length.toLong()) }
                next += missing
            }
            receiver.finish()
        }
        verify(out, spec, receiver.sha256)
    }
}

/** 镜像不认 Range 时整个下载下来，从本地的 [zip] 里取同样那些条目。 */
internal fun readImageEntries(zip: File, wanted: List<ManifestFile>, target: File) {
    val root = target.canonicalFile
    ZipFile(zip).use { archive ->
        for (spec in wanted) {
            val entry = archive.getEntry(spec.path) ?: throw ChecksumMismatchException("image.zip 缺少：${spec.path}")
            val out = outputFor(root, spec)
            val actual = archive.getInputStream(entry).use { input ->
                out.outputStream().use { output -> sha256Hex(input) { buffer, length -> output.write(buffer, 0, length) } }
            }
            verify(out, spec, actual)
        }
    }
}

/** 本机与 [manifest] 不同或缺的文件，加上补丁文件（jar 内容没变，修改时间也对不上新的 AOT 缓存）。 */
internal fun changedFiles(installDir: File, manifest: UpdateManifest): List<ManifestFile> = manifest.files.filter { entry ->
    entry.patch || installDir.resolve(entry.path).let { file ->
        !(file.isFile && file.length() == entry.size && file.inputStream().use(::sha256Hex) == entry.sha256)
    }
}

private fun outputFor(root: File, spec: ManifestFile): File {
    val out = root.resolve(spec.path).canonicalFile
    check(out.path.startsWith(root.path + File.separator)) { "路径越界：${spec.path}" }
    out.parentFile.mkdirs()
    return out
}

private fun verify(out: File, spec: ManifestFile, actual: String) {
    if (actual != spec.sha256 || out.length() != spec.size) throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
    out.setLastModified(spec.mtime)
}

/** 收一个条目的本地文件头加数据，解压写进 [out]。字节可能分几段到。 */
private class EntryReceiver(private val entry: ImageEntry, private val out: OutputStream) : AutoCloseable {
    private val header = ByteArray(LOCAL_HEADER)
    private var headerFilled = 0
    private var skip = -1L
    private var dataReceived = 0L
    private val inflater = Inflater(true)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val inflated = ByteArray(64 * 1024)

    init {
        if (entry.method != STORED && entry.method != DEFLATED) throw IOException("image.zip 的 ${entry.name} 用了不支持的压缩方式 ${entry.method}")
    }

    val complete get() = skip == 0L && dataReceived == entry.compressedSize
    val dataMissing get() = entry.compressedSize - dataReceived + maxOf(skip, 0L)
    val sha256: String get() = digest.digest().toHex()

    fun accept(buffer: ByteArray, length: Int) {
        var at = 0
        while (at < length) {
            if (headerFilled < LOCAL_HEADER) {
                val take = minOf(LOCAL_HEADER - headerFilled, length - at)
                System.arraycopy(buffer, at, header, headerFilled, take)
                headerFilled += take
                at += take
                if (headerFilled == LOCAL_HEADER) {
                    if (header.u32(0) != LOCAL_SIGNATURE.toLong()) throw IOException("image.zip 的 ${entry.name} 本地文件头不对")
                    skip = (header.u16(26) + header.u16(28)).toLong()
                }
                continue
            }
            if (skip > 0) {
                val take = minOf(skip, (length - at).toLong()).toInt()
                skip -= take
                at += take
                continue
            }
            // 第一段按中央目录估的长度取，本地扩展字段较短时末尾多出的是下一个条目的字节，丢掉
            val take = minOf(entry.compressedSize - dataReceived, (length - at).toLong()).toInt()
            if (take == 0) return
            data(buffer, at, take)
            dataReceived += take
            at += take
        }
    }

    private fun data(buffer: ByteArray, offset: Int, length: Int) {
        if (entry.method == STORED) {
            out.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            return
        }
        inflater.setInput(buffer, offset, length)
        while (!inflater.needsInput() && !inflater.finished()) {
            val count = inflater.inflate(inflated)
            if (count > 0) {
                out.write(inflated, 0, count)
                digest.update(inflated, 0, count)
            }
        }
    }

    /** 不带 zlib 头的 Inflater 有时要多喂一个字节才认出流已结束，ZipFile 自己也这样做。 */
    fun finish() {
        if (entry.method == DEFLATED && !inflater.finished()) data(ByteArray(1), 0, 1)
        if (entry.method == DEFLATED && !inflater.finished()) throw IOException("image.zip 的 ${entry.name} 解压不完整")
    }

    override fun close() {
        inflater.end()
        out.close()
    }
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
