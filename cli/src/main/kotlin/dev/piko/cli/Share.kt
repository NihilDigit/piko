package dev.piko.cli

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getShareInfo
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listShareFiles
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.restoredFileIds
import io.github.nihildigit.pikpak.shareIdFromUrl
import kotlinx.coroutines.delay

private const val MAX_SHARE_DEPTH = 3
private const val MAX_TASK_POLLS = 30

/** 只读地列出一个分享：标题、状态、所有者与顶层文件。读分享不需要登录态以外的东西，也不改动网盘。 */
suspend fun describeShare(client: PikPakClient, url: String, passCode: String): List<String> {
    val shareId = shareIdFromUrl(url) ?: error("认不出分享链接：$url")
    val info = client.getShareInfo(shareId, passCode)
    return buildList {
        add("分享 $shareId：${info.title}（${info.shareStatus}）所有者 ${info.owner.nickname}，${info.fileNum} 项")
        info.files.forEach { file ->
            val kind = if (file.kind == FileKind.FOLDER) "目录" else "文件"
            add("  $kind  ${file.name}  ${file.sizeBytes} 字节  id=${file.id}")
        }
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
                    println("  映射 ${task.restoredFileIds}  params=${task.params}")
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
