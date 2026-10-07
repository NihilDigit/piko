package dev.piko.shared.naming.av

// 番号识别的规则表。放 Kotlin 文件而不放 JSON：AndroidRegexGuardTest 只扫 .kt，写进 JSON 的正则躲过它，
// 到 Android 的 ICU 上才炸。改了规则就把版本加一，日志与将来按版本迁移的数据据此分辨。
//
// 部分写法参照 MetaTube（metatube-sdk-go 的 common/number/number.go，Copyright the MetaTube authors，
// Apache License 2.0）：开头的 HD-、FHD-，hhb 一类压制后缀，10musume、pacopacomama、muramura、
// caribbeancompr 的厂牌名。这里只取规则的思路，正则按本文件的约定重写。

/** 规则表的版本。识别结果有变（番号字符串、分段写法、旗标）时加一。 */
const val AV_RULES_VERSION = 2

// 番号只在文件名开头找：站点前缀（xxx.com@、kcf9.com-、[site.net]、123456_site_）与标签方括号
// （[中文字幕]、[HD]）剥掉之后，番号必须是第一个记号。在全文里找会把动画文件名里的
// 「Naruto_198」「HEVC-10」当成番号。

internal val FC2 = Regex("""^FC2[\s_-]*(?:PPV[\s_-]*)?([0-9]{5,8})(?![0-9])""", RegexOption.IGNORE_CASE)

// 「fc2765224」「FC980638」：FC 后面紧跟的数字整体是编号，其中打头的 2 不是 FC2 的 2。
// 网盘里人手整理过的目录名（FC2-PPV-2765224）给出了这个答案；FC2 的 2 只在后面有分隔或 PPV 时才算前缀
internal val FC_GLUED = Regex("""^FC([0-9]{6,8})(?![0-9])""", RegexOption.IGNORE_CASE)

/**
 * 日期加序号的无码厂牌：一本道 092415_001、加勒比 021014-540、10musume 010120_01。厂牌名在前或在后都有。
 * [joiner] 是规范写法里日期与序号之间的符号，照 MDC 的厂牌字典：加勒比用连字符，其余用下划线。
 * 前后两种写法各一条正则，厂牌名后面不许紧跟字母，「caribbeancompr」才不会被「carib」截走。
 */
internal class DatedLabel(val label: String, names: String, serial: String, val joiner: Char) {
    val leading = Regex("""^(?:$names)[\s_-]*([0-9]{6})[-_]($serial)(?![0-9])""", RegexOption.IGNORE_CASE)
    val trailing = Regex("""^([0-9]{6})[-_]($serial)[-_](?:$names)(?![a-z])""", RegexOption.IGNORE_CASE)
}

internal val DATED_LABELS = listOf(
    DatedLabel("1PON", "1pon(?:do)?|一本道", "[0-9]{3}", '_'),
    DatedLabel("CARIBPR", "carib(?:bean)?(?:com)?pr", "[0-9]{3}", '_'),
    DatedLabel("CARIB", "carib(?:bean(?:com)?|com)?|加勒比", "[0-9]{3}", '-'),
    DatedLabel("10MU", "10mu(?:sume)?", "[0-9]{2}", '_'),
    DatedLabel("PACO", "paco(?:pacomama)?", "[0-9]{3}", '_'),
    DatedLabel("MURA", "mura(?:mura)?", "[0-9]{3}", '_'),
)

// Heydouga 的写法最乱：hey4017_244、heydouga 4017-175、HeyDouga-4017-228；「しろハメ」是 4017 这个频道的名字
internal val HEYDOUGA = Regex("""^(?:しろハメ[\s_-]*(?:hey(?:douga)?)?|hey(?:douga)?)[\s_-]*([0-9]{4})[\s_-]+([0-9]{2,5})(?![0-9])""", RegexOption.IGNORE_CASE)

// 没写站点名的日期番号：月日年六位加三位序号，如 123014_949、072815-931。月日须合法，
// 否则「202401-001」这类编号也会被当成番号
internal val BARE_DATED = Regex("""^((?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{2})([-_])([0-9]{3})(?![0-9])""")

// 字母段里夹一位数字（MCB3DBD-47），或数字段前带一个字母（MKBD-S94）。只收全大写，
// 与「Show-S01」这类季号写法区分
internal val DIGIT_IN_PREFIX = Regex("""^([A-Z]{2,5}[0-9][A-Z]{2,5})-([0-9]{2,4})(?![0-9])""")
internal val LETTER_IN_NUMBER = Regex("""^([A-Z]{2,6})-([A-Z][0-9]{2,4})(?![0-9]|E[0-9])""")

// 以一位数字开头的厂牌：3DSVR-0123。泛化成「数字加字母」会吃进「2nd_001」这类序数词，所以逐个列出。
// 编号照 DMM 的写法保留四位
internal val DIGIT_LEADING_LABEL = Regex("""^(3DSVR)[-_]?([0-9]{4})(?![0-9])""", RegexOption.IGNORE_CASE)

// 四位补零的连写：xss0057
internal val GLUED_PADDED = Regex("""^([A-Za-z]{2,6})(0[0-9]{3})(?![0-9])""")

// 素人系列带数字前缀：104DANDAN-015、300MIUM-123、259LUXU-1234
internal val NUMBERED_LABEL = Regex("""^([0-9]{3}[A-Za-z]{2,8})[-_]([0-9]{3,4})(?![0-9])""")

// Tokyo-Hot：单字母 n 或 k 加四位数，如 n0421。单字母前缀太宽，四位数后面紧跟字母的不算，
// 否则「k1080p」会被认成 K1080
internal val TOKYO_HOT = Regex("""^([nk])([0-9]{4})(?=$|[\s_.\-\[(])""", RegexOption.IGNORE_CASE)

internal val HEYZO = Regex("""^HEYZO[\s_-]*(?:HD[\s_-]*)?([0-9]{3,5})(?![0-9])""", RegexOption.IGNORE_CASE)

// 分隔写法：字母段与数字段之间有 - 或 _。字母段须全大写或全小写，「Naruto_198」这种首字母大写的
// 普通单词不是番号
internal val SEPARATED = Regex("""^([A-Za-z]{2,6})[-_]([0-9]{2,5})(?![0-9])""")

// 连写：abcd00123pl、ABCD123C、abcd123c。没有分隔符时与普通单词更难区分，只接受 DMM 式的五位补零，
// 或数字后紧跟已知后缀（C 中字、pl/ps 封面）的写法。补零须是两个：三位编号补成五位才是 DMM 的写法，
// 「clhlh06822」这种账号名只是恰好以 0 开头
internal val GLUED_DMM = Regex("""^([A-Za-z]{2,6})(00[0-9]{3})(?![0-9])""")
internal val GLUED_SUFFIXED = Regex("""^([A-Za-z]{2,6})([0-9]{3})([Cc]|pl|ps)$""")

// 开头的画质前缀：「HD-ABC-123」「FHD-ABC-123」。只在剥掉之后认得出番号时才剥
internal val QUALITY_PREFIX = Regex("""^(?:f?hd|sd|4k)[-_]""", RegexOption.IGNORE_CASE)

// 「image-2026.04.01」：数字后面接着月和日，是日期，不是番号
internal val DATE_CONTINUATION = Regex("""[._-](0[1-9]|1[0-2])[._-][0-9]{2}(?![0-9])""")

internal val NOT_A_PREFIX = setOf(
    "HEVC", "AVC", "AAC", "FLAC", "DTS", "AC", "EAC", "MP", "FHD", "UHD", "HD", "SD", "BD", "DVD", "WEB", "CD",
    "DISC", "DISK", "VOL", "PART", "PT", "EP", "SP", "OP", "ED", "NCOP", "NCED", "PV", "CM", "OVA", "OAD", "MA",
    "HI", "MAIN", "BIT", "FPS", "BS", "AT", "NHK", "TVK", "BSP", "CR", "NF", "TV", "TS", "OST", "VER", "VTS",
    "VIDEO", "AUDIO", "SEASON", "EPISODE", "MPEG", "MENU", "SCAN", "IMG", "DSC", "BOX", "SET", "CH", "MOVIE",
    "ENDING", "OPENING", "TRACK", "CAM", "DB", "DBZ", "DBS", "DBGT", "AT-X",
)

internal val LOOSE = Regex("""^([A-Za-z]{2,6})\s?([0-9]{2,5})(?![0-9])""")
internal val SUFFIX_PIECE = Regex("""([-_.\s]*)([^-_.\s]+)""")
internal val LEADING_TAG_BRACKET = Regex("""[\[【]([^\]】]*)[\]】]""")
internal val SITE_AT = Regex("""^(.*)@(?=[A-Za-z0-9])""")
internal val SITE_NUMBERED = Regex("""^[0-9]{3,8}_([A-Za-z0-9.]+)_""")
internal val LEADING_BRACKET = Regex("""^\s*[\[【(]([^\]】)]*)[\]】)]\s*""")

// 番号专用的旗标后缀。无码与中字的通用写法（uncensored、无码、破解、中文字幕、chs、cht）由标签词表认，
// 这里只列番号片才有的：单字母的 U、C，「leak」，以及只在番号上才算无码的「流出」（见 matchAv）。
// chs、cht 在词表里是简体、繁体字幕，对番号片都意味着中字。「sub」只说有字幕，不说是中文，不算中字
internal val UNCENSORED_SUFFIXES = setOf("u", "leak", "leaked", "流出")
internal val CHINESE_SUFFIXES = setOf("c", "ch", "zh")
// 单字母的 U、C 与 CH、UC 只在连字符、下划线之后算旗标；空格之后是片名的一部分（「ABC-123 C Model」）
internal val SHORT_FLAGS = setOf("u", "c", "ch", "uc")

internal val PART = Regex("""^(?:cd|part|pt|disc|disk)[\s.]?([0-9]{1,2})$""", RegexOption.IGNORE_CASE)
internal val GLUED_PART_LETTERS = setOf("A", "B", "D", "E", "F")
internal val PART_LETTER = Regex("""^[A-Fa-f]$""")
internal val PART_DIGIT = Regex("""^0?[1-9]$""")
// 压制标记带着分段号：Heydouga 的 fhd1、hd2，以及 hhb1
internal val PART_QUALITY = Regex("""^(?:f?hd|hhb)([0-9]{1,2})$""", RegexOption.IGNORE_CASE)
// 片名之后、扩展名之前的分段：「ABC-123-C 片名-CD1」，规范名就这样写
internal val TITLE_PART = Regex("""[\s_-]+(?:cd|part)[\s.]?([0-9]{1,2})$""", RegexOption.IGNORE_CASE)
// 不进名字的压制后缀。hhb 是某个压制组的标记，常粘在番号后面（abc00123hhb）
internal val IGNORED_SUFFIXES = setOf("full", "hd", "pl", "ps", "jp", "mosaic", "high", "low", "hhb")

internal val SITE_WORDS = setOf("3xplanet", "jav", "javhd", "thz", "sis001", "hjd2048", "fc2", "tokyo hot", "tokyo-hot")

// 开头方括号里的发布标记，剥掉但不算站点
internal val RELEASE_MARKS = setOf("nodrm")

// 片名两端的标记与附注：「【無】」「『完全顔出し』」「~ vol.48 ~」在前，「※特典高画質」「【個人撮影】」在后。
// 无码、中字已经成了标签，这里不再重复
// 开头只去掉短标记（【無】『完全顔出し』【個撮】），「【 あの人気シリーズ 】」这种长的是片名的一部分
internal val LEADING_MARK = Regex("""^\s*(?:[【『\[(（]\s*[^】』\])）\s]{1,7}\s*[】』\])）]|~[^~]{1,12}~|vol\.?\s*[0-9]{1,3}\s*~?)\s*""", RegexOption.IGNORE_CASE)
internal val TRAILING_NOTE = Regex("""\s*※.*$""")
internal val TRAILING_MARK = Regex("""\s*[【『\[(（][^】』\])）]{1,12}[】』\])）]\s*$""")
