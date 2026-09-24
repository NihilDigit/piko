package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientProvider
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存里的 PikPak 服务端，经 Ktor MockEngine 接到真实的 SDK 客户端上。
 *
 * 冒烟测试要走的是 piko 与 SDK 合起来的真实路径：SDK 的请求构造、鉴权、验证码、重试与
 * JSON 解析都不替换，只把网络的另一头换成这里。所以这里只模拟服务端的可观察行为
 * （目录树、回收站、离线任务、秒传索引、CDN 分段读取），不去断言请求的具体写法。
 * 响应体的字段取自 SDK 自带的 MockTest，那些是线上响应的裁剪版。
 */
class FakePikPakServer {
    class Node(
        val id: String,
        @Volatile var parentId: String,
        @Volatile var name: String,
        val isFolder: Boolean,
        @Volatile var trashed: Boolean = false,
        val hash: String = "",
        val content: ByteArray = ByteArray(0),
        val size: Long = content.size.toLong(),
    )

    class Task(val id: String, val name: String, phase: String, val parentId: String, val url: String) {
        @Volatile var phase: String = phase
        @Volatile var fileId: String = ""
        @Volatile var fileName: String = ""
    }

    private val lock = Any()
    private val nodes = LinkedHashMap<String, Node>()
    private val tasks = CopyOnWriteArrayList<Task>()
    private val magnetResources = HashMap<String, String>()
    private val nextId = AtomicInteger(1)

    /** 每个请求记一条「方法 路径」，用于判断某类请求有没有发生、发生了几次。 */
    val calls = CopyOnWriteArrayList<String>()

    /** 为真时所有请求在传输层失败，模拟断网。 */
    @Volatile var offline = false

    /** /drive/v1/about 报告的空间上限与已用量。 */
    @Volatile var quotaLimit: Long = 10L shl 40
    @Volatile var quotaUsage: Long = 0

    /** 秒传建出的文件数。只数带 hash 的建文件请求，建目录与离线任务不算。 */
    val instantCreates = AtomicInteger(0)

    /** 为真时密码登录被服务端拒绝。 */
    @Volatile var rejectSignIn = false

    /** 每个 CDN 分段响应前的等待，用来让下载停在半途。 */
    @Volatile var cdnDelayMs = 0L

    val engine = MockEngine { request -> handle(request) }

    fun addFolder(name: String, parentId: String = "", trashed: Boolean = false): Node =
        add(Node(newId(), parentId, name, isFolder = true, trashed = trashed))

    fun addFile(
        name: String,
        parentId: String = "",
        content: ByteArray = ByteArray(0),
        hash: String = "",
        trashed: Boolean = false,
    ): Node = add(Node(newId(), parentId, name, isFolder = false, trashed = trashed, hash = hash, content = content))

    fun addTask(name: String, phase: String) {
        tasks += Task(newId(), name, phase, parentId = "", url = "")
    }

    /** 让 resolveMagnet 对这条链返回给定的资源列表响应体。 */
    fun indexMagnet(magnet: String, resourceListBody: String) {
        synchronized(lock) { magnetResources[magnet] = resourceListBody }
    }

    fun node(id: String): Node? = synchronized(lock) { nodes[id] }

    fun children(parentId: String): List<Node> = synchronized(lock) {
        nodes.values.filter { it.parentId == parentId && !it.trashed }
    }

    fun tasksSnapshot(): List<Task> = tasks.toList()

    /**
     * 让离线任务完成：在任务的保存目录下按种子结构建出产出，与线上一致是一个以种子名
     * 命名的文件夹，里面是 resolveMagnet 报告的相对路径。
     */
    fun completeTask(taskId: String, rootName: String, paths: List<String>) {
        val task = tasks.first { it.id == taskId }
        val root = addFolder(rootName, task.parentId)
        val dirs = HashMap<String, String>().apply { put("", root.id) }
        for (path in paths) {
            val segments = path.split('/')
            var prefix = ""
            for (segment in segments.dropLast(1)) {
                val next = if (prefix.isEmpty()) segment else "$prefix/$segment"
                if (next !in dirs) dirs[next] = addFolder(segment, dirs.getValue(prefix)).id
                prefix = next
            }
            addFile(segments.last(), dirs.getValue(prefix), hash = "H-$path")
        }
        task.fileId = root.id
        task.fileName = rootName
        task.phase = "PHASE_TYPE_COMPLETE"
    }

    /** 某目录下所有文件的相对路径，递归。 */
    fun tree(folderId: String, prefix: String = ""): List<String> = children(folderId).flatMap { node ->
        val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
        if (node.isFolder) tree(node.id, path) else listOf(path)
    }

    fun count(prefix: String): Int = calls.count { it.startsWith(prefix) }

    /** 直接构造的客户端：不限流，会话放内存里，已登录。 */
    fun client(sessionStore: SessionStore = InMemorySessionStore()): PikPakClient = PikPakClient(
        account = "smoke@piko.dev",
        password = "pw",
        sessionStore = sessionStore,
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
        httpClient = httpClient(),
    )

    fun httpClient(): HttpClient = HttpClient(engine)

    fun provider(client: PikPakClient = client()): PikoClientProvider = object : PikoClientProvider {
        override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
    }

    private fun add(node: Node): Node = synchronized(lock) { nodes[node.id] = node; node }

    private fun newId(): String = "N${nextId.getAndIncrement()}"

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        calls += "${request.method.value} $path"
        if (offline) throw IOException("smoke: network is down")
        return when {
            path.endsWith("/v1/shield/captcha/init") ->
                json("""{"captcha_token":"CAP","expires_in":300,"url":""}""")
            path.endsWith("/v1/auth/signin") ->
                if (rejectSignIn) {
                    json("""{"error_code":4002,"error":"invalid_account_or_password"}""", HttpStatusCode.BadRequest)
                } else {
                    tokenResponse()
                }
            path.endsWith("/v1/auth/token") -> tokenResponse()
            request.url.host == CDN_HOST -> cdn(request, path.removePrefix("/"))
            path.endsWith("/drive/v1/resource/list") -> resolveMagnet(request)
            path.endsWith("/drive/v1/about") -> about()
            path.endsWith("/drive/v1/tasks") && request.method == HttpMethod.Delete -> deleteTasks(request)
            path.endsWith("/drive/v1/tasks") -> listTasks(request)
            path.contains("/drive/v1/tasks/") -> taskDetail(path.substringAfterLast('/'))
            path.contains("/drive/v1/files:") -> batch(request, path.substringAfterLast(':'))
            path.endsWith("/drive/v1/files") && request.method == HttpMethod.Post -> createFile(request)
            path.endsWith("/drive/v1/files") -> listFiles(request)
            path.contains("/drive/v1/files/") && request.method == HttpMethod.Patch ->
                rename(request, path.substringAfterLast('/'))
            path.contains("/drive/v1/files/") -> fileDetail(path.substringAfterLast('/'))
            else -> json("""{"error":"not_found"}""", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.about(): HttpResponseData = json(
        buildJsonObject {
            put("kind", "drive#about")
            put("quota", buildJsonObject {
                put("kind", "drive#quota")
                put("limit", quotaLimit.toString())
                put("usage", quotaUsage.toString())
                put("usage_in_trash", "0")
            })
        }.toString(),
    )

    private fun MockRequestHandleScope.taskDetail(id: String): HttpResponseData {
        val task = tasks.firstOrNull { it.id == id }
            ?: return json("""{"error_code":4,"error":"task_not_found"}""", HttpStatusCode.NotFound)
        return json(taskJson(task).toString())
    }

    private fun MockRequestHandleScope.deleteTasks(request: HttpRequestData): HttpResponseData {
        val ids = request.url.parameters.getAll("task_ids").orEmpty().toSet()
        tasks.removeIf { it.id in ids }
        return json("{}")
    }

    private suspend fun MockRequestHandleScope.rename(request: HttpRequestData, id: String): HttpResponseData {
        val name = Json.parseToJsonElement(request.body.text()).jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
        val node = node(id) ?: return json("""{"error_code":3,"error":"file_not_found"}""", HttpStatusCode.NotFound)
        node.name = name
        return json(buildJsonObject { putNode(node) }.toString())
    }

    private fun taskJson(task: Task) = buildJsonObject {
        put("id", task.id)
        put("name", task.name)
        put("phase", task.phase)
        put("file_id", task.fileId)
        put("file_name", task.fileName)
        put("file_size", "0")
    }

    private fun MockRequestHandleScope.tokenResponse() =
        json("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")

    private fun MockRequestHandleScope.fileDetail(id: String): HttpResponseData {
        val node = node(id) ?: return json("""{"error_code":3,"error":"file_not_found"}""", HttpStatusCode.NotFound)
        // 与线上一致：已进回收站的条目照样返回详情，只是 trashed 为真
        val detail = buildJsonObject {
            putNode(node)
            put(
                "links",
                buildJsonObject {
                    put(
                        "application/octet-stream",
                        buildJsonObject {
                            put("url", "https://$CDN_HOST/${node.id}")
                            put("expire", "")
                        },
                    )
                },
            )
        }
        return json(detail.toString())
    }

    private fun MockRequestHandleScope.listFiles(request: HttpRequestData): HttpResponseData {
        val filters = request.url.parameters["filters"].orEmpty()
        val wantsTrash = filters.replace(" ", "").contains("\"trashed\":{\"eq\":true}")
        val parentId = request.url.parameters["parent_id"].orEmpty()
        val listed = synchronized(lock) {
            if (wantsTrash) {
                nodes.values.filter { it.trashed }
            } else {
                nodes.values.filter { it.parentId == parentId && !it.trashed }
            }
        }
        val body = buildJsonObject {
            put("next_page_token", "")
            put("files", buildJsonArray { listed.forEach { add(buildJsonObject { putNode(it) }) } })
        }
        return json(body.toString())
    }

    private suspend fun MockRequestHandleScope.createFile(request: HttpRequestData): HttpResponseData {
        val body = Json.parseToJsonElement(request.body.text()).jsonObject
        val parentId = body["parent_id"]?.jsonPrimitive?.content.orEmpty()
        val name = body["name"]?.jsonPrimitive?.content.orEmpty()
        return when {
            body["kind"]?.jsonPrimitive?.content == "drive#folder" -> {
                val folder = addFolder(name, parentId)
                json(buildJsonObject { put("file", buildJsonObject { putNode(folder) }) }.toString())
            }
            body["upload_type"]?.jsonPrimitive?.content == "UPLOAD_TYPE_URL" -> {
                val url = body["url"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
                val task = Task(newId(), "offline", "PHASE_TYPE_PENDING", parentId, url)
                tasks += task
                json(
                    """{"task":{"id":"${task.id}","name":"offline","phase":"PHASE_TYPE_PENDING",""" +
                        """"file_id":"","file_size":"0"}}""",
                )
            }
            else -> {
                val hash = body["hash"]?.jsonPrimitive?.content.orEmpty()
                instantCreates.incrementAndGet()
                val file = addFile(name, parentId, hash = hash)
                json(buildJsonObject {
                    put("upload_type", "UPLOAD_TYPE_RESUMABLE")
                    put("file", buildJsonObject {
                        putNode(file)
                        put("phase", "PHASE_TYPE_COMPLETE")
                    })
                }.toString())
            }
        }
    }

    private suspend fun MockRequestHandleScope.batch(request: HttpRequestData, op: String): HttpResponseData {
        val body = Json.parseToJsonElement(request.body.text()).jsonObject
        val ids = body["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val moveTo = body["to"]?.jsonObject?.get("parent_id")?.jsonPrimitive?.content.orEmpty()
        synchronized(lock) {
            ids.forEach { id ->
                when (op) {
                    "batchTrash" -> nodes[id]?.trashed = true
                    "batchUntrash" -> nodes[id]?.trashed = false
                    // 目录连同其下的一切一并删掉，与线上的永久删除一致
                    "batchDelete" -> removeSubtree(id)
                    "batchMove" -> nodes[id]?.parentId = moveTo
                }
            }
        }
        return json("{}")
    }

    private fun removeSubtree(id: String) {
        nodes.values.filter { it.parentId == id }.map { it.id }.forEach(::removeSubtree)
        nodes.remove(id)
    }

    private suspend fun MockRequestHandleScope.resolveMagnet(request: HttpRequestData): HttpResponseData {
        val magnet = Json.parseToJsonElement(request.body.text()).jsonObject["urls"]?.jsonPrimitive?.content
        val body = synchronized(lock) { magnetResources[magnet] }
            ?: """{"list_id":"L","list":{"page_size":500,"resources":[]}}"""
        return json(body)
    }

    private fun MockRequestHandleScope.listTasks(request: HttpRequestData): HttpResponseData {
        // 按请求里的 phase 过滤条件返回，与线上一致：没请求的阶段服务端不会给
        val filters = request.url.parameters["filters"].orEmpty()
        val requested = runCatching {
            Json.parseToJsonElement(filters).jsonObject["phase"]?.jsonObject?.get("in")?.jsonPrimitive?.content
        }.getOrNull()?.split(',')?.toSet()
        val listed = tasks.filter { requested == null || it.phase in requested }
        val body = buildJsonObject {
            put("next_page_token", "")
            put("tasks", buildJsonArray {
                listed.forEach { task -> add(taskJson(task)) }
            })
        }
        return json(body.toString())
    }

    private suspend fun MockRequestHandleScope.cdn(request: HttpRequestData, id: String): HttpResponseData {
        val node = node(id) ?: return respond("", HttpStatusCode.NotFound)
        if (cdnDelayMs > 0) delay(cdnDelayMs)
        val content = node.content
        val range = request.headers[HttpHeaders.Range]
        if (range == null) return respond(ByteReadChannel(content), HttpStatusCode.OK)
        val (fromText, toText) = range.removePrefix("bytes=").split('-')
        val from = fromText.toInt()
        val to = (toText.toIntOrNull() ?: content.lastIndex).coerceAtMost(content.lastIndex)
        return respond(
            content = ByteReadChannel(content.copyOfRange(from, to + 1)),
            status = HttpStatusCode.PartialContent,
            headers = headersOf(HttpHeaders.ContentRange, "bytes $from-$to/${content.size}"),
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNode(node: Node) {
        put("kind", if (node.isFolder) "drive#folder" else "drive#file")
        put("id", node.id)
        put("parent_id", node.parentId)
        put("name", node.name)
        put("size", node.size.toString())
        put("hash", node.hash)
        put("trashed", node.trashed)
        put("modified_time", "2026-09-01T00:00:00.000+08:00")
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun OutgoingContent.text(): String = when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> ""
    }

    companion object {
        const val CDN_HOST = "cdn.smoke.test"
    }
}

/** 资源列表响应：一层根目录，下面是给定的文件（路径可含子目录）。gcid 为空表示云端没收录。 */
fun resourceListBody(rootName: String, files: List<Triple<String, Long, String>>): String {
    fun leaf(name: String, size: Long, gcid: String) = buildJsonObject {
        put("id", "r-$name")
        put("name", name)
        put("file_size", size.toString())
        put("is_dir", false)
        put("meta", buildJsonObject { put("hash", gcid) })
    }

    fun dir(name: String, children: JsonArray) = buildJsonObject {
        put("id", "d-$name")
        put("name", name)
        put("file_size", "0")
        put("is_dir", true)
        put("meta", buildJsonObject {})
        put("dir", buildJsonObject { put("resources", children) })
    }

    // 只支持一层子目录，足够表达 sample/ 这类次要目录
    val (nested, top) = files.partition { '/' in it.first }
    val subdirs = nested.groupBy { it.first.substringBefore('/') }
    val rootChildren = buildJsonArray {
        top.forEach { (name, size, gcid) -> add(leaf(name, size, gcid)) }
        subdirs.forEach { (dirName, entries) ->
            add(dir(dirName, buildJsonArray {
                entries.forEach { (path, size, gcid) -> add(leaf(path.substringAfter('/'), size, gcid)) }
            }))
        }
    }
    val root = dir(rootName, rootChildren)
    return buildJsonObject {
        put("list_id", "L")
        put("list", buildJsonObject {
            put("page_size", 500)
            put("resources", buildJsonArray { add(root) })
        })
    }.toString()
}
