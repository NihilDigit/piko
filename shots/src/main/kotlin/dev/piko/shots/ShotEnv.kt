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
import dev.piko.ui.platform.DiskSpace
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.PikoPlatform
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.nio.file.StandardOpenOption
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

    // 以下只在各自的页面读到，加了不影响网盘根目录与传输页的图
    addEvent("TYPE_UPLOAD", node("Perfect.Days.2023.1080p.WEB-DL.mp4"), hoursAgo(3))
    addEvent("TYPE_RESTORE", node("Dune.Part.Two.2024.2160p.UHD.BluRay.x265.10bit.HDR.DTS-HD.MA.7.1.mkv"), hoursAgo(26))
    addEvent("TYPE_UPLOAD", node("2026 年度报告（终稿）.pdf"), hoursAgo(50))
    addEvent("TYPE_PLAY", node("Oppenheimer.2023.1080p.BluRay.x264.mkv"), hoursAgo(2), playSeconds = 4_210, playDuration = 10_800)
    addEvent("TYPE_PLAY", node("$show - 03 [1080p][HEVC].mkv"), hoursAgo(20), playSeconds = 880, playDuration = 1_440)

    addArchive(node("Project Sekai OST Vol.3.zip"), FakePikPak.Archive(listOf(
        "Disc 1/" to 0L, "Disc 2/" to 0L, "cover.jpg" to 2 * MB, "booklet.pdf" to 18 * MB, "readme.txt" to 1_024L,
        "Disc 1/01 - Opening.flac" to 32 * MB, "Disc 1/02 - Theme.flac" to 41 * MB,
    )))
    addArchive(node("截图合集.zip"), FakePikPak.Archive(emptyList()))
    addArchive(node("android-sdk-backup.7z"), FakePikPak.Archive(listOf("platforms/" to 0L, "build-tools/" to 0L, "licenses.txt" to 4_096L), password = "piko"))

    fun share(id: String, title: String, kind: String, size: Long, files: Int, views: Int, saves: Int, status: String = "OK", passCode: String = "") = buildJsonObject {
        put("share_id", id); put("share_url", "https://mypikpak.com/s/$id"); put("share_status", status); put("title", title)
        put("pass_code", passCode); put("file_num", "$files"); put("file_id", node(title).id); put("file_kind", kind)
        put("file_size", "$size"); put("expiration_days", "-1"); put("expiration_at", "-1")
        put("restore_count", "$saves"); put("view_count", "$views"); put("create_time", hoursAgo(views.toLong() * 3))
    }
    shares = listOf(
        share("VSHOT1", "动画", "drive#folder", 6 * GB, 13, 42, 7),
        share("VSHOT2", "Perfect.Days.2023.1080p.WEB-DL.mp4", "drive#file", 4 * GB + 120 * MB, 1, 15, 3, passCode = "pk7q"),
        share("VSHOT3", "三体 全集.epub", "drive#file", 5 * MB, 1, 120, 0, status = "EXPIRED"),
    )
    webDavApps = listOf(buildJsonObject {
        put("id", 1); put("application_name", "Infuse"); put("username", "piko-infuse"); put("password", "s3cret-pass")
        put("max_entries_in_response", 500); put("optimized_for_media_play", 1); put("read_only", 1); put("enable", 1)
        put("created_at", "2026-10-01T10:00:00.000+08:00"); put("last_active_at", hoursAgo(5)); put("last_active_ip", "203.0.113.7")
    })
}

private fun FakePikPak.addVaultManifest(parentId: String, vararg entries: VaultEntry) {
    val body = buildJsonObject { put("entries", Json.encodeToJsonElement(ListSerializer(VaultEntry.serializer()), entries.toList())) }
    addBlob(".piko-vault-v1-${"0".repeat(16)}.json", body.toString().encodeToByteArray(), parentId)
}

/** 日志装到临时目录：不装时 PikoLog 把所有记录攒在内存里的 channel 中。 */
private val logInstalled by lazy {
    PikoLog.install(File(ShotDirs.run, "log-${ProcessHandle.current().pid()}").path)
}

/**
 * 截图的临时文件都在 `%TEMP%/piko-shots/<主进程 pid>/` 下：每张图一个子目录，日志与生成的图片也在这里。
 * `--jobs` 拉起的子进程经 [RUN_PROPERTY] 用主进程的那一份，主进程等它们退出后整个删掉。
 * 按 pid 分而不按图名：别的工作区可能同时在跑一套，图名相同的目录会互相删掉。
 */
object ShotDirs {
    const val RUN_PROPERTY = "piko.shots.run"
    private val tmp = File(System.getProperty("java.io.tmpdir"))
    private val root = File(tmp, "piko-shots")

    val run: File by lazy {
        File(root, System.getProperty(RUN_PROPERTY) ?: ProcessHandle.current().pid().toString()).apply { mkdirs() }
    }

    /**
     * 清掉以前的运行没删成的目录：被强行结束的、或删的时候文件还被占着（Windows 上删不掉打开着的文件）。
     * 按 pid 命名的看那个进程还在不在；旧版按随机数命名的 `piko-shots-*` 与按图名命名的看修改时间，
     * 一张图只活几秒，一小时没动过的不会还有人在用。
     */
    fun sweep() {
        val stale = System.currentTimeMillis() - 3_600_000
        root.listFiles().orEmpty().forEach { dir ->
            val pid = dir.name.toLongOrNull()
            val alive = if (pid != null) ProcessHandle.of(pid).map { it.isAlive }.orElse(false) else dir.lastModified() > stale
            if (!alive) dir.deleteRecursively()
        }
        tmp.listFiles { f -> f.name.startsWith("piko-shots-") || f.name.startsWith("piko-shot-image") }.orEmpty()
            .filter { it.lastModified() < stale }
            .forEach { it.deleteRecursively() }
    }

    private val children = CopyOnWriteArrayList<Process>()

    /** `--jobs` 拉起的子进程，主进程退出时先结束它们再删目录。 */
    fun adopt(process: Process) {
        children += process
    }

    /**
     * 进程开头调用。子进程盯着主进程：主进程被强行结束（任务管理器、Gradle 被杀）时不跑退出钩子，
     * 子进程不跟着退的话会在后台把自己那一片跑完，目录也没人删。
     * 主进程先清以前的遗留，再挂退出钩子：正常退出、Ctrl+C 都先结束子进程、等它们放开文件，再删本次的目录。
     */
    fun start() {
        val owner = System.getProperty(RUN_PROPERTY)?.toLongOrNull()
        if (owner != null) {
            val parent = ProcessHandle.of(owner).filter { it.isAlive }.orElse(null)
            if (parent == null) {
                Runtime.getRuntime().halt(3)
                return
            }
            parent.onExit().thenRun { Runtime.getRuntime().halt(3) }
            return
        }
        sweep()
        Runtime.getRuntime().addShutdownHook(Thread {
            children.forEach { it.destroyForcibly() }
            children.forEach { it.waitFor(10, TimeUnit.SECONDS) }
            run.deleteRecursively()
        })
    }
}

/**
 * 一套完整的 [PikoServices]，照桌面入口（Main.kt）拼装，全部指向临时目录与 [FakePikPak]，
 * 已登录。每个截图各用一份，关掉时连同临时目录一起清掉。
 */
class ShotEnv(
    /** 截图的名字，临时目录按它取名。 */
    name: String,
    viewMode: String? = null,
    extraSeed: FakePikPak.() -> Unit = {},
    login: LoginSeed = LoginSeed.SIGNED_IN,
    /** 为 false 时不预置本机下载，传输页才可能是空的。 */
    localDownloads: Boolean = true,
    /** 只给这一张写的设置项，键照 DesktopSettingsStore，如上传任务 upload.tasks。 */
    prefs: Map<String, String> = emptyMap(),
) : AutoCloseable {
    // 名字在一套里唯一，并行的各片也不撞
    val dir: File = File(ShotDirs.run, name).apply {
        deleteRecursively()
        mkdirs()
    }
    val server = FakePikPak().apply {
        seed()
        extraSeed()
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val downloads = File(dir, "Downloads").apply { mkdirs() }
    val settings = DesktopSettingsStore(File(dir, "settings.properties")).apply {
        setDownloadDirectory(downloads.path)
        if (localDownloads) set("download.tasks", seededDownloads(downloads))
        prefs.forEach { (key, value) -> set(key, value) }
        if (viewMode != null) set("ui.driveViewMode", viewMode)
    }
    val preferences = DesktopPikoPreferences(settings) { PlainFileVault(dir.toPath().resolve("secrets")) }
    val platform: PikoPlatform = FixedDiskPlatform(DesktopPikoPlatform(settings))
    val services: PikoServices

    init {
        logInstalled
        val clientManager = PikoClientManager(MemorySessionStore(ACCOUNT, login), scope, server.httpClient())
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

/**
 * 磁盘空间与下载位置给定值：真实的剩余空间随本机在变，传输页底栏与下载位置对话框会跟着变；
 * 下载目录在按进程号命名的临时目录里，原样显示的话设置页每次都不一样。
 */
private class FixedDiskPlatform(base: PikoPlatform) : PikoPlatform by base {
    override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker by base.downloadLocation {
        override fun diskSpace(storedPath: String) = DiskSpace(freeBytes = 412 * GB, totalBytes = 1_000 * GB)
        override fun displayName(storedPath: String) =
            if (storedPath.isEmpty()) base.downloadLocation.displayName(storedPath) else "D:\\Downloads\\Piko"
    }
}

/** 下载列表：两个已完成（磁盘上放稀疏文件，恢复时按长度核对）、一个暂停、一个失败。 */
private fun seededDownloads(dir: File): String {
    fun sparse(name: String, length: Long) = sparseFile(File(dir, name), length)
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

/**
 * 只占名义大小的文件。RandomAccessFile.setLength 在 NTFS 上按长度实际分配（实测 2 GiB 占 2 GiB），
 * 一张图预置的下载合计十几 GB 真的写到盘上；以 SPARSE 打开时 Windows 先把它标成稀疏文件，
 * 再只写末尾一个字节（同样 2 GiB 占 64 KiB）。Linux 与 macOS 上 setLength 本来就是稀疏的，这样写也一样。
 */
private fun sparseFile(file: File, length: Long) {
    if (length == 0L) {
        file.createNewFile()
        return
    }
    Files.newByteChannel(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { channel ->
        channel.position(length - 1)
        channel.write(ByteBuffer.wrap(byteArrayOf(0)))
    }
}

/** 一次文件夹下载：十二集加两个字幕，前四集已下完（同样放稀疏文件），第五集下到一半，其余未开始。 */
private fun seededFolderBatch(dir: File, createdAtMs: Long, thumbnail: String): List<DownloadTask> {
    val batch = DownloadBatch("shots-batch", "Frieren S01")
    val episodeSize = 1 * GB + 400 * MB
    fun task(index: Int, path: String, size: Long, downloaded: Long, status: DownloadStatus): DownloadTask {
        val fileName = "${batch.folderName}/$path"
        if (downloaded > 0) {
            File(dir, fileName).parentFile.mkdirs()
            sparseFile(File(dir, fileName), downloaded)
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
