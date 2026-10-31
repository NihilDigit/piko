package dev.piko.shared.data

import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LocalMediaMetadata(val width: Int = 0, val height: Int = 0, val durationMs: Long = 0, val thumbnail: String = "", val retryAfterMs: Long = 0) {
    fun apply(file: FileStat): FileStat = file.copy(
        thumbnailLink = thumbnail.ifEmpty { file.thumbnailLink },
        params = file.params + buildMap {
            if (width > 0) put("width", width.toString())
            if (height > 0) put("height", height.toString())
            if (durationMs > 0) put("duration", (durationMs / 1000.0).toString())
        },
    )
}

/** 首屏仅查内存；磁盘读取与未命中探测在 IO 线程执行，最多同时探测两项。文件身份不作为缓存文件名。 */
class LocalMediaCache(private val directory: File, private val scope: CoroutineScope, private val changed: (String) -> Unit) {
    private val lanes = Semaphore(2)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val locks = Array(64) { Mutex() }
    private val memory = object : LinkedHashMap<String, LocalMediaMetadata>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LocalMediaMetadata>?) = size > 1024
    }
    private val generation = AtomicLong(0)
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val writeCount = AtomicLong(0)
    private val lifecycle = Any()

    fun cancelPending() = synchronized(lifecycle) {
        generation.incrementAndGet()
        pending.clear()
        activeJobs.forEach { it.cancel() }
    }

    private fun memoryCached(key: String): LocalMediaMetadata? = synchronized(memory) {
        memory[key]?.takeIf { it.retryAfterMs == 0L || it.retryAfterMs > System.currentTimeMillis() }
            .also { if (it == null) memory.remove(key) }
    }

    private suspend fun trimDisk() {
        val files = directory.listFiles { file -> file.extension == "json" || file.extension == "jpg" } ?: return
        var excess = files.size - 1600
        val cutoff = System.currentTimeMillis() - 60_000L
        for (file in files.sortedBy { it.lastModified() }) {
            if (excess <= 0) break
            val key = file.nameWithoutExtension
            locks[key.hashCode() and 63].withLock {
                // 元数据与封面一起淘汰，避免留下永远命中但封面已不存在的记录。
                val entry = listOf(File(directory, "$key.json"), File(directory, "$key.jpg")).filter { it.exists() }
                if (entry.isNotEmpty() && entry.all { it.lastModified() < cutoff }) {
                    entry.forEach { if (it.delete()) excess-- }
                    synchronized(memory) { memory.remove(key) }
                }
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(file: FileStat) = MessageDigest.getInstance("SHA-256").digest(
        "${file.id}|${file.size}|${file.modifiedTime}".toByteArray(),
    ).joinToString("") { "%02x".format(it) }
    private fun cached(file: FileStat): LocalMediaMetadata? {
        val key = key(file)
        memoryCached(key)?.let { return it }
        return runCatching {
            json.decodeFromString<LocalMediaMetadata>(File(directory, "$key.json").readText())
                .takeIf { it.retryAfterMs == 0L || it.retryAfterMs > System.currentTimeMillis() }
                ?.also { synchronized(memory) { memory[key] = it } }
        }.getOrNull()
    }
    suspend fun probe(file: FileStat, read: suspend (File) -> LocalMediaMetadata?): FileStat = withContext(Dispatchers.IO) {
        locks[key(file).hashCode() and 63].withLock {
            cached(file)?.let { return@withLock it.apply(file) }
            lanes.withPermit {
                try { directory.mkdirs() } catch (_: Exception) { throw IllegalStateException("本地媒体缓存不可写") }
                val metadata = try {
                    read(File(directory, key(file) + ".jpg")) ?: LocalMediaMetadata(retryAfterMs = System.currentTimeMillis() + FAILED_PROBE_RETRY_MS)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    LocalMediaMetadata(retryAfterMs = System.currentTimeMillis() + FAILED_PROBE_RETRY_MS)
                }
                val output = File(directory, key(file) + ".json")
                val temporary = File(directory, key(file) + ".tmp")
                currentCoroutineContext().ensureActive()
                try {
                    temporary.writeText(json.encodeToString(metadata))
                    java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    throw IllegalStateException("本地媒体缓存不可写")
                } finally { runCatching { temporary.delete() } }
                synchronized(memory) { memory[key(file)] = metadata }
                if (writeCount.incrementAndGet() % 64 == 0L) scope.launch(Dispatchers.IO) { runCatching { trimDisk() } }
                metadata.apply(file)
            }
        }
    }
    private companion object { const val FAILED_PROBE_RETRY_MS = 10 * 60 * 1000L }

    fun enrich(file: FileStat, alsoNotifyFolder: String? = null, read: suspend (File) -> LocalMediaMetadata?): FileStat {
        memoryCached(key(file))?.let { return it.apply(file) }
        synchronized(lifecycle) {
            val gen = generation.get()
            val key = key(file)
            if (!file.isFolder && (file.isPlayableVideo() || file.isPreviewableImage()) && pending.add(key)) {
                val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        if (generation.get() != gen) return@launch
                        probe(file, read)
                        val shouldNotify = synchronized(lifecycle) { generation.get() == gen }
                        if (shouldNotify) {
                            changed(file.parentId)
                            if (alsoNotifyFolder != null && alsoNotifyFolder != file.parentId) changed(alsoNotifyFolder)
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // 原生探测错误可能包含真实路径，不传播异常消息。
                    } finally {
                        synchronized(lifecycle) { if (generation.get() == gen) pending.remove(key) }
                        currentCoroutineContext()[Job]?.let(activeJobs::remove)
                    }
                }
                activeJobs.add(job)
                job.invokeOnCompletion {
                    activeJobs.remove(job)
                    synchronized(lifecycle) { if (generation.get() == gen) pending.remove(key) }
                }
                job.start()
            }
        }
        return file
    }
}
