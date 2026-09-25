package dev.piko.desktop

import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import dev.piko.shared.upload.UploadTreeEntry
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import kotlinx.io.RawSource
import kotlinx.io.asSource

/** 桌面端的 uri 就是本机绝对路径。 */
class DesktopPikoUploadSources : PikoUploadSources {
    override fun describe(uri: String): UploadSourceInfo? {
        val path = Path.of(uri)
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null
        return runCatching {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            UploadSourceInfo(path.fileName.toString(), attrs.size(), attrs.lastModifiedTime().toMillis())
        }.getOrNull()
    }

    override fun readAt(uri: String, offset: Long, length: Int): ByteArray =
        RandomAccessFile(uri, "r").use { file ->
            file.seek(offset)
            ByteArray(length).also(file::readFully)
        }

    override fun open(uri: String, offset: Long): RawSource {
        val stream = FileInputStream(uri)
        try {
            stream.channel.position(offset)
        } catch (e: IOException) {
            stream.close()
            throw e
        }
        return stream.asSource()
    }

    override fun listFolder(uri: String): UploadFolder? {
        val root = Path.of(uri)
        if (!Files.isDirectory(root) || !Files.isReadable(root)) return null
        val files = mutableListOf<UploadTreeEntry>()
        // 跟随链接才能让遍历器按文件标识检测环路；不跟随时 NTFS 的目录联接仍被当作普通目录进入，
        // 反而没有环路检测。成环的与无权读取的子目录各自跳过，不让整个文件夹失败
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && attrs.isHiddenSystem()) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isHiddenSystem()) {
                    val relativeDir = root.relativize(file.parent).joinToString("/")
                    files += UploadTreeEntry(file.toString(), relativeDir)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        runCatching { Files.walkFileTree(root, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, visitor) }
            .getOrElse { return null }
        // 选的是盘符根目录时没有文件名，取盘符
        val name = root.fileName?.toString() ?: root.toString().trimEnd('\\', '/', ':')
        return UploadFolder(name, files)
    }
}

/**
 * 同时带隐藏与系统属性的是 Explorer 自己的产物：desktop.ini、Thumbs.db，盘符根目录下的
 * $RECYCLE.BIN 与 System Volume Information。它们在 Explorer 里默认不可见，用户不知道自己选了它们。
 * 只带隐藏属性的照传，点开头的配置文件与用户手动隐藏的文件都属于这一类。
 */
private fun BasicFileAttributes.isHiddenSystem(): Boolean =
    this is DosFileAttributes && isHidden && isSystem
