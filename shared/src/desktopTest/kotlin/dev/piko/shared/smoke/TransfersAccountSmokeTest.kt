package dev.piko.shared.smoke

import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import kotlinx.io.RawSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** 传输页只列当前账号的下载；别的账号的任务原样留在任务表里，切回去照旧看得到。 */
class TransfersAccountSmokeTest {
    private object NoSources : PikoUploadSources {
        override fun describe(uri: String): UploadSourceInfo? = null
        override fun readAt(uri: String, offset: Long, length: Int): ByteArray = error("不该读源文件")
        override fun open(uri: String, offset: Long): RawSource = error("不该读源文件")
        override fun listFolder(uri: String): UploadFolder? = null
    }

    @Test
    fun `downloads of another account are left out of the transfers page`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider(server.client(account = "a@piko.dev"))
        fun paused(id: String, account: String, batch: DownloadBatch? = null) = DownloadTask(
            taskId = id, fileId = id, fileName = "$id.mkv", gcid = "G$id", totalBytes = 100, downloadedBytes = 10,
            status = DownloadStatus.PAUSED, account = account, batch = batch,
        )
        val prefs = MemoryPreferences().apply {
            downloadTasks = Json.encodeToString(
                ListSerializer(DownloadTask.serializer()),
                listOf(
                    paused("mine", "a@piko.dev"),
                    // 有多账号之前建的任务不带账号，哪个账号都列
                    paused("legacy", ""),
                    paused("theirs", "b@piko.dev"),
                    paused("their-batch-file", "b@piko.dev", DownloadBatch("their-batch", "乙的文件夹")),
                ),
            )
        }
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(Files.createTempDirectory("piko-transfers").toFile()), scope)
        awaitUntil("任务表读回") { coordinator.tasks.value.size == 4 }

        val driveRepo = PikoDriveRepository(provider, prefs)
        val instantRepo = InstantMagnetRepository(provider)
        val state = TransfersState(
            coordinator,
            TaskRepository(provider, driveRepo),
            OfflinePackTracker(instantRepo, driveRepo, prefs),
            scope,
            driveRepo,
            PikoUploadCoordinator(provider, prefs, NoSources, driveRepo, scope),
            InstantSaveRecords(provider, null, scope),
            "a@piko.dev",
            ArchiveExtractSession(provider, driveRepo, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope),
            FolderVaultSession(driveRepo, scope),
            listsServerWork = false,
        )

        fun listed() = with(state.sectionsOf(TransferKind.ALL)) { inProgress + needsAttention + completed + outputDeleted }.map { it.key }.toSet()
        awaitUntil("列出当前账号的下载") { listed().isNotEmpty() }
        assertEquals(setOf("local:mine", "local:legacy"), listed())
        assertEquals(4, coordinator.tasks.value.size, "别的账号的任务不该被删")
    }
}
