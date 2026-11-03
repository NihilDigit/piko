package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * 可信写入靠两条规则：同一版本几份并存时随机串最小的算数，输的一方拿同一个改动套到赢家上重写。
 * 规则错一处，丢的是用户刚存下的一集，或者删了又冒出来的一行，而列表照样画得出来，看不出坏了。
 */
class VaultStoreTest {
    @Test
    fun `a stale confirmation listing does not trigger a redundant upload`() = runBlocking {
        val drive = MemoryFolder()
        drive.beforeList(3) { drive.hideCurrentFiles(2) }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val saved = entry("E01")
        assertEquals(listOf(saved), store.update("f", VaultEdits.add(listOf(saved))).getOrThrow().after)
        assertEquals(1, drive.uploads)
    }
    @Test
    fun `a newly uploaded manifest waits for listing visibility without being reuploaded`() = runBlocking {
        val drive = MemoryFolder().apply { listingLag = 3 }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val saved = entry("E01")
        assertEquals(listOf(saved), store.update("f", VaultEdits.add(listOf(saved))).getOrThrow().after)
        assertEquals(1, drive.uploads)
        assertEquals(1, drive.files.size)
    }

    @Test
    fun `an uploaded manifest transiently pending transitions to complete without false-conflict deletion`() = runBlocking {
        val drive = MemoryFolder().apply { pendingListingsAfterUpload = 2 }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val first = entry("E01")
        val second = entry("E02")
        assertEquals(listOf(first), store.update("f", VaultEdits.add(listOf(first))).getOrThrow().after)
        assertEquals(1, drive.uploads, "自身清单短暂处于 PENDING 后转为 COMPLETE 时不应误判冲突重传")
        assertEquals(listOf(first, second), store.update("f", VaultEdits.add(listOf(second))).getOrThrow().after)
        assertEquals(setOf("E01", "E02"), store.committed("f").getOrThrow().map { it.name }.toSet())
    }

    @Test
    fun `own uploaded manifest visible as pending succeeds immediately preserves old baseline for other devices and chains next edits`() = runBlocking {
        val drive = MemoryFolder().apply { forbidPendingReads = true }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val first = entry("E01")
        store.update("f", VaultEdits.add(listOf(first))).getOrThrow()
        assertEquals(1, drive.files.size)

        // 模拟服务端异步转正窗口（PENDING 且无直链）：本机凭已缓存清单直接确认成功，且暂留旧 v1 供其他设备兜底读取
        drive.defaultUploadPhase = TaskPhase.PENDING
        val second = entry("E02")
        assertEquals(listOf(first, second), store.update("f", VaultEdits.add(listOf(second))).getOrThrow().after)
        assertEquals(2, drive.uploads)
        assertEquals(2, drive.files.size, "v2 仍处 PENDING 时暂留旧 COMPLETE 清单供他端兜底读取")

        val otherDevice = VaultStore(drive, confirmDelay = Duration.ZERO)
        assertEquals(listOf("E01"), otherDevice.committed("f").getOrThrow().map { it.name })

        // 本机在 PENDING 窗口内继续追加 E03，无需下载直链即可基于 v2 继续演进
        val third = entry("E03")
        assertEquals(listOf(first, second, third), store.update("f", VaultEdits.add(listOf(third))).getOrThrow().after)

        // 服务端随后翻转为 COMPLETE：他端读到完整最新集合，下次更新顺手清理旧版清单
        drive.defaultUploadPhase = TaskPhase.COMPLETE
        drive.markAllComplete()
        assertEquals(setOf("E01", "E02", "E03"), otherDevice.committed("f").getOrThrow().map { it.name }.toSet())
        store.update("f", VaultEdits.add(listOf(entry("E04")))).getOrThrow()
        assertEquals(1, drive.files.size, "转正后更新顺手清掉全部旧版本清单")
    }

    @Test
    fun `an uploaded manifest that never appears in folder listing fails as unconfirmed without self-deletion`() = runBlocking {
        val drive = MemoryFolder().apply { listingLag = 99 }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val outcome = store.update("f", VaultEdits.add(listOf(entry("E01"))))
        assertTrue(outcome.exceptionOrNull() is VaultWriteUnconfirmed, "目录列表中不可见的上传不可谎报确认成功")
        assertEquals(1, drive.uploads)
        assertEquals(1, drive.files.size)
    }

    @Test
    fun `other device cannot overwrite a pending manifest from older baseline even with smaller token`() = runBlocking {
        val drive = MemoryFolder().apply { forbidPendingReads = true }
        val deviceA = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "bb" })
        val deviceB = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "aa" })

        val e1 = entry("E01")
        deviceA.update("f", VaultEdits.add(listOf(e1))).getOrThrow()

        // 设备 A 写入 v2-bb 处于 PENDING
        drive.defaultUploadPhase = TaskPhase.PENDING
        val e2 = entry("E02")
        deviceA.update("f", VaultEdits.add(listOf(e2))).getOrThrow()
        val uploadsAfterA = drive.uploads

        // 设备 B 即使拥有更小的随机串 "aa"，遇到更高版本未决陌生 PENDING 也必须停止，绝不从 v1 写出 v2-aa 覆盖 E02
        val bWhilePending = deviceB.update("f", VaultEdits.add(listOf(entry("E03"))))
        assertTrue(bWhilePending.isFailure, "存在他端未决新版 PENDING 清单时必须拒绝从旧基线盲写")
        assertEquals(uploadsAfterA, drive.uploads, "写前屏障拦住时不得发起破坏性同版本上传")

        // v2-bb 转正为 COMPLETE 后，设备 B 正常继承 E01+E02 写出 v3-aa
        drive.defaultUploadPhase = TaskPhase.COMPLETE
        drive.markAllComplete()
        deviceB.update("f", VaultEdits.add(listOf(entry("E03")))).getOrThrow()
        assertEquals(setOf("E01", "E02", "E03"), deviceB.committed("f").getOrThrow().map { it.name }.toSet())
    }

    @Test
    fun `fast write followed by empty laggy listing or async restart cache load never regresses to empty baseline`() = runBlocking {
        val drive = MemoryFolder().apply {
            defaultUploadPhase = TaskPhase.PENDING
            forbidPendingReads = true
        }
        val persisted = HashMap<String, String>()
        val slowCache = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                kotlinx.coroutines.delay(50)
                return synchronized(persisted) { persisted[key] }
            }
            override suspend fun write(key: String, value: String) {
                synchronized(persisted) { persisted[key] = value }
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = VaultStore(drive, confirmDelay = Duration.ZERO, cacheStore = slowCache, account = "acc", scope = scope)
            val e1 = entry("E01")
            store.update("fresh", VaultEdits.add(listOf(e1)), freshlyCreatedFolder = true).getOrThrow()

            // H1 场景 1：紧接着追加时服务端列表因可见性延迟返回全空，必须拒绝回退到空基线
            drive.hideCurrentFiles(99)
            val laggyAppend = store.update("fresh", VaultEdits.add(listOf(entry("E02"))))
            assertTrue(laggyAppend.isFailure, "已提交清单在列表中完全不可见时严禁从空基线重写 v1")

            // H1 场景 2：进程重启后即使目录列表暂时全空，凭借持久化的目录级已提交基线证据仍必须拒绝从空基线覆盖
            val restartedWhileEmpty = VaultStore(drive, confirmDelay = Duration.ZERO, cacheStore = slowCache, account = "acc", scope = scope)
            assertTrue(
                restartedWhileEmpty.update("fresh", VaultEdits.add(listOf(entry("E02")))).isFailure,
                "重启后若目录列表尚未刷出已提交清单，仍必须拒绝从空基线覆盖",
            )

            // H1 场景 3：列表恢复可见（仍为 PENDING），重启实例在异步缓存加载后基于 E01 追加 E02
            drive.hideCurrentFiles(0)
            val restarted = VaultStore(drive, confirmDelay = Duration.ZERO, cacheStore = slowCache, account = "acc", scope = scope)
            restarted.update("fresh", VaultEdits.add(listOf(entry("E02")))).getOrThrow()
            assertEquals(setOf("E01", "E02"), restarted.committed("fresh").getOrThrow().map { it.name }.toSet())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `old timestamped pending stranger still blocks overwrite regardless of clock skew`() = runBlocking {
        val drive = MemoryFolder().apply { forbidPendingReads = true }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "aa" })
        store.update("f", VaultEdits.add(listOf(entry("E01")))).getOrThrow()

        // 模拟另一台设备上传了 v2-bb（PENDING），即使其时间戳远早于本机时钟（如时钟快 1 小时），也不允许跳过写 v3 覆盖
        drive.upload("f", ".piko-vault-v2-bb.json", ByteArray(10), phase = TaskPhase.PENDING)
        val outcome = store.update("f", VaultEdits.add(listOf(entry("E03"))))
        assertTrue(outcome.isFailure, "不论客户端时钟偏差多久，存在未决更高版本陌生 PENDING 清单时均禁止覆盖")
    }

    @Test
    fun `fast new-folder path emits incremental files instead of replacing existing subfolders`() = runBlocking {
        val drive = MemoryFolder().apply { pendingListingsAfterUpload = 1 }
        var lastSnapshot: VaultCommitSnapshot? = null
        val store = VaultStore(drive, confirmDelay = Duration.ZERO, onCommitted = { _, _, snapshot -> lastSnapshot = snapshot })
        val first = entry("E01")
        store.update("fresh", VaultEdits.add(listOf(first)), freshlyCreatedFolder = true).getOrThrow()
        assertTrue(lastSnapshot is VaultCommitSnapshot.IncrementalFiles, "快速通道只提交增量文件，不得覆盖同级已建子目录")

        // 紧接着同一进程对该目录执行常规追加（第一份清单刚翻转 COMPLETE），基线不得丢失 E01
        val second = entry("E02")
        store.update("fresh", VaultEdits.add(listOf(second)), freshlyCreatedFolder = false).getOrThrow()
        assertEquals(setOf("E01", "E02"), store.committed("fresh").getOrThrow().map { it.name }.toSet())
    }

    private fun entry(name: String) = VaultEntry.create(name, 100, "G-$name", source = null, addedAt = 0)

    @Test
    fun `two devices writing the same version both keep their change`() = runBlocking {
        val drive = MemoryFolder()
        val a = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "bb" })

        // 另一台设备也基于空目录写了 v1：在自己的网盘副本里写成，再原样出现在共享的目录里，
        // 时机是 a 上传之后、确认输赢之前。它的随机串更小，a 输
        drive.beforeList(2) {
            val elsewhere = MemoryFolder()
            VaultStore(elsewhere, Duration.ZERO, newToken = { "aa" }).update("f", VaultEdits.add(listOf(entry("E02")))).getOrThrow()
            elsewhere.files.values.forEach { (name, bytes) -> drive.upload("f", name, bytes) }
        }
        a.update("f", VaultEdits.add(listOf(entry("E01")))).getOrThrow()

        val names = a.read("f", drive.list("f")).getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("E01", "E02"), names)
        assertEquals(listOf(".piko-vault-v2-bb.json"), drive.files.values.map { it.first }.filter { "-v2-" in it })
    }

    @Test
    fun `a reader takes the newest version and the smallest token, and skips what cannot be read`() = runBlocking {
        val drive = MemoryFolder()
        val store = VaultStore(drive, Duration.ZERO, newToken = { "00" })
        suspend fun write(version: Int, token: String, name: String) {
            val scratch = MemoryFolder()
            val writer = VaultStore(scratch, Duration.ZERO, newToken = { token })
            repeat(version) { writer.update("f", VaultEdits.add(listOf(entry("$name-$it")))) }
            val (fileName, bytes) = scratch.files.values.single()
            drive.upload("f", fileName, bytes)
        }
        write(2, "00", "old")
        write(3, "bb", "loser")
        write(3, "aa", "winner")
        drive.upload("f", ".piko-vault-v9-ff.json", ByteArray(0), phase = TaskPhase.PENDING)

        val entries = store.read("f", drive.list("f")).getOrThrow().map { it.name }
        assertEquals(listOf("winner-0", "winner-1", "winner-2"), entries)
    }

    @Test
    fun `an edit that changes nothing writes nothing`() = runBlocking {
        val drive = MemoryFolder()
        val store = VaultStore(drive, Duration.ZERO)
        val saved = entry("E01")
        store.update("f", VaultEdits.add(listOf(saved))).getOrThrow()
        val before = drive.files.keys.toSet()

        store.update("f", VaultEdits.remove(setOf("no-such-entry"))).getOrThrow()
        store.update("f", VaultEdits.add(listOf(saved))).getOrThrow()
        assertEquals(before, drive.files.keys)
    }

    @Test
    fun `sources are stored in single manifest without sidecar files and restored metadata follows renames`() = runBlocking {
        val drive = MemoryFolder()
        val store = VaultStore(drive, Duration.ZERO)
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val share = "https://mypikpak.com/s/share?password=1234"
        val one = entry("E01").copy(source = magnet, cid = "CID")
        val two = entry("E02").copy(source = magnet)
        val three = entry("E03").copy(source = share)
        store.update("f", VaultEdits.add(listOf(one, two, three))).getOrThrow()
        assertEquals(1, drive.files.size, "每次归档仅写唯一一份清单文件，不另写 .magnet/.txt 旁路文件")
        assertEquals(0, drive.files.values.count { it.first.endsWith(".magnet") || it.first.endsWith(".txt") })
        val restored = one.toFileStat("f").copy(id = "real-id")
        store.update("f", VaultEdits.restore(mapOf(one.id to restored))).getOrThrow()
        val reader = VaultStore(drive, Duration.ZERO)
        val saved = reader.read("f", drive.list("f")).getOrThrow().single { it.id == one.id }
        assertEquals("real-id", saved.restoredFileId)
        val renamed = restored.copy(name = "renamed.mkv", params = emptyMap())
        assertEquals(magnet, saved.enrich(renamed).sourceUrl)
        assertEquals("CID", saved.enrich(renamed).params["piko_vault_cid"])
        assertEquals(1, drive.files.size)
    }

    /** 一个目录，列出时按插入顺序。 */
    private class MemoryFolder : VaultFolderIo {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        private val phases = HashMap<String, String>()
        private var nextId = 0
        private var lists = 0
        var listingLag = 0
        var pendingListingsAfterUpload = 0
        var uploads = 0
        var defaultUploadPhase = TaskPhase.COMPLETE
        var forbidPendingReads = false
        private val visibleAt = HashMap<String, Int>()
        private val completeAt = HashMap<String, Int>()
        private val hooks = HashMap<Int, suspend () -> Unit>()

        /** 第 [nth] 次列目录之前先做 [action]，模拟另一台设备恰好在这时写入。 */
        fun beforeList(nth: Int, action: suspend () -> Unit) {
            hooks[nth] = action
        }

        fun hideCurrentFiles(lag: Int) { files.keys.forEach { visibleAt[it] = lists + lag } }

        fun markAllComplete() {
            phases.keys.forEach { phases[it] = TaskPhase.COMPLETE }
            completeAt.clear()
        }

        override suspend fun list(folderId: String): List<FileStat> {
            hooks.remove(++lists)?.invoke()
            return files.filterKeys { lists >= (visibleAt[it] ?: 0) }.map { (id, file) ->
                val phase = when {
                    phases[id] != TaskPhase.COMPLETE -> phases[id] ?: TaskPhase.PENDING
                    lists < (completeAt[id] ?: 0) -> TaskPhase.PENDING
                    else -> TaskPhase.COMPLETE
                }
                FileStat(kind = FileKind.FILE, id = id, name = file.first, phase = phase)
            }
        }

        override suspend fun read(fileId: String): ByteArray {
            if (forbidPendingReads && phases[fileId] != TaskPhase.COMPLETE) {
                error("Download link unavailable")
            }
            return files.getValue(fileId).second
        }

        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = upload(folderId, name, bytes, defaultUploadPhase)

        fun upload(folderId: String, name: String, bytes: ByteArray, phase: String): String {
            val id = "id${nextId++}"
            files[id] = name to bytes
            phases[id] = phase
            visibleAt[id] = lists + listingLag
            if (pendingListingsAfterUpload > 0) {
                completeAt[id] = lists + pendingListingsAfterUpload + 1
            }
            uploads++
            return id
        }

        override suspend fun delete(ids: List<String>) {
            ids.forEach(files::remove)
        }
    }
}
