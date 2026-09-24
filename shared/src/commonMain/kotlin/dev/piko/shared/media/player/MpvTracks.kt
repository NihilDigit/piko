package dev.piko.shared.media.player

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
        val track = MediaTrack(
            id = id,
            title = property("$prefix/title")?.takeIf { it.isNotBlank() },
            language = property("$prefix/lang")?.takeIf { it.isNotBlank() },
            isExternal = property("$prefix/external") == "yes",
        )
        val selected = property("$prefix/selected") == "yes"
        when (property("$prefix/type")) {
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
 * 文件本身没有选中任何字幕时，第一条外挂直接选中：跟视频放在一起的字幕通常就是要看的那条，
 * 而内封字幕已经按 slang 选过一轮，有选中的就不去抢。其余用 auto，只加进列表。
 */
fun mpvSubtitleAddCommands(subtitles: List<ExternalSubtitle>, hasSelectedSubtitle: Boolean): List<Array<String>> =
    subtitles.mapIndexed { index, subtitle ->
        val flag = if (index == 0 && !hasSelectedSubtitle) "select" else "auto"
        arrayOf("sub-add", subtitle.url, flag, subtitle.title, subtitle.language.orEmpty())
    }

/** 两端 mpv 共用的字幕语言偏好：简体优先，其次繁体。内封字幕按它自动选中。 */
const val MPV_SUBTITLE_LANGUAGES = "zh-CN,zh-Hans,chs,sc,zh,chi,zho,zh-TW,zh-Hant,cht,tc"
