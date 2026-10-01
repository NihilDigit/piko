package dev.piko.cli

import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakHash
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.upload
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import io.ktor.utils.io.toByteArray
import java.util.concurrent.ConcurrentHashMap

/** 独立目录中的真实清单写入；所有清单与目录均由本次实验创建。 */
suspend fun benchArchiveWrites(client: PikPakClient, path: String, levels: List<Int>, count: Int, timeoutSeconds: Long) {
    require(count in 1..64 && levels.all { it in 1..64 } && timeoutSeconds in 1..120)
    val dramas = resolvePath(client, path)
    val name = "piko-probe-archive-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
    val probe = client.createFolder(dramas, name)
    println("scope=$path probe_name=$name probe_id=$probe entries_added_per_round=16")
    val folders = mutableListOf<String>()
    val ownedFiles = ConcurrentHashMap.newKeySet<String>()
    val uploads = java.util.concurrent.atomic.AtomicInteger()
    val listings = java.util.concurrent.atomic.AtomicInteger()
    try {
        repeat(count) { folders += client.createFolder(probe, "probe-$it") }
        val io = object : VaultFolderIo {
            override suspend fun list(folderId: String): List<io.github.nihildigit.pikpak.FileStat> {
                require(folderId in folders)
                listings.incrementAndGet()
                return client.listFiles(parentId = folderId)
            }
            override suspend fun read(fileId: String): ByteArray {
                require(fileId in ownedFiles)
                var detail = client.getFile(fileId)
                repeat(5) { if (detail.downloadUrl == null) { delay(500); detail = client.getFile(fileId) } }
                return client.streamRangeFromUrl(detail.downloadUrl ?: error("Manifest download link unavailable"), 0, detail.sizeBytes) {
                    it.channel.toByteArray()
                }
            }
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                require(folderId in folders)
                uploads.incrementAndGet()
                val size = bytes.size.toLong()
                val hash = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
                return client.upload(folderId, name, size, hash, { Buffer().apply { write(bytes) } }).fileId.also { ownedFiles += it }
            }
            override suspend fun delete(ids: List<String>) {
                require(ids.all { it in ownedFiles })
                client.batchDelete(ids)
            }
        }
        val store = VaultStore(io)
        println("stage,parallel,requests,success,failed,peak,http_peak,http_attempts,http_429,wall_ms,p50_ms,p95_ms,ops_per_sec,errors")
        for ((round, parallel) in levels.withIndex()) {
            uploads.set(0)
            listings.set(0)
            val success = measure("manifest-write-confirm", parallel, count, timeoutSeconds) { directory ->
                // 每个目录的内容不同，避免用相同 GCID 秒传掩盖实际上传耗时。
                val entries = List(16) { index -> VaultEntry.create("probe-$directory-$index.mkv", 50L * 1024 * 1024, "0".repeat(40), null, round.toLong()) }
                val written = store.update(folders[directory], VaultEdits.add(entries)).getOrThrow()
                check(entries.all { entry -> entry in written.after })
                written
            }
            println("manifest_checks parallel=$parallel uploads=${uploads.get()} listings=${listings.get()}")
            if (!success) break
            delay(1_000)
        }
    } finally {
        withContext(NonCancellable) {
            cleanArchiveProbe(client, path, probe, name)
        }
    }
}

internal suspend fun cleanArchiveProbe(client: PikPakClient, path: String, probe: String, name: String) {
    require(path.trim('/').split('/').first() == "Dramas" && name.startsWith("piko-probe-archive-"))
    val dramas = resolvePath(client, path)
    val present = client.listFiles(parentId = dramas).firstOrNull { it.id == probe }
    if (present != null) {
        val actual = client.getFile(probe)
        check(actual.parentId == dramas && actual.name == name) { "Probe identity changed; cleanup refused" }
        client.batchDelete(listOf(probe))
    }
    var absent = false
    repeat(20) {
        if (!absent) {
            absent = client.listFiles(parentId = dramas).none { it.id == probe }
            if (!absent) delay(500)
        }
    }
    check(absent) { "Probe cleanup not confirmed" }
    println("cleanup=confirmed probe_id=$probe existing_files_unchanged=true")
}
