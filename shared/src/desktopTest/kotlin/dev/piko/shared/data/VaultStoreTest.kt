package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class VaultStoreTest {
    @Test
    fun `failed cache commit never grants pending trust`() = runBlocking {
        val drive = MemoryFolder().apply { defaultUploadPhase = TaskPhase.PENDING }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val cache = object : PikoCacheStore {
            override suspend fun read(key: String): String? = null
            override suspend fun write(key: String, value: String) { error("disk full") }
        }
        try {
            val store = VaultStore(drive, Duration.ZERO, cacheStore = cache, account = "a", scope = scope, saveDelayMillis = 0)
            store.awaitLoaded()
            assertTrue(store.update("f", VaultEdits.add(listOf(entry("one"))), freshlyCreatedFolder = true).isFailure)
            assertEquals(null, store.winnerOf(drive.list("f")))
            assertEquals(1, drive.files.size, "未确认上传保留在远端，不重传或删除")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

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
        assertEquals(2, store.parsedFlow.value.size, "PENDING 与兜底内容均不得淘汰")

        val otherDevice = VaultStore(drive, confirmDelay = Duration.ZERO)
        assertEquals(listOf("E01"), otherDevice.committed("f").getOrThrow().map { it.name })

        // 本机在 PENDING 窗口内继续追加 E03，无需下载直链即可基于 v2 继续演进
        val third = entry("E03")
        assertEquals(listOf(first, second, third), store.update("f", VaultEdits.add(listOf(third))).getOrThrow().after)

        // 服务端随后翻转为 COMPLETE：他端读到完整最新集合，下次更新在后台清理更旧版本（保留最近 1 份旧 COMPLETE 兜底）
        drive.defaultUploadPhase = TaskPhase.COMPLETE
        drive.markAllComplete()
        assertEquals(setOf("E01", "E02", "E03"), otherDevice.committed("f").getOrThrow().map { it.name }.toSet())
        store.update("f", VaultEdits.add(listOf(entry("E04")))).getOrThrow()
        assertEquals(setOf("E01", "E02", "E03", "E04"), store.committed("f").getOrThrow().map { it.name }.toSet())
        assertEquals(setOf("E01", "E02", "E03", "E04"), otherDevice.committed("f").getOrThrow().map { it.name }.toSet())
    }

    @Test
    fun `an uploaded manifest succeeds immediately even when folder listing lags and chains subsequent edits`() = runBlocking {
        val drive = MemoryFolder().apply { listingLag = 99 }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val first = entry("E01")
        assertEquals(listOf(first), store.update("f", VaultEdits.add(listOf(first))).getOrThrow().after)
        assertEquals(1, drive.uploads)
        assertEquals(1, drive.files.size)

        // 即使服务端列表持续滞后，后续写入仍基于本地已提交基线推进，绝不回退到空基线
        val second = entry("E02")
        assertEquals(listOf(first, second), store.update("f", VaultEdits.add(listOf(second))).getOrThrow().after)
        assertEquals(setOf("E01", "E02"), store.committed("f").getOrThrow().map { it.name }.toSet())
    }

    @Test
    fun `upload failure propagates original error directly without wrapping as unconfirmed`() = runBlocking {
        val drive = object : EmptyIo() {
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
                throw IllegalStateException("file_space_not_enough")
        }
        val store = VaultStore(drive, confirmDelay = Duration.ZERO)
        val outcome = store.update("f", VaultEdits.add(listOf(entry("E01"))))
        assertTrue(outcome.isFailure)
        assertEquals("file_space_not_enough", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `same-version manifests from concurrent devices merge on read without losing entries`() = runBlocking {
        val drive = MemoryFolder()
        val deviceA = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "aa" })
        val deviceB = VaultStore(drive, confirmDelay = Duration.ZERO, newToken = { "bb" })

        val scratchA = MemoryFolder()
        VaultStore(scratchA, Duration.ZERO, newToken = { "aa" }).update("f", VaultEdits.add(listOf(entry("E01")))).getOrThrow()
        val scratchB = MemoryFolder()
        VaultStore(scratchB, Duration.ZERO, newToken = { "bb" }).update("f", VaultEdits.add(listOf(entry("E02")))).getOrThrow()

        scratchA.files.values.forEach { (name, bytes) -> drive.upload("f", name, bytes) }
        scratchB.files.values.forEach { (name, bytes) -> drive.upload("f", name, bytes) }

        val merged = deviceA.read("f", drive.list("f")).getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("E01", "E02"), merged)
        assertEquals(setOf("E01", "E02"), deviceB.committed("f").getOrThrow().map { it.name }.toSet())
    }

    @Test
    fun `fast write followed by empty laggy listing or restart continues from persisted local baseline`() = runBlocking {
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
            val store = VaultStore(drive, confirmDelay = Duration.ZERO, cacheStore = slowCache, account = "acc", scope = scope, saveDelayMillis = 0)
            val e1 = entry("E01")
            store.update("fresh", VaultEdits.add(listOf(e1)), freshlyCreatedFolder = true).getOrThrow()

            // 服务端列表因可见性延迟返回全空时，仍基于本机已提交基线推进，不丢 E01
            drive.hideCurrentFiles(99)
            val laggyAppend = store.update("fresh", VaultEdits.add(listOf(entry("E02")))).getOrThrow()
            assertEquals(setOf("E01", "E02"), laggyAppend.after.map { it.name }.toSet())

            // 进程重启后列表恢复可见，凭持久化缓存继续追加
            drive.hideCurrentFiles(0)
            val restarted = VaultStore(drive, confirmDelay = Duration.ZERO, cacheStore = slowCache, account = "acc", scope = scope, saveDelayMillis = 0)
            restarted.update("fresh", VaultEdits.add(listOf(entry("E03")))).getOrThrow()
            assertEquals(setOf("E01", "E02", "E03"), restarted.committed("fresh").getOrThrow().map { it.name }.toSet())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
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

        a.update("f", VaultEdits.add(listOf(entry("E01")))).getOrThrow()
        val elsewhere = MemoryFolder()
        VaultStore(elsewhere, Duration.ZERO, newToken = { "aa" }).update("f", VaultEdits.add(listOf(entry("E02")))).getOrThrow()
        elsewhere.files.values.forEach { (name, bytes) -> drive.upload("f", name, bytes) }

        val names = a.read("f", drive.list("f")).getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("E01", "E02"), names)
    }

    @Test
    fun `a reader merges same-version manifests and skips pending strangers`() = runBlocking {
        val drive = MemoryFolder()
        val store = VaultStore(drive, Duration.ZERO, newToken = { "00" })
        suspend fun write(version: Int, token: String, name: String) {
            val scratch = MemoryFolder()
            val writer = VaultStore(scratch, Duration.ZERO, newToken = { token })
            repeat(version) { writer.update("f", VaultEdits.add(listOf(entry("$name-$it")))) }
            val (fileName, bytes) = scratch.files.values.last()
            drive.upload("f", fileName, bytes)
        }
        // 历史非因果 v2 清单由更高版本 v3 清单取代；待转正的陌生 v9 清单跳过
        drive.upload(
            "f",
            ".piko-vault-v2-00.json",
            """{"entries":[{"id":"old","name":"old-0","size":100,"gcid":"G-old","addedAt":0}]}""".encodeToByteArray(),
        )
        write(3, "bb", "branchB")
        write(3, "aa", "branchA")
        drive.upload("f", ".piko-vault-v9-ff.json", ByteArray(0), phase = TaskPhase.PENDING)

        val entries = store.read("f", drive.list("f")).getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("branchA-0", "branchA-1", "branchA-2", "branchB-0", "branchB-1", "branchB-2"), entries)
    }

    @Test
    fun `offline multi-step v2 and v3 branches sharing folder origin merge and cleanup waits until replacement is complete`() = runBlocking {
        val drive = MemoryFolder()
        val scratchA = MemoryFolder()
        val scratchB = MemoryFolder()
        val writerA = VaultStore(scratchA, Duration.ZERO, newToken = { "aa" })
        val writerB = VaultStore(scratchB, Duration.ZERO, newToken = { "bb" })
        repeat(3) { writerA.update("f", VaultEdits.add(listOf(entry("A-$it")))).getOrThrow() }
        repeat(2) { writerB.update("f", VaultEdits.add(listOf(entry("B-$it")))).getOrThrow() }

        val (nameA, bytesA) = scratchA.files.values.last()
        val (nameB, bytesB) = scratchB.files.values.last()
        val idA = drive.upload("f", nameA, bytesA, phase = TaskPhase.COMPLETE)
        val idB = drive.upload("f", nameB, bytesB, phase = TaskPhase.COMPLETE)

        // Merged replacement v4 stays PENDING initially: neither COMPLETE head may be deleted yet!
        drive.defaultUploadPhase = TaskPhase.PENDING
        val merger = VaultStore(drive, Duration.ZERO, newToken = { "cc" })
        val extra = entry("C-merged")
        merger.update("f", VaultEdits.add(listOf(extra))).getOrThrow()
        kotlinx.coroutines.delay(50)

        assertTrue(idA in drive.files, "COMPLETE head A must not be deleted while merged v4 is still PENDING")
        assertTrue(idB in drive.files, "COMPLETE head B must not be deleted while merged v4 is still PENDING")

        // Another cold device reading while v4 is PENDING still recovers all 5 offline entries from idA + idB
        val coldDuringPending = VaultStore(drive, Duration.ZERO)
        assertEquals(
            setOf("A-0", "A-1", "A-2", "B-0", "B-1"),
            coldDuringPending.committed("f").getOrThrow().map { it.name }.toSet(),
        )

        // Once v4 transitions to COMPLETE and barrier confirms it, superseded heads retire and all 6 entries remain readable
        drive.defaultUploadPhase = TaskPhase.COMPLETE
        drive.markAllComplete()
        merger.awaitRemoteComplete("f", setOf(extra.id)).getOrThrow()
        withTimeout(5_000) {
            while (drive.files.size > 1) kotlinx.coroutines.delay(10)
        }
        val coldAfterComplete = VaultStore(drive, Duration.ZERO)
        assertEquals(
            setOf("A-0", "A-1", "A-2", "B-0", "B-1", "C-merged"),
            coldAfterComplete.committed("f").getOrThrow().map { it.name }.toSet(),
        )
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
        assertTrue(drive.files.size <= 2)
    }

    @Test
    fun `entry enrichment and ingest preserve original instance timestamps`() {
        val entry = VaultEntry("entry", "video.mkv", 10, "HASH", addedAt = 200, createdAt = 100, restoredFileId = "restored")
        val file = FileStat(kind = FileKind.FILE, id = "restored", name = "renamed.mkv", hash = "HASH", size = "10",
            createdTime = "2026-01-01T00:00:00Z", modifiedTime = "2026-02-01T00:00:00Z")
        val enriched = entry.enrich(file)
        assertEquals("100", enriched.params["piko_original_created_at"])
        assertNull(entry.enrich(file.copy(id = "copy", name = entry.name)).params["piko_original_created_at"])

        val origin = FileStat(kind = FileKind.FILE, id = "original", name = "v", hash = "HASH", size = "10", createdTime = "1970-01-01T00:00:01Z")
        assertEquals(1_000L, VaultIngest.buildEntry(VaultEntryDraft("v", 10, "HASH", originFile = origin), 2_000).createdAt)
        assertEquals(500L, VaultIngest.buildEntry(VaultEntryDraft("v", 10, "HASH", originFile = origin.copy(params = mapOf("piko_original_created_at" to "500"))), 2_000).createdAt)
        assertEquals(2_000L, VaultIngest.buildEntry(VaultEntryDraft("v", 10, "HASH", originFile = null), 2_000).createdAt)
    }

    @Test
    fun `streaming cache deduplicates sources reads reordered v2 and legacy v1 without whole document reads`() = runBlocking {
        val directory = Files.createTempDirectory("piko-vault-stream")
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val files = FilePikoCacheStore(directory.toString(), Json { ignoreUnknownKeys = true }.streamingCodec())
        val streamOnly = object : PikoCacheStore by files {
            override suspend fun read(key: String): String? = error("whole-document reads are forbidden")
            override suspend fun write(key: String, value: String) = error("whole-document writes are forbidden")
            override suspend fun <T> readStream(key: String, decode: (Source) -> T): T? = files.readStream(key, decode)
            override suspend fun writeStream(key: String, encode: (Sink) -> Unit) = files.writeStream(key, encode)
        }
        val emptyIo = object : EmptyIo() {}
        try {
            val sharedSources = List(50) { "magnet:?xt=urn:btih:shared-$it" }
            val entries = List(500) { idx ->
                VaultEntry("$idx", "file$idx.mkv", 1000, "HASH$idx", source = sharedSources[idx % sharedSources.size], addedAt = 1, createdAt = idx.toLong())
            }
            val writer = VaultStore(emptyIo, cacheStore = streamOnly, account = "stream", scope = scope, saveDelayMillis = 0, initialParsed = mapOf("manifest" to entries))
            withTimeout(5_000) { writer.close().await() }

            val rawPath = directory.resolve(VaultStore.cacheKeyOf("stream"))
            val rawJson = Files.readString(rawPath)
            assertEquals(1, Regex(Regex.escape(sharedSources.first())).findAll(rawJson).count(), "v2 cache must deduplicate shared source URLs")

            // Reorder JSON fields and entry object keys on disk and verify streaming reader restores interned sources
            val parsedObj = Json.parseToJsonElement(rawJson).jsonObject
            val reorderedManifests = parsedObj.getValue("manifests").jsonObject.mapValues { (_, v) ->
                JsonArray(v.jsonArray.map { row -> JsonObject(row.jsonObject.toList().reversed().toMap()) })
            }
            Files.writeString(rawPath, JsonObject(linkedMapOf(
                "manifests" to JsonObject(reorderedManifests),
                "sources" to parsedObj.getValue("sources"),
                "version" to parsedObj.getValue("version"),
            )).toString())

            val restored = VaultStore(emptyIo, cacheStore = streamOnly, account = "stream", scope = scope, saveDelayMillis = 0)
            withTimeout(5_000) { restored.awaitLoaded() }
            val loaded = restored.parsedFlow.value.getValue("manifest")
            assertEquals(entries, loaded)
            assertSame(loaded[0].source, loaded[50].source)
            withTimeout(5_000) { restored.close().await() }

            // Legacy v1 map format compatibility
            val legacySnapshot = mapOf("m1" to entries.take(3))
            Files.writeString(
                directory.resolve(VaultStore.cacheKeyOf("legacy")),
                Json.encodeToString(MapSerializer(String.serializer(), ListSerializer(VaultEntry.serializer())), legacySnapshot),
            )
            val legacyStore = VaultStore(emptyIo, cacheStore = files, account = "legacy", scope = scope, saveDelayMillis = 0)
            withTimeout(5_000) { legacyStore.awaitLoaded() }
            assertEquals(legacySnapshot, legacyStore.parsedFlow.value)
            withTimeout(5_000) { legacyStore.close().await() }
        } finally {
            owner.cancelAndJoin()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `close drains accepted transaction blocks premature reload and survives failed close`() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val cache = LifecycleCache()
        val uploaded = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val io = object : EmptyIo() {
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                uploaded.complete(Unit)
                releaseUpload.await()
                return "manifest"
            }
        }
        try {
            val first = VaultStore(io, Duration.ZERO, cacheStore = cache, account = "A", scope = scope, saveDelayMillis = 0)
            first.awaitLoaded()
            val item = VaultEntry("one", "one.mkv", 10, "HASH", addedAt = 1)
            val transaction = async { first.update("folder", VaultEdits.add(listOf(item)), freshlyCreatedFolder = true).getOrThrow() }
            withTimeout(5_000) { uploaded.await() }
            val handoff = first.close()
            assertSame(handoff, first.close())
            assertFalse(handoff.isCompleted)
            assertFailsWith<IllegalStateException> { first.update("new", VaultEdits.remove(emptySet())) }
            val readsBefore = cache.reads.get()
            val next = VaultStore(EmptyIo(), Duration.ZERO, cacheStore = cache, account = "A", scope = scope, saveDelayMillis = 0, predecessor = handoff)
            yield()
            assertEquals(readsBefore, cache.reads.get(), "new account generation must not read before the handoff")
            releaseUpload.complete(Unit)
            withTimeout(5_000) { transaction.await(); handoff.await(); next.awaitLoaded() }
            assertEquals(listOf(item), next.parsedFlow.value["manifest"])
            val pending = FileStat(kind = FileKind.FILE, id = "manifest", name = ".piko-vault-v1-00.json", phase = TaskPhase.PENDING)
            assertNotNull(next.winnerOf(listOf(pending)))
            withTimeout(5_000) { next.close().await() }

            cache.fail = true
            val failing = VaultStore(EmptyIo(), cacheStore = cache, account = "B", scope = scope, initialParsed = mapOf("old" to emptyList()), saveDelayMillis = 0)
            val failedHandoff = failing.close()
            assertFailsWith<IllegalStateException> { withTimeout(5_000) { failedHandoff.await() } }
            cache.fail = false
            val recovered = VaultStore(EmptyIo(), cacheStore = cache, account = "B", scope = scope, predecessor = failedHandoff, initialParsed = mapOf("new" to emptyList()), saveDelayMillis = 0)
            withTimeout(5_000) { recovered.awaitLoaded(); recovered.close().await() }
            assertNotNull(cache.values[VaultStore.cacheKeyOf("B")])
            assertTrue(owner.children.none())
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `late lower-version causal branch survives higher-version fast writer and subsequent gc`() = runBlocking {
        val drive = MemoryFolder()
        val seed = VaultStore(drive, Duration.ZERO)
        val base = entry("base")
        seed.update("f", VaultEdits.add(listOf(base))).getOrThrow()

        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slowIo = object : VaultFolderIo by drive {
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                staged.complete(Unit)
                release.await()
                return drive.upload(folderId, name, bytes)
            }
        }
        val slowStore = VaultStore(slowIo, Duration.ZERO, newToken = { "99" })
        val fastStore = VaultStore(drive, Duration.ZERO, newToken = { "11" })

        val lateEntry = entry("late-v2")
        val fastFirst = entry("fast-v2")
        val fastSecond = entry("fast-v3")
        val fastThird = entry("fast-v4")

        val slowJob = async { slowStore.update("f", VaultEdits.add(listOf(lateEntry))).getOrThrow() }
        withTimeout(5_000) { staged.await() }
        fastStore.update("f", VaultEdits.add(listOf(fastFirst))).getOrThrow()
        fastStore.update("f", VaultEdits.add(listOf(fastSecond))).getOrThrow()
        release.complete(Unit)
        slowJob.await()

        val coldAfterLate = VaultStore(drive, Duration.ZERO)
        val mergedBeforeNextWrite = coldAfterLate.committed("f").getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("base", "late-v2", "fast-v2", "fast-v3"), mergedBeforeNextWrite)

        fastStore.update("f", VaultEdits.add(listOf(fastThird))).getOrThrow()
        val coldFinal = VaultStore(drive, Duration.ZERO)
        assertEquals(
            setOf("base", "late-v2", "fast-v2", "fast-v3", "fast-v4"),
            coldFinal.committed("f").getOrThrow().map { it.name }.toSet(),
        )
    }

    @Test
    fun `concurrent delete and add do not resurrect deleted entry and concurrent rename preserves latest timestamp`() = runBlocking {
        val drive = MemoryFolder()
        val initStore = VaultStore(drive, Duration.ZERO)
        val a = VaultEntry("id-a", "old-a.mkv", 100, "GA", addedAt = 100)
        val b = VaultEntry("id-b", "keep-b.mkv", 100, "GB", addedAt = 100)
        initStore.update("f", VaultEdits.add(listOf(a, b))).getOrThrow()
        val baseListing = drive.list("f")

        fun isolatedBranchIo(): VaultFolderIo = object : VaultFolderIo {
            override suspend fun list(folderId: String): List<FileStat> = baseListing
            override suspend fun read(fileId: String): ByteArray = drive.read(fileId)
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = drive.upload(folderId, name, bytes)
            override suspend fun delete(ids: List<String>) = Unit
        }

        VaultStore(isolatedBranchIo(), Duration.ZERO, newToken = { "ff" })
            .update("f", VaultEdits.remove(setOf(a.id)))
            .getOrThrow()

        VaultStore(isolatedBranchIo(), Duration.ZERO, newToken = { "ee" }).let { writer ->
            val d = VaultEntry("id-d", "added-d.mkv", 100, "GD", addedAt = 200)
            writer.update("f", { cur -> VaultEdits.rename(b.id, "renamed-b.mkv")(VaultEdits.add(listOf(d))(cur)) }).getOrThrow()
        }

        // Also simulate a third sibling with smaller random token "00" that did NOT rename b
        VaultStore(isolatedBranchIo(), Duration.ZERO, newToken = { "00" })
            .update("f", VaultEdits.add(listOf(VaultEntry("id-e", "added-e.mkv", 100, "GE", addedAt = 200))))
            .getOrThrow()

        val cold = VaultStore(drive, Duration.ZERO)
        val merged = cold.committed("f").getOrThrow()
        assertEquals(setOf("renamed-b.mkv", "added-d.mkv", "added-e.mkv"), merged.map { it.name }.toSet())
    }

    @Test
    fun `laggy listing showing only sibling of same version preserves own local committed entries`() = runBlocking {
        val drive = MemoryFolder()
        val ownStore = VaultStore(drive, Duration.ZERO, newToken = { "bb" })
        val ownEntry = entry("own-v1")
        ownStore.update("f", VaultEdits.add(listOf(ownEntry))).getOrThrow()

        val siblingScratch = MemoryFolder()
        VaultStore(siblingScratch, Duration.ZERO, newToken = { "aa" })
            .update("f", VaultEdits.add(listOf(entry("sibling-v1"))))
            .getOrThrow()
        val (sibName, sibBytes) = siblingScratch.files.values.single()
        val sibId = drive.upload("f", sibName, sibBytes)
        val sibOnlyListing = drive.list("f").filter { it.id == sibId }

        assertNull(ownStore.cached("f", sibOnlyListing), "远端存在尚未解析的同版本兄弟清单时，缓存不得只返回本机子集")
        assertEquals(setOf("own-v1", "sibling-v1"), ownStore.read("f", sibOnlyListing).getOrThrow().map { it.name }.toSet())
        assertEquals(setOf("own-v1", "sibling-v1"), ownStore.cached("f", sibOnlyListing)?.map { it.name }?.toSet())
    }

    private open class EmptyIo : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> = emptyList()
        override suspend fun read(fileId: String): ByteArray = error("unexpected remote read")
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = error("unexpected upload")
        override suspend fun delete(ids: List<String>) = Unit
    }

    private class LifecycleCache : PikoCacheStore {
        override val jsonCodec = Json { ignoreUnknownKeys = true }.streamingCodec()
        val values = HashMap<String, String>()
        val reads = AtomicInteger()
        var fail = false
        override suspend fun read(key: String): String? { reads.incrementAndGet(); return values[key] }
        override suspend fun write(key: String, value: String) {
            check(!fail) { "disk full" }
            values[key] = value
        }
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
