package dev.piko.shared.naming.av

/** 番号的类别。规范名的写法、刮削时怎么查都按它分。 */
enum class AvKind {
    /** 字母段加数字段：SSIS-123、ABC3DEF-47、3DSVR-0123。 */
    STANDARD,

    /** 素人系列，厂牌带数字前缀：300MIUM-123、259LUXU-1234。 */
    AMATEUR,

    /** FC2-PPV-1234567。 */
    FC2,

    /** 无码厂牌：一本道、加勒比、10musume、Heydouga、HEYZO、东京热，以及没写厂牌的日期番号。 */
    UNCENSORED_LABEL,
}

/**
 * 番号及其结构化标记。[code] 是归一后的番号本身：全大写，各段以连字符分隔。常规番号与素人系列的数字去掉
 * 前导零后补足三位（abc00123、ABC-0123 都是 ABC-123）；厂牌番号保留原貌（HEYZO-0123、1PON-092415_001）。
 * 站点前缀、压制标记、分段都不进 [code]。
 *
 * [prefix] 与 [number] 是 [code] 的两段：「SSIS」「123」、「FC2-PPV」「1234567」、「1PON」「092415_001」；
 * 没写厂牌的日期番号 [prefix] 为空。
 */
data class AvInfo(
    val code: String,
    val prefix: String,
    val number: String,
    val kind: AvKind,
    val uncensored: Boolean = false,
    val chineseSubtitles: Boolean = false,
    /** 分段，一律写作「CD1」「CD2」：原名里的 -1、_1、-A、part1、fhd1 都归到这里，A 是 CD1。 */
    val part: String? = null,
    /** 文件名里夹带的站点或上传者前缀，如「site.net」「3xplanet」。 */
    val site: String? = null,
    /** 认不出含义的后缀，原样保留，如「AI」「YP」。 */
    val marks: List<String> = emptyList(),
    /** 番号后面的片名，已去掉「【無】」「※特典高画質」这类标记与附注；名字里没写时为 null。 */
    val title: String? = null,
    /**
     * 中字只来自一个单独的 C（「-C」或粘着的「C」）。成套的「-A」「-B」「-C」里它是第三段，
     * 单看一个文件分不出，由 [resolveLetteredParts] 按同目录的兄弟文件定。
     */
    val letterC: Boolean = false,
) {
    private val codeLabel: String get() = listOfNotNull(code, part).joinToString(" ")

    /** 列表里的行标题：有片名就写片名，光有番号认不出是哪部；名字里没写片名时只能写番号。 */
    fun displayTitle(): String = title ?: codeLabel

    /** 行上的番号芯片，分段号也在里面。番号已经是标题时不重复。 */
    val chip: String? get() = codeLabel.takeIf { title != null }
}

/** 分段的序号，「CD2」是 2；不是这个写法时为 null。 */
val AvInfo.partNumber: Int? get() = part?.removePrefix("CD")?.toIntOrNull()

/**
 * 同一番号成套的「-A」「-B」「-C」：C 在这里是第三段，不是中字。逐个文件看分不出，要看同一批里
 * 是否还有同一番号的第一、第二段。[infos] 是同一目录里的一批，返回同样长度、改过的结果。
 */
fun resolveLetteredParts(infos: List<AvInfo?>): List<AvInfo?> {
    val partsByCode = infos.filterNotNull().groupBy { it.code }.mapValues { (_, group) -> group.mapNotNull { it.partNumber }.toSet() }
    return infos.map { info ->
        if (info == null || !info.letterC || info.part != null) return@map info
        val parts = partsByCode[info.code].orEmpty()
        if (1 in parts && 2 in parts) info.copy(part = "CD3", chineseSubtitles = false, letterC = false) else info
    }
}
