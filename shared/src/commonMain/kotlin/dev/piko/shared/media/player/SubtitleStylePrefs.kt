package dev.piko.shared.media.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 字幕字体预设：前三种为跨平台语义预设，[Custom] 使用用户选定的系统字体族名并在目标设备缺失时安全回退。
 */
@Serializable
enum class SubtitleFontPreset {
    Default,
    Serif,
    Monospace,
    Custom,
}

/**
 * 预设字幕底色：高对比度深色调，确保白色字幕文字在任意底色与不透明度下均清晰易读。
 */
@Serializable
enum class SubtitleBackgroundPreset(val rgb: Int) {
    Black(0x000000),
    Charcoal(0x262626),
    MidnightBlue(0x102038),
    DeepForest(0x122920),
    DeepPlum(0x2A172D),
    WarmEspresso(0x2D1E14),
}

/**
 * 播放器字幕外观偏好（跨设备同步）：
 * - [showBackground]：关闭时使用清晰的描边文字（`outline-and-shadow`），开启时使用半透明深色统一底框（`background-box`）。
 * - [backgroundColorRgb]：底框 RGB 颜色（低 24 位有效，默认 `#000000` 纯黑）。
 * - [backgroundOpacity]：底框不透明度百分比（默认 `60`，范围 `10..100`，步进 `5`）。
 * - [fontPreset] 与 [customFontFamily]：跨平台字体预设或已安装系统字体名；若同步到未安装该字体的系统，由播放后端自动回退至平台默认字体。
 * - [fontSize]：映射至 mpv `sub-font-size`（720p 视频高度基准，默认 `38`，范围 `20..72`）。
 * - [position]：映射至 mpv `sub-pos`（默认 `100` 贴近画面底边，范围 `0..100`，数值越小字幕越靠上）。
 * - [overrideAssStyles]：默认关闭（`sub-ass-override=yes`），保留特效 ASS 字幕排版的同时仍允许 `sub-pos` 调整纵向位置；
 *   开启后设为 `sub-ass-override=force`，将字体、字号与底色一并覆盖应用到 ASS 字幕。
 */
@Serializable
data class SubtitleStylePrefs(
    val showBackground: Boolean = false,
    val fontPreset: SubtitleFontPreset = SubtitleFontPreset.Default,
    val customFontFamily: String? = null,
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val position: Int = DEFAULT_POSITION,
    val overrideAssStyles: Boolean = false,
    val backgroundColorRgb: Int = DEFAULT_BACKGROUND_COLOR_RGB,
    val backgroundOpacity: Int = DEFAULT_BACKGROUND_OPACITY,
) {
    fun sanitize(): SubtitleStylePrefs {
        val cleanedCustom = customFontFamily?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedPreset = if (fontPreset == SubtitleFontPreset.Custom && cleanedCustom == null) {
            SubtitleFontPreset.Default
        } else {
            fontPreset
        }
        return copy(
            fontPreset = resolvedPreset,
            customFontFamily = if (resolvedPreset == SubtitleFontPreset.Custom) cleanedCustom else null,
            fontSize = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
            position = position.coerceIn(MIN_POSITION, MAX_POSITION),
            backgroundColorRgb = backgroundColorRgb and RGB_MASK,
            backgroundOpacity = backgroundOpacity.coerceIn(MIN_BACKGROUND_OPACITY, MAX_BACKGROUND_OPACITY),
        )
    }

    val isDefault: Boolean
        get() = sanitize() == DEFAULT

    /** 按当前底色 RGB 与不透明度百分比生成 mpv `#AARRGGBB` 颜色字符串。 */
    fun formattedBackgroundBoxColor(): String = formatBackgroundBoxColor(backgroundColorRgb, backgroundOpacity)

    /**
     * 生成 Android 与桌面端 libmpv 完全一致的属性表。
     *
     * 底色采用 libmpv 的 `sub-border-style`（已核实 Android `dev.jdtech.mpv:libmpv:1.0.0` 与桌面 `mediamp 0.5.3`
     * 均原生支持 `outline-and-shadow` 与 `background-box`）：
     * - 无底色：`sub-border-style=outline-and-shadow`、`sub-back-color=#00000000`、`sub-border-color=#FF000000`、
     *   `sub-border-size=2`、`sub-shadow-offset=0`。
     * - 有底色：`sub-border-style=background-box`（libass BorderStyle=4 统一底框，多行字幕共用一块平滑半透明底色）、
     *   `sub-back-color=#AARRGGBB`（由 [backgroundOpacity] 与 [backgroundColorRgb] 实时计算，默认 `#99000000`）、
     *   `sub-border-color=#00000000`、`sub-border-size=0`、`sub-shadow-offset=3`（底框内边距）。
     */
    fun toMpvProperties(
        platformDefaultFont: String,
        resolveFontFamily: (SubtitleStylePrefs) -> String = { prefs ->
            when (prefs.fontPreset) {
                SubtitleFontPreset.Default -> platformDefaultFont
                SubtitleFontPreset.Serif -> "serif"
                SubtitleFontPreset.Monospace -> "monospace"
                SubtitleFontPreset.Custom -> prefs.customFontFamily?.takeIf { it.isNotBlank() } ?: platformDefaultFont
            }
        },
    ): Map<String, String> {
        val clean = sanitize()
        val resolvedFont = resolveFontFamily(clean).trim().ifEmpty { platformDefaultFont }
        return linkedMapOf(
            "sub-font" to resolvedFont,
            "sub-font-size" to clean.fontSize.toString(),
            "sub-pos" to clean.position.toString(),
            "sub-ass-override" to if (clean.overrideAssStyles) "force" else "yes",
            "sub-border-style" to if (clean.showBackground) BORDER_STYLE_BACKGROUND_BOX else BORDER_STYLE_OUTLINE,
            "sub-back-color" to if (clean.showBackground) clean.formattedBackgroundBoxColor() else TRANSPARENT_COLOR,
            "sub-border-color" to if (clean.showBackground) TRANSPARENT_COLOR else DEFAULT_OUTLINE_COLOR,
            "sub-border-size" to if (clean.showBackground) "0" else DEFAULT_BORDER_SIZE.toString(),
            "sub-shadow-offset" to if (clean.showBackground) BACKGROUND_BOX_PADDING.toString() else "0",
        )
    }

    fun encodeJson(): String = SubtitleStyleJson.encodeToString(serializer(), sanitize())

    companion object {
        const val DEFAULT_FONT_SIZE = 38
        const val MIN_FONT_SIZE = 20
        const val MAX_FONT_SIZE = 72
        const val FONT_SIZE_STEP = 2

        const val DEFAULT_POSITION = 100
        const val MIN_POSITION = 0
        const val MAX_POSITION = 100
        const val POSITION_STEP = 2

        const val DEFAULT_BACKGROUND_COLOR_RGB = 0x000000
        const val DEFAULT_BACKGROUND_OPACITY = 60
        const val MIN_BACKGROUND_OPACITY = 10
        const val MAX_BACKGROUND_OPACITY = 100
        const val BACKGROUND_OPACITY_STEP = 5
        private const val RGB_MASK = 0x00FFFFFF

        const val DEFAULT_BORDER_SIZE = 2
        const val BACKGROUND_BOX_PADDING = 3
        const val BACKGROUND_BOX_COLOR = "#99000000"
        const val DEFAULT_OUTLINE_COLOR = "#FF000000"
        const val TRANSPARENT_COLOR = "#00000000"

        const val BORDER_STYLE_OUTLINE = "outline-and-shadow"
        const val BORDER_STYLE_BACKGROUND_BOX = "background-box"

        val DEFAULT = SubtitleStylePrefs()

        private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

        fun formatBackgroundBoxColor(rgb: Int, opacityPercent: Int): String {
            val clampedOpacity = opacityPercent.coerceIn(MIN_BACKGROUND_OPACITY, MAX_BACKGROUND_OPACITY)
            val alpha = ((clampedOpacity * 255 + 50) / 100).coerceIn(0, 255)
            val masked = rgb and RGB_MASK
            return buildString(9) {
                append('#')
                appendHexByte(alpha)
                appendHexByte((masked ushr 16) and 0xFF)
                appendHexByte((masked ushr 8) and 0xFF)
                appendHexByte(masked and 0xFF)
            }
        }

        private fun StringBuilder.appendHexByte(value: Int) {
            append(HEX_DIGITS[(value ushr 4) and 0x0F])
            append(HEX_DIGITS[value and 0x0F])
        }

        private val SubtitleStyleJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decodeJson(raw: String?): SubtitleStylePrefs {
            if (raw.isNullOrBlank()) return DEFAULT
            return runCatching { SubtitleStyleJson.decodeFromString(serializer(), raw).sanitize() }
                .getOrDefault(DEFAULT)
        }
    }
}
