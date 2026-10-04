package dev.piko.cli

import dev.piko.shared.naming.parseMediaName
import io.github.nihildigit.pikpak.getFile
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """piko-cli：Piko 开发工具

  snapshot -o <文件> [--root <路径>] [--depth <层数>] [--deep <名字,…>]
      只读地列网盘目录，存成快照（文件名、类型、大小）。
      从 --root（默认 /）起列 --depth 层（默认 1，即根目录与其下一层）；
      --deep 里的 root 直属子目录递归到底。会话取自 ~/.piko-dev，与开发版共用。

  dryrun <快照> [--path <前缀>] [--visited] [-o <文件>]
      离线对快照跑网盘页的解析流水线，逐行写出原名与界面上的样子。
      --path 只看路径以此开头的目录。--visited 模拟每个目录都点进去过，
      文件夹行用里面的文件名描述，与 app 记住内容之后的样子一致。

  score <快照>
      以整理过的文件夹名为标注，统计里面视频的番号识别率，列出认错的。

  ls <路径>
      只读地列出网盘一个目录，逐项打印名字与服务端给的 params（来源链接、时长、宽高等）。

  media <文件ID>
      打印一个文件的各路流（原画与各档转码）的尺寸与直链。直链是凭据，不要贴进日志与议题。

  parse <文件名>…
      单独解析几个文件名，打印 parseMediaName 的结果。

  bench <路径或文件夹ID> [--count <段数>] [--seconds <秒>] [--sequential] [--playing]
      只读实验：对目录（含子目录）下几个有 720P 转码的视频，照信息流的做法预取开头，量 SDK 的吞吐与同时在途数。
      默认 8 段、每段 5 秒，一齐发出；--sequential 一段取完再取下一段；--playing 同时另开一路前台读者模拟正在放的段。

  share <分享链接> [--pass <提取码>] [--restore]
      只读地列出一个分享的顶层内容。--restore 实测转存：把其中最小的一个文件转存进
      根目录下新建的 piko-probe-restore-* 文件夹，等任务结束后列出结果，再永久删除该文件夹。

  archive-bench /Dramas [--parallel <1,2,4,8,16,32>] [--count <32>] [--timeout <15>] [--api-rate <1000>] [--combined]
      只读取 Dramas 内的文件，分别测文件详情 API 与归档 CID 取样的并发吞吐，不写清单、不处置原文件。

  archive-write-bench /Dramas [--parallel <1,2,4,8,16,32>] [--count <32>] [--timeout <60>] [--api-rate <1000>]
      在 Dramas 内新建独立测试目录，实测清单写入和延迟确认。只删除本次新建的目录及其内容。
"""

fun main(args: Array<String>) {
    // Windows 控制台默认按系统代码页输出，中文文件名会变成问号
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
    // 与开发版共用 ~/.piko-dev 的登录态，不碰安装版的 ~/.piko：两边轮换 refresh token 会互相把对方踢回登录页，见 PikoHome。
    // 要在任何东西读 PikoHome 之前设
    if (System.getProperty("piko.home").isNullOrBlank()) {
        System.setProperty("piko.home", File(System.getProperty("user.home"), ".piko-dev").path)
    }
    val command = args.firstOrNull() ?: usage()
    val options = Options(args.drop(1))
    when (command) {
        "archive-write-cleanup" -> runBlocking {
            val path = options.positional.firstOrNull() ?: "/Dramas"
            val client = appClient()
            try { cleanArchiveProbe(client, path, options.value("--probe-id") ?: usage(), options.value("--probe-name") ?: usage()) }
            finally { client.close() }
        }
        "archive-write-bench" -> runBlocking {
            val path = options.positional.firstOrNull() ?: "/Dramas"
            require(path.trim('/').split('/').first() == "Dramas") { "此实验仅允许 Dramas 内的路径" }
            val apiRate = options.value("--api-rate")?.toInt() ?: 1000
            require(apiRate in 1..1000)
            val http = archiveBenchmarkHttpClient()
            val client = appClient(io.github.nihildigit.pikpak.RateLimiter(capacity = minOf(apiRate, 128), refillPerSecond = apiRate.toDouble()), http)
            try {
                benchArchiveWrites(client, path,
                    options.value("--parallel")?.split(',')?.map { it.toInt() } ?: listOf(1, 2, 4, 8, 16, 32),
                    options.value("--count")?.toInt() ?: 32,
                    options.value("--timeout")?.toLong() ?: 60)
            } finally { client.close(); http.close() }
        }
        "archive-bench" -> runBlocking {
            val path = options.positional.firstOrNull() ?: "/Dramas"
            require(path.trim('/').split('/').first() == "Dramas") { "此实验仅允许 Dramas 内的路径" }
            val apiRate = options.value("--api-rate")?.toInt() ?: 1000
            require(apiRate in 1..1000)
            val http = archiveBenchmarkHttpClient()
            val client = appClient(io.github.nihildigit.pikpak.RateLimiter(capacity = minOf(apiRate, 128), refillPerSecond = apiRate.toDouble()), http)
            try {
                benchArchiveReads(client, path,
                    options.value("--parallel")?.split(',')?.map { it.toInt() } ?: listOf(1, 2, 4, 8, 16, 32),
                    options.value("--count")?.toInt() ?: 32,
                    options.value("--timeout")?.toLong() ?: 15, apiRate, "--combined" in options.flags)
            } finally { client.close(); http.close() }
        }
        "snapshot" -> {
            val output = File(options.value("-o") ?: usage())
            val snapshot = runBlocking {
                takeSnapshot(
                    appClient(),
                    rootPath = options.value("--root") ?: "",
                    depth = options.value("--depth")?.toInt() ?: 1,
                    deep = options.value("--deep")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty(),
                )
            }
            output.absoluteFile.parentFile?.mkdirs()
            output.writeText(snapshotJson.encodeToString(Snapshot.serializer(), snapshot))
            System.err.println("快照：${snapshot.folders.size} 个目录，${snapshot.folders.sumOf { it.files.size }} 项 → $output")
        }
        "dryrun" -> {
            val input = File(options.positional.firstOrNull() ?: usage())
            val snapshot = snapshotJson.decodeFromString(Snapshot.serializer(), input.readText())
            val report = StringBuilder()
            renderDryRun(snapshot, options.value("--path"), visited = "--visited" in options.flags, report)
            options.value("-o")?.let { File(it).writeText(report.toString()) } ?: print(report)
        }
        "score" -> {
            val input = File(options.positional.firstOrNull() ?: usage())
            renderScore(snapshotJson.decodeFromString(Snapshot.serializer(), input.readText()), System.out)
        }
        "ls" -> runBlocking {
            val path = options.positional.firstOrNull() ?: usage()
            listWithParams(appClient(), path).forEach(::println)
        }
        "media" -> runBlocking {
            val fileId = options.positional.firstOrNull() ?: usage()
            appClient().getFile(fileId).medias.forEach { media ->
                println("${media.mediaName}  origin=${media.isOrigin}  ${media.video?.width}x${media.video?.height}\n  ${media.link.url}")
            }
        }
        "bench" -> runBlocking {
            benchClipHeads(
                appClient(),
                path = options.positional.firstOrNull() ?: usage(),
                count = options.value("--count")?.toInt() ?: 8,
                headSeconds = options.value("--seconds")?.toInt() ?: 5,
                sequential = "--sequential" in options.flags,
                withPlayer = "--playing" in options.flags,
                backgroundFirst = "--background-first" in options.flags,
                storeDirectory = options.value("--store")?.let(::File),
            )
        }
        "parse" -> options.positional.ifEmpty { usage() }.forEach { name -> println("$name\n  ${parseMediaName(name)}") }
        "share" -> runBlocking {
            val url = options.positional.firstOrNull() ?: usage()
            val passCode = options.value("--pass").orEmpty()
            if ("--restore" in options.flags) {
                probeShareRestore(appClient(), url, passCode)
            } else {
                describeShare(appClient(), url, passCode).forEach(::println)
            }
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.print(USAGE)
    exitProcess(2)
}

private class Options(args: List<String>) {
    private val named = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    val flags = mutableSetOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg in FLAGS) {
                flags += arg
                i++
            } else if (arg.startsWith("-") && i + 1 < args.size) {
                named[arg] = args[i + 1]
                i += 2
            } else {
                positional += arg
                i++
            }
        }
    }

    fun value(name: String): String? = named[name]

    companion object {
        // 不带值的开关
        val FLAGS = setOf("--visited", "--restore", "--sequential", "--playing", "--background-first", "--combined")
    }
}
