package dev.piko.shared.state

import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.ScannedFile
import dev.piko.shared.naming.Confidence
import dev.piko.shared.naming.EntryLocation
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
    val originalCreatedAtMillis: Long? = null,
) {
    val path: String = if (folderPath.isEmpty()) name else "$folderPath/$name"
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
    val totalBytes: Long

    /** 按默认勾选移除后能腾出的空间。版本组默认什么都不勾，为 0。 */
    val reclaimableBytes: Long

    init {
        var total = 0L
        var reclaimable = 0L
        val isIdentical = kind == DuplicateKind.IDENTICAL
        for (i in 0 until rows.size) {
            val file = rows[i].file
            val size = file.size
            total += size
            if (isIdentical && file.id != keptId) {
                reclaimable += size
            }
        }
        totalBytes = total
        reclaimableBytes = reclaimable
    }
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
    if (files.size < 2) return DuplicateReport.EMPTY
    val all = ArrayList<Pair<ScannedFile, DuplicateFile>>(files.size)
    for (i in 0 until files.size) {
        val scanned = files[i]
        all.add(scanned to scanned.toDuplicateFile())
    }
    val identical = identicalGroups(all)
    val versions = versionGroups(all, identical, rootName)
    return DuplicateReport(identical, versions)
}

// region 完全相同

private val IDENTICAL_GROUP_ORDER: Comparator<DuplicateGroup> =
    compareByDescending<DuplicateGroup> { it.reclaimableBytes }.thenBy { it.title }

private val VERSION_GROUP_ORDER: Comparator<DuplicateGroup> =
    compareByDescending<DuplicateGroup> { it.totalBytes }.thenBy { it.title }

/**
 * 按 gcid 与大小分组。没有 gcid 的（上传中、转码产物）与空文件不参与：空文件的 gcid 全都一样，
 * 但删掉它们腾不出空间，还常是占位用的。
 */
private fun identicalGroups(files: List<Pair<ScannedFile, DuplicateFile>>): List<DuplicateGroup> {
    val buckets = LinkedHashMap<String, MutableList<DuplicateFile>>()
    for (i in 0 until files.size) {
        val (scanned, file) = files[i]
        val hash = scanned.file.hash
        if (hash.isNotBlank() && file.size > 0L) {
            val key = "${hash.uppercase()}:${file.size}"
            buckets.getOrPut(key) { ArrayList(2) }.add(file)
        }
    }
    val groups = ArrayList<DuplicateGroup>()
    for ((key, members) in buckets) {
        if (members.size <= 1) continue
        val kept = members.minWith(KEEP_ORDER)
        val rest = ArrayList<DuplicateFile>(members.size - 1)
        for (i in 0 until members.size) {
            val candidate = members[i]
            if (candidate !== kept) rest.add(candidate)
        }
        rest.sortWith(KEEP_ORDER)
        val rows = ArrayList<DuplicateRow>(members.size)
        rows.add(DuplicateRow(kept))
        for (i in 0 until rest.size) {
            rows.add(DuplicateRow(rest[i]))
        }
        groups.add(DuplicateGroup(DuplicateKind.IDENTICAL, key, kept.name, rows, kept.id))
    }
    groups.sortWith(IDENTICAL_GROUP_ORDER)
    return groups
}

/**
 * 默认保留哪一份：名字里副本标记最少的，其次最早存进网盘的，同时存入的取路径最短的。
 * 「(1)」「 - Copy」是重名时系统或网盘加的，会一层层叠起来（「x (1)(1)」），叠得最多的最先删；
 * 存入时间靠不住：先把副本整理进目录、再删掉原件的情形很常见。
 * 最早的那份通常在用户最初整理好的位置，后来的多是重复离线或转存产生的；路径短的一般是
 * 手动放置的，深路径多是种子或分享自带的目录结构。时间解析失败的排在最后。
 */
internal val KEEP_ORDER: Comparator<DuplicateFile> =
    compareBy<DuplicateFile> { copyMarkerCount(it.name) }
        .thenBy(nullsLast()) { it.originalCreatedAtMillis ?: parseInstant(it.createdTime)?.toEpochMilliseconds() }
        .thenBy { it.path.length }
        .thenBy { it.path }
        .thenBy { it.id }

// 扩展名前的「 (1)」「(2)」「 - Copy」「 - 副本」「 copy」，可以连着好几个
private val COPY_MARKER = Regex("""\s*\([0-9]{1,3}\)|\s*-\s*(?:copy\b|副本|复制)|\s+copy\b""", RegexOption.IGNORE_CASE)

private fun mayEndWithCopyMarker(stem: String): Boolean {
    if (stem.length < 3) return false
    return when (stem[stem.lastIndex]) {
        ')', 'y', 'Y', '本', '制' -> true
        else -> false
    }
}

internal fun copyMarkerCount(name: String): Int {
    var stem = name.substringBeforeLast('.')
    var count = 0
    while (mayEndWithCopyMarker(stem)) {
        val last = COPY_MARKER.findAll(stem).lastOrNull()?.takeIf { it.range.last == stem.lastIndex } ?: return count
        stem = stem.removeRange(last.range)
        count++
    }
    return count
}

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
private val VERSION_CANDIDATE_ORDER: Comparator<VersionCandidate> =
    compareByDescending<VersionCandidate> { it.file.size }.thenBy { it.file.path }

private fun versionGroups(
    all: List<Pair<ScannedFile, DuplicateFile>>,
    identical: List<DuplicateGroup>,
    rootName: String?,
): List<DuplicateGroup> {
    val representativeOf = HashMap<String, String>(identical.size * 2)
    val copiesOf = HashMap<String, Int>(identical.size)
    for (i in 0 until identical.size) {
        val group = identical[i]
        val keptId = group.keptId!!
        val rows = group.rows
        for (j in 0 until rows.size) {
            representativeOf[rows[j].file.id] = keptId
        }
        copiesOf[keptId] = rows.size - 1
    }

    val byFolder = LinkedHashMap<String, MutableList<Pair<ScannedFile, DuplicateFile>>>()
    for (i in 0 until all.size) {
        val pair = all[i]
        byFolder.getOrPut(pair.first.folderPath) { ArrayList() }.add(pair)
    }
    val byKey = LinkedHashMap<String, MutableList<VersionCandidate>>()
    for ((folderPath, members) in byFolder) {
        var hasVideo = false
        for (i in 0 until members.size) {
            if (members[i].second.isVideo) {
                hasVideo = true
                break
            }
        }
        if (!hasVideo) continue
        val analyzed = analyzeFolder(folderPath, members, rootName)
        for (i in 0 until analyzed.size) {
            val candidate = analyzed[i]
            val id = candidate.file.id
            if ((representativeOf[id] ?: id) == id) {
                byKey.getOrPut(candidate.key) { ArrayList(2) }.add(candidate)
            }
        }
    }

    val groups = ArrayList<DuplicateGroup>()
    for (members in byKey.values) {
        if (members.size <= 1) continue
        var distinctHashes = 0
        var firstHash: String? = null
        for (i in 0 until members.size) {
            val h = members[i].hash.ifBlank { members[i].file.id }
            if (firstHash == null) {
                firstHash = h
                distinctHashes = 1
            } else if (h != firstHash) {
                distinctHashes = 2
                break
            }
        }
        if (distinctHashes <= 1) continue
        val sorted = members.sortedWith(VERSION_CANDIDATE_ORDER)
        val tagsOf = HashMap<String, List<String>>(sorted.size)
        for (i in 0 until sorted.size) {
            val c = sorted[i]
            tagsOf[c.file.id] = versionTags(c.parsed)
        }
        val firstTags = tagsOf.getValue(sorted[0].file.id)
        val common = HashSet<String>(firstTags.size)
        for (i in 0 until firstTags.size) {
            val tag = firstTags[i]
            var inAll = true
            for (j in 1 until sorted.size) {
                if (tag !in tagsOf.getValue(sorted[j].file.id)) {
                    inAll = false
                    break
                }
            }
            if (inAll) common.add(tag)
        }
        val rows = ArrayList<DuplicateRow>(sorted.size)
        for (i in 0 until sorted.size) {
            val candidate = sorted[i]
            val candidateTags = tagsOf.getValue(candidate.file.id)
            val details = if (common.isEmpty()) candidateTags else candidateTags.filterNot { it in common }
            rows.add(
                DuplicateRow(
                    file = candidate.file,
                    details = details,
                    sameCopies = copiesOf[candidate.file.id] ?: 0,
                ),
            )
        }
        groups.add(DuplicateGroup(DuplicateKind.VERSIONS, members[0].key, members[0].title, rows, keptId = null))
    }
    groups.sortWith(VERSION_GROUP_ORDER)
    return groups
}

private fun analyzeFolder(
    folderPath: String,
    members: List<Pair<ScannedFile, DuplicateFile>>,
    rootName: String?,
): List<VersionCandidate> {
    val dirs = listOfNotNull(rootName) + folderPath.split('/').filter { it.isNotEmpty() }
    val prefix = dirs.joinToString("/")
    var cachedDirSeasonComputed = false
    var cachedDirSeason: Int? = null
    fun folderSeason(): Int? {
        if (!cachedDirSeasonComputed) {
            cachedDirSeason = seasonFromDirectories(dirs)
            cachedDirSeasonComputed = true
        }
        return cachedDirSeason
    }
    // 同目录的字幕、图片也一起送进去：广告、样片、附件的判定要看兄弟文件
    val inputs = ArrayList<dev.piko.shared.naming.MediaFileInput>(members.size)
    for (i in 0 until members.size) {
        val file = members[i].first.file
        inputs.add(file.toMediaFileInput(if (prefix.isEmpty()) file.name else "$prefix/${file.name}"))
    }
    val batch = analyzeMediaBatch(inputs)
    val result = ArrayList<VersionCandidate>()
    for (index in 0 until members.size) {
        val (scanned, file) = members[index]
        if (!file.isVideo) continue
        val location = batch.locate(index) ?: continue
        val parsed = batch.parsed[index]
        val identity = episodeIdentity(parsed, location, ::folderSeason, scanned.file.name) ?: continue
        result.add(VersionCandidate(file, scanned.file.hash, parsed, identity.first, identity.second))
    }
    return result
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
    folderSeason: () -> Int?,
    fileName: String,
): Pair<String, String>? {
    val work = location.work
    val entry = location.entry
    return when (work.kind) {
        WorkKind.AV -> {
            val av = entry.av ?: return null
            val label = if (av.part == null) av.code else "${av.code} ${av.part}"
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
                    val season = episode.season ?: folderSeason() ?: 1
                    // v2 是修正版，Beta 这类却可能是另一集（Steins;Gate 的 23β），只有前者算同一集
                    val variant = episode.version?.takeUnless(::isRevisionVersion)?.lowercase().orEmpty()
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

private fun isRevisionVersion(s: String): Boolean {
    if (s.length < 2 || (s[0] != 'v' && s[0] != 'V')) return false
    for (i in 1 until s.length) {
        if (s[i] !in '0'..'9') return false
    }
    return true
}

// 前后不能紧挨字母或数字：1920x1080 的 1920、2160p、校验码「[AB2019CD]」里的 2019 都不算年份
private val YEAR = Regex("""(?<![0-9A-Za-z×])((?:19|20)[0-9]{2})(?![0-9A-Za-z×])""")

/** 文件名里最后一个像年份的数。取最后一个：「Blade Runner 2049 (2017)」的年份在后面。 */
internal fun releaseYear(fileName: String): Int? {
    val stem = fileName.substringBeforeLast('.')
    if (stem.length < 4 || ("19" !in stem && "20" !in stem)) return null
    return YEAR.findAll(stem).lastOrNull()?.groupValues?.get(1)?.toInt()
}

private val SEASON_PATTERNS = listOf(
    Regex("""(?i)\bseason\s*([0-9]{1,2})\b"""),
    Regex("""(?i)\b([0-9]{1,2})(?:st|nd|rd|th)\s+season\b"""),
    Regex("""(?i)(?:^|[^a-z0-9])s([0-9]{1,2})(?:$|[^a-z0-9])"""),
    Regex("""第\s*([0-9]{1,2})\s*[季期]"""),
)
private val CHINESE_SEASON = Regex("""第\s*([一二三四五六七八九十])\s*[季期]""")
private const val CHINESE_DIGITS = "一二三四五六七八九十"

/** 最近一层写明季号的目录给出的季号。 */
internal fun seasonFromDirectories(dirs: List<String>): Int? {
    for (i in dirs.lastIndex downTo 0) {
        val dir = dirs[i]
        SEASON_PATTERNS.firstNotNullOfOrNull { it.find(dir)?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }
        if ('第' in dir) {
            CHINESE_SEASON.find(dir)?.let { return CHINESE_DIGITS.indexOf(it.groupValues[1]) + 1 }
        }
    }
    return null
}

/** 行上用于区分版本的标签：字幕组、分辨率、编码、来源、字幕语言与集号版本。 */
private fun versionTags(parsed: ParsedName): List<String> = buildList {
    parsed.episode?.version?.let { add(it) }
    val tags = parsed.tags
    for (i in 0 until tags.size) {
        val text = tags[i].text
        if (text !in this) add(text)
    }
}

// endregion

internal fun ScannedFile.toDuplicateFile(): DuplicateFile = DuplicateFile(
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
    originalCreatedAtMillis = file.params["piko_original_created_at"]?.toLongOrNull(),
)
