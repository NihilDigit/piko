package dev.piko.shots

import kotlinx.serialization.json.jsonArray
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import io.github.nihildigit.pikpak.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * 只作答界面读得到的接口：列目录、详情、回收站、星标、事件（最近添加与播放历史）、离线任务、配额、
 * 用户信息、分享、压缩包、WebDAV。
 */
class FakePikPak {
    class Node(
        val id: String,
        var parentId: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: String,
        @Volatile var trashed: Boolean,
        @Volatile var starred: Boolean,
        /** 列表与详情里的 params：时长、分辨率（duration、width、height）、来源（url）。 */
        @Volatile var params: Map<String, String> = emptyMap(),
        @Volatile var thumbnail: String = "",
    )

    class Task(
        val id: String,
        val name: String,
        val phase: String,
        val progress: Int,
        val size: Long,
        val created: String,
        val message: String = "",
        val url: String = "",
        val fileId: String = "",
    )

    /** 压缩包的内容。[password] 非空时不带或带错密码都回密码类状态。 */
    class Archive(val entries: List<Pair<String, Long>>, val password: String = "", val status: String = "OK")

    private class Event(val id: String, val type: String, val node: Node, val time: String, val params: Map<String, String>)

    private val nodes = LinkedHashMap<String, Node>()
    private val tasks = CopyOnWriteArrayList<Task>()
    private val events = CopyOnWriteArrayList<Event>()
    private val archives = ConcurrentHashMap<String, Archive>()
    private val nextId = AtomicInteger(1)
    val calls = CopyOnWriteArrayList<String>()

    var quotaLimit: Long = 6L shl 40
    var quotaUsage: Long = (1.8 * (1L shl 40)).toLong()
    var userName = "Piko 演示账号"
    var email = "demo@piko.dev"

    /** 会员与否：quantity/list 的 vip_status。免费账号没有 WebDAV 入口，「我的」多一块今日额度。 */
    @Volatile var premium = true

    /** 今日已用下载流量，逼近 1 TB 的上限时文件夹下载会停在「将超出今日下载额度」。 */
    @Volatile var dailyDownloadUsed = 85L shl 30

    /** 我的分享；null 时接口回错，页面停在「读取分享失败」。 */
    @Volatile var shares: List<JsonObject>? = null

    /** WebDAV 应用列表；null 时接口回错。 */
    @Volatile var webDavApps: List<JsonObject>? = null

    // 时序与失败的开关，截图步骤里经 Step.Run 拨动。拦截器跑在 OkHttp 的线程上，睡眠不卡界面
    @Volatile var signinDelayMs = 0L
    @Volatile var signinRejects = false
    @Volatile var listDelayMs = 0L
    @Volatile var listFails = false
    @Volatile var tasksDelayMs = 0L
    @Volatile var sharesDelayMs = 0L

    fun addFolder(name: String, parentId: String = "", modified: String = "2026-09-20T10:00:00.000+08:00", trashed: Boolean = false, starred: Boolean = false): Node =
        add(Node(newId(), parentId, name, true, 0, modified, trashed, starred))

    fun addFile(name: String, size: Long, parentId: String = "", modified: String = "2026-09-18T21:30:00.000+08:00", trashed: Boolean = false, starred: Boolean = false): Node =
        add(Node(newId(), parentId, name, false, size, modified, trashed, starred))

    fun node(name: String): Node = synchronized(nodes) { nodes.values.first { it.name == name } }

    // 带内容的文件（归档清单），直链指向 /blob/<id>，由同一个拦截器作答：没有单独注入 CDN 客户端时，SDK 下载也走它
    private val blobs = ConcurrentHashMap<String, ByteArray>()

    fun addBlob(name: String, bytes: ByteArray, parentId: String = ""): Node =
        addFile(name, bytes.size.toLong(), parentId).also { blobs[it.id] = bytes }

    fun addTask(
        name: String,
        phase: String,
        progress: Int,
        size: Long,
        created: String = "2026-09-27T09:00:00.000+08:00",
        message: String = "",
        url: String = "",
        fileId: String = "",
    ): String = newId().also { tasks += Task(it, name, phase, progress, size, created, message, url, fileId) }

    fun clearTasks() = tasks.clear()

    /** [type] 是 TYPE_UPLOAD、TYPE_RESTORE 或 TYPE_PLAY；播放事件带 [playSeconds] 与 [playDuration]。 */
    fun addEvent(type: String, node: Node, time: String, playSeconds: Long = 0, playDuration: Long = 0) {
        val params = if (type == "TYPE_PLAY") mapOf("play_seconds" to "$playSeconds", "play_duration" to "$playDuration") else emptyMap()
        events += Event(newId(), type, node, time, params)
    }

    fun clearEvents() = events.clear()

    fun addArchive(node: Node, archive: Archive) {
        archives[node.id] = archive
    }

    fun unstarAll() = synchronized(nodes) { nodes.values.forEach { it.starred = false } }

    fun purgeTrash() = synchronized(nodes) { nodes.values.removeIf { it.trashed } }

    /** 视频在列表里带上时长与分辨率。信息流只挑一分钟以上的，属性里也多出这两行。 */
    fun giveVideosDuration() = synchronized(nodes) {
        nodes.values.filter { isVideo(it.name) }.forEach { it.params = it.params + mapOf("duration" to "1450", "width" to "1920", "height" to "1080") }
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
                when (op) {
                    "batchMove" -> node.parentId = moveTo
                    "batchTrash" -> node.trashed = true
                    "batchUntrash" -> node.trashed = false
                    "batchDelete" -> nodes.remove(id)
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
            path.endsWith("/drive/v1/share/list") -> {
                if (sharesDelayMs > 0) Thread.sleep(sharesDelayMs)
                shares?.let { list -> 200 to buildJsonObject { put("next_page_token", ""); put("data", buildJsonArray { list.forEach { add(it) } }) }.toString() }
                    ?: (400 to """{"error_code":3,"error":"invalid_argument","error_description":"读取分享失败"}""")
            }
            path.endsWith("/drive/v1/share:batchDelete") -> 200 to "{}"
            path.endsWith("/drive/v1/resource/list") -> 200 to """{"list_id":"SHOT","list":{"page_size":500,"resources":[{"id":"root","name":"Frieren S01","file_size":"1048576000","is_dir":true,"meta":{},"dir":{"resources":[{"id":"ep1","name":"Frieren - 01.mkv","file_size":"524288000","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}},{"id":"ep2","name":"Frieren - 02.mkv","file_size":"524288000","is_dir":false,"meta":{"hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"}}]}}]}}"""
            path.endsWith("/v1/shield/captcha/init") -> 200 to """{"captcha_token":"CAP","expires_in":300,"url":""}"""
            path.endsWith("/v1/auth/signin") -> {
                if (signinDelayMs > 0) Thread.sleep(signinDelayMs)
                if (signinRejects) 400 to """{"error_code":4002,"error":"invalid_account_or_password","error_description":"账号或密码错误"}"""
                else 200 to """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}"""
            }
            path.endsWith("/v1/auth/token") ->
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
            path.endsWith("/drive/v1/events") && method == "GET" -> 200 to listEvents(url.queryParameter("filters").orEmpty())
            path.contains("/drive/v1/events:") -> 200 to "{}"
            path.endsWith("/drive/v1/tasks") && method == "GET" -> {
                if (tasksDelayMs > 0) Thread.sleep(tasksDelayMs)
                200 to listTasks(url.queryParameter("filters").orEmpty())
            }
            path.contains("/drive/v1/tasks/") -> tasks.firstOrNull { it.id == path.substringAfterLast('/') }
                ?.let { 200 to taskJson(it).toString() } ?: (404 to """{"error_code":4,"error":"task_not_found"}""")
            path.endsWith("/drive/v1/files") && method == "GET" -> {
                if (listDelayMs > 0) Thread.sleep(listDelayMs)
                // 用 4xx：5xx 会被 SDK 退避重试三次，也会被当成网络波动
                if (listFails) 400 to """{"error_code":3,"error":"invalid_argument","error_description":"服务暂时不可用"}"""
                else 200 to listFiles(url.queryParameter("parent_id").orEmpty(), url.queryParameter("filters").orEmpty())
            }
            path.contains("/drive/v1/files/") && method == "GET" -> synchronized(nodes) { nodes[path.substringAfterLast('/')] }
                ?.let { 200 to buildJsonObject { putNode(it, detail = true) }.toString() }
                ?: (404 to """{"error_code":3,"error":"file_not_found"}""")
            // 新建文件夹与小文件上传（设置同步要用）：上传一律当作秒传完成
            path.endsWith("/drive/v1/files") && method == "POST" -> 200 to createFile(request.bodyText())
            // 移动、移入回收站、恢复与彻底删除：拖放、撤销与回收站要用
            path.contains("/drive/v1/files:") && method == "POST" -> 200 to batch(path.substringAfterLast(':'), request.bodyText())
            path.endsWith("/decompress/v1/list") -> 200 to listArchive(request.bodyText())
            path.endsWith("/decompress/v1/decompress") -> 200 to """{"status":"OK","task_id":"T1","files_num":3}"""
            // 解压一直停在 37%：完成的任务从会话里移走，行与卡片就没了
            path.endsWith("/decompress/v1/progress") -> 200 to
                """{"progress":37,"phase":"PHASE_TYPE_RUNNING","file_id":"","task_size":"1000","task_size_completed":"370","expires_in":2}"""
            path.endsWith("/webdav/v1/applications") -> webDavApps?.let { apps ->
                200 to buildJsonObject {
                    put("webdav_enable", true); put("is_premium", premium)
                    put("applications", buildJsonArray { apps.forEach { add(it) } })
                }.toString()
            } ?: (400 to """{"error_code":3,"error":"invalid_argument"}""")
            else -> 404 to """{"error":"not_found"}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /**
     * SDK 按 Range 读，只认 bytes=a-b 这一种写法。没有登记内容的文件（视频）回全零，总长按节点大小报，
     * 转码档（id 带 -720 后缀）按三分之一报：画质对话框靠 1 字节的 Range 探大小。每次最多回 2 MB。
     */
    private fun blob(request: okhttp3.Request, id: String): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        val nodeId = id.substringBefore('-')
        val total: Long = blobs[id]?.size?.toLong()
            ?: synchronized(nodes) { nodes[nodeId] }?.size?.let { if (id.endsWith("-720")) it / 3 else it }
            ?: return builder.code(404).message("Not Found").body("".toResponseBody(null)).build()
        val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
        val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = minOf(range?.getOrNull(1)?.toLongOrNull() ?: (total - 1), total - 1, start + (2L shl 20) - 1)
        val body = blobs[id]?.copyOfRange(start.toInt(), end.toInt() + 1) ?: ByteArray((end - start + 1).toInt())
        if (range != null) builder.header("Content-Range", "bytes $start-$end/$total")
        return builder
            .code(if (range != null) 206 else 200)
            .message("OK")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }

    private fun transferQuota(): String {
        fun allowance(limit: Long, used: Long) = buildJsonObject { put("total_assets", limit); put("assets", used) }
        return buildJsonObject {
            put("base", buildJsonObject {
                put("offline", allowance(3000, 412))
                put("download", allowance(40L shl 40, (6.3 * (1L shl 40)).toLong()))
                put("upload", allowance(40L shl 40, 120L shl 30))
                put("download_daily", allowance(1L shl 40, dailyDownloadUsed))
                put("vip_status", if (premium) "ok" else "invalid")
                put("expire_time", if (premium) "2027-03-01T00:00:00.000+08:00" else "")
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

    private fun listEvents(filters: String): String {
        val types = runCatching {
            Json.parseToJsonElement(filters).jsonObject["type"]?.jsonObject?.get("in")?.jsonPrimitive?.content
        }.getOrNull()?.split(',')?.toSet()
        return buildJsonObject {
            put("next_page_token", "")
            put("events", buildJsonArray {
                events.filter { types == null || it.type in types }.sortedByDescending { it.time }.forEach { event ->
                    add(buildJsonObject {
                        put("id", event.id); put("type", event.type); put("type_name", event.type.removePrefix("TYPE_"))
                        put("file_id", event.node.id); put("file_name", event.node.name); put("mime_type", mimeOf(event.node.name))
                        put("folder_id", event.node.parentId); put("created_time", event.time); put("updated_time", event.time)
                        put("params", buildJsonObject { event.params.forEach { (k, v) -> put(k, v) } })
                        put("reference_resource", buildJsonObject { putNode(event.node) })
                    })
                }
            })
        }.toString()
    }

    private fun listArchive(body: String): String {
        val json = Json.parseToJsonElement(body).jsonObject
        val fileId = json["file_id"]?.jsonPrimitive?.content.orEmpty()
        val password = json["password"]?.jsonPrimitive?.content.orEmpty()
        val archive = archives[fileId] ?: Archive(emptyList())
        if (archive.status != "OK") return """{"status":"${archive.status}"}"""
        if (archive.password.isNotEmpty() && password != archive.password) {
            return if (password.isEmpty()) """{"status":"PASS_WORD_EMPTY"}""" else """{"status":"PASS_WORD_ERROR"}"""
        }
        val path = json["path"]?.jsonPrimitive?.content.orEmpty()
        return buildJsonObject {
            put("status", "OK"); put("status_text", ""); put("title", synchronized(nodes) { nodes[fileId] }?.name.orEmpty())
            put("file_size", "0"); put("gcid", "GCID$fileId"); put("current_path", path)
            put("files", buildJsonArray {
                archive.entries.forEachIndexed { index, (entryPath, size) ->
                    if (!entryPath.startsWith(path)) return@forEachIndexed
                    val rest = entryPath.removePrefix(path)
                    // 只列这一层：子目录以「名字/」登记
                    val isFolder = rest.endsWith("/")
                    if (rest.trimEnd('/').contains('/')) return@forEachIndexed
                    add(buildJsonObject {
                        put("index", index); put("filename", rest.trimEnd('/')); put("filesize", "$size")
                        put("mime_type", if (isFolder) "" else mimeOf(rest)); put("gcid", "")
                        put("kind", if (isFolder) "drive#folder" else "drive#file"); put("path", entryPath)
                    })
                }
            })
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
        put("message", task.message)
        put("params", buildJsonObject { if (task.url.isNotEmpty()) put("url", task.url) })
        put("file_id", task.fileId)
        put("file_name", task.name)
        put("file_size", task.size.toString())
        put("created_time", task.created)
        put("updated_time", task.created)
    }

    /** [detail] 为真时按详情接口的形状多给直链与媒体列表：视频有原画与 720P 两档，播放、片段与画质下载要用。 */
    private fun JsonObjectBuilder.putNode(node: Node, detail: Boolean = false) {
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
        if (node.thumbnail.isNotEmpty()) put("thumbnail_link", node.thumbnail)
        if (node.params.isNotEmpty()) put("params", buildJsonObject { node.params.forEach { (k, v) -> put(k, v) } })
        if (node.trashed) put("delete_time", "2026-09-25T12:00:00.000+08:00")
        val link = "https://fake-cdn.piko.test/blob/${node.id}"
        if (blobs.containsKey(node.id) || (detail && !node.isFolder)) {
            put("links", buildJsonObject {
                put("application/octet-stream", buildJsonObject {
                    put("url", link)
                    put("token", "")
                    put("expire", "2099-01-01T00:00:00.000+08:00")
                })
            })
        }
        if (detail && isVideo(node.name)) {
            put("medias", buildJsonArray {
                add(buildJsonObject {
                    put("media_id", "morigin"); put("media_name", "Original"); put("is_origin", true)
                    put("video", buildJsonObject { put("width", 1920); put("height", 1080); put("duration", 1450); put("video_type", "matroska,webm") })
                    put("link", buildJsonObject { put("url", link); put("expire", "") })
                })
                add(buildJsonObject {
                    put("media_id", "m720"); put("media_name", "720P"); put("resolution_name", "720P"); put("is_origin", false)
                    put("video", buildJsonObject { put("width", 1280); put("height", 720); put("duration", 1450); put("video_type", "mpegts") })
                    put("link", buildJsonObject { put("url", "$link-720"); put("expire", "") })
                })
            })
        }
    }

    private fun isVideo(name: String) = name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4")

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

/** 现在往前 [hoursAgo] 小时的 RFC 3339 时刻。界面上「已完成」只列 7 天内的云端任务，写死日期的数据会过期消失。 */
fun hoursAgo(hoursAgo: Long): String =
    OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(hoursAgo).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** 启动时会话存储里有什么，决定落在主界面还是哪一种登录页。 */
enum class LoginSeed {
    /** 一个未过期的会话，恢复时不发登录请求。 */
    SIGNED_IN,

    /** 同上，另存一个已登录过的账号，账号切换里有两行。 */
    TWO_ACCOUNTS,

    /** 什么也没有：无账号的登录页。 */
    NONE,

    /** 会话过期、没有刷新令牌也没存密码：恢复时要密码，落到「登录已失效」的登录页。 */
    EXPIRED,

    /** 读账号表一直不返回：停在初始化的整窗加载。 */
    STUCK,
}

/** 内存会话存储，内容按 [seed] 预置。 */
class MemorySessionStore(account: String, private val seed: LoginSeed = LoginSeed.SIGNED_IN) : PikoSessionStore {
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var accounts = when (seed) {
        LoginSeed.NONE, LoginSeed.STUCK -> SavedAccounts()
        LoginSeed.TWO_ACCOUNTS -> SavedAccounts(account, listOf(SavedAccount(account), SavedAccount(SECOND_ACCOUNT, name = "备用账号", usageBytes = 420L shl 30)))
        else -> SavedAccounts(account, listOf(SavedAccount(account)))
    }

    init {
        when (seed) {
            LoginSeed.EXPIRED -> sessions[account] = Session(accessToken = "OLD", refreshToken = "", sub = "UID", expiresAt = 1L)
            LoginSeed.NONE, LoginSeed.STUCK -> Unit
            else -> {
                sessions[account] = Session(accessToken = "AT", refreshToken = "RT", sub = "UID", expiresAt = 4_000_000_000L)
                sessions[SECOND_ACCOUNT] = Session(accessToken = "AT2", refreshToken = "RT2", sub = "UID2", expiresAt = 4_000_000_000L)
            }
        }
    }

    override suspend fun load(account: String): Session? = sessions[account]
    override suspend fun save(account: String, session: Session) { sessions[account] = session }
    override suspend fun clear(account: String) { sessions.remove(account) }
    override suspend fun loadAccounts(): SavedAccounts {
        if (seed == LoginSeed.STUCK) kotlinx.coroutines.awaitCancellation()
        return accounts
    }
    override suspend fun saveAccounts(accounts: SavedAccounts) { this.accounts = accounts }
    override suspend fun loadCredentials(account: String): PikoCredentials? =
        if (seed == LoginSeed.EXPIRED) null else PikoCredentials(account, "pw")
    override suspend fun saveCredentials(account: String, password: String) = Unit
    override suspend fun clearCredentials(account: String) = Unit
    private val archivePasswords = ConcurrentHashMap<String, String>()
    override suspend fun loadArchivePasswords(account: String): String = archivePasswords[account].orEmpty()
    override suspend fun saveArchivePasswords(account: String, serialized: String) { archivePasswords[account] = serialized }
}

private const val SECOND_ACCOUNT = "backup@piko.dev"
