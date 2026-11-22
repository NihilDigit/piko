package dev.piko.cli

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.ShareTreeBrowser
import dev.piko.shared.state.ShareTreeDataSource
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakHash
import io.github.nihildigit.pikpak.ShareFolderPage
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getShareInfo
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listShareFiles
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.shareIdFromUrl
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.upload
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Buffer

private const val MAX_SHARE_DEPTH = 3
private const val MAX_TASK_POLLS = 30

private fun extractShareId(url: String): String =
    InstantSheetState.findShareLink(url)?.let(::shareIdFromUrl)
        ?: shareIdFromUrl(url)
        ?: error("认不出分享链接：$url")

/** 只读地列出一个分享：标题、状态、所有者与顶层文件。读分享不需要登录态以外的东西，也不改动网盘。 */
suspend fun describeShare(client: PikPakClient, url: String, passCode: String): List<String> {
    val shareId = extractShareId(url)
    val info = client.getShareInfo(shareId, passCode)
    return buildList {
        add("分享 $shareId：${info.title}（${info.shareStatus}）所有者 ${info.owner.nickname}，fileNum=${info.fileNum}，rootFiles=${info.files.size}，rootNextToken=${info.nextPageToken.isNotEmpty()}")
        info.files.forEach { file ->
            val kind = if (file.kind == FileKind.FOLDER) "目录" else "文件"
            add("  $kind  ${file.name}  ${file.sizeBytes} 字节  id=${file.id}  gcid=${file.hash.ifEmpty { "无" }}")
        }
    }
}

/** 只读分页遍历分享树，统计每层页数、目录数、文件数与有 GCID 的文件数。 */
suspend fun inspectShareTree(client: PikPakClient, url: String, passCode: String) {
    val shareId = extractShareId(url)
    val info = client.getShareInfo(shareId, passCode)
    println("shareId=$shareId fileNum=${info.fileNum} rootFiles=${info.files.size} rootNextToken=${info.nextPageToken.take(24)}")
    val queue = ArrayDeque<Pair<String, String>>()
    var totalFolders = 0
    var totalFiles = 0
    var totalWithGcid = 0
    var totalPages = 1
    for (stat in info.files) {
        if (stat.isFolder) {
            totalFolders++
            queue.addLast(stat.id to stat.name)
        } else {
            totalFiles++
            if (stat.hash.isNotBlank()) totalWithGcid++
        }
    }
    var rootToken = info.nextPageToken
    while (rootToken.isNotEmpty()) {
        val page = client.listShareFiles(shareId, info.passCodeToken, parentId = "", pageToken = rootToken)
        totalPages++
        println("  root page=$totalPages files=${page.files.size} next=${page.nextPageToken.take(24)}")
        for (stat in page.files) {
            if (stat.isFolder) {
                totalFolders++
                queue.addLast(stat.id to stat.name)
            } else {
                totalFiles++
                if (stat.hash.isNotBlank()) totalWithGcid++
            }
        }
        rootToken = page.nextPageToken
    }
    while (queue.isNotEmpty()) {
        val (folderId, folderName) = queue.removeFirst()
        var token = ""
        var folderPages = 0
        var folderFiles = 0
        var folderSubdirs = 0
        var prevToken: String? = null
        while (folderPages == 0 || token.isNotEmpty()) {
            val page = client.listShareFiles(shareId, info.passCodeToken, parentId = folderId, pageToken = token)
            folderPages++
            totalPages++
            for (stat in page.files) {
                if (stat.isFolder) {
                    totalFolders++
                    folderSubdirs++
                    queue.addLast(stat.id to "$folderName/${stat.name}")
                } else {
                    totalFiles++
                    folderFiles++
                    if (stat.hash.isNotBlank()) totalWithGcid++
                }
            }
            if (folderPages <= 5 || page.nextPageToken.isEmpty() || page.nextPageToken == prevToken) {
                println("  folder id=$folderId page=$folderPages pageItems=${page.files.size} accumFiles=$folderFiles subdirs=$folderSubdirs nextTokenLen=${page.nextPageToken.length} sameToken=${page.nextPageToken == token}")
            }
            if (page.nextPageToken.isNotEmpty() && page.nextPageToken == token) {
                println("  WARNING: repeated nextPageToken detected at page=$folderPages!")
                break
            }
            prevToken = token
            token = page.nextPageToken
            if (folderPages >= 60) {
                println("  stopping preview at 60 pages for safety")
                break
            }
        }
        println("folder summary id=$folderId pages=$folderPages files=$folderFiles subdirs=$folderSubdirs")
    }
    println("TOTAL pages=$totalPages folders=$totalFolders files=$totalFiles withGcid=$totalWithGcid")
}

/** 走与客户端完全一致的 ShareTreeBrowser + InstantMagnetRepository.saveReferences + VaultStore 链路将分享以引用存入指定路径。 */
suspend fun saveShareAsReferenceCli(client: PikPakClient, url: String, passCode: String, targetPath: String) {
    val shareId = extractShareId(url)
    val targetParentId = resolvePath(client, targetPath)
    val info = client.getShareInfo(shareId, passCode)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val ds = object : ShareTreeDataSource {
            override suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> =
                runCatching { client.getShareInfo(shareId, passCode) }

            override suspend fun shareFolderPage(
                shareId: String,
                passCodeToken: String,
                parentId: String,
                pageToken: String,
            ): Result<ShareFolderPage> = runCatching {
                client.listShareFiles(shareId, passCodeToken, parentId = parentId, pageToken = pageToken)
            }

            override suspend fun restoreFromShare(
                shareId: String,
                passCodeToken: String,
                fileIds: List<String>,
                toParentId: String,
                ancestorIds: List<String>,
            ): Result<Unit> = Result.failure(UnsupportedOperationException())

            override suspend fun createFolder(parentId: String, name: String): Result<String> =
                runCatching { client.createFolder(parentId, name) }
        }
        val browser = ShareTreeBrowser(
            shareId = shareId,
            info = info,
            dataSource = ds,
            scope = scope,
            autoExpandSingleRoot = true,
        )
        // 模拟用户打开面板后首屏单根目录自动展开完成再点击保存
        delay(1_200)
        val io = object : VaultFolderIo {
            override suspend fun list(folderId: String): List<FileStat> = client.listFiles(parentId = folderId)
            override suspend fun read(fileId: String): ByteArray {
                var detail = client.getFile(fileId)
                repeat(5) { if (detail.downloadUrl == null) { delay(500); detail = client.getFile(fileId) } }
                return client.streamRangeFromUrl(detail.downloadUrl ?: error("Manifest download link unavailable"), 0, detail.sizeBytes) {
                    it.channel.toByteArray()
                }
            }
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                val size = bytes.size.toLong()
                val hash = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
                return client.upload(folderId, name, size, hash, { Buffer().apply { write(bytes) } }).fileId
            }
            override suspend fun delete(ids: List<String>) {
                client.batchDelete(ids)
            }
        }
        val vault = VaultStore(io = io, account = client.account, scope = scope)
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(client)
        }
        val instantRepo = InstantMagnetRepository(provider)
        val canonicalShare = InstantSheetState.findShareLink(url) ?: "https://mypikpak.com/s/$shareId"
        val source = canonicalShare + if (passCode.isNotBlank()) "?pwd=$passCode" else ""
        var resolvedFolders = mapOf("" to targetParentId)
        var batchIndex = 0
        val savedTotal = browser.streamSelectedForVault { batch ->
            batchIndex++
            println("batch #$batchIndex size=${batch.size} firstPath=${batch.firstOrNull()?.file?.path}")
            val ids = instantRepo.saveReferences(
                items = batch,
                targetParentId = targetParentId,
                keepStructure = true,
                names = emptyMap(),
                existingFolders = resolvedFolders,
                vault = vault,
                source = source,
                targetIsFreshFolder = false,
                onFoldersResolved = { resolvedFolders = it },
            ).getOrThrow()
            println("batch #$batchIndex committed=${ids.size} resolvedFolders=${resolvedFolders.size}")
            Result.success(ids.size)
        }.getOrThrow()
        println("DONE savedTotal=$savedTotal targetPath=$targetPath targetParentId=$targetParentId resolvedFolders=${resolvedFolders.size}")
        for ((relPath, folderId) in resolvedFolders) {
            if (relPath.isEmpty()) continue
            val committed = vault.committed(folderId).getOrThrow()
            println("  verified folderId=$folderId committedEntries=${committed.size}")
        }
    } finally {
        scope.cancel()
    }
}

/**
 * 实测转存：把分享里体积最小的一个文件转存进根目录下新建的探测文件夹，等任务结束，列出结果，再永久删除探测文件夹。
 * 只写入这个新建的文件夹，不碰网盘里已有的东西。
 */
suspend fun probeShareRestore(client: PikPakClient, url: String, passCode: String) {
    val shareId = shareIdFromUrl(url) ?: error("认不出分享链接：$url")
    val info = client.getShareInfo(shareId, passCode)
    // 顶层常常只有一个文件夹：往下找最小的文件，记下它的各级上级目录，转存子目录里的文件要带上它们
    var level = info.files
    val ancestors = mutableListOf<String>()
    var smallest = level.filter { it.kind != FileKind.FOLDER }.minByOrNull { it.sizeBytes }
    while (smallest == null && ancestors.size < MAX_SHARE_DEPTH) {
        val folder = level.firstOrNull { it.kind == FileKind.FOLDER } ?: break
        ancestors += folder.id
        level = client.listShareFiles(shareId, info.passCodeToken, parentId = folder.id).files
        smallest = level.filter { it.kind != FileKind.FOLDER }.minByOrNull { it.sizeBytes }
    }
    val target = smallest ?: error("分享前三层里没有文件可以转存")
    val probeName = "piko-probe-restore-${System.currentTimeMillis()}"
    val probeId = client.createFolder("", probeName)
    println("探测文件夹 $probeName（$probeId）")
    try {
        println("转存 ${target.name}（${target.sizeBytes} 字节），上级目录 $ancestors")
        val restore = client.restoreShare(shareId, info.passCodeToken, listOf(target.id), toParentId = probeId, ancestorIds = ancestors)
        println("restore_status=${restore.restoreStatus} task=${restore.restoreTaskId} file=${restore.fileId}")
        if (restore.restoreTaskId.isNotEmpty()) {
            for (attempt in 1..MAX_TASK_POLLS) {
                val task = client.getTask(restore.restoreTaskId)
                println("  任务 ${task.phase} ${task.progress}%")
                if (task.phase in TaskPhase.TERMINAL) {
                    println("  params=${task.params}")
                    break
                }
                delay(1_000)
            }
        }
        // 探测文件夹里可能是转存来的整条上级目录链，递归打出来看清落在哪一层
        suspend fun printTree(parentId: String, indent: String) {
            client.listFiles(parentId).forEach {
                println("$indent${it.name}  ${it.sizeBytes} 字节  id=${it.id}")
                if (it.kind == FileKind.FOLDER) printTree(it.id, "$indent  ")
            }
        }
        println("  探测文件夹里：")
        printTree(probeId, "    ")
    } finally {
        client.batchDelete(listOf(probeId))
        println("已永久删除探测文件夹")
    }
}
