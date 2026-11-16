package dev.piko.shared.state

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr

/** 密码登录方式的账号类型。 */
enum class LoginAccountMode {
    EMAIL,
    PHONE,
}

/** 手机号登录时可选的常用国家/地区国际电话区号。 */
data class PhoneRegion(
    val isoCode: String,
    val dialCode: String,
    val zhHans: String,
    val en: String,
    val ja: String,
    val ko: String,
    val zhHant: String,
    val keywords: String = "",
) {
    val localizedName: String get() = tr(zhHans, en, ja, ko, zhHant)
}

object PhoneRegions {
    val all: List<PhoneRegion> = listOf(
        PhoneRegion("CN", "86", "中国大陆", "China (Mainland)", "中国（本土）", "중국 본토", "中國大陸", "china mainland zhongguo zg"),
        PhoneRegion("HK", "852", "中国香港", "Hong Kong", "香港", "홍콩", "中國香港", "hong kong xianggang hk"),
        PhoneRegion("MO", "853", "中国澳门", "Macau", "マカオ", "마카오", "中國澳門", "macau macao aomen mo"),
        PhoneRegion("TW", "886", "中国台湾", "Taiwan", "台湾", "대만", "中國台灣", "taiwan tw"),
        PhoneRegion("JP", "81", "日本", "Japan", "日本", "일본", "日本", "japan nihon nippon jp"),
        PhoneRegion("KR", "82", "韩国", "South Korea", "韓国", "대한민국", "韓國", "korea hanguk kr"),
        PhoneRegion("SG", "65", "新加坡", "Singapore", "シンガポール", "싱가포르", "新加坡", "singapore xinjiapo sg"),
        PhoneRegion("MY", "60", "马来西亚", "Malaysia", "マレーシア", "말레이시아", "馬來西亞", "malaysia malaixiya my"),
        PhoneRegion("US", "1", "美国 / 加拿大", "US / Canada", "アメリカ / カナダ", "미국 / 캐나다", "美國 / 加拿大", "united states america usa canada us ca"),
        PhoneRegion("GB", "44", "英国", "United Kingdom", "イギリス", "영국", "英國", "united kingdom britain england uk gb"),
        PhoneRegion("AU", "61", "澳大利亚", "Australia", "オーストラリア", "호주", "澳洲", "australia aodaliya au"),
        PhoneRegion("NZ", "64", "新西兰", "New Zealand", "ニュージーランド", "뉴질랜드", "紐西蘭", "new zealand xinxilan nz"),
        PhoneRegion("DE", "49", "德国", "Germany", "ドイツ", "독일", "德國", "germany deutschland deguo de"),
        PhoneRegion("FR", "33", "法国", "France", "フランス", "프랑스", "法國", "france faguo fr"),
        PhoneRegion("TH", "66", "泰国", "Thailand", "タイ", "태국", "泰國", "thailand taiguo th"),
        PhoneRegion("VN", "84", "越南", "Vietnam", "ベトナム", "베트남", "越南", "vietnam yuenan vn"),
        PhoneRegion("ID", "62", "印度尼西亚", "Indonesia", "インドネシア", "인도네시아", "印尼", "indonesia yinni id"),
        PhoneRegion("PH", "63", "菲律宾", "Philippines", "フィリピン", "필리핀", "菲律賓", "philippines feilvbin ph"),
        PhoneRegion("IN", "91", "印度", "India", "インド", "인도", "印度", "india yindu in"),
    )

    fun filter(query: String): List<PhoneRegion> {
        val cleaned = PhoneLoginFormat.stripInvisibleMarks(query).trim()
        if (cleaned.isEmpty()) return all
        val normalized = cleaned.lowercase()
        val codeQuery = normalized.removePrefix("+").removePrefix("00").filterNot(Char::isWhitespace)
        return all.filter { region ->
            val keywords = region.keywords.lowercase()
            val keywordTokens = keywords.split(' ').filter { it.isNotEmpty() }
            region.dialCode == codeQuery ||
                (codeQuery.isNotEmpty() && region.dialCode.startsWith(codeQuery)) ||
                "+${region.dialCode}".contains(normalized) ||
                region.isoCode.lowercase().startsWith(normalized) ||
                region.zhHans.lowercase().contains(normalized) ||
                region.en.lowercase().contains(normalized) ||
                region.ja.lowercase().contains(normalized) ||
                region.ko.lowercase().contains(normalized) ||
                region.zhHant.lowercase().contains(normalized) ||
                keywordTokens.any { it.startsWith(normalized) } ||
                keywords.startsWith(normalized) ||
                keywords.contains(" $normalized")
        }.sortedBy { region ->
            val keywordTokens = region.keywords.lowercase().split(' ').filter { it.isNotEmpty() }
            when {
                region.dialCode == codeQuery ||
                    region.isoCode.equals(cleaned, ignoreCase = true) ||
                    keywordTokens.any { it == normalized } -> 0
                codeQuery.isNotEmpty() && region.dialCode.startsWith(codeQuery) -> 1
                else -> 2
            }
        }
    }

    /** 若输入形如 `31`、`+31`、`0031`，提取可直接使用的 1~4 位国际区号。 */
    fun customDialCodeCandidate(query: String): String? {
        val cleaned = PhoneLoginFormat.stripInvisibleMarks(query).trim()
        val withoutPrefix = when {
            cleaned.startsWith("+") -> cleaned.drop(1)
            cleaned.startsWith("00") -> cleaned.drop(2)
            else -> cleaned
        }
        val compact = withoutPrefix.filterNot(Char::isWhitespace)
        return compact.takeIf { it.length in 1..4 && it.all { c -> c in '0'..'9' } && it[0] in '1'..'9' }
    }
}

/** 拆解后的国际电话号码（[dialCode] 不含 `+`）。 */
data class ParsedPhone(
    val dialCode: String,
    val nationalNumber: String,
)

/**
 * 手机号密码登录的规范化与校验工具。
 *
 * PikPak `/v1/auth/signin` 要求手机号采用 `+<区号><号码>`（无空格或连字符）的 E.164 写法。
 */
object PhoneLoginFormat {
    /** 国际电信联盟（ITU-T E.164）固定的两位国家/地区区号集合；1 位只有 `1` 与 `7`，其余为 3 位。 */
    private val TWO_DIGIT_DIAL_CODES = setOf(
        "20", "27", "30", "31", "32", "33", "34", "36", "39",
        "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58",
        "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86",
        "90", "91", "92", "93", "94", "95", "98",
    )

    /** 国际拨号时需去掉国内长途前缀 `0` 的常用区号（例如台湾 `09...`、日本 `090...`、韩国 `010...`、英国 `07...`）。 */
    private val TRUNK_ZERO_DIAL_CODES = setOf(
        "886", "81", "82", "44", "61", "64", "60", "66", "84", "62", "63", "49", "33",
    )

    private val REGION_DIAL_BY_ISO: Map<String, String> =
        PhoneRegions.all.associate { it.isoCode.lowercase() to it.dialCode } + ("ca" to "1")

    /** 去除通讯录/系统剪贴板常附带的双向控制符与零宽字符，并把全角 `＋` 与全角数字转为 ASCII。 */
    fun stripInvisibleMarks(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                in '\u200B'..'\u200F',
                in '\u202A'..'\u202E',
                in '\u2060'..'\u2069',
                '\uFEFF',
                -> Unit
                '\uFF0B' -> append('+')
                in '\uFF10'..'\uFF19' -> append('0' + (ch.code - 0xFF10))
                else -> append(ch)
            }
        }
    }

    fun defaultDialCode(
        systemLanguageTag: String = PikoLocale.systemLanguageTag,
        effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    ): String {
        val subtags = systemLanguageTag.trim().lowercase().split('-', '_', '#').filter { it.isNotEmpty() }
        val fromRegion = subtags.drop(1).firstNotNullOfOrNull { REGION_DIAL_BY_ISO[it] }
        if (fromRegion != null) return fromRegion
        return when (effectiveLanguage) {
            AppLanguage.ZH_CN -> "86"
            AppLanguage.ZH_HANT -> "886"
            AppLanguage.JA -> "81"
            AppLanguage.KO -> "82"
            else -> "1"
        }
    }

    /** 若 [raw] 为带 `+` 或 `00` 前缀的国际电话号码，拆分为 `(dialCode, nationalNumber)`；否则返回 null。 */
    fun splitInternationalPhone(raw: String): ParsedPhone? {
        val cleaned = stripInvisibleMarks(raw).trim()
        val body = when {
            cleaned.startsWith("+") -> cleaned.drop(1)
            cleaned.startsWith("00") -> cleaned.drop(2)
            else -> return null
        }
        if (body.isEmpty()) return null
        val digits = StringBuilder(body.length)
        for (ch in body) {
            when {
                ch in '0'..'9' -> digits.append(ch)
                isPhoneSeparator(ch) -> Unit
                else -> return null
            }
        }
        val allDigits = digits.toString()
        if (allDigits.isEmpty() || allDigits[0] !in '1'..'9') return null
        val codeLen = when {
            allDigits[0] == '1' || allDigits[0] == '7' -> 1
            allDigits.length >= 2 && allDigits.substring(0, 2) in TWO_DIGIT_DIAL_CODES -> 2
            allDigits.length >= 3 -> 3
            else -> return null
        }
        if (allDigits.length <= codeLen) return null
        val dialCode = allDigits.substring(0, codeLen)
        val rawNational = allDigits.substring(codeLen)
        val national = stripLeadingTrunkZero(dialCode, rawNational)
        if (national.isEmpty()) return null
        return ParsedPhone(dialCode = dialCode, nationalNumber = national)
    }

    fun sanitizeNationalInput(raw: String): String =
        stripInvisibleMarks(raw).filter { it in '0'..'9' }.take(15)

    fun isDigitsAndSeparatorsOnly(raw: String): Boolean {
        val cleaned = stripInvisibleMarks(raw).trim()
        if (cleaned.isEmpty()) return false
        var hasDigit = false
        for (ch in cleaned) {
            when {
                ch in '0'..'9' -> hasDigit = true
                isPhoneSeparator(ch) -> Unit
                else -> return false
            }
        }
        return hasDigit
    }

    fun formatE164(dialCode: String, nationalInput: String): String {
        val cleanDial = stripInvisibleMarks(dialCode).filter { it in '0'..'9' }.dropWhile { it == '0' }.take(4)
        val rawSubscriber = sanitizeNationalInput(nationalInput)
        val subscriber = stripLeadingTrunkZero(cleanDial, rawSubscriber)
        return "+$cleanDial$subscriber"
    }

    fun isValidPhone(dialCode: String, nationalInput: String): Boolean {
        val cleanDial = stripInvisibleMarks(dialCode).filter { it in '0'..'9' }.dropWhile { it == '0' }
        if (cleanDial.length !in 1..4 || cleanDial[0] !in '1'..'9') return false
        val rawSubscriber = stripInvisibleMarks(nationalInput).filter { it in '0'..'9' }
        val subscriber = stripLeadingTrunkZero(cleanDial, rawSubscriber)
        return when (cleanDial) {
            "86" -> subscriber.length == 11 && subscriber.startsWith('1')
            "852", "853" -> subscriber.length == 8
            "886" -> subscriber.length == 9
            "1" -> subscriber.length == 10
            else -> subscriber.length in 5..14 && (cleanDial.length + subscriber.length) in 7..15
        }
    }

    fun normalizeAccountInput(raw: String): String {
        val cleaned = stripInvisibleMarks(raw).trim()
        val parsed = splitInternationalPhone(cleaned) ?: return cleaned
        return formatE164(parsed.dialCode, parsed.nationalNumber)
    }

    private fun stripLeadingTrunkZero(dialCode: String, subscriber: String): String =
        if (dialCode in TRUNK_ZERO_DIAL_CODES && subscriber.length > 1 && subscriber.startsWith('0')) {
            subscriber.drop(1)
        } else {
            subscriber
        }

    private fun isPhoneSeparator(ch: Char): Boolean =
        ch == ' ' ||
            ch == '\t' ||
            ch == '\u00A0' ||
            ch == '-' ||
            ch in '\u2010'..'\u2015' ||
            ch == '\u2212' ||
            ch == '(' ||
            ch == ')' ||
            ch == '.' ||
            ch == '/'
}
