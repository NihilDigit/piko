package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale

/** mpv 的 track-list 读出来的一份快照。选中的编号为 null 表示该类轨道关闭。 */
class MpvTrackSnapshot(
    val audio: List<MediaTrack>,
    val subtitles: List<MediaTrack>,
    val selectedAudioId: String?,
    val selectedSubtitleId: String?,
)

/**
 * 从 mpv 的 track-list 读出音轨与字幕轨。两端的 mpv 绑定不是同一个类，这里只要求能按名字读字符串属性：
 * 标志位读出来是「yes」「no」，编号读出来是十进制字符串。
 * 同时读取 `external-filename` 供外挂字幕识别简繁变体。
 */
fun readMpvTracks(property: (String) -> String?): MpvTrackSnapshot {
    val count = property("track-list/count")?.toIntOrNull() ?: 0
    val audio = mutableListOf<MediaTrack>()
    val subtitles = mutableListOf<MediaTrack>()
    var selectedAudio: String? = null
    var selectedSubtitle: String? = null
    for (i in 0 until count) {
        val prefix = "track-list/$i"
        val id = property("$prefix/id") ?: continue
        val type = property("$prefix/type") ?: continue
        val isExternal = property("$prefix/external") == "yes"
        val codec = property("$prefix/codec")?.takeIf { it.isNotBlank() }
        val isImage = type == "sub" && (property("$prefix/image") == "yes" || isImageSubtitleCodec(codec))
        val track = MediaTrack(
            id = id,
            title = property("$prefix/title")?.takeIf { it.isNotBlank() },
            language = property("$prefix/lang")?.takeIf { it.isNotBlank() },
            isExternal = isExternal,
            externalFilename = if (isExternal) property("$prefix/external-filename")?.takeIf { it.isNotBlank() } else null,
            codec = codec,
            isImage = isImage,
        )
        val selected = property("$prefix/selected") == "yes"
        when (type) {
            "audio" -> {
                audio += track
                if (selected) selectedAudio = id
            }
            "sub" -> {
                subtitles += track
                if (selected) selectedSubtitle = id
            }
        }
    }
    return MpvTrackSnapshot(audio, subtitles, selectedAudio, selectedSubtitle)
}

/**
 * 把外挂字幕挂到已加载文件上的 mpv 命令（sub-add 地址 标志 标题 语言）。
 *
 * 文件本身没有选中任何字幕时，优先挑选符合当前语言偏好的外挂字幕（简体/繁体）作为 `"select"`，
 * 没有明确命中简繁时回退到第一条；内封字幕已经选中时其余外挂均用 `"auto"` 挂入列表。
 */
fun mpvSubtitleAddCommands(
    subtitles: List<ExternalSubtitle>,
    hasSelectedSubtitle: Boolean,
    preferredVariant: ChineseSubtitleVariant = preferredChineseSubtitleVariant(),
): List<Array<String>> {
    val selectIndex = if (hasSelectedSubtitle || subtitles.isEmpty()) {
        -1
    } else {
        pickPreferredExternalSubtitleIndex(subtitles, preferredVariant)
    }
    return subtitles.mapIndexed { index, subtitle ->
        val flag = if (index == selectIndex) "select" else "auto"
        arrayOf("sub-add", subtitle.url, flag, subtitle.title, subtitle.language.orEmpty())
    }
}

private fun pickPreferredExternalSubtitleIndex(
    subtitles: List<ExternalSubtitle>,
    preferredVariant: ChineseSubtitleVariant,
): Int {
    val exact = subtitles.indexOfFirst {
        detectChineseSubtitleVariant(it.language, it.title, it.url) == preferredVariant
    }
    if (exact >= 0) return exact
    val generic = subtitles.indexOfFirst {
        detectChineseSubtitleVariant(it.language, it.title, it.url) == ChineseSubtitleVariant.Generic
    }
    if (generic >= 0) return generic
    return 0
}

/** 手动挂上的一条外挂字幕：直接选中，不看文件里原来选没选。 */
fun mpvSubtitleSelectCommand(subtitle: ExternalSubtitle): Array<String> =
    arrayOf("sub-add", subtitle.url, "select", subtitle.title, subtitle.language.orEmpty())

// mpv 能直接当外挂字幕加载的格式。idx/sub 要成对，smi 不认，都不列
val PLAYER_SUBTITLE_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt", "sup")

fun isPlayerSubtitleName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in PLAYER_SUBTITLE_EXTENSIONS

private val IMAGE_SUBTITLE_CODECS = setOf(
    "hdmv_pgs_subtitle",
    "dvd_subtitle",
    "dvb_subtitle",
    "xsub",
    "pgssub",
    "vobsub",
    "dvbsub",
)

/** 判断 mpv 字幕编解码器名是否属于位图/图像字幕（如 Blu-ray PGS、DVD VobSub、DVB）。 */
fun isImageSubtitleCodec(codec: String?): Boolean {
    val normalized = codec?.trim()?.lowercase() ?: return false
    return normalized in IMAGE_SUBTITLE_CODECS ||
        normalized.contains("pgs") ||
        normalized.contains("vobsub")
}

/** 图像字幕格式的简洁可读标签（如 `PGS`、`VobSub`、`DVB`、`XSUB`），未知时返回 null。 */
fun imageSubtitleFormatLabel(codec: String?): String? {
    val normalized = codec?.trim()?.lowercase() ?: return null
    return when {
        normalized == "hdmv_pgs_subtitle" || normalized == "pgssub" || normalized.contains("pgs") -> "PGS"
        normalized == "dvd_subtitle" || normalized == "vobsub" || normalized.contains("vobsub") -> "VobSub"
        normalized == "dvb_subtitle" || normalized == "dvbsub" -> "DVB"
        normalized == "xsub" -> "XSUB"
        else -> null
    }
}

/**
 * 两端 mpv 共用的字幕语言偏好（slang）：按当前界面语言自动切换简体/繁体优先级。
 * 繁体中文界面下繁体优先（zh-TW/zh-HK/zh-Hant/cht/tc/big5）；简体中文与其他语言默认简体优先。
 */
fun mpvSubtitleLanguages(
    effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    systemLanguage: AppLanguage = PikoLocale.systemLanguage,
): String = when (preferredChineseSubtitleVariant(effectiveLanguage, systemLanguage)) {
    ChineseSubtitleVariant.Traditional -> MPV_SUBTITLE_LANGUAGES_CHT
    ChineseSubtitleVariant.Simplified, ChineseSubtitleVariant.Generic -> MPV_SUBTITLE_LANGUAGES_CHS
}

const val MPV_SUBTITLE_LANGUAGES_CHS = "zh-CN,zh-SG,zh-Hans,chs,sc,gb,zh,chi,zho,zh-TW,zh-HK,zh-MO,zh-Hant,cht,tc,big5"
const val MPV_SUBTITLE_LANGUAGES_CHT = "zh-TW,zh-HK,zh-MO,zh-Hant,cht,tc,big5,zh,chi,zho,zh-CN,zh-SG,zh-Hans,chs,sc,gb"

/** 保留旧常量名以兼容既有调用方；新代码请调用 [mpvSubtitleLanguages]。 */
const val MPV_SUBTITLE_LANGUAGES = MPV_SUBTITLE_LANGUAGES_CHS

/**
 * 两端 mpv 共用的非 UTF-8 字幕回退编码（`sub-codepage`）。
 *
 * 注意：绝不加 `+` 前缀！mpv 对不带 `+` 的 `sub-codepage=<charset>` 会先检测 BOM 与合法 UTF-8，
 * 仅在字节流不是合法 UTF-8 时才按指定编码解码，避免在缺少 `uchardet` 的构建上退化为 Latin-1（`UTF-8-BROKEN`）
 * 产生乱码；加 `+` 则会强制改码并破坏正常的 UTF-8 字幕。
 */
fun mpvSubtitleCodepage(
    effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    systemLanguage: AppLanguage = PikoLocale.systemLanguage,
): String {
    val primary = if (effectiveLanguage == AppLanguage.EN && systemLanguage != AppLanguage.EN) {
        systemLanguage
    } else {
        effectiveLanguage
    }
    return when (primary) {
        AppLanguage.ZH_CN, AppLanguage.SYSTEM -> "gb18030"
        AppLanguage.ZH_HANT -> "big5"
        AppLanguage.JA -> "cp932"
        AppLanguage.KO -> "cp949"
        AppLanguage.EN -> "gb18030"
    }
}
