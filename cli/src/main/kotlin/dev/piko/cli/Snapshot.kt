package dev.piko.cli

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore
import io.github.nihildigit.pikpak.listFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/** 一个目录下每项的类型、名字与 params，查接口到底返回了什么时用。 */
suspend fun listWithParams(client: PikPakClient, path: String): List<String> =
    client.listFiles(parentId = resolvePath(client, path)).map { file ->
        val kind = if (file.isFolder) "目录" else "文件"
        val params = file.params.entries.joinToString("  ") { (key, value) -> "$key=${value.take(120)}" }.ifEmpty { "（无 params）" }
        "$kind  ${file.name}\n      $params"
    }

private suspend fun resolvePath(client: PikPakClient, path: String): String {
    var id = ""
    path.split('/').filter(String::isNotEmpty).forEach { name ->
        id = client.listFiles(parentId = id).firstOrNull { it.isFolder && it.name == name }?.id
            ?: error("找不到目录：$path（卡在 $name）")
    }
    return id
}

/**
 * 与 app 共用 ~/.piko 下的会话。SDK 刷新 token 时会轮换 refresh token，新会话必须写回同一个文件，
 * 否则 app 手里那份作废，下次打开要重新登录。写法与桌面端的 FilePikoSessionStore 相同：先写临时文件再原子替换。
 */
class AppSessionStore(private val root: File = File(System.getProperty("user.home"), ".piko")) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionFile = File(root, "pikpak-session.json")

    fun account(): String = File(root, "pikpak-account.txt").takeIf { it.isFile }?.readText()?.trim()
        ?: error("~/.piko 下没有登录信息，先在桌面端登录一次")

    fun password(): String = File(root, "pikpak-password.txt").readText()

    override suspend fun load(account: String): Session? =
        sessionFile.takeIf { it.isFile }?.let { json.decodeFromString(Session.serializer(), it.readText()) }

    override suspend fun save(account: String, session: Session) {
        val staging = File(root, "${sessionFile.name}.tmp")
        staging.writeText(json.encodeToString(Session.serializer(), session))
        Files.move(staging.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // 会话失效时 SDK 会调它；这里不删文件，留给 app 自己处理重新登录
    override suspend fun clear(account: String) = Unit
}

fun appClient(): PikPakClient {
    val store = AppSessionStore()
    return PikPakClient(store.account(), passwordSupplier = { store.password() }, sessionStore = store)
}
