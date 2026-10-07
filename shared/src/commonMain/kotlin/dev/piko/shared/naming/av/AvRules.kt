package dev.piko.shared.naming.av

// 番号识别的规则表。放 Kotlin 文件而不放 JSON：AndroidRegexGuardTest 只扫 .kt，写进 JSON 的正则躲过它，
// 到 Android 的 ICU 上才炸。改了规则就把版本加一，日志与将来按版本迁移的数据据此分辨。

/** 规则表的版本。识别结果有变（番号字符串、分段写法、旗标）时加一。 */
const val AV_RULES_VERSION = 1

// 番号只在文件名开头找：站点前缀（xxx.com@、[site.net]、123456_site_）与标签方括号
// （[中文字幕]、[HD]）剥掉之后，番号必须是第一个记号。在全文里找会把动画文件名里的
// 「Naruto_198」「HEVC-10」当成番号。

internal val FC2 = Regex("""^FC2[\s_-]*(?:PPV[\s_-]*)?([0-9]{5,8})(?![0-9])""", RegexOption.IGNORE_CASE)

// 「fc2765224」「FC980638」：FC 后面紧跟的数字整体是编号，其中打头的 2 不是 FC2 的 2。
// 网盘里人手整理过的目录名（FC2-PPV-2765224）给出了这个答案；FC2 的 2 只在后面有分隔或 PPV 时才算前缀
internal val FC_GLUED = Regex("""^FC([0-9]{6,8})(?![0-9])""", RegexOption.IGNORE_CASE)

// 日期加序号的站点：一本道 092415_001、加勒比 021014-540，站点名在前或在后都有
internal val ONE_PONDO = Regex("""^(?:1pon(?:do)?|一本道)[\s_-]*([0-9]{6})[-_]([0-9]{3})(?![0-9])""", RegexOption.IGNORE_CASE)
internal val CARIB = Regex("""^(?:carib(?:bean(?:com)?|com)?|加勒比)[\s_-]*([0-9]{6})[-_]([0-9]{3})(?![0-9])""", RegexOption.IGNORE_CASE)
internal val CARIB_TRAILING = Regex("""^([0-9]{6})[-_]([0-9]{3})[-_]carib""", RegexOption.IGNORE_CASE)

// Heydouga 的写法最乱：hey4017_244、heydouga 4017-175、HeyDouga-4017-228；「しろハメ」是 4017 这个频道的名字
internal val HEYDOUGA = Regex("""^(?:しろハメ[\s_-]*(?:hey(?:douga)?)?|hey(?:douga)?)[\s_-]*([0-9]{4})[\s_-]+([0-9]{2,5})(?![0-9])""", RegexOption.IGNORE_CASE)

// 没写站点名的日期番号：月日年六位加三位序号，如 123014_949、072815-931。月日须合法，
// 否则「202401-001」这类编号也会被当成番号
internal val BARE_DATED = Regex("""^((?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{2})([-_])([0-9]{3})(?![0-9])""")

// 字母段里夹一位数字（MCB3DBD-47），或数字段前带一个字母（MKBD-S94）。只收全大写，
// 与「Show-S01」这类季号写法区分
internal val DIGIT_IN_PREFIX = Regex("""^([A-Z]{2,5}[0-9][A-Z]{2,5})-([0-9]{2,4})(?![0-9])""")
internal val LETTER_IN_NUMBER = Regex("""^([A-Z]{2,6})-([A-Z][0-9]{2,4})(?![0-9]|E[0-9])""")

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

// 连写：abcd00123pl、ABCD123C。没有分隔符时与普通单词更难区分，只接受 DMM 式的五位补零，
// 或数字后紧跟已知后缀（C 中字、pl/ps 封面）的写法。补零须是两个：三位编号补成五位才是 DMM 的写法，
// 「clhlh06822」这种账号名只是恰好以 0 开头
internal val GLUED_DMM = Regex("""^([A-Za-z]{2,6})(00[0-9]{3})(?![0-9])""")
internal val GLUED_SUFFIXED = Regex("""^([A-Za-z]{2,6})([0-9]{3})(C|pl|ps)$""")

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
internal val DOMAIN = Regex("""(?i)^(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|me|tv|cc|xyz|top|vip|club|fun|app|live|info|io|co|in|site|online|pw|ru|jp|tw|la|cn)$""")
internal val LEADING_BRACKET = Regex("""^\s*[\[【(]([^\]】)]*)[\]】)]\s*""")

internal val UNCENSORED_WORDS = setOf("u", "uncensored", "ucensored", "uncen", "leak", "leaked", "无码", "無碼", "無修正", "无修正", "破解", "流出")
internal val CHINESE_WORDS = setOf("c", "ch", "chs", "cht", "中文字幕", "中字", "sub", "zh")
internal val PART = Regex("""^(?:cd|part|pt|disc|disk)[\s.]?([0-9]{1,2})$""", RegexOption.IGNORE_CASE)
internal val GLUED_PART_LETTERS = setOf("A", "B", "D", "E", "F")
internal val PART_LETTER = Regex("""^[A-Fa-f]$""")
internal val PART_DIGIT = Regex("""^0?[1-9]$""")
// Heydouga 的分段写成 fhd1、hd2
internal val PART_QUALITY = Regex("""^f?hd([0-9]{1,2})$""", RegexOption.IGNORE_CASE)
internal val IGNORED_SUFFIXES = setOf("full", "hd", "pl", "ps", "jp", "mosaic", "high", "low")

internal val SITE_WORDS = setOf("3xplanet", "jav", "javhd", "thz", "sis001", "hjd2048", "fc2", "tokyo hot", "tokyo-hot")

// 开头方括号里的发布标记，剥掉但不算站点
internal val RELEASE_MARKS = setOf("nodrm")

// 片名两端的标记与附注：「【無】」「『完全顔出し』」「~ vol.48 ~」在前，「※特典高画質」「【個人撮影】」在后。
// 无码、中字已经成了标签，这里不再重复
// 开头只去掉短标记（【無】『完全顔出し』【個撮】），「【 あの人気シリーズ 】」这种长的是片名的一部分
internal val LEADING_MARK = Regex("""^\s*(?:[【『\[(（]\s*[^】』\])）\s]{1,7}\s*[】』\])）]|~[^~]{1,12}~|vol\.?\s*[0-9]{1,3}\s*~?)\s*""", RegexOption.IGNORE_CASE)
internal val TRAILING_NOTE = Regex("""\s*※.*$""")
internal val TRAILING_MARK = Regex("""\s*[【『\[(（][^】』\])）]{1,12}[】』\])）]\s*$""")
