package dev.piko.shared.naming

/**
 * 论坛与分享站在文件名里留下的网址：「www.98T.la@」「kcf9.com-」「[thz.la]」「… auu32.com」。
 * 与内容无关，洗掉。欧美片方括号里的出品方（[LegalPorno.com]）不洗，那是发布组一类的信息。
 *
 * scene 名「BlackedRaw.19.05.17.Lena…」「CzechAV.SiteRip…」用点连接，形似网址而不是，所以只认
 * 已知顶级域名之后紧跟 @、-、_、空格或名字结尾的写法。不带 @ 的写法只认不会是普通单词的后缀：
 * 「Sword.Art.Online - 01」的 online 是作品名。
 */
private const val ANY_TLD = "com|net|org|la|cc|vip|xyz|top|club|cn|biz|ws|ru|jp|tw|me|tv|co|io|info|us|pw|site|online|live|fun|app|one"
private const val STRONG_TLD = "com|net|org|la|cc|vip|xyz|top|club|cn|biz|ws|ru|jp|tw"

private fun host(tlds: String) = """(?:www\.)?[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:$tlds)"""

// 「www.98T.la@」「美库meiku.vip内购首发@」：@ 之前出现网址，整段到 @ 都是站点前缀，可能叠了几层
// 网址前面只容一个不带空格与括号的短词（「美库」），否则「某人 [标签] www.98T.la@」会连人名一起洗掉
private val AT_PREFIX = Regex("""^(?:[^@\s\[\]【】()]{0,8}?${host(ANY_TLD)}[^@\s]{0,12}@\s*)+""", RegexOption.IGNORE_CASE)
// 「域名@」出现在任何位置都是论坛标记：「某人 [PornhubFans 1080p] www.98T.la@标题」
private val ANYWHERE_AT = Regex("""${host(ANY_TLD)}@\s*""", RegexOption.IGNORE_CASE)
private val LEADING = Regex("""^${host(STRONG_TLD)}(?=[-_\s])[-_\s]*""", RegexOption.IGNORE_CASE)
private val TRAILING = Regex("""[\s\-_]*${host(STRONG_TLD)}$""", RegexOption.IGNORE_CASE)
private val BRACKETED = Regex("""[\[【(]\s*(${host(ANY_TLD)})\s*[\]】)]""", RegexOption.IGNORE_CASE)

// 论坛与分享站：短域名后缀，或名字里带数字（98t.la、2048.cc、hhd800.com）。出品方多是完整单词加 .com
private val FORUM_LIKE = Regex("""(?i)\.(?:la|cc|vip|xyz|top|club|cn)$|\d""")

internal fun stripSiteNoise(stem: String): String {
    var s = stem.replace(AT_PREFIX, "").replace(ANYWHERE_AT, "")
    s = s.replace(LEADING, "")
    s = s.replace(TRAILING, "")
    s = BRACKETED.replace(s) { match ->
        val domain = match.groupValues[1]
        if (FORUM_LIKE.containsMatchIn(domain.substringBeforeLast('.').removePrefix("www.")) ||
            FORUM_LIKE.containsMatchIn(".${domain.substringAfterLast('.')}")
        ) {
            ""
        } else {
            match.value
        }
    }
    // 洗完只剩空白或标点时保留原名：「hhd800.com@」之后什么都没有的不是网址前缀
    return s.trim().takeIf { rest -> rest.any { it.isLetterOrDigit() } } ?: stem
}
