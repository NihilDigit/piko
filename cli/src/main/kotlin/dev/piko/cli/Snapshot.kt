package dev.piko.cli

import dev.piko.shared.auth.DesktopSessionStore
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.SessionStore
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 网盘目录的快照：只有文件名、类型与大小。解析器按大小区分正片与花絮，其余元数据用不上，
 * 缩略图链接之类也不该落进一个随手传来传去的文件里。
 */
@Serializable
data class Snapshot(val folders: List<SnapshotFolder>)

@Serializable
data class SnapshotFolder(val id: String, val path: String, val files: List<SnapshotFile>)

@Serializable
data class SnapshotFile(val id: String, val name: String, val kind: String, val size: Long) {
    fun toFileStat(parentId: String) = FileStat(kind = kind, id = id, parentId = parentId, name = name, size = size.toString())
}

internal const val FOLDER_KIND = "drive#folder"

internal val snapshotJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

/**
 * 从 [rootPath] 起列 [depth] 层；[deep] 里的子目录名（相对 [rootPath] 的第一层）递归到底。
 * 路径按名字逐级找，同名文件夹取第一个。
 */
suspend fun takeSnapshot(client: PikPakClient, rootPath: String, depth: Int, deep: Set<String>): Snapshot {
    val folders = mutableListOf<SnapshotFolder>()
    suspend fun walk(id: String, path: String, depthLeft: Int, isRoot: Boolean) {
        val files = client.listFiles(parentId = id)
        folders += SnapshotFolder(id, path, files.map { SnapshotFile(it.id, it.name, it.kind, it.size.toLongOrNull() ?: 0) })
        System.err.println("已列 ${path.ifEmpty { "/" }}：${files.size} 项")
        files.filter(FileStat::isFolder).forEach { child ->
            // 负数表示递归到底
            val childDepth = when {
                depthLeft < 0 -> -1
                isRoot && child.name in deep -> -1
                depthLeft == 0 -> return@forEach
                else -> depthLeft - 1
            }
            walk(child.id, "$path/${child.name}", childDepth, isRoot = false)
        }
    }
    walk(resolvePath(client, rootPath), rootPath.trimEnd('/'), depth, isRoot = true)
    return Snapshot(folders)
}

/**
 * 一个目录下每项的类型、名字与 params，查接口到底返回了什么时用。文件另附大小、phase 与 gcid，
 * 核对上传的内容是否与本机一致。
 */
suspend fun listWithParams(client: PikPakClient, path: String): List<String> =
    client.listFiles(parentId = resolvePath(client, path)).map { file ->
        val kind = if (file.isFolder) "目录" else "文件"
        val content = if (file.isFolder) "" else "  ${file.sizeBytes} B  ${file.phase}  gcid=${file.hash}  mime=${file.mimeType}"
        val params = file.params.entries.joinToString("  ") { (key, value) -> "$key=${value.take(120)}" }.ifEmpty { "（无 params）" }
        "$kind  ${file.name}$content\n      $params"
    }

internal suspend fun resolvePath(client: PikPakClient, path: String): String {
    var id = ""
    path.split('/').filter(String::isNotEmpty).forEach { name ->
        id = client.listFiles(parentId = id).firstOrNull { it.isFolder && it.name == name }?.id
            ?: error("找不到目录：$path（卡在 $name）")
    }
    return id
}

/**
 * 与桌面端共用 ~/.piko 下的登录态，取桌面端当前的账号。SDK 刷新 token 时会轮换 refresh token，
 * 新会话必须写回桌面端读的同一处，否则桌面端手里那份作废，下次打开要重新登录。
 */
class AppSessionStore(private val store: DesktopSessionStore = DesktopSessionStore()) : SessionStore by store {
    fun account(): String = runBlocking { store.loadAccounts().current }
        ?: error("~/.piko 下没有登录信息，先在桌面端登录一次")

    suspend fun password(account: String): String = store.loadCredentials(account)?.password
        ?: error("没有保存这个账号的密码，先在桌面端重新登录一次")

    // 会话失效时 SDK 会调它；这里不删，留给桌面端自己处理重新登录
    override suspend fun clear(account: String) = Unit
}

fun appClient(rateLimiter: io.github.nihildigit.pikpak.RateLimiter = io.github.nihildigit.pikpak.RateLimiter.default(), httpClient: io.ktor.client.HttpClient? = null): PikPakClient {
    val store = AppSessionStore()
    val account = store.account()
    return PikPakClient(account, passwordSupplier = { store.password(account) }, sessionStore = store, rateLimiter = rateLimiter, httpClient = httpClient)
}
