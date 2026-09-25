package dev.piko.shared.state

import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.ScannedFile
import dev.piko.shared.naming.Confidence
import dev.piko.shared.naming.EntryLocation
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.MediaTag
import dev.piko.shared.naming.NameKind
import dev.piko.shared.naming.ParsedName
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.WorkKind
import dev.piko.shared.naming.analyzeMediaBatch
import kotlin.time.Instant

/** 结果里的一个文件。[folderPath] 相对扫描起点，起点下的文件为空串。 */
data class DuplicateFile(
    val id: String,
    val name: String,
    val folderPath: String,
    val size: Long,
    val createdTime: String,
    val modifiedTime: String,
    val isVideo: Boolean,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Long?,
) {
    val path: String get() = if (folderPath.isEmpty()) name else "$folderPath/$name"
}

enum class DuplicateKind {
    /** gcid 与大小都相同，内容逐字节一致。 */
    IDENTICAL,

    /** 解析为同一集（或同一部电影、同一番号），内容不同：分辨率、字幕组、版本之别。 */
    VERSIONS,
}

/**
 * 组里的一行。[details] 是本组内有区分度的标签（字幕组、分辨率、版本等），全组相同的已去掉。
 * [sameCopies] 只用于版本组：这一版在网盘里另有几份完全相同的副本，它们在完全相同的组里处理。
 */
data class DuplicateRow(
    val file: DuplicateFile,
    val details: List<String> = emptyList(),
    val sameCopies: Int = 0,
)

/** [keptId] 是完全相同的组里默认保留的一份，版本组为 null。 */
data class DuplicateGroup(
    val kind: DuplicateKind,
    val key: String,
    val title: String,
    val rows: List<DuplicateRow>,
    val keptId: String?,
) {
    val totalBytes: Long get() = rows.sumOf { it.file.size }

    /** 按默认勾选移除后能腾出的空间。版本组默认什么都不勾，为 0。 */
    val reclaimableBytes: Long
        get() = if (kind == DuplicateKind.IDENTICAL) rows.filter { it.file.id != keptId }.sumOf { it.file.size } else 0L
}

data class DuplicateReport(
    val identical: List<DuplicateGroup>,
    val versions: List<DuplicateGroup>,
) {
    companion object {
        val EMPTY = DuplicateReport(emptyList(), emptyList())
    }
}

/**
 * 把扫描结果分成两类重复。纯函数，不碰网络，便于离线核对。
 *
 * [rootName] 是扫描起点的目录名，扫全盘时传 null。起点名也参与解析：在「Title S2」里扫描时，
 * 起点下的「Title - 03.mkv」要从它得知是第二季。
 */
fun findDuplicates(files: List<ScannedFile>, rootName: String? = null): DuplicateReport {
    val all = files.map { it to it.toDuplicateFile() }
    val identical = identicalGroups(all.map { (scanned, file) -> file to scanned.file.hash })
    val versions = versionGroups(all, identical, rootName)
    return DuplicateReport(identical, versions)
}

// region 完全相同

/**
 * 按 gcid 与大小分组。没有 gcid 的（上传中、转码产物）与空文件不参与：空文件的 gcid 全都一样，
 * 但删掉它们腾不出空间，还常是占位用的。
 */
private fun identicalGroups(files: List<Pair<DuplicateFile, String>>): List<DuplicateGroup> =
    files.filter { (file, hash) -> hash.isNotBlank() && file.size > 0 }
        .groupBy({ (file, hash) -> "${hash.uppercase()}:${file.size}" }, { it.first })
        .filterValues { it.size > 1 }
        .map { (key, members) ->
            val kept = members.minWith(KEEP_ORDER)
            val rows = (listOf(kept) + members.filter { it !== kept }.sortedWith(KEEP_ORDER)).map { DuplicateRow(it) }
            DuplicateGroup(DuplicateKind.IDENTICAL, key, kept.name, rows, kept.id)
        }
        .sortedWith(compareByDescending<DuplicateGroup> { it.reclaimableBytes }.thenBy { it.title })

/**
 * 默认保留哪一份：最早存进网盘的，同时存入的取路径最短的。
 * 最早的那份通常在用户最初整理好的位置，后来的多是重复离线或转存产生的；路径短的一般是
 * 手动放置的，深路径多是种子或分享自带的目录结构。时间解析失败的排在最后。
 */
internal val KEEP_ORDER: Comparator<DuplicateFile> =
    compareBy<DuplicateFile, Instant?>(nullsLast()) { parseInstant(it.createdTime) }
        .thenBy { it.path.length }
        .thenBy { it.path }
        .thenBy { it.id }

private fun parseInstant(text: String): Instant? =
    text.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

// endregion

// region 同集不同版本

private class VersionCandidate(val file: DuplicateFile, val hash: String, val parsed: ParsedName, val key: String, val title: String)

/**
 * 同一集的不同版本。只看视频，宁缺毋滥：集号拿不准、作品名只能从目录猜的都不参与，
 * 因为把两集错并成一组的代价（误删）远大于漏掉一组。
 *
 * 解析按目录分批：同目录的兄弟文件能互相印证集号（见 BatchAnalyzer），跨目录只比结果的键。
 * 完全相同的文件只算一份，由该组默认保留的那份代表。
 */
private fun versionGroups(
    all: List<Pair<ScannedFile, DuplicateFile>>,
    identical: List<DuplicateGroup>,
    rootName: String?,
): List<DuplicateGroup> {
    val representativeOf = HashMap<String, String>()
    val copiesOf = HashMap<String, Int>()
    identical.forEach { group ->
        group.rows.forEach { representativeOf[it.file.id] = group.keptId!! }
        copiesOf[group.keptId!!] = group.rows.size - 1
    }

    val candidates = all.groupBy { (scanned, _) -> scanned.folderPath }
        .filterValues { members -> members.any { it.second.isVideo } }
        .flatMap { (folderPath, members) -> analyzeFolder(folderPath, members, rootName) }
        .filter { (representativeOf[it.file.id] ?: it.file.id) == it.file.id }

    return candidates.groupBy { it.key }
        .values
        .filter { members -> members.distinctBy { it.hash.ifBlank { it.file.id } }.size > 1 }
        .map { members ->
            val sorted = members.sortedWith(compareByDescending<VersionCandidate> { it.file.size }.thenBy { it.file.path })
            val tagsOf = sorted.associate { it.file.id to versionTags(it.parsed) }
            val common = tagsOf.values.reduce { acc, tags -> acc.intersect(tags.toSet()).toList() }.toSet()
            val rows = sorted.map { candidate ->
                DuplicateRow(
                    file = candidate.file,
                    details = tagsOf.getValue(candidate.file.id).filterNot { it in common },
                    sameCopies = copiesOf[candidate.file.id] ?: 0,
                )
            }
            DuplicateGroup(DuplicateKind.VERSIONS, members.first().key, members.first().title, rows, keptId = null)
        }
        .sortedWith(compareByDescending<DuplicateGroup> { it.totalBytes }.thenBy { it.title })
}

private fun analyzeFolder(
    folderPath: String,
    members: List<Pair<ScannedFile, DuplicateFile>>,
    rootName: String?,
): List<VersionCandidate> {
    val dirs = listOfNotNull(rootName) + folderPath.split('/').filter { it.isNotEmpty() }
    val prefix = dirs.joinToString("/")
    // 同目录的字幕、图片也一起送进去：广告、样片、附件的判定要看兄弟文件
    val batch = analyzeMediaBatch(members.map { (scanned, _) -> MediaFileInput(if (prefix.isEmpty()) scanned.file.name else "$prefix/${scanned.file.name}", scanned.file.sizeBytes) })
    return members.mapIndexedNotNull { index, (scanned, file) ->
        if (!file.isVideo) return@mapIndexedNotNull null
        val location = batch.locate(index) ?: return@mapIndexedNotNull null
        val parsed = batch.parsed[index]
        val identity = episodeIdentity(parsed, location, dirs, scanned.file.name) ?: return@mapIndexedNotNull null
        VersionCandidate(file, scanned.file.hash, parsed, identity.first, identity.second)
    }
}

/**
 * 同一集的比较键与组标题，拿不准时返回 null。
 *
 * - 番号：番号加分段。中字、无码、不同压制都是同一番号的不同版本。
 * - 剧集：作品名必须来自文件名本身且集号把握为 HIGH。只有集号的「01.mkv」、作品名取自目录的
 *   「Camera/IMG_1234.mp4」、裸数字集号的「Lesson 03」都不参与。季号缺省时先看目录名
 *   （「Title S2/」「第3季/」），再缺省视为第一季，于是「[Group] Title - 03」与「Title.S01E03」同组，
 *   与「Title.S02E03」不同组。修正版号（v2）不进键：它正是要并在一起比较的差别。
 * - 电影：只有文件名里带年份时才参与，年份进键。解析器会把年份从作品名里去掉，
 *   不带年份的话「Dune 1984」与「Dune 2021」会被并成一组。
 *
 * 年份也进剧集的键：翻拍作品（「Hunter x Hunter (2011)」）与旧版同名同集号。
 * 一边写了年份一边没写的因此并不起来，这是有意的取舍。
 */
private fun episodeIdentity(
    parsed: ParsedName,
    location: EntryLocation,
    dirs: List<String>,
    fileName: String,
): Pair<String, String>? {
    val work = location.work
    val entry = location.entry
    return when (work.kind) {
        WorkKind.AV -> {
            val av = entry.av ?: return null
            val label = listOfNotNull(av.code, av.part).joinToString(" ")
            "av|${av.code}|${av.part.orEmpty().uppercase()}" to label
        }
        WorkKind.SERIES -> {
            val title = parsed.title ?: return null
            if (parsed.confidence != Confidence.HIGH || parsed.opaque || parsed.timed) return null
            val year = releaseYear(fileName)
            val episode = entry.episode
            val section = location.section.section
            when {
                parsed.kind == NameKind.EPISODE && episode != null -> {
                    val season = episode.season ?: seasonFromDirectories(dirs) ?: 1
                    // v2 是修正版，Beta 这类却可能是另一集（Steins;Gate 的 23β），只有前者算同一集
                    val variant = episode.version?.takeUnless { REVISION.matches(it) }?.lowercase().orEmpty()
                    val number = "${episode.number}.${episode.decimal.orEmpty()}${episode.suffix.lowercase()}~${episode.last ?: ""}|$variant"
                    val sectionLabel = if (section == Section.MAIN) "" else "${section.label} "
                    val episodeLabel = if (season == 1) episode.shortText else "S${season.toString().padStart(2, '0')}E${episode.shortText}"
                    "ep|${work.key}|${year ?: ""}|$section|$season|$number" to "$title $sectionLabel$episodeLabel"
                }
                parsed.kind == NameKind.STANDALONE && episode == null && year != null -> {
                    // 「Movies/」目录会让单文件落进剧场版分区，文件名本身并没有不同
                    val movieSection = if (section == Section.MOVIE) Section.MAIN else section
                    "one|${work.key}|$year|$movieSection" to "$title ($year)"
                }
                else -> null
            }
        }
        WorkKind.UNKNOWN -> null
    }
}

private val REVISION = Regex("""(?i)v\d+""")

// 前后不能紧挨字母或数字：1920x1080 的 1920、2160p、校验码「[AB2019CD]」里的 2019 都不算年份
private val YEAR = Regex("""(?<![\dA-Za-z×])((?:19|20)\d{2})(?![\dA-Za-z×])""")

/** 文件名里最后一个像年份的数。取最后一个：「Blade Runner 2049 (2017)」的年份在后面。 */
internal fun releaseYear(fileName: String): Int? =
    YEAR.findAll(fileName.substringBeforeLast('.')).lastOrNull()?.groupValues?.get(1)?.toInt()

private val SEASON_PATTERNS = listOf(
    Regex("""(?i)\bseason\s*(\d{1,2})\b"""),
    Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)\s+season\b"""),
    Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})(?:$|[^a-z0-9])"""),
    Regex("""第\s*(\d{1,2})\s*[季期]"""),
)
private val CHINESE_SEASON = Regex("""第\s*([一二三四五六七八九十])\s*[季期]""")
private const val CHINESE_DIGITS = "一二三四五六七八九十"

/** 最近一层写明季号的目录给出的季号。 */
internal fun seasonFromDirectories(dirs: List<String>): Int? {
    for (dir in dirs.asReversed()) {
        SEASON_PATTERNS.firstNotNullOfOrNull { it.find(dir)?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }
        CHINESE_SEASON.find(dir)?.let { return CHINESE_DIGITS.indexOf(it.groupValues[1]) + 1 }
    }
    return null
}

/** 行上用于区分版本的标签：字幕组、分辨率、编码、来源、字幕语言与集号版本。 */
private fun versionTags(parsed: ParsedName): List<String> = buildList {
    parsed.episode?.version?.let { add(it) }
    parsed.tags.map(MediaTag::text).forEach { if (it !in this) add(it) }
}

// endregion

private fun ScannedFile.toDuplicateFile(): DuplicateFile = DuplicateFile(
    id = file.id,
    name = file.name,
    folderPath = folderPath,
    size = file.sizeBytes,
    createdTime = file.createdTime,
    modifiedTime = file.modifiedTime,
    isVideo = file.isPlayableVideo(),
    width = file.params["width"]?.toIntOrNull()?.takeIf { it > 0 },
    height = file.params["height"]?.toIntOrNull()?.takeIf { it > 0 },
    durationSeconds = file.params["duration"]?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
)
