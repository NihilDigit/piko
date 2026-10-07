package dev.piko.shots

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** 一张图的结果。子进程逐行报给主进程，格式见 [toLine]。 */
data class ShotResult(
    val name: String,
    val ok: Boolean,
    val ms: Long,
    /** 收尾时画面停下来了；没停下的照当时的画面存了图。 */
    val settled: Boolean = true,
    val error: String? = null,
) {
    fun toLine(): String = listOf(
        LINE_TAG, if (ok) "ok" else "fail", name, ms.toString(), if (settled) "settled" else "moving",
        error.orEmpty().replace('\n', ' ').replace('\t', ' '),
    ).joinToString("\t")

    companion object {
        private const val LINE_TAG = "@shot"

        fun parse(line: String): ShotResult? {
            val parts = line.split('\t')
            if (parts.size < 6 || parts[0] != LINE_TAG) return null
            return ShotResult(parts[2], parts[1] == "ok", parts[3].toLongOrNull() ?: 0, parts[4] == "settled", parts[5].ifEmpty { null })
        }
    }
}

/**
 * 第 [index] 片（从 1 起），共 [count] 片。按序号取模分而不是切成连续的几段：standardSet 按节排列，
 * 同一节的步骤相近、耗时也相近（信息流、批量重命名一节明显更慢），连续切段会让某一片整节都是慢的。
 */
class Shard(private val index: Int, private val count: Int) {
    fun <T> pick(items: List<T>): List<T> = items.filterIndexed { i, _ -> i % count == index - 1 }

    companion object {
        fun parse(spec: String): Shard {
            val (i, n) = spec.split('/').map { it.toIntOrNull() ?: throw IllegalArgumentException("--shard 写成 1/6") }
            require(n > 0 && i in 1..n) { "--shard 写成 1/6，i 在 1 到 n 之间" }
            return Shard(i, n)
        }
    }
}

/** 逐张打印进度，各片的结果都经它，所以加锁。 */
class Progress(private val total: Int) {
    private var done = 0

    @Synchronized
    fun report(result: ShotResult) {
        done++
        val note = when {
            !result.ok -> "  失败：${result.error}"
            !result.settled -> "  画面没有停下"
            else -> ""
        }
        println("[$done/$total] ${result.name} ${"%.1f".format(result.ms / 1000.0)} 秒$note")
    }
}

/**
 * 默认的并行数。所有场景共用一条 Swing EDT，一个进程里只能逐张渲染，并行只能靠多开进程；
 * 每个进程主要占一个核（EDT 上的渲染）外加协程与 GC，取核数的一半。
 */
fun defaultJobs(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)

private const val MAIN_CLASS = "dev.piko.shots.MainKt"

/**
 * 拉起 [jobs] 个子进程，各渲染 [names] 的一片，写进同一个 [outDir]。子进程用本进程的 java、JVM 参数与类路径：
 * 从 installDist 的副本或 renderAll 任务启动时，类路径是装出来的 jar，别处重新编译界面碰不到它们。
 */
fun renderInChildren(jobs: Int, names: List<String>, outDir: File, extraArgs: List<String>): List<ShotResult> {
    val count = jobs.coerceAtMost(names.size)
    // 类路径有几百个 jar，Windows 的命令行放不下（上限 32K 字符），经参数文件传
    val argFile = File(ShotDirs.run, "jvm.args").apply {
        writeText(childJvmArgs().joinToString("\n", transform = ::quoteArg), Charset.forName(System.getProperty("native.encoding")))
    }
    val java = ProcessHandle.current().info().command().orElse("java")
    val results = ConcurrentLinkedQueue<ShotResult>()
    val progress = Progress(names.size)
    val started = System.currentTimeMillis()
    val shardMs = LongArray(count)
    val processes = (1..count).map { i ->
        val command = listOf(java, "@${argFile.absolutePath}", MAIN_CLASS, "all", "--shard", "$i/$count", "-o", outDir.absolutePath) + extraArgs
        ProcessBuilder(command).redirectErrorStream(true).start().also(ShotDirs::adopt)
    }
    val readers = processes.mapIndexed { index, process ->
        thread(name = "shard-${index + 1}") {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val result = ShotResult.parse(line)
                if (result != null) {
                    results += result
                    progress.report(result)
                } else {
                    println("[片 ${index + 1}] $line")
                }
            }
            val code = process.waitFor()
            shardMs[index] = System.currentTimeMillis() - started
            if (code != 0) println("[片 ${index + 1}] 退出码 $code")
        }
    }
    readers.forEach { it.join() }
    println("各片用时：" + shardMs.joinToString("、") { "${it / 1000} 秒" })
    // 子进程中途崩掉时，它那一片剩下的图没有结果
    val reported = results.map { it.name }.toSet()
    val missing = names.filter { it !in reported }.map { ShotResult(it, ok = false, ms = 0, error = "子进程没有报告这一张，中途退出了") }
    return results.toList() + missing
}

private fun childJvmArgs(): List<String> {
    val inherited = ManagementFactory.getRuntimeMXBean().inputArguments.filterNot { arg ->
        // 调试器会让每个子进程争同一个端口；类路径与输出编码下面另给
        listOf("-agentlib", "-javaagent", "-Djava.class.path", "-Dstdout.encoding", "-Dstderr.encoding", "-D${ShotDirs.RUN_PROPERTY}").any { arg.startsWith(it) }
    }
    // 子进程的输出由主进程按 UTF-8 读，不跟随控制台的代码页；临时目录用主进程的那一份，见 ShotDirs
    return inherited + listOf(
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-D${ShotDirs.RUN_PROPERTY}=${ShotDirs.run.name}",
        "-cp", System.getProperty("java.class.path"),
    )
}

/** 参数文件里含空格的参数要加引号，引号里反斜杠是转义符，Windows 路径的反斜杠要成对写。 */
private fun quoteArg(arg: String): String = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 输出目录的上一版与差异清单。基线放在输出目录旁边的 `<目录>-baseline`，与输出同为一张图一个 PNG。
 * 只动名字以 [only] 开头的图：`--only` 只重出几张时，其余的基线与输出原样留着。
 */
class Baseline(private val outDir: File, private val only: String, private val keep: Boolean) {
    private val dir = File(outDir.absoluteFile.parentFile, "${outDir.name}-baseline")
    private val diffDir = File(outDir, "diff")

    private fun inScope(name: String) = name.startsWith(only)

    private fun pngs(of: File): Map<String, File> =
        of.listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty()
            .associateBy { it.name.removeSuffix(".png") }.filterKeys(::inScope)

    /** 渲染前调用：把范围内的上一版挪作基线（[keep] 时保留原有基线，只清掉上一版）。 */
    fun roll() {
        outDir.mkdirs()
        val previous = pngs(outDir)
        if (keep) {
            // 本次没出成的图不能拿上一轮的冒充
            previous.values.forEach { it.delete() }
        } else {
            dir.mkdirs()
            pngs(dir).values.forEach { it.delete() }
            previous.values.forEach { Files.move(it.toPath(), File(dir, it.name).toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        diffDir.deleteRecursively()
    }

    /** 渲染后调用：逐像素比对范围内的图，写 changes.md 与 diff/ 下的对照图，返回清单文件。 */
    fun report(order: List<String>, results: List<ShotResult>, elapsedMs: Long): File {
        val rank = order.withIndex().associate { it.value to it.index }
        val sorted = results.sortedBy { rank[it.name] ?: Int.MAX_VALUE }
        val old = pngs(dir)
        val produced = sorted.filter { it.ok }.map { it.name }
        val diffs = produced.parallelStream().map { name ->
            name to old[name]?.let { compare(it, File(outDir, "$name.png"), File(diffDir, "$name.png")) }
        }.toList().toMap()
        val changed = produced.filter { (diffs[it]?.pixels ?: 0) > 0 }
        val added = produced.filter { it !in old }
        val unchanged = produced.size - changed.size - added.size
        val attempted = results.map { it.name }.toSet()
        val removed = old.keys.filter { it !in attempted }.sorted()
        val failed = sorted.filter { !it.ok }
        val moving = sorted.filter { it.ok && !it.settled }

        val baseLink = "../${dir.name}"
        val text = buildString {
            appendLine("# 截图变化")
            appendLine()
            appendLine("基线：`${dir.path}`（${if (keep) "沿用已有的基线" else "上一次渲染的输出"}）  ")
            appendLine("范围：${if (only.isEmpty()) "全部" else "名字以 `$only` 开头的图"}  ")
            appendLine("本次 ${results.size} 张，用时 ${elapsedMs / 1000} 秒：变化 ${changed.size}，新增 ${added.size}，" +
                "删除 ${removed.size}，失败 ${failed.size}，未变 $unchanged。")
            if (changed.isNotEmpty()) {
                appendLine()
                appendLine("## 变化")
                appendLine()
                appendLine("对照图从左到右是旧图、新图、差异（新图淡化，变了的像素标红，红框是包围盒）。")
                appendLine()
                appendLine("| 图 | 差异像素 | 包围盒（x, y, 宽×高） | 对照 |")
                appendLine("| --- | ---: | --- | --- |")
                for (name in changed) {
                    val diff = diffs.getValue(name)!!
                    val box = diff.box.let { "${it.x}, ${it.y}, ${it.width}×${it.height}" } + if (diff.resized) "（尺寸变了）" else ""
                    appendLine("| $name | ${diff.pixels} | $box | [对照](diff/$name.png)、[旧]($baseLink/$name.png)、[新]($name.png) |")
                }
            }
            if (added.isNotEmpty()) {
                appendLine()
                appendLine("## 新增")
                appendLine()
                added.forEach { appendLine("- [$it]($it.png)") }
            }
            if (removed.isNotEmpty()) {
                appendLine()
                appendLine("## 删除")
                appendLine()
                appendLine("基线里有、这次没有渲染的图：清单里删掉或改了名。")
                appendLine()
                removed.forEach { appendLine("- [$it]($baseLink/$it.png)") }
            }
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("## 失败")
                appendLine()
                failed.forEach { appendLine("- ${it.name}：${it.error}") }
            }
            if (moving.isNotEmpty()) {
                appendLine()
                appendLine("## 画面没有停下")
                appendLine()
                appendLine("收尾等稳定超时，照当时的画面存图；连跑两次可能不一样。")
                appendLine()
                moving.forEach { appendLine("- ${it.name}") }
            }
        }
        return File(outDir, "changes.md").apply { writeText(text) }
    }
}

private class PixelDiff(val pixels: Long, val box: Rectangle, val resized: Boolean)

private const val PANEL_GAP = 16

/**
 * 逐像素比对两张图，有差异时把旧、新、差异三栏并排写到 [out]。尺寸不同时按两者的并集比，
 * 只有一边有的像素都算变了。没有差异返回像素数为 0 的结果，不写文件。
 */
private fun compare(oldFile: File, newFile: File, out: File): PixelDiff {
    val old = ImageIO.read(oldFile)
    val new = ImageIO.read(newFile)
    val width = max(old.width, new.width)
    val height = max(old.height, new.height)
    val oldPixels = old.getRGB(0, 0, old.width, old.height, null, 0, old.width)
    val newPixels = new.getRGB(0, 0, new.width, new.height, null, 0, new.width)
    val changed = BooleanArray(width * height)
    var count = 0L
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            val inOld = x < old.width && y < old.height
            val inNew = x < new.width && y < new.height
            val differs = inOld != inNew || (inOld && oldPixels[y * old.width + x] != newPixels[y * new.width + x])
            if (differs) {
                changed[y * width + x] = true
                count++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
    }
    val resized = old.width != new.width || old.height != new.height
    if (count == 0L) return PixelDiff(0, Rectangle(), resized)
    val box = Rectangle(left, top, right - left + 1, bottom - top + 1)

    val sheet = BufferedImage(old.width + new.width + width + PANEL_GAP * 2, height, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.color = Color(0x80, 0x80, 0x80)
    g.fillRect(0, 0, sheet.width, sheet.height)
    g.drawImage(old, 0, 0, null)
    g.drawImage(new, old.width + PANEL_GAP, 0, null)
    val diffX = old.width + new.width + PANEL_GAP * 2
    for (y in 0 until height) {
        for (x in 0 until width) {
            val rgb = when {
                changed[y * width + x] -> 0xE00000
                // 淡化成浅灰作底，红点才显眼
                else -> {
                    val p = newPixels[y * new.width + x]
                    val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    val light = 255 - (255 - luma) / 4
                    (light shl 16) or (light shl 8) or light
                }
            }
            sheet.setRGB(diffX + x, y, rgb)
        }
    }
    // 只差几十个像素时红点不好找，再框出包围盒
    g.color = Color(0xE0, 0, 0)
    g.stroke = BasicStroke(2f)
    g.drawRect(diffX + box.x - 4, box.y - 4, box.width + 8, box.height + 8)
    g.dispose()
    out.parentFile.mkdirs()
    ImageIO.write(sheet, "png", out)
    return PixelDiff(count, box, resized)
}
