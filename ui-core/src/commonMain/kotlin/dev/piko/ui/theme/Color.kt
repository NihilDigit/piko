package dev.piko.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * M3 色彩角色之外的语义状态色，按深浅主题分别校准 HCT 明度以满足 WCAG AA/AAA 对比度：
 * - 浅色模式在浅底 surface 上均 >= 6:1；
 * - 深色模式在深底 surface / 纯黑 OLED 上均 >= 10:1。
 */
@Immutable
data class PikoStatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val PikoLightStatusColors = PikoStatusColors(
    success = Color(0xFF1B6C3C),
    successContainer = Color(0xFFC8F0D4),
    onSuccessContainer = Color(0xFF0A381D),
    info = Color(0xFF1A5FB4),
    infoContainer = Color(0xFFD6E7FF),
    onInfoContainer = Color(0xFF072E5C),
    warning = Color(0xFF8A5000),
    warningContainer = Color(0xFFFFE1B3),
    onWarningContainer = Color(0xFF3E2200),
)

val PikoDarkStatusColors = PikoStatusColors(
    success = Color(0xFF8AD6A0),
    successContainer = Color(0xFF144729),
    onSuccessContainer = Color(0xFFC8F0D4),
    info = Color(0xFF8FC2FF),
    infoContainer = Color(0xFF0E396D),
    onInfoContainer = Color(0xFFD6E7FF),
    warning = Color(0xFFFFC169),
    warningContainer = Color(0xFF532F00),
    onWarningContainer = Color(0xFFFFE1B3),
)

/** 水墨屏高对比度单色状态色：纯黑墨迹配浅墨底，零彩色干扰。 */
val PikoEInkStatusColors = PikoStatusColors(
    success = Color(0xFF000000),
    successContainer = Color(0xFFEBEBEB),
    onSuccessContainer = Color(0xFF000000),
    info = Color(0xFF000000),
    infoContainer = Color(0xFFEBEBEB),
    onInfoContainer = Color(0xFF000000),
    warning = Color(0xFF000000),
    warningContainer = Color(0xFFDFDFDF),
    onWarningContainer = Color(0xFF000000),
)

/**
 * Oklab 纯黑 OLED 深色表面阶梯。
 * 将主画布锚定到 `#000000`，同时以纯黑为起点对五档容器与高亮表面做 Oklab 线性缩放，
 * 保留种子色相并兼容标准深色（ColorSpec2021）与 Android 16+ 动态深色（ColorSpec2025），
 * 避免 `surfaceContainerLow` 在 8-bit sRGB 量化下塌缩为 `#000000` 或 `surfaceBright` 产生高阶断层。
 */
fun ColorScheme.toPureBlack(): ColorScheme {
    val black = Color.Black
    return copy(
        background = black,
        surface = black,
        surfaceDim = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = lerp(black, surfaceContainerLow, 0.68f),
        surfaceContainer = lerp(black, surfaceContainer, 0.75f),
        surfaceContainerHigh = lerp(black, surfaceContainerHigh, 0.80f),
        surfaceContainerHighest = lerp(black, surfaceContainerHighest, 0.85f),
        surfaceBright = lerp(black, surfaceBright, 0.88f),
    )
}

/**
 * 不受系统深浅主题影响的固定色彩。
 * 用于压在视频、图片、封面、播放控制条上的元素，保证高对比度与绝对可读性。
 */
object FixedColors {
    /** 压在媒体封面或画面上的半透明遮罩 (0.55 alpha，过雪景/亮底保 4.5:1 对比度) */
    val ScrimOnMedia = Color(0x8C000000)

    /** 压在密集媒体说明或字幕遮罩上的强对比遮罩 (0.72 alpha) */
    val ScrimOnMediaStrong = Color(0xB8000000)

    /** 压在媒体遮罩上的文字与图标 */
    val OnMedia = Color(0xFFF0F4F8)

    /** 播放器主控件的控制层衬底 */
    val PlayerControlScrim = Color(0x99000000)

    /** 播放器与媒体浮动胶囊的微妙高光描边，提升纯黑与暗色画面上的边缘锐度 */
    val HudSpecularBorder = Color.White.copy(alpha = 0.08f)

    /** 链接与高亮色 (按明度分深浅两档以保读性) */
    val MentionLight = Color(0xFF026AA7)
    val MentionDark = Color(0xFF58B6FF)

    /** 磁力秒传命中标记绿（仅限深色媒体遮罩；常规界面请使用 `LocalStatusColors.current.success`） */
    @Deprecated("Use LocalStatusColors.current.success for adaptive surface contrast.")
    val InstantMatchGreen = Color(0xFF10B981)

    /** 待下载/离线下载状态蓝（仅限深色媒体遮罩；常规界面请使用 `LocalStatusColors.current.info`） */
    @Deprecated("Use LocalStatusColors.current.info for adaptive surface contrast.")
    val OfflinePendingBlue = Color(0xFF3B82F6)
}
