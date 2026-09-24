package dev.piko.shared.naming

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * 只在本机运行：遍历 piko-name-corpus 的全部种子，统计识别率并列出典型失败样本。
 *
 * 语料里有 sukebei 的真实文件名，仓库是公开的，所以语料不进仓库，这个测试在 CI 上自动跳过。
 * 报告写到 shared/build/reports/naming-corpus.txt。它只统计、不断言：识别率随规则调整而变，
 * 把某个数字写死成断言，只会让每次改进都先去改这个数字。
 */
class NamingCorpusLocalTest {

    private val corpus = File("C:/Codes/piko-name-corpus")

    private class Torrent(val site: String, val id: String, val label: String, val title: String, val files: List<MediaFileInput>)

    private class Stats {
        var torrents = 0
        var videos = 0
        var secondaryVideos = 0
        var disc = 0
        var episodeHigh = 0
        var episodeLow = 0
        var episodeUntitled = 0
        var standalone = 0
        var standaloneCrowded = 0
        var av = 0
        var unknown = 0
        var subtitles = 0
        var subtitlesAttached = 0
        val failureKinds = linkedMapOf<String, Int>()
        val samples = linkedMapOf<String, MutableList<String>>()

        fun fail(kind: String, sample: String) {
            failureKinds[kind] = (failureKinds[kind] ?: 0) + 1
            val list = samples.getOrPut(kind) { mutableListOf() }
            if (list.size < 16 && list.none { it.substringBefore(':') == sample.substringBefore(':') }) list += sample
        }
    }

    @Test
    fun `corpus recognition report`() {
        assumeTrue("语料目录不存在，跳过", File(corpus, "index.tsv").exists())
        val torrents = loadTorrents()
        val byLabel = linkedMapOf<String, Stats>()
        val avChecks = mutableListOf<String>()
        var avAgree = 0
        var avTotal = 0
        val titleMismatch = mutableListOf<String>()
        var titleChecked = 0
        var titleAgree = 0
        val adVideosNyaa = mutableListOf<String>()
        var rangeChecked = 0
        var rangeAgree = 0
        val rangeMismatch = mutableListOf<String>()
        var slowest = 0L to ""

        torrents.forEach { torrent ->
            val started = System.nanoTime()
            val batch = analyzeMediaBatch(torrent.files)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (elapsed > slowest.first) slowest = elapsed to "${torrent.site}/${torrent.id} (${torrent.files.size} 个文件)"
            val stats = byLabel.getOrPut(torrent.label) { Stats() }
            stats.torrents++
            collect(torrent, batch, stats)

            if (torrent.site == "nyaa") {
                batch.secondary.filter { it.reason == SecondaryReason.AD && batch.parsed[it.index].fileKind == FileKind.VIDEO }
                    .forEach { adVideosNyaa += "${torrent.id}: ${torrent.files[it.index].path}" }
            }
            if (torrent.label == "sukebei-av" || torrent.label == "sukebei-real") {
                val expected = expectedCode(torrent.title)
                val main = batch.works.firstOrNull { it.kind == WorkKind.AV }
                if (expected != null) {
                    avTotal++
                    if (main?.title == expected) avAgree++ else avChecks += "${torrent.id}: 标题 $expected，解析 ${main?.title} ← ${largestVideo(torrent)}"
                }
            }
            if (torrent.label.startsWith("anime")) {
                val main = batch.works.filter { it.kind == WorkKind.SERIES && it.title != null }
                    .maxByOrNull { work -> work.sections.sumOf { it.entries.size } }
                if (main != null) {
                    titleChecked++
                    if (titleMatches(main.title!!, torrent)) titleAgree++ else titleMismatch += "${torrent.id}: 「${main.title}」 vs ${torrent.title.take(90)}"
                }
                // 集号是否可信：种子标题写了「01-24」这类范围时，与正片解析出的首尾集号比对
                val expected = describeFolder(torrent.title).episodeRange?.split('–')?.mapNotNull { it.toIntOrNull() }
                val episodes = batch.works.filter { it.kind == WorkKind.SERIES }.flatMap { work ->
                    work.sections.filter { it.section == Section.MAIN }.flatMap { it.entries }.mapNotNull { it.episode }
                }
                if (expected != null && expected.size == 2 && episodes.isNotEmpty()) {
                    rangeChecked++
                    val first = episodes.minOf { it.number }
                    val last = episodes.maxOf { it.last ?: it.number }
                    if (first == expected[0] && last == expected[1]) rangeAgree++ else rangeMismatch += "${torrent.id}: 标题 ${expected[0]}–${expected[1]}，解析 $first–$last ← ${torrent.title.take(80)}"
                }
            }
        }

        val report = buildString {
            appendLine("# 文件名解析器语料报告")
            appendLine("种子 ${torrents.size} 个；最慢一批 ${slowest.first} ms：${slowest.second}")
            appendLine()
            appendLine("## 视频文件识别率（按标签）")
            appendLine("标签 | 种子 | 视频 | 次要 | 原盘 | 集号(高) | 集号(低) | 其中无作品名 | 无集号 | 其中同目录多视频 | 番号 | 未识别 | 识别率 | 字幕挂上")
            val total = Stats()
            byLabel.forEach { (label, s) ->
                appendLine(row(label, s))
                total.torrents += s.torrents; total.videos += s.videos; total.secondaryVideos += s.secondaryVideos
                total.disc += s.disc; total.episodeHigh += s.episodeHigh; total.episodeLow += s.episodeLow
                total.episodeUntitled += s.episodeUntitled; total.standalone += s.standalone
                total.standaloneCrowded += s.standaloneCrowded; total.av += s.av; total.unknown += s.unknown
                total.subtitles += s.subtitles; total.subtitlesAttached += s.subtitlesAttached
                s.failureKinds.forEach { (k, v) -> total.failureKinds[k] = (total.failureKinds[k] ?: 0) + v }
            }
            appendLine(row("合计", total))
            appendLine()
            appendLine("识别率 = 认出集号、番号、原盘或作品名的内容视频 / 内容视频（不含判为次要的）。")
            appendLine()
            appendLine("## 未识别与存疑的分布")
            total.failureKinds.entries.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("- $k：$v") }
            appendLine()
            appendLine("## 番号与种子标题一致性：$avAgree / $avTotal")
            avChecks.take(20).forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 动画主作品名与种子标题一致性：$titleAgree / $titleChecked")
            titleMismatch.take(40).forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 正片首尾集号与种子标题里的范围一致：$rangeAgree / $rangeChecked")
            rangeMismatch.take(40).forEach { appendLine("- $it") }
            appendLine()
            appendLine("## nyaa 里被判为广告的视频（应为 0）：${adVideosNyaa.size}")
            adVideosNyaa.take(20).forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 样本")
            byLabel.forEach { (label, s) ->
                s.samples.forEach { (kind, list) ->
                    appendLine("### $label：$kind")
                    list.forEach { appendLine("- $it") }
                }
            }
        }
        val out = File("build/reports/naming-corpus.txt")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report.lineSequence().take(40).joinToString("\n"))
        println("完整报告：${out.absolutePath}")
    }

    private fun row(label: String, s: Stats): String {
        val content = s.videos - s.secondaryVideos
        val recognized = s.disc + s.episodeHigh + s.episodeLow + s.standalone + s.av
        val rate = if (content == 0) "-" else "%.1f%%".format(recognized * 100.0 / content)
        val subs = if (s.subtitles == 0) "-" else "${s.subtitlesAttached}/${s.subtitles}"
        return "$label | ${s.torrents} | ${s.videos} | ${s.secondaryVideos} | ${s.disc} | ${s.episodeHigh} | ${s.episodeLow} | " +
            "${s.episodeUntitled} | ${s.standalone} | ${s.standaloneCrowded} | ${s.av} | ${s.unknown} | $rate | $subs"
    }

    private fun collect(torrent: Torrent, batch: MediaBatch, stats: Stats) {
        val hasVideo = batch.parsed.any { it.fileKind == FileKind.VIDEO }
        val standaloneFolders = batch.parsed.indices
            .filter { batch.roles[it] == FileRole.CONTENT && batch.parsed[it].kind == NameKind.STANDALONE && batch.parsed[it].fileKind == FileKind.VIDEO }
            .groupingBy { torrent.files[it].path.substringBeforeLast('/', "") }
            .eachCount()
        batch.parsed.forEachIndexed { index, parsed ->
            val path = torrent.files[index].path
            if (parsed.fileKind == FileKind.SUBTITLE && hasVideo) {
                stats.subtitles++
                if (batch.roles[index] == FileRole.ATTACHMENT) stats.subtitlesAttached++
            }
            if (parsed.fileKind != FileKind.VIDEO && parsed.fileKind != FileKind.DISC_IMAGE) return@forEachIndexed
            stats.videos++
            if (batch.roles[index] == FileRole.SECONDARY) {
                stats.secondaryVideos++
                return@forEachIndexed
            }
            val location = batch.locate(index)
            if (location?.entry?.key?.startsWith("disc|") == true) {
                stats.disc++
                return@forEachIndexed
            }
            val finalParsed = batch.parsed[index]
            when (finalParsed.kind) {
                NameKind.AV -> stats.av++
                NameKind.EPISODE -> {
                    if (finalParsed.confidence == Confidence.HIGH) stats.episodeHigh++ else stats.episodeLow++
                    if (location?.work?.title == null) {
                        stats.episodeUntitled++
                        stats.fail("有集号但找不到作品名", "${torrent.id}: $path")
                    }
                }
                NameKind.STANDALONE -> {
                    stats.standalone++
                    val folder = path.substringBeforeLast('/', "")
                    if ((standaloneFolders[folder] ?: 0) >= 3) {
                        stats.standaloneCrowded++
                        stats.fail("无集号，但同目录有 3 个以上同类（可能漏认集号）", "${torrent.id}: $path")
                    }
                }
                NameKind.UNKNOWN -> {
                    stats.unknown++
                    stats.fail(failureKind(parsed.fileName), "${torrent.id}: $path")
                }
            }
        }
    }

    private fun failureKind(name: String): String {
        val stem = name.substringBeforeLast('.')
        return when {
            stem.all { it.isDigit() || it in "-_ ." } -> "未识别：纯数字文件名"
            stem.any(::isCjk) && stem.count { it in 'A'..'Z' || it in 'a'..'z' } < 2 -> "未识别：只有中日文"
            Regex("""[A-Za-z]{2,6}[-_]?\d{2,5}""").containsMatchIn(stem) -> "未识别：像番号但未采信"
            Regex("""^[0-9a-zA-Z_-]{12,}$""").matches(stem) -> "未识别：哈希或随机串"
            else -> "未识别：其他"
        }
    }

    private fun titleMatches(title: String, torrent: Torrent): Boolean {
        val words = title.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }
        val haystack = (torrent.title + " " + torrent.files.take(3).joinToString(" ") { it.path }).lowercase()
        return words.isEmpty() || words.any { it in haystack }
    }

    private fun expectedCode(title: String): String? {
        val tokens = title.split(Regex("""[\s\[\]【】()]+""")).filter { it.isNotBlank() }
        return tokens.firstNotNullOfOrNull { token ->
            if (Regex("""^(?:FC2|HEYZO|[A-Za-z]{2,6})[-_]?(?:PPV[-_]?)?\d{2,8}(?:-[A-Za-z0-9]+)?$""", RegexOption.IGNORE_CASE).matches(token)) {
                normalizeAvCode(token)
            } else {
                null
            }
        }
    }

    private fun largestVideo(torrent: Torrent): String =
        torrent.files.filter { fileKindOf(it.path) == FileKind.VIDEO }.maxByOrNull { it.size }?.path.orEmpty()

    private fun loadTorrents(): List<Torrent> {
        val json = Json { ignoreUnknownKeys = true }
        return File(corpus, "index.tsv").readLines().drop(1).mapNotNull { line ->
            val cells = line.split('\t')
            if (cells.size < 6) return@mapNotNull null
            val file = File(corpus, "${cells[0]}/${cells[1]}.json")
            if (!file.exists()) return@mapNotNull null
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val files = root["files"]!!.jsonArray.map { element ->
                val obj = element.jsonObject
                MediaFileInput(obj["path"]!!.jsonPrimitive.content, obj["bytes"]?.jsonPrimitive?.long ?: 0)
            }
            Torrent(cells[0], cells[1], cells[3], cells[5], files)
        }
    }
}
