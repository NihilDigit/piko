package dev.piko.shared.naming.av

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
