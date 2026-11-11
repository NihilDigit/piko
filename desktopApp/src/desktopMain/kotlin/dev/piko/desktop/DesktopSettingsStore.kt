package dev.piko.desktop

import dev.piko.shared.PikoHome
import dev.piko.shared.log.PikoLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DesktopSettingsStore(
    private val file: File = PikoHome.root.resolve("settings.properties").toFile(),
    programDirectory: File? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parentFile },
    workingDirectory: File = File("").absoluteFile,
) : AutoCloseable {
    private val normalizedPath: Path = file.toPath().toAbsolutePath().normalize()
    private val values = ConcurrentHashMap<String, String>()
    private val stateLock = Any()
    private val flushLock = Any()
    private var mutationVersion = 0L

    @Volatile
    private var flushedVersion = 0L
    private var scheduledSave: ScheduledFuture<*>? = null

    init {
        flushPendingFor(normalizedPath)
        if (file.isFile) {
            val loaded = Properties()
            file.inputStream().use(loaded::load)
            for (name in loaded.stringPropertyNames()) {
                loaded.getProperty(name)?.let { values[name] = it }
            }
        }
        dropBrokenDownloadDirectory(programDirectory, workingDirectory)
    }

    private fun mutate(
        flushImmediately: Boolean = false,
        edit: (ConcurrentHashMap<String, String>) -> Boolean,
    ) {
        synchronized(stateLock) {
            if (!edit(values)) return
            mutationVersion++
            pendingByPath[normalizedPath] = this
            if (!flushImmediately) {
                ensureScheduledLocked()
            }
        }
        if (flushImmediately) {
            flush()
        }
    }

    private fun ensureScheduledLocked() {
        val existing = scheduledSave
        if (existing != null && !existing.isDone) return
        scheduledSave = writerExecutor.schedule(
            { flushSafely() },
            SAVE_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun flushSafely() {
        try {
            flushInternal(drainAll = false)
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to persist desktop settings", e)
        }
    }

    /** 立即将当前待刷新的设置同步落盘。 */
    fun flush() {
        flushInternal(drainAll = true)
    }

    override fun close() {
        flush()
    }

    private fun flushInternal(drainAll: Boolean) {
        val requiredVersion = synchronized(stateLock) { mutationVersion }
        if (flushedVersion >= requiredVersion) return
        synchronized(flushLock) {
            while (true) {
                val snapshot: Properties
                val snapshotVersion: Long
                synchronized(stateLock) {
                    if (flushedVersion >= mutationVersion) {
                        pendingByPath.remove(normalizedPath, this)
                        return
                    }
                    snapshotVersion = mutationVersion
                    scheduledSave?.cancel(false)
                    scheduledSave = null
                    snapshot = Properties().also { it.putAll(values) }
                }
                writeSnapshotToDisk(snapshot)
                synchronized(stateLock) {
                    if (snapshotVersion > flushedVersion) {
                        flushedVersion = snapshotVersion
                    }
                    if (flushedVersion >= mutationVersion) {
                        pendingByPath.remove(normalizedPath, this)
                        return
                    }
                    if (!drainAll) {
                        ensureScheduledLocked()
                        return
                    }
                }
            }
        }
    }

    private fun writeSnapshotToDisk(snapshot: Properties) {
        val parent = normalizedPath.parent
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent)
        }
        val temporary = Files.createTempFile(parent, ".${file.name}.", ".tmp")
        var moved = false
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                snapshot.store(output, "Piko desktop settings")
                output.fd.sync()
            }
            try {
                Files.move(temporary, normalizedPath, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedPath, REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) {
                runCatching { Files.deleteIfExists(temporary) }
            }
        }
    }

    /**
     * 1.0.0 的「恢复默认」把空路径当成 File("") 存下，得到的是当时的工作目录：从开始菜单或 zip 里打开时是程序目录，
     * macOS 从访达打开时是 /。删掉这个键，回到默认位置。
     *
     * 只认当前工作目录不够：经磁力链接或种子关联拉起时工作目录是别处，写坏的值认不出，下载就落进安装目录，
     * 卸载与整包升级时随安装目录一起删掉。所以另与程序目录比较，落在它之内的都算：没人会特意把下载放进程序目录。
     */
    private fun dropBrokenDownloadDirectory(programDirectory: File?, workingDirectory: File) {
        val stored = values["downloadDirectory"]?.let { File(it).toPath().normalize() } ?: return
        val inProgram = programDirectory != null && stored.startsWith(programDirectory.toPath().normalize())
        if (inProgram || stored == workingDirectory.toPath().normalize()) {
            mutate(flushImmediately = true) { it.remove("downloadDirectory") != null }
        }
    }

    /** 下载位置，没选过时是 ~/Downloads/Piko。 */
    val downloadDirectory: File
        get() = values["downloadDirectory"]?.let(::File)
            ?: File(System.getProperty("user.home"), "Downloads/Piko")

    /** [path] 为空时回到默认位置，删掉存下的值，而不是存一个空路径。 */
    fun setDownloadDirectory(path: String) {
        if (path.isBlank()) {
            mutate { it.remove("downloadDirectory") != null }
        } else {
            val normalized = File(path).absolutePath
            mutate { it.put("downloadDirectory", normalized) != normalized }
        }
    }

    /** 通用 KV：给 DesktopPikoPreferences 做写穿持久化。调用方约定 key 命名空间。 */
    fun get(key: String, default: String = ""): String =
        values[key] ?: default

    fun set(key: String, value: String) {
        mutate { it.put(key, value) != value }
    }

    fun setAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        mutate { map ->
            var changed = false
            for ((key, value) in entries) {
                if (map.put(key, value) != value) {
                    changed = true
                }
            }
            changed
        }
    }

    fun keysWithPrefix(prefix: String): List<String> =
        values.keys.filter { it.startsWith(prefix) }.sorted()

    fun remove(key: String) {
        // 旧版明文压缩包密码迁移清除后立即擦除磁盘文件，避免残留窗口。
        val immediate = key == LEGACY_ARCHIVE_PASSWORDS_KEY
        mutate(flushImmediately = immediate) { it.remove(key) != null }
    }

    companion object {
        private const val TAG = "Settings"
        private const val SAVE_DEBOUNCE_MS = 100L
        private const val LEGACY_ARCHIVE_PASSWORDS_KEY = "drive.archivePasswords"

        /** 仅在有未落盘改动期间持有实例引用；落盘完成后自动移除，既防临时实例丢失写入又不泄漏内存。 */
        private val pendingByPath = ConcurrentHashMap<Path, DesktopSettingsStore>()

        private val writerExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "Piko-Settings-Writer").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread(::flushAllPending, "Piko-Settings-Shutdown"),
            )
        }

        internal fun flushPendingFor(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            pendingByPath[normalized]?.flush()
        }

        internal fun flushAllPending() {
            for (store in pendingByPath.values.toList()) {
                runCatching { store.flush() }
                    .onFailure { PikoLog.w(TAG, "Failed to flush desktop settings during shutdown", it) }
            }
        }
    }
}
