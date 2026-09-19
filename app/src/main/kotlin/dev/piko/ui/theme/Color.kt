package dev.piko.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Piko 基线色板，基于 M3E 规范，种子色选择深邃青蓝 #0077B6。
 * 色彩层级通过 surfaceContainer 家族精准表达层级深度。
 *
 * Documentation references:
 * - Material 3 Color Roles & System: `m3-material-mirror/pages/styles/color.md`
 * - Android Material 3 Color Schemes: `android-docs-mirror/pages/develop/ui/compose/designsystems/material3.md`
 */
val PikoLightColors = lightColorScheme(
    primary = Color(0xFF006590),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E6FF),
    onPrimaryContainer = Color(0xFF001E2F),
    secondary = Color(0xFF4F606E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF64597B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFB),
    onTertiaryContainer = Color(0xFF201635),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41474D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEFF2),
    surfaceContainerHigh = Color(0xFFE6E9EC),
    surfaceContainerHighest = Color(0xFFE0E3E7),
)

val PikoDarkColors = darkColorScheme(
    primary = Color(0xFF88CEFF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF004C6E),
    onPrimaryContainer = Color(0xFFC8E6FF),
    secondary = Color(0xFFB6C9D8),
    onSecondary = Color(0xFF21323F),
    secondaryContainer = Color(0xFF374956),
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFFCEC0E8),
    onTertiary = Color(0xFF352B4B),
    tertiaryContainer = Color(0xFF4C4162),
    onTertiaryContainer = Color(0xFFEBDDFB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE0E3E7),
    surface = Color(0xFF101417),
    onSurface = Color(0xFFE0E3E7),
    surfaceVariant = Color(0xFF41474D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41474D),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF272A2E),
    surfaceContainerHighest = Color(0xFF313539),
)

/**
 * 不受系统深浅主题影响的固定色彩。
 * 用于压在视频、图片、封面、播放控制条上的元素，保证高对比度与绝对可读性。
 */
object FixedColors {
    /** 压在媒体封面或画面上的半透明遮罩 (0.55 alpha，过雪景/亮底保 4.5:1 对比度) */
    val ScrimOnMedia = Color(0x8C000000)

    /** 压在媒体遮罩上的文字与图标 */
    val OnMedia = Color(0xFFF0F4F8)

    /** 播放器主控件的控制层衬底 */
    val PlayerControlScrim = Color(0x99000000)

    /** 链接与高亮色 (按明度分深浅两档以保读性) */
    val MentionLight = Color(0xFF026AA7)
    val MentionDark = Color(0xFF58B6FF)

    /** 磁力秒传命中标记绿 */
    val InstantMatchGreen = Color(0xFF10B981)

    /** 待下载/离线下载状态蓝 */
    val OfflinePendingBlue = Color(0xFF3B82F6)
}
