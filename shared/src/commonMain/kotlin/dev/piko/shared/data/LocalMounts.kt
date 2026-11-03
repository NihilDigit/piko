package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class LocalMount(
    val mountId: String,
    val account: String,
    val parentFolderId: String,
    val localRootUriOrPath: String,
    val fallbackName: String,
    val createdAtMs: Long,
    val pinned: Boolean = false,
) {
    val location: LocalLocation get() = LocalLocation(mountId)
    fun toFileStat(thumbnailLink: String = "") = FileStat(id = location.id, name = fallbackName, parentId = parentFolderId,
        kind = io.github.nihildigit.pikpak.FileKind.FOLDER, thumbnailLink = thumbnailLink,
        params = mapOf("local_path" to localRootUriOrPath),
        createdTime = kotlin.time.Instant.fromEpochMilliseconds(createdAtMs).toString())
}

data class LocalMountOutcome(val mount: LocalMount, val alreadyMounted: Boolean)

/** 将存储的本机路径或 Android SAF URI 转换为适合在属性面板展示的人类可读路径。 */
fun formatLocalPathForDisplay(raw: String): String {
    if (!raw.startsWith("content://")) return raw
    val marker = when {
        "/document/" in raw -> raw.substringAfterLast("/document/")
        "/tree/" in raw -> raw.substringAfterLast("/tree/")
        else -> return raw
    }.substringBefore('?').substringBefore('#')
    val decoded = decodePercentUtf8(marker)
    return when {
        decoded.startsWith("primary:") -> decoded.removePrefix("primary:").ifEmpty { "/" }
        decoded.startsWith("home:") -> "Documents/" + decoded.removePrefix("home:")
        else -> decoded
    }
}

private fun decodePercentUtf8(encoded: String): String {
    if ('%' !in encoded) return encoded
    val out = ByteArray(encoded.length)
    var writeIdx = 0
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        if (c == '%' && i + 2 < encoded.length) {
            val hi = encoded[i + 1].digitToIntOrNull(16)
            val lo = encoded[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out[writeIdx++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        val bytes = c.toString().encodeToByteArray()
        bytes.copyInto(out, writeIdx)
        writeIdx += bytes.size
        i++
    }
    return out.decodeToString(0, writeIdx)
}
/** 平台文件访问只提供读取，不暴露任何磁盘写操作。媒体缓存写入由实现封装在应用缓存目录中。 */
interface LocalFileAccess {
    suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat>
    suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String
    suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String = playbackPath(mount, location)
    suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat?
    fun folderCover(mount: LocalMount, location: LocalLocation = mount.location): String = ""
    fun cancelPending() {}
}

/** 挂载表属于设备，表内显式按账号隔离，不进入网盘设置同步。 */
class LocalMounts(
    private val preferences: PikoUserPreferences?,
    private val account: () -> String?,
    private val changed: (Set<String>) -> Unit,
    scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    var access: LocalFileAccess? = null
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<LocalMount>>(emptyList())
    val flow: kotlinx.coroutines.flow.StateFlow<List<LocalMount>> = state
    private var cached: List<LocalMount>
        get() = state.value
        set(value) { state.value = value }
    init {
        scope.launch {
            preferences?.localMountsFlow?.collect { raw ->
                val next = runCatching { json.decodeFromString<List<LocalMount>>(raw.ifBlank { "[]" }) }.getOrDefault(emptyList())
                val old = cached
                cached = next
                changed((old + next).filter { it.account == account() }.mapTo(mutableSetOf()) { it.parentFolderId })
            }
        }
    }
    private fun toRootStat(mount: LocalMount): FileStat =
        mount.toFileStat(thumbnailLink = runCatching { access?.folderCover(mount, mount.location).orEmpty() }.getOrDefault(""))

    fun cachedRoots(parentId: String): List<FileStat> =
        cached.filter { it.account == account() && it.parentFolderId == parentId }.map(::toRootStat)
    private suspend fun read(): List<LocalMount> = runCatching {
        json.decodeFromString<List<LocalMount>>(preferences?.localMountsFlow?.first().orEmpty().ifBlank { "[]" })
    }.getOrDefault(emptyList())

    suspend fun all(): List<LocalMount> = read().also { cached = it }.filter { it.account == account() }
    fun isMountedCached(folderId: String): Boolean {
        if (!LocalLocation.isLocal(folderId)) return true
        val location = LocalLocation.of(folderId) ?: return false
        return cached.any { it.account == account() && it.mountId == location.mountId }
    }

    fun findCached(id: String): LocalMount? = LocalLocation.of(id)?.let { location -> cached.firstOrNull { it.account == account() && it.mountId == location.mountId } }
    suspend fun find(id: String): LocalMount? = LocalLocation.of(id)?.let { location -> all().firstOrNull { it.mountId == location.mountId } }
    suspend fun roots(parentId: String): List<FileStat> = all().filter { it.parentFolderId == parentId }.map(::toRootStat)
    suspend fun mount(parentId: String, path: String, name: String): LocalMount =
        mountOrExisting(parentId, path, name).mount

    suspend fun mountOrExisting(parentId: String, path: String, name: String): LocalMountOutcome = lock.withLock {
        require(isDriveFolderId(parentId))
        val owner = requireNotNull(account())
        val currentEntries = read()
        val existing = currentEntries.firstOrNull {
            it.account == owner && it.parentFolderId == parentId && it.localRootUriOrPath == path
        }
        if (existing != null) {
            cached = currentEntries
            return@withLock LocalMountOutcome(existing, alreadyMounted = true)
        }
        val entry = LocalMount(Uuid.random().toString(), owner, parentId, path, name, Clock.System.now().toEpochMilliseconds())
        val entries = currentEntries + entry
        check(owner == account())
        requireNotNull(preferences).saveLocalMounts(json.encodeToString(entries))
        cached = entries
        changed(setOf(parentId))
        LocalMountOutcome(entry, alreadyMounted = false)
    }
    suspend fun pin(id: String, pinned: Boolean) = lock.withLock {
        val entry = find(id) ?: return@withLock
        val entries = read().map { if (it.account == entry.account && it.mountId == entry.mountId) it.copy(pinned = pinned) else it }
        if (entry.account != account()) return@withLock
        preferences?.saveLocalMounts(json.encodeToString(entries))
        cached = entries
    }
    fun restoredStack(stack: List<PikoPathBreadcrumb>): List<PikoPathBreadcrumb> {
        val invalid = stack.indexOfFirst { !isMountedCached(it.id) }
        return if (invalid < 0) stack else stack.take(invalid)
    }
    suspend fun unmount(id: String) = lock.withLock {
        val entry = find(id) ?: return@withLock
        val entries = read().filterNot { it.mountId == entry.mountId && it.account == entry.account }
        if (entry.account != account()) return@withLock
        preferences?.saveLocalMounts(json.encodeToString(entries))
        cached = entries
        changed(setOf(entry.parentFolderId, entry.location.id))
    }
    suspend fun list(id: String): List<FileStat> {
        val location = requireNotNull(LocalLocation.of(id))
        val mount = find(id) ?: error("本地文件夹已取消挂载")
        return safeRead { requireNotNull(access).list(mount, location) }
    }
    suspend fun resolve(id: String, subtitle: Boolean = false): String? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: error("本地文件夹已取消挂载")
        val reader = requireNotNull(access) { "本机文件夹不可访问" }
        return safeRead { if (subtitle) reader.subtitlePath(mount, location) else reader.playbackPath(mount, location) }
    }
    suspend fun probe(id: String): FileStat? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: return null
        return safeRead { access?.probe(mount, location) }
    }
    /** 文件系统异常包含本机路径，跨出访问边界时只传递不含路径的可展示错误。 */
    private suspend fun <T> safeRead(read: suspend () -> T): T = try {
        read()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw IllegalStateException(dev.piko.shared.i18n.tr("本机文件夹不可访问", "Local folder is unavailable", "ローカルフォルダーにアクセスできません", "로컬 폴더에 액세스할 수 없습니다", "本機資料夾無法存取"))
    }
    suspend fun pathTo(id: String): List<PikoPathBreadcrumb>? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: return null
        return listOf(PikoPathBreadcrumb(mount.location.id, mount.fallbackName)) + location.segments.mapIndexed { i, name ->
            PikoPathBreadcrumb(LocalLocation(location.mountId, location.segments.take(i + 1)).id, name)
        }
    }
}
