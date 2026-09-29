package dev.piko.shared.rename

/**
 * 参与批量重命名的一项。只取名称、所在目录与时间，逻辑不依赖 FileStat，便于离线测试。
 * 时间是接口原样返回的 RFC 3339 字符串，取不到时为空。
 */
data class RenameSource(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val createdTime: String = "",
    val modifiedTime: String = "",
)

/**
 * 批量重命名的一环：给一批条目逐个算出新名称。预览、冲突检查与执行只认规则算出的「原名 → 新名」，
 * 不关心名称从何而来，所以规则来源可以替换或串接（查找替换、去掉共同前后缀，以后还有按解析结果整理）。
 *
 * 规则拿到的是整批而不是单个名称：计数器这类规则的结果取决于条目在批次里的先后。
 */
fun interface RenameRule {
    /** [names] 与 [items] 一一对应，是上一环给出的名称；返回同样长度的新名称，不改的项原样返回。 */
    fun apply(items: List<RenameSource>, names: List<String>): List<String>
}

/** 依次套用 [rules]，第一环拿到的是原名。 */
fun applyRules(items: List<RenameSource>, rules: List<RenameRule>): List<String> =
    rules.fold(items.map { it.name }) { names, rule ->
        rule.apply(items, names).also { require(it.size == items.size) { "规则返回的名称数与条目数不符" } }
    }

/**
 * 拆成主名与扩展名（含句点）。文件夹没有扩展名。
 * 只认最后一段短的 ASCII 字母数字为扩展名：「Movie.2020 高清版」的最后一段不是扩展名，按最后一个句点拆的话
 * 「只改主名」会把「 2020 高清版」当扩展名留着不动。以句点开头且只有这一个句点的（如 .nfo）整个算主名。
 */
fun splitExtension(name: String, isFolder: Boolean): Pair<String, String> {
    if (isFolder) return name to ""
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name to ""
    val extension = name.substring(dot + 1)
    val looksLikeExtension = extension.length in 1..MAX_EXTENSION_LENGTH &&
        extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    return if (looksLikeExtension) name.substring(0, dot) to name.substring(dot) else name to ""
}

private const val MAX_EXTENSION_LENGTH = 10
