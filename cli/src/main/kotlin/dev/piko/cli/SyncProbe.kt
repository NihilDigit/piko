package dev.piko.cli

import io.github.nihildigit.pikpak.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Only empty folders created by this run are mutated; output excludes unrelated drive rows. */
internal suspend fun probeSyncPagination(client: PikPakClient) {
    val capacity = client.listFileChangesPage(pageSize = 2).files.size
    println("requestedPageSize=2 observedPageSize=$capacity")
    check(capacity in 2..1000) { "Unexpected page capacity; no probe folders created" }
    val root = client.createFolder("", "piko-sync-watermark-probe-${System.currentTimeMillis()}")
    println("probeRoot=$root")
    try {
        val baseline = client.getFile(root).params["db_modify_time"]
            ?.takeIf { it.isNotBlank() } ?: error("Probe root has no db_modify_time")
        val permits = Semaphore(4)
        val ids = coroutineScope {
            (0 until capacity + 3).map { index -> async {
                permits.withPermit { client.createFolder(root, "item-$index") }
            } }.awaitAll().toMutableList()
        }
        println("created=${ids.size}")
        val observed = linkedMapOf<String, FileStat>()
        var token = ""
        var first = ""
        var last = ""
        var pageNumber = 0
        do {
            val page = client.listFileChangesPage(baseline, token, pageSize = 2)
            pageNumber++
            check(pageNumber <= 100) { "Probe page budget exceeded" }
            if (first.isEmpty()) first = page.syncTime
            last = page.syncTime
            val own = page.files.filter { it.id in ids }
            own.forEach { observed[it.id] = it }
            println("page=$pageNumber count=${page.files.size} own=${own.size} watermark=${page.syncTime} ownDbTimes=${listOf(own.firstOrNull()?.params?.get("db_modify_time"), own.lastOrNull()?.params?.get("db_modify_time"))} next=${page.nextPageToken.isNotEmpty()}")
            if (pageNumber == 1) {
                check(own.isNotEmpty()) { "First page has no probe row" }
                client.rename(own.first().id, "changed-after-first-page")
                client.rename(ids.first { it !in observed }, "changed-before-read")
                ids += client.createFolder(root, "created-between-pages")
            }
            token = page.nextPageToken
        } while (token.isNotEmpty())
        check(pageNumber > 1) { "No page boundary exercised" }
        suspend fun delta(cursor: String): Map<String, FileStat> {
            val result = linkedMapOf<String, FileStat>()
            var pages = 0
            client.consumeFileChanges(cursor, 2) { page ->
                check(++pages <= 100)
                page.files.filter { it.id in ids }.forEach { result[it.id] = it }
            }
            return result
        }
        val afterFirst = delta(first)
        val afterLast = delta(last)
        val expected = client.batchGetFiles(ids).associateBy { it.id }
        check(expected.keys == ids.toSet()) { "Batch details did not return every probe child" }
        println("verifiedDetails=${expected.size}")
        fun missing(delta: Map<String, FileStat>): Int {
            val merged = observed + delta
            return expected.count { (id, file) -> merged[id]?.name != file.name }
        }
        println("first=$first last=$last observed=${observed.size}/${ids.size} replayFirst=${afterFirst.size} replayLast=${afterLast.size} missingAfterFirst=${missing(afterFirst)} missingAfterLast=${missing(afterLast)}")
        check(missing(afterFirst) == 0) { "First-page checkpoint lost a probe mutation" }
        check(missing(afterLast) == 0) { "Last-page checkpoint lost a probe mutation" }
    } finally {
        withContext(NonCancellable) {
            client.batchTrash(listOf(root))
            println("probeCleanup=trashed")
        }
    }
}
