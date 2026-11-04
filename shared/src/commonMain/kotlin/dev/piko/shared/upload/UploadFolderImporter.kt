package dev.piko.shared.upload

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/** 外部文件夹快照里一条路径的类型。符号链接、设备文件之类都算 [OTHER]，导入时直接跳过。 */
enum class UploadFolderEntryKind { DIRECTORY, FILE, OTHER }

/**
 * 一份外部文件夹在导入那一刻的只读快照。平台各自实现：iOS 用 NSFileManager 在安全作用域里列目录，
 * 其它平台若将来需要导入也能照这个面写。相对路径一律用斜杠分隔，空串代表根。
 */
interface UploadFolderSnapshot {
    /** 不存在（或读不了）返回 null。 */
    fun kindOf(relativePath: String): UploadFolderEntryKind?

    /** 一层的名字，顺序不保证，导入方自己排。读不了抛 [IOException]。 */
    fun listDirectory(relativePath: String): List<String>

    /** 打开一个普通文件，按顺序读。用完由导入方关闭。 */
    fun openFile(relativePath: String): RawSource
}

/**
 * 把一个外部文件夹整棵复制进应用私有的上传根，返回 `piko-app-upload:` 标识，交给
 * [PikoUploadSources.listFolder] 就能列出整棵树。与 [AppDirectoryUploadImporter] 同一套约定：
 * 先整棵复制到独立的暂存目录，成功后才整个目录搬进上传根，失败与取消不留半截；
 * 每个文件按 64 KiB 分片流式复制，逐文件与总量两道上限。
 *
 * 与单文件导入的差别只在发布方式：单文件是 `atomicMove` 一个文件，这里是整个目录一次
 * `atomicMove`；文件系统不支持目录原子移动时退回逐个搬文件，仍保证上传根里只出现完整的树。
 *
 * 不在外部快照里跟随符号链接、不递归进未列出的条目；名字里含分隔符的条目、`.`、`..`、
 * `.DS_Store` 一律跳过（它们既代表不了用户的选择，也会破坏相对路径的语义）。
 */
class AppDirectoryUploadFolderImporter(managedDirectory: String, stagingDirectory: String) {
    private val root = Path(managedDirectory)
    private val staging = Path(stagingDirectory)
    private val guard = pathGuard(managedDirectory)
    private val stagingGuard = pathGuard(stagingDirectory)

    init {
        require(independent(root, staging) && independent(physical(root), physical(staging))) {
            "Import staging must be outside the managed upload directory"
        }
    }

    /**
     * [folderName] 是导入后这一层目录的名字；[maxBytesPerFile] 与 [maxTotalBytes] 都是显式上限，
     * 超了整次导入失败并清掉已复制的部分。[checkCancelled] 应当抛异常，分片之间与发布之前都会调一次。
     */
    fun importFolder(
        folderName: String,
        maxBytesPerFile: Long,
        maxTotalBytes: Long,
        checkCancelled: () -> Unit = {},
        snapshot: UploadFolderSnapshot,
    ): String {
        require(maxBytesPerFile >= 0 && maxTotalBytes >= 0) { "Import byte limits must not be negative" }
        require(isValidEntryName(folderName)) { "Invalid import folder name" }
        checkCancelled()
        val id = Uuid.random().toString()
        val relativeDirectory = "imports/$id/$folderName"
        val uri = guard.uriFor(relativeDirectory)
        stagingGuard.uriFor(id)
        val target = Path(root, relativeDirectory)
        val stage = Path(staging, id)
        check(!SystemFileSystem.exists(target) && !SystemFileSystem.exists(stage)) { "Import allocation already exists" }
        var published = false
        try {
            SystemFileSystem.createDirectories(stage)
            guard.uriFor(relativeDirectory)
            stagingGuard.uriFor(id)
            copyTree(stage, maxBytesPerFile, maxTotalBytes, checkCancelled, snapshot)
            checkCancelled()
            guard.uriFor(relativeDirectory)
            stagingGuard.uriFor(id)
            SystemFileSystem.createDirectories(target.parent!!)
            publish(stage, target)
            published = true
            return uri
        } finally {
            if (!published) {
                // 清理失败不能盖掉真正的失败原因；这里只删自己分配的目录
                runCatching { stagingGuard.uriFor(id); deleteTree(stage) }
                runCatching { guard.uriFor(relativeDirectory); deleteTree(Path(root, "imports/$id")) }
            }
        }
    }

    private fun copyTree(
        stage: Path,
        maxBytesPerFile: Long,
        maxTotalBytes: Long,
        checkCancelled: () -> Unit,
        snapshot: UploadFolderSnapshot,
    ) {
        val pending = ArrayDeque<String>()
        pending.addLast("")
        var total = 0L
        var visited = 0
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            checkCancelled()
            if (++visited > MAX_ENTRIES) throw IOException("Folder contains too many entries")
            val names = snapshot.listDirectory(directory).sorted()
            for (rawName in names) {
                val name = rawName
                if (!isValidEntryName(name) || name == ".DS_Store") continue
                val relative = if (directory.isEmpty()) name else "$directory/$name"
                when (snapshot.kindOf(relative)) {
                    UploadFolderEntryKind.DIRECTORY -> {
                        require(relative.count { it == '/' } <= MAX_DEPTH) { "Folder nesting is too deep" }
                        SystemFileSystem.createDirectories(Path(stage, relative))
                        pending.addLast(relative)
                    }

                    UploadFolderEntryKind.FILE -> {
                        val bytes = copyFile(snapshot, relative, Path(stage, relative), maxBytesPerFile, checkCancelled)
                        total += bytes
                        if (total > maxTotalBytes) throw IOException("Folder exceeds import byte limit")
                    }

                    UploadFolderEntryKind.OTHER, null -> Unit
                }
            }
        }
    }

    private fun copyFile(
        snapshot: UploadFolderSnapshot,
        relative: String,
        destination: Path,
        maxBytes: Long,
        checkCancelled: () -> Unit,
    ): Long {
        var total = 0L
        snapshot.openFile(relative).use { source ->
            SystemFileSystem.sink(destination).buffered().use { sink ->
                val buffer = Buffer()
                while (true) {
                    checkCancelled()
                    val count = source.readAtMostTo(buffer, CHUNK)
                    if (count == -1L) break
                    if (count <= 0L || count > CHUNK || buffer.size != count) {
                        throw IOException("Folder source returned an invalid read count")
                    }
                    if (count > maxBytes - total) throw IOException("File exceeds import byte limit: $relative")
                    sink.write(buffer, count)
                    total += count
                }
            }
        }
        return total
    }

    /** 整个目录一次搬过去；文件系统不支持时退回逐个搬文件，最后删掉暂存里剩下的空目录。 */
    private fun publish(stage: Path, target: Path) {
        try {
            SystemFileSystem.atomicMove(stage, target)
            return
        } catch (_: IOException) {
            // 跨卷或平台不支持目录原子移动，走逐个文件
        }
        SystemFileSystem.createDirectories(target)
        moveTree(stage, target)
        SystemFileSystem.delete(stage, mustExist = false)
    }

    private fun moveTree(source: Path, destination: Path) {
        for (child in SystemFileSystem.list(source)) {
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            val target = Path(destination, child.name)
            if (metadata.isDirectory) {
                SystemFileSystem.createDirectories(target)
                moveTree(child, target)
            } else {
                SystemFileSystem.atomicMove(child, target)
            }
        }
        SystemFileSystem.delete(source, mustExist = false)
    }

    private companion object {
        const val CHUNK = 64L * 1024
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 32

        fun isValidEntryName(name: String): Boolean = name.isNotBlank() && '/' !in name && '\\' !in name &&
            ':' !in name && '\u0000' !in name && name != "." && name != ".."

        fun deleteTree(path: Path) {
            val metadata = SystemFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) {
                for (child in SystemFileSystem.list(path)) deleteTree(child)
            }
            SystemFileSystem.delete(path, mustExist = false)
        }

        // 与 AppDirectoryUploadImporter 同款：借上传源自己的物理路径守卫校验相对名
        fun pathGuard(root: String) = AppDirectoryUploadSources(root, object : UploadFileAccess {
            override fun modifiedAtMs(path: String): Long = error("Folder importer does not query timestamps")
            override fun openAt(path: String, offset: Long): RawSource = error("Folder importer does not read uploads")
        })

        fun independent(a: Path, b: Path): Boolean {
            fun text(path: Path) = path.toString().replace('\\', '/').trimEnd('/')
            val first = text(a)
            val second = text(b)
            return first != second && !first.startsWith("$second/") && !second.startsWith("$first/")
        }

        fun physical(path: Path): Path {
            if (SystemFileSystem.metadataOrNull(path) != null) return SystemFileSystem.resolve(path)
            return Path(physical(path.parent ?: throw IOException("No existing import ancestor")), path.name)
        }
    }
}
