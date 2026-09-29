package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * 可信写入靠两条规则：同一版本几份并存时随机串最小的算数，输的一方拿同一个改动套到赢家上重写。
 * 规则错一处，丢的是用户刚存下的一集，或者删了又冒出来的一行，而列表照样画得出来，看不出坏了。
 */
class VaultStoreTest {

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

    /** 一个目录，列出时按插入顺序。 */
    private class MemoryFolder : VaultFolderIo {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        private val phases = HashMap<String, String>()
        private var nextId = 0
        private var lists = 0
        private val hooks = HashMap<Int, suspend () -> Unit>()

        /** 第 [nth] 次列目录之前先做 [action]，模拟另一台设备恰好在这时写入。 */
        fun beforeList(nth: Int, action: suspend () -> Unit) {
            hooks[nth] = action
        }

        override suspend fun list(folderId: String): List<FileStat> {
            hooks.remove(++lists)?.invoke()
            return files.map { (id, file) ->
                FileStat(kind = FileKind.FILE, id = id, name = file.first, phase = phases[id] ?: TaskPhase.COMPLETE)
            }
        }

        override suspend fun read(fileId: String): ByteArray = files.getValue(fileId).second

        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = upload(folderId, name, bytes, TaskPhase.COMPLETE)

        fun upload(folderId: String, name: String, bytes: ByteArray, phase: String): String {
            val id = "id${nextId++}"
            files[id] = name to bytes
            phases[id] = phase
            return id
        }

        override suspend fun delete(ids: List<String>) {
            ids.forEach(files::remove)
        }
    }
}
