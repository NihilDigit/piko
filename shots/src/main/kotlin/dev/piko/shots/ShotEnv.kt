package dev.piko.shots

import dev.piko.desktop.DesktopPikoDownloadStorage
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopPikoPreferences
import dev.piko.desktop.DesktopPikoSegmentDownloader
import dev.piko.desktop.DesktopPikoUploadSources
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.auth.PlainFileVault
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.ui.PikoServices
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface

private const val ACCOUNT = "demo@piko.dev"
private const val GB = 1L shl 30
private const val MB = 1L shl 20

/** 假服务端上的一份像样的网盘：根目录 6 个文件夹、25 个文件，一个子目录，回收站与离线任务。 */
fun FakePikPak.seed() {
    val anime = addFolder("动画", modified = "2026-09-26T20:10:00.000+08:00", starred = true)
    val movies = addFolder("电影", modified = "2026-09-24T18:00:00.000+08:00")
    addFolder("文档", modified = "2026-09-12T09:30:00.000+08:00")
    addFolder("音乐", modified = "2026-08-30T22:15:00.000+08:00")
    addFolder("照片备份 2026", modified = "2026-08-02T08:00:00.000+08:00")
    addFolder("Piko-Temp", modified = "2026-09-27T08:00:00.000+08:00")
    val show = "[Nekomoe kissaten] Kusuriya no Hitorigoto S2"
    for (ep in 1..12) {
        addFile(
            "[Nekomoe kissaten] Kusuriya no Hitorigoto S2 - %02d [1080p][HEVC].mkv".format(ep),
            size = 620 * MB + ep * 7 * MB,
            modified = "2026-09-%02dT23:%02d:00.000+08:00".format(10 + ep, ep * 3),
        )
    }
    addFile("Dune.Part.Two.2024.2160p.UHD.BluRay.x265.10bit.HDR.DTS-HD.MA.7.1.mkv", 27 * GB + 400 * MB, modified = "2026-09-22T12:00:00.000+08:00", starred = true)
    addFile("Oppenheimer.2023.1080p.BluRay.x264.mkv", 14 * GB + 300 * MB, modified = "2026-09-21T12:00:00.000+08:00")
    addFile("Perfect.Days.2023.1080p.WEB-DL.mp4", 4 * GB + 120 * MB, modified = "2026-09-19T12:00:00.000+08:00")
    addFile("Kotlin in Action, Second Edition.pdf", 11 * MB, modified = "2026-09-05T10:00:00.000+08:00")
    addFile("2026 年度报告（终稿）.pdf", 3 * MB, modified = "2026-09-02T10:00:00.000+08:00", starred = true)
    addFile("发票-2026-09.pdf", 420 * 1024L, modified = "2026-09-01T10:00:00.000+08:00")
    addFile("Project Sekai OST Vol.3.zip", 1 * GB + 80 * MB, modified = "2026-08-28T10:00:00.000+08:00")
    addFile("android-sdk-backup.7z", 5 * GB + 900 * MB, modified = "2026-08-20T10:00:00.000+08:00")
    addFile("截图合集.zip", 260 * MB, modified = "2026-08-11T10:00:00.000+08:00")
    addFile("IMG_20260801_183012.jpg", 6 * MB, modified = "2026-08-01T18:30:00.000+08:00")
    addFile("三体 全集.epub", 5 * MB, modified = "2026-07-15T10:00:00.000+08:00")
    addFile("Yoasobi - 群青.flac", 38 * MB, modified = "2026-07-10T10:00:00.000+08:00")
    addFile("readme.txt", 2 * 1024L, modified = "2026-07-01T10:00:00.000+08:00")

    // 子目录：另一部完整季，外加字幕
    for (ep in 1..12) {
        addFile("[SweetSub] Frieren - %02d [WebRip 1080p HEVC-10bit AAC][CHS_JPN].mkv".format(ep), 480 * MB + ep * MB, parentId = anime.id)
    }
    addFile("[SweetSub] Frieren - 01-12 [CHS_JPN].ass.zip", 2 * MB, parentId = anime.id)
    addFolder("SPs", parentId = anime.id)

    // 归档条目：根目录一个，「电影」里两个。清单与 Piko 写的同一格式
    addVaultManifest(
        "",
        VaultEntry("v-root-1", "Blade.Runner.2049.2017.2160p.UHD.BluRay.x265.mkv", 22 * GB, "A".repeat(40), source = "magnet:?xt=urn:btih:demo", addedAt = 1_790_600_000_000),
    )
    addVaultManifest(
        movies.id,
        VaultEntry("v-mov-1", "Interstellar.2014.2160p.UHD.BluRay.x265.mkv", 31 * GB, "B".repeat(40), source = "magnet:?xt=urn:btih:demo2", addedAt = 1_790_500_000_000),
        VaultEntry("v-mov-2", "Arrival.2016.1080p.BluRay.x264.mkv", 9 * GB, "C".repeat(40), source = "https://mypikpak.com/s/demo", addedAt = 1_790_400_000_000),
    )

    addFile("old-backup.zip", 3 * GB, trashed = true)
    addFile("$show - 01 [720p].mkv", 310 * MB, trashed = true)
    addFolder("临时下载", trashed = true)

    addTask("[ANi] Dandadan S2 - 12 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4", "PHASE_TYPE_RUNNING", 42, 1 * GB + 300 * MB)
    addTask("Ubuntu 26.04 LTS Desktop amd64.iso", "PHASE_TYPE_PENDING", 0, 6 * GB)
    addTask("Blade.Runner.2049.2017.2160p.mkv", "PHASE_TYPE_COMPLETE", 100, 22 * GB)
}

private fun FakePikPak.addVaultManifest(parentId: String, vararg entries: VaultEntry) {
    val body = buildJsonObject { put("entries", Json.encodeToJsonElement(ListSerializer(VaultEntry.serializer()), entries.toList())) }
    addBlob(".piko-vault-v1-${"0".repeat(16)}.json", body.toString().encodeToByteArray(), parentId)
}

/** 日志装到临时目录：不装时 PikoLog 把所有记录攒在内存里的 channel 中。 */
private val logInstalled by lazy {
    PikoLog.install(Files.createTempDirectory("piko-shots-log").toString())
}

/**
 * 一套完整的 [PikoServices]，照桌面入口（Main.kt）拼装，全部指向临时目录与 [FakePikPak]，
 * 已登录。每个截图各用一份，关掉时连同临时目录一起清掉。
 */
class ShotEnv(viewMode: String? = null, extraSeed: FakePikPak.() -> Unit = {}) : AutoCloseable {
    val dir: File = Files.createTempDirectory("piko-shots-").toFile()
    val server = FakePikPak().apply {
        seed()
        extraSeed()
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val downloads = File(dir, "Downloads").apply { mkdirs() }
    val settings = DesktopSettingsStore(File(dir, "settings.properties")).apply {
        setDownloadDirectory(downloads.path)
        set("download.tasks", seededDownloads(downloads))
        if (viewMode != null) set("ui.driveViewMode", viewMode)
    }
    val preferences = DesktopPikoPreferences(settings) { PlainFileVault(dir.toPath().resolve("secrets")) }
    val platform = DesktopPikoPlatform(settings)
    val services: PikoServices

    init {
        logInstalled
        val clientManager = PikoClientManager(MemorySessionStore(ACCOUNT), scope, server.httpClient())
        val media = PikoMediaRepository(clientManager, preferences, clipCache = FileClipCache(File(dir, "clip-cache")))
        services = PikoServices(
            platformPreferences = preferences,
            clientManager = clientManager,
            mediaRepository = media,
            downloadManager = PikoDownloadCoordinator(
                clientManager,
                preferences,
                DesktopPikoDownloadStorage(File(dir, "download-cache")) { downloads },
                scope,
                segmentDownloader = DesktopPikoSegmentDownloader(),
                mediaRepository = media,
            ),
            uploadSources = DesktopPikoUploadSources(),
            cacheStore = FilePikoCacheStore(File(dir, "cache").path),
        )
    }

    override fun close() {
        // 退出登录让 PikoServices 里随账号运行的后台协程停下，下一个用例不背着它们跑
        runBlocking { withTimeoutOrNull(2_000) { services.clientManager.logout().join() } }
        scope.cancel()
        dir.deleteRecursively()
    }
}

/** 下载列表：两个已完成（磁盘上放稀疏文件，恢复时按长度核对）、一个暂停、一个失败。 */
private fun seededDownloads(dir: File): String {
    fun sparse(name: String, length: Long) = RandomAccessFile(File(dir, name), "rw").use { it.setLength(length) }
    val now = System.currentTimeMillis()
    val done1 = "Perfect.Days.2023.1080p.WEB-DL.mp4"
    val done2 = "Kotlin in Action, Second Edition.pdf"
    val paused = "Oppenheimer.2023.1080p.BluRay.x264.mkv"
    // 本地缩略图：界面对已下载的文件用 Coil 解码本地文件，几 GB 的稀疏文件会整个读进堆里，给视频另备小图
    fun thumb(name: String, from: Int, to: Int): String {
        val surface = Surface.makeRasterN32Premul(320, 180)
        surface.canvas.clear(from)
        surface.canvas.drawCircle(220f, 70f, 60f, Paint().apply { color = to })
        val file = File(dir.parentFile, "$name.png")
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file.absolutePath
    }
    sparse(done1, 4 * GB + 120 * MB)
    sparse(done2, 11 * MB)
    sparse(paused, 5 * GB + 700 * MB)
    val tasks = listOf(
        DownloadTask("d1", "X1", done1, "G1", 4 * GB + 120 * MB, 4 * GB + 120 * MB, status = DownloadStatus.COMPLETED,
            destinationPath = File(dir, done1).path, thumbnailLink = thumb("t1", 0xFF3B6E8F.toInt(), 0xFFE0B27A.toInt()), createdAtMs = now - 3_600_000),
        DownloadTask("d2", "X2", done2, "G2", 11 * MB, 11 * MB, status = DownloadStatus.COMPLETED,
            destinationPath = File(dir, done2).path, createdAtMs = now - 7_200_000),
        DownloadTask("d3", "X3", paused, "G3", 14 * GB + 300 * MB, 5 * GB + 700 * MB, status = DownloadStatus.PAUSED,
            destinationPath = File(dir, paused).path, thumbnailLink = thumb("t3", 0xFF1E1E2A.toInt(), 0xFFC4572E.toInt()), createdAtMs = now - 600_000),
        DownloadTask("d4", "X4", "Project Sekai OST Vol.3.zip", "G4", 1 * GB + 80 * MB, 0, status = DownloadStatus.FAILED,
            errorMessage = "网络连接中断", destinationPath = File(dir, "Project Sekai OST Vol.3.zip").path, createdAtMs = now - 300_000),
    ) + seededFolderBatch(dir, now - 120_000, thumb("t5", 0xFF2F4A3A.toInt(), 0xFFD9D2E9.toInt()))
    return Json.encodeToString(ListSerializer(DownloadTask.serializer()), tasks)
}

/** 一次文件夹下载：十二集加两个字幕，前四集已下完（同样放稀疏文件），第五集下到一半，其余未开始。 */
private fun seededFolderBatch(dir: File, createdAtMs: Long, thumbnail: String): List<DownloadTask> {
    val batch = DownloadBatch("shots-batch", "Frieren S01")
    val episodeSize = 1 * GB + 400 * MB
    fun task(index: Int, path: String, size: Long, downloaded: Long, status: DownloadStatus): DownloadTask {
        val fileName = "${batch.folderName}/$path"
        if (downloaded > 0) {
            File(dir, fileName).parentFile.mkdirs()
            RandomAccessFile(File(dir, fileName), "rw").use { it.setLength(downloaded) }
        }
        return DownloadTask("b$index", "XB$index", fileName, "GB$index", size, downloaded, status = status,
            destinationPath = File(dir, fileName).path, createdAtMs = createdAtMs, batch = batch,
            // 视频配小图，理由同上
            thumbnailLink = if (path.endsWith(".mkv")) thumbnail else "")
    }
    val episodes = (1..12).map { n ->
        val name = "[SubsPlease] Sousou no Frieren - %02d (1080p).mkv".format(n)
        when {
            n <= 4 -> task(n, name, episodeSize, episodeSize, DownloadStatus.COMPLETED)
            n == 5 -> task(n, name, episodeSize, 600 * MB, DownloadStatus.PAUSED)
            else -> task(n, name, episodeSize, 0, DownloadStatus.PAUSED)
        }
    }
    return episodes + listOf(
        task(13, "Subs/Frieren - 01.ass", 80_000, 80_000, DownloadStatus.COMPLETED),
        task(14, "Subs/Frieren - 02.ass", 80_000, 0, DownloadStatus.PAUSED),
    )
}
