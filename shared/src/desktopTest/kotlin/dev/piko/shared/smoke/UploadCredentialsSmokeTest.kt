package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import io.github.nihildigit.pikpak.UploadSession
import kotlinx.io.RawSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 上传会话的 OSS 凭据不随任务表明文存：1.1.0 留下的明文在启动后搬进机密存储，任务移除后随之清掉。
 */
class UploadCredentialsSmokeTest {
    private object NoSources : PikoUploadSources {
        override fun describe(uri: String): UploadSourceInfo? = null
        override fun readAt(uri: String, offset: Long, length: Int): ByteArray = error("不该读源文件")
        override fun open(uri: String, offset: Long): RawSource = error("不该读源文件")
        override fun listFolder(uri: String): UploadFolder? = null
    }

    private val session = UploadSession(
        fileId = "pending-file",
        size = 10,
        partSize = 10,
        uploadId = "upload-1",
        bucket = "bucket",
        endpoint = "oss.example.com",
        key = "object-key",
        accessKeyId = "STS.key-id",
        accessKeySecret = "the-secret",
        securityToken = "the-token",
        expiration = "2099-01-01T00:00:00.000+08:00",
    )

    @Test
    fun `legacy plaintext credentials move into the secret store and leave with the task`() = smoke { scope ->
        val prefs = MemoryPreferences()
        // 另一个账号的任务：移除时不去服务端放弃会话，测试不碰网络
        val task = UploadTask(
            taskId = "1-1",
            account = "someone-else",
            sourceUri = "file:///a.bin",
            fileName = "a.bin",
            size = 10,
            lastModifiedMs = 0,
            parentId = "",
            parentName = "网盘",
            status = UploadStatus.PAUSED,
            gcid = "gcid",
            session = session,
        )
        prefs.uploadTasks = Json.encodeToString(ListSerializer(UploadTask.serializer()), listOf(task))

        val server = FakePikPakServer()
        val coordinator = PikoUploadCoordinator(server.provider(), prefs, NoSources, PikoDriveRepository(server.provider(), prefs), scope)

        awaitUntil("凭据搬出任务表") { "the-secret" !in prefs.uploadTasks && prefs.uploadCredentials.containsKey("1-1") }
        assertTrue("the-token" !in prefs.uploadTasks && "upload-1" in prefs.uploadTasks, prefs.uploadTasks)
        // 内存里的会话仍带着凭据，续传不受影响
        assertEquals("the-secret", coordinator.tasks.value.getValue("1-1").session?.accessKeySecret)

        coordinator.remove("1-1")
        awaitUntil("移除任务后凭据清掉") { prefs.uploadCredentials.isEmpty() }
    }
}
