package dev.piko.cli

import dev.piko.shared.naming.parseMediaName
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """piko-cli：Piko 开发工具

  snapshot -o <文件> [--root <路径>] [--depth <层数>] [--deep <名字,…>]
      只读地列网盘目录，存成快照（文件名、类型、大小）。
      从 --root（默认 /）起列 --depth 层（默认 1，即根目录与其下一层）；
      --deep 里的 root 直属子目录递归到底。会话取自 ~/.piko，与桌面端共用。

  dryrun <快照> [--path <前缀>] [--visited] [-o <文件>]
      离线对快照跑网盘页的解析流水线，逐行写出原名与界面上的样子。
      --path 只看路径以此开头的目录。--visited 模拟每个目录都点进去过，
      文件夹行用里面的文件名描述，与 app 记住内容之后的样子一致。

  score <快照>
      以整理过的文件夹名为标注，统计里面视频的番号识别率，列出认错的。

  parse <文件名>…
      单独解析几个文件名，打印 parseMediaName 的结果。
"""

fun main(args: Array<String>) {
    // Windows 控制台默认按系统代码页输出，中文文件名会变成问号
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
    val command = args.firstOrNull() ?: usage()
    val options = Options(args.drop(1))
    when (command) {
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
        "parse" -> options.positional.ifEmpty { usage() }.forEach { name -> println("$name\n  ${parseMediaName(name)}") }
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
        val FLAGS = setOf("--visited")
    }
}
