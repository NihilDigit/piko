package dev.piko.shots

import kotlinx.serialization.json.jsonArray
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import io.github.nihildigit.pikpak.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 截图用的假 PikPak 服务端。精简自 shared 冒烟测试的 FakePikPakServer：desktopApp 的测试类路径里
 * 没有 ktor-client-mock，这里改用 OkHttp 引擎加应用拦截器在本地作答，SDK 的请求与解析仍走真实代码。
 * 只作答列目录、回收站、离线任务、配额与用户信息这些界面读得到的接口。
 */
class FakePikPak {
    class Node(
        val id: String,
        val parentId: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: String,
        val trashed: Boolean,
        val starred: Boolean,
    )

    class Task(val id: String, val name: String, val phase: String, val progress: Int, val size: Long, val created: String)

    private val nodes = LinkedHashMap<String, Node>()
    private val tasks = CopyOnWriteArrayList<Task>()
    private val nextId = AtomicInteger(1)
    val calls = CopyOnWriteArrayList<String>()

    var quotaLimit: Long = 6L shl 40
    var quotaUsage: Long = (1.8 * (1L shl 40)).toLong()
    var userName = "Piko 演示账号"
    var email = "demo@piko.dev"

    fun addFolder(name: String, parentId: String = "", modified: String = "2026-09-20T10:00:00.000+08:00", trashed: Boolean = false, starred: Boolean = false): Node =
        add(Node(newId(), parentId, name, true, 0, modified, trashed, starred))

    fun addFile(name: String, size: Long, parentId: String = "", modified: String = "2026-09-18T21:30:00.000+08:00", trashed: Boolean = false, starred: Boolean = false): Node =
        add(Node(newId(), parentId, name, false, size, modified, trashed, starred))

    // 带内容的文件（归档清单），直链指向 /blob/<id>，由同一个拦截器作答：没有单独注入 CDN 客户端时，SDK 下载也走它
    private val blobs = ConcurrentHashMap<String, ByteArray>()

    fun addBlob(name: String, bytes: ByteArray, parentId: String = ""): Node =
        addFile(name, bytes.size.toLong(), parentId).also { blobs[it.id] = bytes }

    fun addTask(name: String, phase: String, progress: Int, size: Long, created: String = "2026-09-27T09:00:00.000+08:00") {
        tasks += Task(newId(), name, phase, progress, size, created)
    }

    private fun add(node: Node) = synchronized(nodes) { nodes[node.id] = node; node }

    private fun createFile(body: String): String {
        val json = Json.parseToJsonElement(body).jsonObject
        val name = json["name"]?.jsonPrimitive?.content.orEmpty()
        val parentId = json["parent_id"]?.jsonPrimitive?.content.orEmpty()
        val isFolder = json["kind"]?.jsonPrimitive?.content == "drive#folder"
        val node = if (isFolder) addFolder(name, parentId) else addFile(name, 0, parentId)
        return buildJsonObject {
            if (!isFolder) put("upload_type", "UPLOAD_TYPE_RESUMABLE")
            put("file", buildJsonObject { putNode(node) })
        }.toString()
    }

    private fun batch(op: String, body: String): String {
        val json = Json.parseToJsonElement(body).jsonObject
        val ids = json["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val moveTo = json["to"]?.jsonObject?.get("parent_id")?.jsonPrimitive?.content.orEmpty()
        synchronized(nodes) {
            ids.forEach { id ->
                val node = nodes[id] ?: return@forEach
                nodes[id] = when (op) {
                    "batchMove" -> Node(node.id, moveTo, node.name, node.isFolder, node.size, node.modified, node.trashed, node.starred)
                    "batchTrash" -> Node(node.id, node.parentId, node.name, node.isFolder, node.size, node.modified, true, node.starred)
                    "batchUntrash" -> Node(node.id, node.parentId, node.name, node.isFolder, node.size, node.modified, false, node.starred)
                    else -> node
                }
            }
        }
        return "{}"
    }

    private fun okhttp3.Request.bodyText(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
    private fun newId() = "F${nextId.getAndIncrement()}"

    fun httpClient(): HttpClient = HttpClient(OkHttp) {
        engine { addInterceptor(Interceptor { chain -> respond(chain) }) }
    }

    private fun respond(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        val method = request.method
        calls += "$method $path?${url.encodedQuery.orEmpty()}"
        if (path.startsWith("/blob/")) return blob(request, path.substringAfterLast('/'))
        val (code, text) = when {
            path.endsWith("/drive/v1/share") -> 200 to """{"share_status":"OK","pass_code_token":"SHOT","title":"Frieren 分享内容","files":[{"id":"shared-video","kind":"drive#file","name":"[SweetSub] Frieren - 01 [1080p].mkv","size":"524288000","hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","phase":"PHASE_TYPE_COMPLETE"},{"id":"shared-sub","kind":"drive#file","name":"Frieren - 01.ass","size":"102400","hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","phase":"PHASE_TYPE_COMPLETE"}]}"""
            path.endsWith("/drive/v1/resource/list") -> 200 to """{"list_id":"SHOT","list":{"page_size":500,"resources":[{"id":"root","name":"Frieren S01","file_size":"1048576000","is_dir":true,"meta":{},"dir":{"resources":[{"id":"ep1","name":"Frieren - 01.mkv","file_size":"524288000","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}},{"id":"ep2","name":"Frieren - 02.mkv","file_size":"524288000","is_dir":false,"meta":{"hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"}}]}}]}}"""
            path.endsWith("/v1/shield/captcha/init") -> 200 to """{"captcha_token":"CAP","expires_in":300,"url":""}"""
            path.endsWith("/v1/auth/signin") || path.endsWith("/v1/auth/token") ->
                200 to """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}"""
            path.endsWith("/v1/user/me") -> 200 to buildJsonObject {
                put("sub", "UID"); put("name", userName); put("email", email); put("picture", "")
                put("created_at", "2024-03-01T00:00:00.000Z")
            }.toString()
            path.endsWith("/vip/v1/quantity/list") -> 200 to transferQuota()
            path.endsWith("/drive/v1/about") -> 200 to buildJsonObject {
                put("kind", "drive#about")
                put("quota", buildJsonObject {
                    put("kind", "drive#quota"); put("limit", quotaLimit.toString()); put("usage", quotaUsage.toString())
                    put("usage_in_trash", "0")
                })
            }.toString()
            path.endsWith("/drive/v1/tasks") && method == "GET" -> 200 to listTasks(url.queryParameter("filters").orEmpty())
            path.contains("/drive/v1/tasks/") -> tasks.firstOrNull { it.id == path.substringAfterLast('/') }
                ?.let { 200 to taskJson(it).toString() } ?: (404 to """{"error_code":4,"error":"task_not_found"}""")
            path.endsWith("/drive/v1/files") && method == "GET" ->
                200 to listFiles(url.queryParameter("parent_id").orEmpty(), url.queryParameter("filters").orEmpty())
            path.contains("/drive/v1/files/") && method == "GET" -> synchronized(nodes) { nodes[path.substringAfterLast('/')] }
                ?.let { 200 to buildJsonObject { putNode(it) }.toString() }
                ?: (404 to """{"error_code":3,"error":"file_not_found"}""")
            // 新建文件夹与小文件上传（设置同步要用）：上传一律当作秒传完成
            path.endsWith("/drive/v1/files") && method == "POST" -> 200 to createFile(request.bodyText())
            // 移动、移入回收站与恢复：拖放与撤销要用
            path.contains("/drive/v1/files:") && method == "POST" -> 200 to batch(path.substringAfterLast(':'), request.bodyText())
            else -> 404 to """{"error":"not_found"}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Not Found")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }

    // SDK 按 Range 读，只认 bytes=a-b 这一种写法
    private fun blob(request: okhttp3.Request, id: String): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        val bytes = blobs[id] ?: return builder.code(404).message("Not Found").body("".toResponseBody(null)).build()
        val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
        val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
        val end = range?.getOrNull(1)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
        if (range != null) builder.header("Content-Range", "bytes $start-$end/${bytes.size}")
        return builder
            .code(if (range != null) 206 else 200)
            .message("OK")
            .body(bytes.copyOfRange(start, end + 1).toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }

    private fun transferQuota(): String {
        fun allowance(limit: Long, used: Long) = buildJsonObject { put("total_assets", limit); put("assets", used) }
        return buildJsonObject {
            put("base", buildJsonObject {
                put("offline", allowance(3000, 412))
                put("download", allowance(40L shl 40, (6.3 * (1L shl 40)).toLong()))
                put("upload", allowance(40L shl 40, 120L shl 30))
                put("download_daily", allowance(1L shl 40, 85L shl 30))
                put("vip_status", "ok")
                put("expire_time", "2027-03-01T00:00:00.000+08:00")
            })
        }.toString()
    }

    private fun listFiles(parentId: String, filters: String): String {
        val compact = filters.replace(" ", "")
        val listed = synchronized(nodes) {
            when {
                compact.contains("\"trashed\":{\"eq\":true}") -> nodes.values.filter { it.trashed }
                compact.contains("STAR") -> nodes.values.filter { it.starred && !it.trashed }
                else -> nodes.values.filter { it.parentId == parentId && !it.trashed }
            }
        }
        return buildJsonObject {
            put("next_page_token", "")
            put("files", buildJsonArray { listed.forEach { add(buildJsonObject { putNode(it) }) } })
        }.toString()
    }

    private fun listTasks(filters: String): String {
        val requested = runCatching {
            Json.parseToJsonElement(filters).jsonObject["phase"]?.jsonObject?.get("in")?.jsonPrimitive?.content
        }.getOrNull()?.split(',')?.toSet()
        return buildJsonObject {
            put("next_page_token", "")
            put("tasks", buildJsonArray { tasks.filter { requested == null || it.phase in requested }.forEach { add(taskJson(it)) } })
        }.toString()
    }

    private fun taskJson(task: Task) = buildJsonObject {
        put("kind", "drive#task")
        put("id", task.id)
        put("name", task.name)
        put("type", "offline")
        put("phase", task.phase)
        put("progress", task.progress)
        put("file_id", "")
        put("file_name", task.name)
        put("file_size", task.size.toString())
        put("created_time", task.created)
        put("updated_time", task.created)
    }

    private fun JsonObjectBuilder.putNode(node: Node) {
        put("kind", if (node.isFolder) "drive#folder" else "drive#file")
        put("id", node.id)
        put("parent_id", node.parentId)
        put("name", node.name)
        put("size", node.size.toString())
        put("hash", if (node.isFolder) "" else "GCID${node.id}")
        put("trashed", node.trashed)
        put("phase", "PHASE_TYPE_COMPLETE")
        put("file_extension", if (node.isFolder) "" else "." + node.name.substringAfterLast('.', ""))
        put("mime_type", if (node.isFolder) "" else mimeOf(node.name))
        put("created_time", node.modified)
        put("modified_time", node.modified)
        if (node.trashed) put("delete_time", "2026-09-25T12:00:00.000+08:00")
        if (blobs.containsKey(node.id)) {
            put("links", buildJsonObject {
                put("application/octet-stream", buildJsonObject {
                    put("url", "https://fake-cdn.piko.test/blob/${node.id}")
                    put("token", "")
                    put("expire", "2099-01-01T00:00:00.000+08:00")
                })
            })
        }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "ass" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "flac" -> "audio/flac"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }
}

/** 内存会话存储，预先放好一个未过期的会话，PikoClientManager 恢复时不发登录请求。 */
class MemorySessionStore(account: String) : PikoSessionStore {
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var accounts = SavedAccounts(account, listOf(SavedAccount(account)))

    init {
        sessions[account] = Session(accessToken = "AT", refreshToken = "RT", sub = "UID", expiresAt = 4_000_000_000L)
    }

    override suspend fun load(account: String): Session? = sessions[account]
    override suspend fun save(account: String, session: Session) { sessions[account] = session }
    override suspend fun clear(account: String) { sessions.remove(account) }
    override suspend fun loadAccounts(): SavedAccounts = accounts
    override suspend fun saveAccounts(accounts: SavedAccounts) { this.accounts = accounts }
    override suspend fun loadCredentials(account: String): PikoCredentials? = PikoCredentials(account, "pw")
    override suspend fun saveCredentials(account: String, password: String) = Unit
    override suspend fun clearCredentials(account: String) = Unit
}
