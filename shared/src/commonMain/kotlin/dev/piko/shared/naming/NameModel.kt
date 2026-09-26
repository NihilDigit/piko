package dev.piko.shared.naming

import dev.piko.data.repository.NaturalOrder

/**
 * 文件大类。比 [dev.piko.data.repository.FileCategory] 细：广告快捷方式、字体、原盘结构文件
 * 都要单独判断，归进「其他」就分不出来了。
 */
enum class FileKind { VIDEO, AUDIO, IMAGE, SUBTITLE, ARCHIVE, DOCUMENT, FONT, LINK, PROGRAM, DISC_IMAGE, DISC_METADATA }

/** 分区。声明顺序即各分区之间的展示顺序。 */
enum class Section(val label: String) {
    MAIN("正片"),
    SPECIAL("SP"),
    OVA("OVA/OAD"),
    MOVIE("剧场版"),
    PREVIEW("PV/CM"),
    CREDITLESS("NCOP/NCED"),
    BONUS("特典"),
    MENU("菜单"),
    OTHER("其他"),
}

enum class NameKind {
    /** 有集号（含 PV 01、NCOP 这类带标记的条目）。 */
    EPISODE,

    /** 只认出作品名、没有集号，如单文件剧场版。 */
    STANDALONE,

    /** 认出番号。 */
    AV,

    /** 未识别，UI 应退回原始文件名。 */
    UNKNOWN,
}

/**
 * 集号的把握。[LOW] 只有一种来源：作品名后面紧跟的裸数字，如「Deji Meets Girl  04」。
 * 这种写法与「Mob Psycho 100」无从区分，批量分析时会拿同作品的其他文件核对。
 */
enum class Confidence { HIGH, LOW }

enum class TagKind {
    GROUP, RESOLUTION, VIDEO_CODEC, BIT_DEPTH, AUDIO_CODEC, SOURCE, PLATFORM,
    SUBTITLES, AUDIO_LANGUAGE, FRAME_RATE, CENSORSHIP, EDITION,
}

data class MediaTag(val kind: TagKind, val text: String) {
    /**
     * 「中字」「无码」存在与否本身就有意义，同一批里人人都有也逐行显示，不收进作品级的公共标签。
     * 动画的「简繁」不算：同一批几乎总是一致，逐行重复只是噪声。
     */
    val pinned: Boolean
        get() = kind == TagKind.CENSORSHIP || (kind == TagKind.SUBTITLES && text == CHINESE_SUBTITLES)

    companion object {
        const val CHINESE_SUBTITLES = "中字"
        const val UNCENSORED = "无码"
    }
}

/**
 * 集号。[text] 保留原文的数字写法（「01」「0184」），行标题照原样显示；比较只看数值。
 *
 * [version] 是版本限定（v2、Beta），属于集号的一部分：同一集的 v1 与 v2、正式版与 Beta 版
 * 是两个条目，排序时无版本的在前。[suffix] 是 NCOP 19b 这类的字母分段。
 */
data class EpisodeNumber(
    val number: Int,
    val text: String,
    val decimal: String? = null,
    val lastText: String? = null,
    val season: Int? = null,
    val suffix: String = "",
    val version: String? = null,
) : Comparable<EpisodeNumber> {
    val last: Int? get() = lastText?.toIntOrNull()

    /** 不含季号与版本的集号文本，如「13.5」「952-953」「19b」。 */
    val shortText: String
        get() = buildString {
            append(text)
            decimal?.let { append('.').append(it) }
            lastText?.let { append('-').append(it) }
            append(suffix)
        }

    override fun compareTo(other: EpisodeNumber): Int = compareValuesBy(
        this, other,
        { it.season ?: -1 },
        { it.number },
        { it.decimal?.toIntOrNull() ?: -1 },
        { it.suffix },
    ).takeIf { it != 0 } ?: compareVersions(version, other.version)

    private fun compareVersions(a: String?, b: String?): Int = when {
        a == b -> 0
        a == null -> -1
        b == null -> 1
        else -> NaturalOrder.compare(a, b)
    }
}

/**
 * 番号及其结构化标记。[code] 是归一后的番号本身：全大写，各段以连字符分隔，数字保留原始
 * 位数（HEYZO-0123），只有 DMM 风格的五位补零（ssis00123）还原为 SSIS-123。
 * 站点前缀、压制标记、分段都不进 [code]。
 */
data class AvInfo(
    val code: String,
    val uncensored: Boolean = false,
    val chineseSubtitles: Boolean = false,
    /** 分段，如「CD1」「A」「2」。 */
    val part: String? = null,
    /** 文件名里夹带的站点或上传者前缀，如「site.net」「3xplanet」。 */
    val site: String? = null,
    /** 认不出含义的后缀，原样保留，如「AI」「YP」。 */
    val marks: List<String> = emptyList(),
    /** 番号后面的片名，已去掉「【無】」「※特典高画質」这类标记与附注；名字里没写时为 null。 */
    val title: String? = null,
) {
    private val codeLabel: String get() = listOfNotNull(code, part).joinToString(" ")

    /** 列表里的行标题：有片名就写片名，光有番号认不出是哪部；名字里没写片名时只能写番号。 */
    fun displayTitle(): String = title ?: codeLabel

    /** 行上的番号芯片，分段号也在里面。番号已经是标题时不重复。 */
    val chip: String? get() = codeLabel.takeIf { title != null }
}

/**
 * 单个文件名的解析结果。
 *
 * [title] 取文件名里的写法，通常是罗马音或英文，不做翻译，也不从种子标题补中文名。
 * [section] 只反映文件名里写明的分区标记，没有标记时为 null；目录给出的分区由批量分析决定。
 * [label] 是行标题用的短标签，不含季号与版本，二者是否显示取决于同批其他文件。
 */
data class ParsedName(
    val fileName: String,
    val fileKind: FileKind,
    val kind: NameKind,
    val confidence: Confidence,
    val title: String?,
    val group: String?,
    val episode: EpisodeNumber?,
    val episodeTitle: String?,
    val section: Section?,
    val marker: String?,
    val label: String?,
    val av: AvInfo?,
    val tags: List<MediaTag>,
    /** 字幕或音轨的语言后缀，归一后的显示文本，如「简日」「英」。 */
    val language: String?,
    /** 语言后缀原文，如「scjp」「zh」。 */
    val languageCode: String?,
    /** 自动生成、没有任何可读信息的名字（Telegram 导出、哈希），由批量分析按顺序编号。 */
    val opaque: Boolean = false,
    /** 行标题是从自动生成名里解出的时间，如「LINE 视频 2020-07-29 00:15」；账号加时间的名字作品名是账号。 */
    val timed: Boolean = false,
) {
    val recognized: Boolean get() = kind != NameKind.UNKNOWN
}

/** 批量分析的输入：带目录的相对路径（只有文件名也可以）与字节数，未知时传 0。 */
/**
 * [kind] 是文件名之外得知的类型，如网盘按 mime 给出的。只在名字本身认不出类型时采用：网盘里有不带扩展名的视频
 * （「… - 12 [WebRip 1080p HEVC-10bit AAC][END]」），光看名字会被当成说明文件。
 */
data class MediaFileInput(val path: String, val size: Long, val kind: FileKind? = null)

enum class FileRole {
    /** 条目的主文件。 */
    CONTENT,

    /** 挂在某个条目下：外挂字幕、外挂音轨、封面、原盘结构文件。 */
    ATTACHMENT,

    /** 次要文件，网盘列表折叠、磁链面板默认不勾选。 */
    SECONDARY,
}

enum class SecondaryReason {
    /** 广告：推广链接、宣传图、夹带的引流视频。 */
    AD,
    SAMPLE,
    SCANS,
    SCREENSHOTS,
    FONTS,
    /** nfo、txt、日志、校验文件、种子文件。 */
    INFO,
    /** 与所在目录的正文不同类的文件，如视频目录里的配图、压缩包、零散音频。 */
    EXTRA,
}

enum class AttachmentKind { SUBTITLE, AUDIO_TRACK, COVER, DISC_FILE }

data class Attachment(val index: Int, val kind: AttachmentKind, val language: String?)

/** 条目下的一个文件。同一集的不同压制（分辨率、字幕组）是同一条目下的多个文件。 */
data class EntryFile(
    val index: Int,
    val name: ParsedName,
    /** 有区分度的逐行标签：去掉了作品级的公共标签，但保留 [MediaTag.pinned] 的。 */
    val tags: List<MediaTag>,
    val attachments: List<Attachment>,
)

/**
 * 一个条目：一集、一个 PV、一个番号（的一个分段）或一张原盘。
 * [label] 是选集与列表的行标题，如「24」「25(SP)」「23 Beta」「ABC-123」；为 null 表示未识别，
 * UI 应显示完整文件名。[files] 按体积从大到小。
 */
data class MediaEntry(
    val key: String,
    val label: String?,
    val section: Section,
    val episode: EpisodeNumber?,
    val av: AvInfo?,
    val files: List<EntryFile>,
) {
    val primary: EntryFile get() = files.first()
}

data class WorkSection(val section: Section, val entries: List<MediaEntry>)

enum class WorkKind { SERIES, AV, UNKNOWN }

/**
 * 一部作品。[title] 为 null 有两种情况：[WorkKind.UNKNOWN] 收容未识别的文件；
 * [WorkKind.SERIES] 则是只认出集号、找不到作品名（如网盘里被改名为「01.mkv」的文件）。
 */
data class MediaWork(
    val key: String,
    val title: String?,
    val kind: WorkKind,
    /** 作品内所有文件共有的标签，只在作品一级显示一次。 */
    val commonTags: List<MediaTag>,
    val sections: List<WorkSection>,
)

data class SecondaryFile(val index: Int, val reason: SecondaryReason)

data class EntryLocation(val work: MediaWork, val section: WorkSection, val entry: MediaEntry)

data class MediaBatch(
    val works: List<MediaWork>,
    val secondary: List<SecondaryFile>,
    /** 与输入逐一对应。 */
    val parsed: List<ParsedName>,
    val roles: List<FileRole>,
) {
    private val locations: Map<Int, EntryLocation> by lazy {
        buildMap {
            works.forEach { work ->
                work.sections.forEach { section ->
                    section.entries.forEach { entry ->
                        val location = EntryLocation(work, section, entry)
                        entry.files.forEach { file ->
                            put(file.index, location)
                            file.attachments.forEach { put(it.index, location) }
                        }
                    }
                }
            }
        }
    }

    /** 文件所在的作品、分区与条目；次要文件返回 null。选集取同一分区的 entries 即可连播。 */
    fun locate(index: Int): EntryLocation? = locations[index]

    /**
     * 磁链面板的默认勾选：各作品正片条目的全部文件及其附件。整批没有正片时（如只有 PV 的包）
     * 勾选全部内容；未识别的视频一并勾上，宁可多选也不漏掉主体。
     */
    fun defaultSelection(): Set<Int> {
        fun MediaEntry.allIndices() = files.flatMap { file -> listOf(file.index) + file.attachments.map { it.index } }
        val main = works.filter { it.kind != WorkKind.UNKNOWN }
            .flatMap { work -> work.sections.filter { it.section == Section.MAIN }.flatMap { it.entries } }
        val unknown = works.filter { it.kind == WorkKind.UNKNOWN }.flatMap { work -> work.sections.flatMap { it.entries } }
            .filter { it.primary.name.fileKind == FileKind.VIDEO || it.primary.name.fileKind == FileKind.DISC_IMAGE }
        val chosen = (main + unknown).flatMap { it.allIndices() }
        if (chosen.isNotEmpty()) return chosen.toSet()
        return works.flatMap { work -> work.sections.flatMap { it.entries } }.flatMap { it.allIndices() }.toSet()
    }
}
