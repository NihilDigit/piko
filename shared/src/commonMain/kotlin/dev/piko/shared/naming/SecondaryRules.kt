package dev.piko.shared.naming

/**
 * 广告文件的特征。只收明确的特征：推广词、快捷方式与安装包、逐字加空格的标题、只有域名的文件名。
 * 「体积小」不在其列，PV、NCOP 都很短；体积只在番号目录里与「没有番号」一起用，见 [analyzeMediaBatch]。
 */
private val AD_KEYWORDS = listOf(
    "永久地址", "最新地址", "最新網址", "最新网址", "最新位址", "位址獲取", "地址发布", "地址發布", "发布页", "發布頁", "发布器", "發布器",
    "宣传", "宣傳", "资源推荐", "資源推薦", "下载地址", "下載地址", "免翻墙", "免翻牆", "直播", "注册送", "註冊送",
    "代币", "代幣", "手游", "手遊", "约炮", "約炮", "约啪", "約啪", "品茶", "担保", "擔保", "收藏不迷路", "官方指定",
    "無料で楽しめる", "最新情报", "最新情報", "社区", "社區", "论坛", "論壇", "色中色", "草榴", "sexinsex",
    "第一会所", "第一會所", "影视联盟", "影視聯盟", "javsubs", "付费频道", "付費頻道", "立即下载", "立即下載", "签到", "簽到",
    "免费玩", "免費玩", "游戏链接", "遊戲鏈接", "手机网址", "手機網址", "网址发布", "網址發布", "推特", "电报", "電報",
)

private val LEADING_1024 = Regex("""^[\s_(（]*1024""")
private val DOMAIN_STEM = Regex("""(?i)^(?:www\.)?[a-z0-9-]+\.(?:com|net|org|me|tv|cc|xyz|top|vip|club|fun|app|live|info|io|co|site|online|pw|ru|la|tw)(?:$|[-\s_])""")
private val SAMPLE_WORD = Regex("""(?i)(^|[^a-z])sample([^a-z]|$)""")

internal fun isAdName(name: String): Boolean {
    val lower = name.lowercase()
    if (AD_KEYWORDS.any { it in lower }) return true
    val stem = name.substringBeforeLast('.').ifEmpty { name }
    if (LEADING_1024.containsMatchIn(stem)) return true
    if (DOMAIN_STEM.containsMatchIn(stem) && '@' !in stem) return true
    return isSpacedOut(stem)
}

/**
 * 「精 彩 视 频 免 费 看」：逐字加空格是引流视频躲关键词过滤的写法，正常片名不会这样写。
 */
private fun isSpacedOut(stem: String): Boolean {
    val pieces = stem.trim().split(' ').filter { it.isNotEmpty() }
    if (pieces.size < 4) return false
    val singles = pieces.count { it.length == 1 }
    val cjkSingles = pieces.count { it.length == 1 && isCjk(it[0]) }
    return cjkSingles >= 3 && singles * 10 >= pieces.size * 7
}

internal fun isSampleName(name: String): Boolean = SAMPLE_WORD.containsMatchIn(name.substringBeforeLast('.'))

internal fun isFontArchive(name: String): Boolean = "font" in name.lowercase() || "字体" in name || "字體" in name
