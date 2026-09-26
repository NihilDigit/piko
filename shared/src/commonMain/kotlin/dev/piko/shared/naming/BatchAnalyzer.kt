package dev.piko.shared.naming

import dev.piko.data.repository.NaturalOrder

/**
 * 一批文件的整体分析：磁链解析出的文件树，或网盘里一个目录下的文件。
 *
 * 路径可以带目录，也可以只有文件名（网盘里被拍平的旧数据）：没有目录时分区只看文件名里的标记。
 * 输出作品 → 分区 → 条目，字幕与外挂音轨挂在视频下，次要文件单列并注明原因。
 */
fun analyzeMediaBatch(files: List<MediaFileInput>): MediaBatch = BatchAnalyzer(files).run()

private class Item(val index: Int, val path: String, val size: Long, knownKind: FileKind?) {
    val dirs: List<String> = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
    val name: String = path.substringAfterLast('/')
    val folder: String = dirs.joinToString("/")
    var parsed: ParsedName = parseMediaName(name, knownKind)
    val kind: FileKind get() = parsed.fileKind

    var secondary: SecondaryReason? = null
    var attachedTo: Item? = null
    var attachmentKind: AttachmentKind? = null
    var discRoot: String? = null

    var workKey: String = ""
    var workTitle: String? = null
    var workKind: WorkKind = WorkKind.UNKNOWN
    var section: Section = Section.MAIN
    var labelOverride: String? = null
    var dirSection: Section? = null
    var dirAuthoritative: Boolean = false
    var sectionDirDepth: Int = -1

    val isVideoLike: Boolean get() = kind == FileKind.VIDEO || kind == FileKind.DISC_IMAGE

    /** 按集号组织的文件：视频与字幕。音轨、图片即便成了正文也逐个成条目。 */
    val isEpisodic: Boolean get() = isVideoLike || kind == FileKind.SUBTITLE || discRoot != null
}

// 序号多在结尾，也有夹在中间的：「绿裙子  IMG_5845 (1) 6669」
private val COPY_MARKER = Regex("""\s*\((\d{1,2})\)""")

private val WHITESPACE_RUN = Regex("""\s+""")

// [发布组] 作品名 [条目名] 其后全是方括号
private val BRACKET_ENTRY = Regex("""^\[([^\]]+)\]\s*([^\[\]]+?)\s*\[([^\]]+)\]((?:\s*\[[^\]]*\])*)\s*$""")
private val TRAILING_SEASON = Regex("""(?i)\s+(?:season\s*\d{1,2}|s\d{1,2})$""")
private val BRACKET_SEGMENT = Regex("""\[([^\]]*)\]""")

// 「22 标题」：开头一到三位数字、空格、再接标题
private val LEADING_SEQUENCE = Regex("""^(\d{1,3})\s+(\S.*?)(?:\.[^.]+)?$""")

private class BatchAnalyzer(inputs: List<MediaFileInput>) {
    private val items = inputs.mapIndexed { index, input -> Item(index, input.path.replace('\\', '/').trim('/'), input.size, input.kind) }
    private val meanings = HashMap<String, DirectoryMeaning>()

    private fun meaning(dir: String) = meanings.getOrPut(dir) { directoryMeaning(dir) }

    fun run(): MediaBatch {
        markDiscs()
        applyDirectories()
        markExplicitSecondary()
        markAdVideos()
        attachByStem()
        attachAvByCode()
        classifyByFolderKind()
        assignWorks()
        adoptBracketEntries()
        rejectCopyMarkers()
        rejectUploaderNumbering()
        alignSiblingNames()
        nameOpaqueFiles()
        numberSameTimes()
        validateWeakEpisodes()
        adoptOrphans()
        inferMovies()
        attachSubtitlesByEpisode()
        return build()
    }

    // region 原盘

    /** BDMV/、VIDEO_TS/ 之上的目录是一张盘，盘里的所有文件合成一个条目，主文件取最大的流。 */
    private fun markDiscs() {
        items.forEach { item ->
            val at = item.dirs.indexOfFirst { it.uppercase() in DISC_STRUCTURE_DIRS }
            if (at >= 0) item.discRoot = item.dirs.take(at).joinToString("/")
        }
    }

    // endregion

    private fun applyDirectories() {
        items.forEach { item ->
            for (depth in item.dirs.indices.reversed()) {
                val meaning = meaning(item.dirs[depth])
                if (item.secondary == null && meaning.secondary != null && item.discRoot == null) item.secondary = meaning.secondary
                if (item.dirSection == null && meaning.section != null) {
                    item.dirSection = meaning.section
                    item.dirAuthoritative = meaning.authoritative
                    item.sectionDirDepth = depth
                }
            }
        }
    }

    private fun markExplicitSecondary() {
        items.forEach { item ->
            if (item.discRoot != null || item.secondary != null) return@forEach
            item.secondary = when {
                item.kind == FileKind.LINK || item.kind == FileKind.PROGRAM -> SecondaryReason.AD
                isAdName(item.name) -> SecondaryReason.AD
                item.kind == FileKind.FONT -> SecondaryReason.FONTS
                item.kind == FileKind.ARCHIVE && isFontArchive(item.name) -> SecondaryReason.FONTS
                isSampleName(item.name) -> SecondaryReason.SAMPLE
                item.kind == FileKind.DISC_METADATA -> SecondaryReason.INFO
                else -> null
            }
        }
    }

    /**
     * 番号目录里夹带的引流视频：文件名没有番号，且不到同目录最大视频（有番号）的五分之一。
     * 两个条件缺一不可：单看体积会把 PV、NCOP 当成广告，单看番号会误伤无番号的正片。
     * 只看一部片子一个目录的情形：几十个番号放在一起的合集目录里，没有番号的小视频是正常收藏，不是夹带
     */
    private fun markAdVideos() {
        items.filter { it.isVideoLike && it.secondary == null && it.discRoot == null }.groupBy { it.folder }.values.forEach { videos ->
            val largest = videos.maxByOrNull { it.size } ?: return@forEach
            if (largest.parsed.av == null) return@forEach
            if (videos.mapNotNull { it.parsed.av?.code }.distinct().size > 1) return@forEach
            videos.forEach { video ->
                if (video.parsed.av == null && video.size * 5 < largest.size) video.secondary = SecondaryReason.AD
            }
        }
    }

    // region 附件

    private fun contentVideos() = items.filter { it.isVideoLike && it.secondary == null && it.discRoot == null }

    /**
     * 字幕、外挂音轨、同名封面：名字以「视频名去掉扩展名 + .」开头即配上。多个视频都能配上时取名字最长的；
     * 同名视频在不同目录时优先同目录。字幕不看目录，有的组把字幕放在 Subs/ 下；音轨与封面只认同目录，
     * 否则原声 CD 的「01.flac」会挂到别处的「01.mkv」上。
     */
    private fun attachByStem() {
        val byStem = contentVideos().groupBy { it.name.substringBeforeLast('.') }
        items.forEach { item ->
            if (item.secondary != null || item.discRoot != null) return@forEach
            val kind = when (item.kind) {
                FileKind.SUBTITLE -> AttachmentKind.SUBTITLE
                FileKind.AUDIO -> AttachmentKind.AUDIO_TRACK
                FileKind.IMAGE -> AttachmentKind.COVER
                else -> return@forEach
            }
            var stem = item.name.substringBeforeLast('.')
            while (true) {
                val candidates = byStem[stem]?.filter { kind == AttachmentKind.SUBTITLE || it.folder == item.folder }.orEmpty()
                if (candidates.isNotEmpty()) {
                    item.attachedTo = candidates.firstOrNull { it.folder == item.folder } ?: candidates.first()
                    item.attachmentKind = kind
                    return@forEach
                }
                // 封面只认完全同名，「Show [01].jpg」不该挂到「Show [01].mkv」以外的地方
                if (kind == AttachmentKind.COVER || '.' !in stem) return@forEach
                stem = stem.substringBeforeLast('.')
            }
        }
    }

    /** 番号字幕「ABC-123-zh.srt」与封面「abc00123pl.jpg」按番号配对。 */
    private fun attachAvByCode() {
        val byCode = contentVideos().filter { it.parsed.av != null }.groupBy { it.parsed.av!!.code }
        items.forEach { item ->
            if (item.attachedTo != null || item.secondary != null || item.discRoot != null) return@forEach
            val av = item.parsed.av ?: return@forEach
            val kind = when (item.kind) {
                FileKind.SUBTITLE -> AttachmentKind.SUBTITLE
                FileKind.IMAGE -> AttachmentKind.COVER
                else -> return@forEach
            }
            val videos = byCode[av.code] ?: return@forEach
            item.attachedTo = videos.firstOrNull { it.parsed.av?.part == av.part } ?: videos.first()
            item.attachmentKind = kind
        }
    }

    // endregion

    /**
     * 先按类型再比数量：每个目录里视频与图片谁多谁是正文，少数一方的图片、零散音频、压缩包算次要。
     * 视频本身不因类型成为次要。文档在有媒体内容的批次里算说明文件。
     */
    private fun classifyByFolderKind() {
        val hasMedia = items.any { it.secondary == null && (it.isVideoLike || it.kind == FileKind.AUDIO || it.kind == FileKind.IMAGE || it.discRoot != null) }
        items.filter { it.secondary == null && it.attachedTo == null && it.discRoot == null }.groupBy { it.folder }.values.forEach { folder ->
            val videos = folder.count { it.isVideoLike }
            val images = folder.count { it.kind == FileKind.IMAGE }
            val audio = folder.count { it.kind == FileKind.AUDIO }
            val archives = folder.count { it.kind == FileKind.ARCHIVE }
            val primary = when {
                videos > 0 && videos >= images && videos >= audio -> FileKind.VIDEO
                images > 0 && images >= audio -> FileKind.IMAGE
                audio > 0 -> FileKind.AUDIO
                archives > 0 -> FileKind.ARCHIVE
                else -> FileKind.SUBTITLE
            }
            folder.forEach { item ->
                val isContent = when (item.kind) {
                    FileKind.VIDEO, FileKind.DISC_IMAGE, FileKind.SUBTITLE -> true
                    FileKind.IMAGE -> primary == FileKind.IMAGE
                    FileKind.AUDIO -> primary == FileKind.AUDIO || item.dirSection == Section.OTHER
                    FileKind.ARCHIVE -> primary == FileKind.ARCHIVE || item.dirSection == Section.OTHER
                    FileKind.DOCUMENT -> !hasMedia
                    else -> false
                }
                if (!isContent) item.secondary = if (item.kind == FileKind.DOCUMENT || item.kind == FileKind.DISC_METADATA) SecondaryReason.INFO else SecondaryReason.EXTRA
            }
        }
    }

    // region 作品与分区

    private fun contentItems() = items.filter { it.secondary == null && it.attachedTo == null && (it.discRoot == null || isDiscPrimary(it)) }

    private val discPrimary: Map<String, Item> by lazy {
        items.filter { it.discRoot != null }.groupBy { it.discRoot!! }.mapValues { (_, members) ->
            val streams = members.filter { it.isVideoLike }
            (streams.ifEmpty { members }).maxBy { it.size }
        }
    }

    private fun isDiscPrimary(item: Item) = item.discRoot != null && discPrimary[item.discRoot] === item

    private fun assignWorks() {
        contentItems().forEach { item ->
            val parsed = item.parsed
            when {
                item.discRoot != null -> assignDisc(item)
                // 音轨、图集这类非视频正文不按集号解析：「01. unified perspective.flac」的「01」是曲目号，
                // 几张 CD 的 01 会被并成同一条目。作品名取所在目录，行标题就是文件名
                !item.isEpisodic -> {
                    val title = titleFromDirectories(item)
                    item.workKind = if (title != null) WorkKind.SERIES else WorkKind.UNKNOWN
                    item.workTitle = title
                    item.workKey = title?.let { "s:" + workKeyOf(it) } ?: "?"
                    item.labelOverride = item.name.substringBeforeLast('.')
                }
                parsed.kind == NameKind.AV -> {
                    item.workKind = WorkKind.AV
                    item.workTitle = parsed.av!!.code
                    item.workKey = "av:" + parsed.av.code
                }
                parsed.kind == NameKind.EPISODE || parsed.kind == NameKind.STANDALONE -> {
                    val title = parsed.title ?: titleFromDirectories(item)
                    item.workKind = WorkKind.SERIES
                    item.workTitle = title
                    item.workKey = "s:" + (title?.let(::workKeyOf) ?: "")
                }
                else -> {
                    item.workKind = WorkKind.UNKNOWN
                    item.workKey = "?"
                }
            }
            item.section = resolveSection(item)
        }
    }

    private fun assignDisc(item: Item) {
        val root = item.discRoot!!.split('/').filter { it.isNotEmpty() }
        val disc = root.lastOrNull()?.let(::parseDiscName)
        val title = disc?.title ?: root.dropLast(1).asReversed().firstNotNullOfOrNull { dir ->
            dir.takeUnless { meaning(it).neutral }?.let { parseDiscName(it).title }
        }
        item.workKind = WorkKind.SERIES
        item.workTitle = title
        item.workKey = "s:" + (title?.let(::workKeyOf) ?: "")
        item.labelOverride = disc?.number?.let { "Disc $it" } ?: root.lastOrNull() ?: item.name
    }

    /** 文件名没有作品名时（「Ending 27.mkv」「181.mp4」），取最近一个携带作品名的目录。 */
    private fun titleFromDirectories(item: Item): String? {
        for (dir in item.dirs.asReversed()) {
            val meaning = meaning(dir)
            if (meaning.neutral || meaning.section != null || meaning.secondary != null) continue
            parseSeriesStem(dir).title?.let { return it }
        }
        return null
    }

    private fun resolveSection(item: Item): Section {
        val fromName = item.parsed.section
        val fromDir = item.dirSection
        return when {
            fromDir != null && item.dirAuthoritative -> fromDir
            fromDir != null -> fromName?.takeIf { it in REFINABLE } ?: fromDir
            fromName != null -> fromName
            item.workKind == WorkKind.UNKNOWN -> Section.OTHER
            else -> Section.MAIN
        }
    }

    /**
     * 结尾的「(1)」「(2)」多半是浏览器或网盘给重名文件加的序号，不是集号。两种情形改回无集号：
     * 同目录里有去掉序号后同名的文件（archlinux.iso 与 archlinux(1).iso）；或者这部「作品」只有它一个文件
     * （「…_source(1).mp4」一个一个都不同名）。一串「(1)」到「(42)」而没有本体的，仍按集号处理。
     */
    private fun rejectCopyMarkers() {
        val stems = items.map { it.name.substringBeforeLast('.') }.toSet()
        contentItems().filter { it.workKind == WorkKind.SERIES && it.parsed.episode != null }
            .groupBy { it.workKey }.values.forEach { group ->
                group.forEach { item ->
                    val stem = item.name.substringBeforeLast('.')
                    val marker = COPY_MARKER.findAll(stem).lastOrNull() ?: return@forEach
                    if (marker.groupValues[1].toInt() != item.parsed.episode!!.number) return@forEach
                    val base = stem.removeRange(marker.range).trim()
                    if (group.size > 1 && base !in stems) return@forEach
                    // 标题取洗过的名字：「NIUC.NET@1 (1)」的标题是「1」，不是「NIUC.NET@1」
                    val title = item.parsed.title ?: stripSiteNoise(base)
                    item.parsed = item.parsed.copy(kind = NameKind.STANDALONE, episode = null, title = title, label = title)
                    item.workTitle = title
                    item.workKey = "s:" + workKeyOf(title)
                }
            }
    }

    /**
     * 「SP01 标题甲」「SP02 标题乙」…：上传者给一批互不相干的短片编的号，不是某部作品的特别篇。
     * 同一目录里三部以上作品各只有一个条目、都在同一个非正片分区、标题各不相同，就是这种情形，
     * 改回独立文件，标题取整个名字。动画里的 SP 与所属作品同名，不会各成一部。
     *
     * 纯数字开头的「22 标题甲」「23 标题乙」同理。单个文件名的解析不把开头的数字当集号（「86 Eighty-Six」
     * 「7 Seeds」是作品名），于是「22 Cyberfxxk 2077」只剩结尾的 2077 可选，被当成了第 2077 集。
     * 同目录三个以上文件都这样开头、后面的标题各不相同，开头的数字就是上传者的编号，结尾的数字属于标题
     */
    private fun rejectUploaderNumbering() {
        contentItems().filter { it.workKind == WorkKind.SERIES && it.isVideoLike }
            .groupBy { it.folder }.values.forEach { folderItems ->
                val numbered = folderItems.mapNotNull { item -> LEADING_SEQUENCE.find(item.name)?.let { item to it.groupValues[2] } }
                if (numbered.size >= 3 && numbered.map { it.second.lowercase() }.distinct().size == numbered.size) {
                    numbered.map { it.first }
                        .filter { it.parsed.episode != null && it.parsed.confidence == Confidence.LOW }
                        .forEach(::makeStandalone)
                }
                val works = folderItems.groupBy { it.workKey }.values.filter { it.size == 1 }.map { it.single() }
                works.filter { it.section != Section.MAIN && it.parsed.episode != null }
                    .groupBy { it.section }.values
                    .filter { group -> group.size >= 3 && group.map { it.workTitle }.distinct().size == group.size }
                    .flatten()
                    .forEach(::makeStandalone)
            }
    }

    /**
     * 兄弟文件对齐（见 SiblingAlignment）。补上逐文件解析看不出的两件事：
     * - 同一番号的一簇文件里逐个在变的小数字是分段号：「(素人…)_4k6」「_6fhd」「ei3_n」的分段写在描述后面或粘着别的词，
     *   后缀扫描认不出，第 5、6 段就成了同一条目的两个版本，合并版本时只剩一个能点开；
     * - 逐文件只拿到弱集号或没有集号的剧集，按簇里的编号槽位定集号，不变的前缀作作品名。
     * 高置信度的集号（S01E02、[01]）与已经分得开的分段不动。
     */
    private fun alignSiblingNames() {
        contentItems().filter { it.isVideoLike && it.discRoot == null && !it.parsed.opaque && !it.parsed.timed && it.labelOverride == null }
            .groupBy { it.folder }.values.forEach { folderItems ->
                alignSiblings(folderItems.map { stripSiteNoise(it.name.substringBeforeLast('.')) }, minSize = 2).forEach { cluster ->
                    val members = cluster.members.map { folderItems[it] }
                    val sequence = cluster.sequence ?: return@forEach
                    val codes = members.map { it.parsed.av?.code }.distinct()
                    if (codes.size == 1 && codes.single() != null) {
                        numberParts(members, sequence)
                    } else if (members.size >= 3 && members.all { it.workKind == WorkKind.SERIES } && !cluster.timeVariesBeforeSequence) {
                        numberEpisodes(members, sequence, alignedTitle(cluster.head) ?: alignedTitle(cluster.tail), "${folderItems.first().folder}/${cluster.members.first()}")
                    }
                }
            }
    }

    private fun numberParts(members: List<Item>, sequence: List<String>) {
        // 已有的分段能把成员分开，说明后缀扫描认对了，不动
        if (members.map { it.parsed.av!!.part }.distinct().size == members.size) return
        members.forEachIndexed { index, item ->
            val av = item.parsed.av!!.copy(part = sequence[index].trimStart('0').ifEmpty { "0" })
            item.parsed = item.parsed.copy(av = av, label = "${av.code} ${av.part}")
        }
    }

    /**
     * 整簇定为一部作品。作品名为 null 时不起作品头，但成员仍共用一个作品键：退回各自的解析标题的话，
     * 标题里带着各不相同的编号（「0499-Misa」「0501-Misa」），又碎成一个文件一部
     */
    private fun numberEpisodes(members: List<Item>, sequence: List<String>, alignedTitle: String?, clusterKey: String) {
        if (members.all { it.parsed.episode != null && it.parsed.confidence == Confidence.HIGH }) return
        // 逐文件解析给出的作品名彼此一致、且被簇里的不变文字包含时沿用它：它认得发布组方括号与标签，
        // 拼出来的文字认不得（「【三个小乖乖】3个…」会多出半个括号）。不被包含的是解析错了，
        // 如「www.98T.la@胶衣1」一致解析成「www」，而不变文字是「胶衣」
        val agreed = members.map { it.parsed.title }.distinct().singleOrNull()
        val title = agreed?.takeIf { alignedTitle == null || workKeyOf(it) in workKeyOf(alignedTitle) } ?: alignedTitle
        val workKey = title?.let { "s:" + workKeyOf(it) } ?: "s:aligned:$clusterKey"
        members.forEachIndexed { index, item ->
            val text = sequence[index]
            val episode = EpisodeNumber(number = text.toInt(), text = text, decimal = null, lastText = null, season = null, suffix = "", version = null)
            item.parsed = item.parsed.copy(kind = NameKind.EPISODE, episode = episode, title = title, label = text)
            item.workTitle = title
            item.workKey = workKey
        }
    }

    /**
     * 「[发布组] 作品名 [条目名][技术标签]」：VCB-Studio 一类的发布把条目名单独放在一个方括号里。
     * 条目名是数字时就是集号，逐文件解析认得；是文字时（[Survival Camp]、[Making Documentary]、[IV01]、[CM]）
     * 逐文件解析读不出来，特别篇、访谈、特典便各自成了一部作品，或者全都叫「Movie」。
     *
     * 同一目录里几个文件共享发布组与作品名，或者作品名就是已有剧集的名字（季号与剧场版标记不算），
     * 就把那个方括号当条目名，归进同名的作品。分区先看条目名里的标记词，再看所在目录，都没有就是特别篇
     */
    private fun adoptBracketEntries() {
        val candidates = contentItems().filter { it.isVideoLike && it.discRoot == null && it.parsed.av == null }.mapNotNull { item ->
            val match = BRACKET_ENTRY.matchEntire(stripSiteNoise(item.name.substringBeforeLast('.'))) ?: return@mapNotNull null
            val (group, rawTitle, entry, rest) = match.destructured
            val techTail = BRACKET_SEGMENT.findAll(rest).map { it.groupValues[1] }.toList()
            val entryIsName = entry.isNotBlank() && !scanTags(entry).isTagText && entry.any(Char::isLetter)
            if (!entryIsName || techTail.none { scanTags(it).isTagText }) return@mapNotNull null
            // 作品名那一段里有明确的集号时，方括号是认不得的标记而不是条目名：
            // 「[smzase&Y-Raws] Saijo no Osewa - S01E08 - [CHI_JPN][WebRip …]」的条目是第 8 集，不是 CHI_JPN。
            // 只认高置信度的：「Show Season 2」的 2 也会被解析成弱集号
            val titleEpisode = parseSeriesStem(rawTitle)
            if (titleEpisode.episode != null && titleEpisode.confidence == Confidence.HIGH) return@mapNotNull null
            BracketEntry(item, group.trim(), rawTitle.trim(), entry.trim())
        }
        // 只认正片的剧集：「[Menu01]」也带编号，它的作品名是拆掉季号的「Yuru Camp」，第二季的 SPs 就会被并过去
        val seriesTitles = contentItems()
            .filter { it.workKind == WorkKind.SERIES && it.section == Section.MAIN && it.parsed.episode != null && it.workTitle != null }
            .associateBy({ workKeyOf(it.workTitle!!) }, { it.workTitle!! })
        candidates.groupBy { Triple(it.item.folder, it.group.lowercase(), workKeyOf(baseTitle(it.title))) }.values.forEach { family ->
            val key = workKeyOf(baseTitle(family.first().title))
            val known = seriesTitles[key]
            if (known == null && family.size < 2) return@forEach
            // 没有同名剧集可并时照原样写作品名：第二季的 SPs 目录里没有正片，季号拆掉就只剩「Yuru Camp Season」
            val title = known ?: family.first().title
            family.forEach { candidate ->
                val item = candidate.item
                item.workKind = WorkKind.SERIES
                item.workTitle = title
                item.workKey = "s:" + workKeyOf(title)
                item.section = entrySection(item, candidate.entry)
                item.labelOverride = candidate.entry
                item.parsed = item.parsed.copy(kind = NameKind.STANDALONE, episode = null, title = title, label = candidate.entry)
            }
        }
    }

    /**
     * 条目名里的标记词优先，但服从专属目录；通用目录只细化「Special」：SPs/ 里写着 Special 的是特典，
     * 与 resolveSection 对文件名标记的处理一致
     */
    private fun entrySection(item: Item, entry: String): Section {
        val fromEntry = bracketEntrySection(entry)
        val fromDir = item.dirSection
        return when {
            fromDir != null && item.dirAuthoritative -> fromDir
            fromEntry == null -> fromDir ?: Section.SPECIAL
            fromDir != null && fromEntry == Section.SPECIAL -> fromDir
            else -> fromEntry
        }
    }

    private class BracketEntry(val item: Item, val group: String, val title: String, val entry: String)

    /** 作品名去掉季号与剧场版标记，才能与剧集的作品名对上：「Yuru Camp Season 2」「Yuru Camp Movie」都是「Yuru Camp」。 */
    private fun baseTitle(title: String): String {
        val withoutSeason = title.replace(TRAILING_SEASON, "").trim()
        val parsed = parseSeriesStem(withoutSeason)
        return parsed.title?.takeIf { it.isNotBlank() } ?: withoutSeason
    }

    /** 改回独立文件，标题取整个名字（洗掉网址与频道推广）。 */
    private fun makeStandalone(item: Item) {
        val title = stripSiteNoise(item.name.substringBeforeLast('.')).replace(WHITESPACE_RUN, " ").trim()
        item.parsed = item.parsed.copy(kind = NameKind.STANDALONE, episode = null, section = null, title = title, label = title)
        item.section = Section.MAIN
        item.workTitle = title
        item.workKey = "s:" + workKeyOf(title)
    }

    /**
     * 没有任何可读信息的自动生成名（Telegram 导出、哈希）按目录里的顺序编号：「视频 1」「视频 2」。
     * 按文件名的自然顺序而不是界面上当前的排序编号，换个排序方式，同一个文件的编号不变。
     * 每个都自成一部独立作品，与其他独立文件一起平铺。
     */
    private fun nameOpaqueFiles() {
        contentItems().filter { it.parsed.opaque && it.workKind == WorkKind.SERIES }
            .groupBy { it.folder to opaqueNoun(it.kind) }
            .forEach { (key, group) ->
                group.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.forEachIndexed { index, item ->
                    val title = "${key.second} ${index + 1}"
                    item.parsed = item.parsed.copy(title = title, label = title)
                    item.workTitle = title
                    item.workKey = "s:opaque:${key.first}/$title"
                }
            }
    }

    /**
     * 同一分钟里拍的几段（VID_20260913_090829_383 与 _470）解出同一个时间，按文件名顺序加序号区分。
     * 账号加时间的名字（「From-某频道-…Z」）作品是账号，只给行标题加序号：转存机器人一分钟能转几十条
     */
    private fun numberSameTimes() {
        contentItems().filter { it.parsed.timed && it.workKind == WorkKind.SERIES }
            .groupBy { Triple(it.folder, it.parsed.title, it.parsed.label) }
            .values.filter { it.size > 1 }
            .forEach { group ->
                group.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.forEachIndexed { index, item ->
                    if (item.parsed.title != item.parsed.label) {
                        item.parsed = item.parsed.copy(label = "${item.parsed.label} (${index + 1})")
                        return@forEachIndexed
                    }
                    val title = "${item.parsed.title} (${index + 1})"
                    item.parsed = item.parsed.copy(title = title, label = title)
                    item.workTitle = title
                    // workKeyOf 会忽略括号里的内容，三个就又合成一部了
                    item.workKey = "s:timed:${item.folder}/$title"
                }
            }
    }

    private fun opaqueNoun(kind: FileKind): String = when (kind) {
        FileKind.VIDEO, FileKind.DISC_IMAGE -> "视频"
        FileKind.IMAGE -> "图片"
        else -> "文件"
    }

    /**
     * 裸数字集号的核对：同一作品里有两个以上这类文件而数字全相同，说明数字是作品名的一部分
     * （「Mob Psycho 100」的正片与特典），改回无集号。
     */
    private fun validateWeakEpisodes() {
        contentItems().filter { it.parsed.confidence == Confidence.LOW && it.parsed.episode != null && it.workKind == WorkKind.SERIES }
            .groupBy { it.workKey }.values.forEach { group ->
                if (group.size < 2) return@forEach
                if (group.map { it.parsed.episode!!.number }.distinct().size > 1) return@forEach
                group.forEach { item ->
                    val parsed = item.parsed
                    val title = listOfNotNull(parsed.title, parsed.episode!!.text).joinToString(" ")
                    item.parsed = parsed.copy(kind = NameKind.STANDALONE, episode = null, title = title, label = title)
                    item.workTitle = title
                    item.workKey = "s:" + workKeyOf(title)
                }
            }
    }

    /**
     * 没有正片、自成一部「作品」的文件并回正片所在的作品，三种情形依次尝试：
     * 1. 「Deji Meets Girl - Cast Talk」按「 - 」切开，前半段是已有作品，后半段就是行标题；
     * 2. 放在特典一类目录里（Extras/Soundtrack OST/…），往上找到的第一个只含一部正片作品的目录，归给那部作品；
     * 3. 作品名以某部正片作品的名字加空格开头（「One Piece Wano Arc」之于「One Piece」），且并入后行标题不冲突。
     * 自身带正片的作品不动：「Steins;Gate Fuka Ryouiki no Deja vu」有自己的本篇与 PV，并入后两边的「PV 01」会撞在一起。
     */
    private fun adoptOrphans() {
        val content = contentItems()
        val mainWorks = content.filter { it.workKind == WorkKind.SERIES && it.section == Section.MAIN && it.parsed.kind == NameKind.EPISODE }
            .groupBy { it.workKey }
        val orphanWorks = content.filter { it.workKind == WorkKind.SERIES && it.workKey !in mainWorks }.groupBy { it.workKey }
        orphanWorks.values.forEach { orphans ->
            val hasOwnMain = orphans.any { it.section == Section.MAIN && it.isVideoLike }
            orphans.forEach { item -> adoptByDash(item, mainWorks) || !hasOwnMain && adoptByFolder(item, mainWorks) }
        }
        val remaining = content.filter { it.workKind == WorkKind.SERIES && it.workKey !in mainWorks }.groupBy { it.workKey }
        remaining.values.forEach { orphans ->
            if (orphans.any { it.section == Section.MAIN }) return@forEach
            val title = orphans.first().workTitle ?: return@forEach
            val owner = mainWorks.entries.filter { (_, members) ->
                val ownerTitle = members.first().workTitle ?: return@filter false
                title.length > ownerTitle.length && title.startsWith("$ownerTitle ", ignoreCase = true)
            }.singleOrNull() ?: return@forEach
            val taken = content.filter { it.workKey == owner.key }.map { it.section to it.parsed.label }.toSet()
            if (orphans.any { (it.section to it.parsed.label) in taken }) return@forEach
            orphans.forEach { item ->
                item.workKey = owner.key
                item.workTitle = owner.value.first().workTitle
            }
        }
    }

    private fun adoptByDash(item: Item, mainWorks: Map<String, List<Item>>): Boolean {
        val pieces = item.workTitle?.split(" - ") ?: return false
        for (cut in 1 until pieces.size) {
            val key = "s:" + workKeyOf(pieces.take(cut).joinToString(" - "))
            val owner = mainWorks[key] ?: continue
            item.workKey = key
            item.workTitle = owner.first().workTitle
            if (item.parsed.kind == NameKind.STANDALONE) item.labelOverride = pieces.drop(cut).joinToString(" - ")
            return true
        }
        return false
    }

    private fun adoptByFolder(item: Item, mainWorks: Map<String, List<Item>>): Boolean {
        if (item.sectionDirDepth < 0 || item.section == Section.MAIN) return false
        for (depth in item.sectionDirDepth downTo 0) {
            val parent = item.dirs.take(depth).joinToString("/")
            val owners = mainWorks.filterValues { members -> members.any { parent.isEmpty() || it.folder == parent || it.folder.startsWith("$parent/") } }
            if (owners.isEmpty()) continue
            val owner = owners.entries.singleOrNull() ?: return false
            if (item.parsed.kind == NameKind.STANDALONE && item.labelOverride == null) item.labelOverride = item.workTitle
            item.workKey = owner.key
            item.workTitle = owner.value.first().workTitle
            return true
        }
        return false
    }

    /**
     * 没有集号的大文件与分集放在一起时，是剧场版：命运石之门合集里的「负荷领域的既视感」
     * 就只有一个名字，没有「Movie」字样。门槛是同批正片分集体积中位数的三倍。
     */
    private fun inferMovies() {
        val content = contentItems()
        val episodic = content.filter { it.workKind == WorkKind.SERIES && it.section == Section.MAIN && it.parsed.episode != null && it.isVideoLike }
        if (episodic.size < 3) return
        val median = episodic.map { it.size }.sorted()[episodic.size / 2]
        if (median <= 0) return
        // 发布组须与分集一致：动画发布里那部电影与分集出自同一组。自拍合集里的文件没有发布组，
        // 几段「(1)…(8)」的小分段拉低中位数，其余整段视频就全成了剧场版
        val group = episodic.mapNotNull { it.parsed.group }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
        content.filter {
            it.workKind == WorkKind.SERIES && it.section == Section.MAIN && it.parsed.kind == NameKind.STANDALONE &&
                it.discRoot == null && it.isVideoLike && it.size >= median * 3 && it.parsed.group == group
        }.forEach { it.section = Section.MOVIE }
    }

    /**
     * 字幕与视频不同名时按（作品、分区、集号）配对：「English Subtitles/Deji Meets Girl  04.en.ass」
     * 对「[Shiniori-Raws] Deji Meets Girl - 04 (BD …).mkv」。
     */
    private fun attachSubtitlesByEpisode() {
        val content = contentItems()
        val videos = content.filter { it.isVideoLike && it.parsed.episode != null }
            .groupBy { Triple(it.workKey, it.section, episodeKey(it.parsed.episode!!)) }
        content.filter { it.kind == FileKind.SUBTITLE && it.parsed.episode != null }.forEach { sub ->
            val target = videos[Triple(sub.workKey, sub.section, episodeKey(sub.parsed.episode!!))]?.firstOrNull() ?: return@forEach
            sub.attachedTo = target
            sub.attachmentKind = AttachmentKind.SUBTITLE
        }
    }

    // endregion

    // region 组装

    private fun build(): MediaBatch {
        val content = contentItems()
        val attachments = items.filter { it.attachedTo != null && it.secondary == null }.groupBy { it.attachedTo!! }
        val discMembers = items.filter { it.discRoot != null && !isDiscPrimary(it) }.groupBy { it.discRoot!! }

        val works = content.groupBy { it.workKey }.map { (key, members) -> buildWork(key, members, attachments, discMembers) }
            .sortedWith(
                compareBy<MediaWork> { it.kind == WorkKind.UNKNOWN }
                    .thenBy { it.title == null }
                    .thenComparator { a, b -> NaturalOrder.compare(a.title.orEmpty(), b.title.orEmpty()) },
            )
        val roles = items.map { item ->
            when {
                item.secondary != null -> FileRole.SECONDARY
                item in content -> FileRole.CONTENT
                else -> FileRole.ATTACHMENT
            }
        }
        val secondary = items.filter { it.secondary != null }.map { SecondaryFile(it.index, it.secondary!!) }
        return MediaBatch(works = works, secondary = secondary, parsed = items.map { it.parsed }, roles = roles)
    }

    private fun buildWork(
        key: String,
        members: List<Item>,
        attachments: Map<Item, List<Item>>,
        discMembers: Map<String, List<Item>>,
    ): MediaWork {
        val kind = members.first().workKind
        val title = members.firstNotNullOfOrNull { it.workTitle }
        // 公共标签取正片视频共有的：原声 CD 没有 HEVC、1080p，JySzE 的无字幕 OP 文件名里没有组名，
        // 把它们算进来会让正片每一行都重复这些标签
        val videos = members.filter { it.isVideoLike }
        val tagged = videos.filter { it.section == Section.MAIN }.ifEmpty { videos }.ifEmpty { members }
        val common = if (kind == WorkKind.UNKNOWN) emptySet() else tagged.map { it.parsed.tags.toSet() }.reduce { acc, tags -> acc intersect tags }
            .filterNot { it.pinned }.toSet()
        val order = entryOrder(members.associate { it.index to it.path })

        val sections = members.groupBy { it.section }.entries.sortedBy { it.key.ordinal }.map { (section, sectionItems) ->
            val seasons = sectionItems.mapNotNull { it.parsed.episode?.season }.distinct()
            // 分区里全都是 v2 时版本不必逐行显示；有 v1 有 v2、或只有个别 Beta 版时才显示
            val versions = sectionItems.mapNotNull { it.parsed.episode }.map { it.version }
            val sharedVersion = versions.isNotEmpty() && versions.distinct().size == 1
            val entries = sectionItems.groupBy { entryKey(it) }.map { (entryKey, files) ->
                val ordered = files.sortedByDescending { it.size }
                val first = ordered.first()
                MediaEntry(
                    key = entryKey,
                    label = labelOf(first, showSeason = seasons.size > 1, showVersion = !sharedVersion),
                    section = section,
                    episode = first.parsed.episode?.takeIf { first.isEpisodic },
                    av = first.parsed.av,
                    files = ordered.map { file -> entryFile(file, common, attachments, discMembers) },
                )
            }.sortedWith(order)
            WorkSection(section, entries)
        }
        return MediaWork(
            key = key,
            title = title,
            kind = kind,
            commonTags = common.sortedBy { it.kind.ordinal },
            sections = sections,
        )
    }

    private fun entryFile(item: Item, common: Set<MediaTag>, attachments: Map<Item, List<Item>>, discMembers: Map<String, List<Item>>): EntryFile {
        val attached = attachments[item].orEmpty().map { Attachment(it.index, it.attachmentKind!!, it.parsed.language) }
        val disc = item.discRoot?.let { root -> discMembers[root].orEmpty().map { Attachment(it.index, AttachmentKind.DISC_FILE, null) } }.orEmpty()
        return EntryFile(
            index = item.index,
            name = item.parsed,
            tags = item.parsed.tags.filter { it !in common || it.pinned }.sortedBy { it.kind.ordinal },
            attachments = (attached + disc).sortedBy { it.index },
        )
    }

    private fun entryKey(item: Item): String {
        val parsed = item.parsed
        return when {
            item.discRoot != null -> "disc|" + item.discRoot
            item.workKind == WorkKind.UNKNOWN || !item.isEpisodic -> "file|" + item.index
            parsed.av != null -> "av|" + parsed.av.code + "|" + parsed.av.part.orEmpty()
            parsed.episode != null -> "ep|" + item.section + "|" + (parsed.label ?: "").lowercase() + "|" + episodeKey(parsed.episode)
            else -> "one|" + item.section + "|" + (item.labelOverride ?: parsed.label ?: parsed.title ?: item.name).lowercase()
        }
    }

    private fun labelOf(item: Item, showSeason: Boolean, showVersion: Boolean): String? {
        item.labelOverride?.let { return it }
        val parsed = item.parsed
        val episode = parsed.episode
        return when {
            item.workKind == WorkKind.UNKNOWN -> null
            parsed.av != null -> parsed.label
            episode != null -> buildString {
                val base = parsed.label ?: episode.shortText
                if (showSeason && episode.season != null && base == episode.shortText) {
                    append('S').append(episode.season.toString().padStart(2, '0')).append('E')
                }
                append(base)
                if (showVersion && episode.version != null) append(' ').append(episode.version)
            }
            else -> parsed.label ?: parsed.title
        }
    }

    // endregion

    private companion object {
        val REFINABLE = setOf(Section.PREVIEW, Section.CREDITLESS, Section.MENU, Section.MOVIE, Section.OVA)

        /**
         * 有集号的先按标记再按集号（「Ending 01-03」排在「Opening 01-03」之前，不交错），番号按番号，
         * 其余按完整路径的自然顺序。无集号的条目不按行标题排：几张原声 CD 的曲目都从「01.」开始，
         * 按标题排会交错，按路径才是一张一张排下来。
         */
        fun entryOrder(paths: Map<Int, String>): Comparator<MediaEntry> = Comparator { a, b ->
            val ea = a.episode
            val eb = b.episode
            when {
                a.av != null && b.av != null -> NaturalOrder.compare(a.label.orEmpty(), b.label.orEmpty())
                ea != null && eb != null -> NaturalOrder.compare(a.primary.name.marker.orEmpty(), b.primary.name.marker.orEmpty()).takeIf { it != 0 }
                    ?: ea.compareTo(eb).takeIf { it != 0 }
                    ?: NaturalOrder.compare(a.label.orEmpty(), b.label.orEmpty())
                ea != null -> -1
                eb != null -> 1
                else -> NaturalOrder.compare(paths[a.primary.index].orEmpty(), paths[b.primary.index].orEmpty())
            }
        }
    }
}

private fun episodeKey(episode: EpisodeNumber): String =
    "${episode.season ?: ""}:${episode.number}.${episode.decimal.orEmpty()}${episode.suffix}~${episode.last ?: ""}|${episode.version.orEmpty()}"
