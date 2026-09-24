package dev.piko.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val cjkLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun cjkStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = cjkLineHeightStyle,
)

/**
 * Piko 排版规范：15 档基线 + 15 档 Emphasized 变体。
 * 针对中文字形特性：字距归零、小字号行高增加 2sp，杜绝正文拥挤。
 *
 * Documentation references:
 * - Material 3 Typography Scale: `m3-material-mirror/pages/styles/typography.md`
 * - Android Material 3 Typography: `android-docs-mirror/pages/develop/ui/compose/designsystems/material3.md`
 */
val PikoTypography = Typography(
    displayLarge = cjkStyle(57, 64, FontWeight.Normal, -0.25),
    displayMedium = cjkStyle(45, 52, FontWeight.Normal),
    displaySmall = cjkStyle(36, 44, FontWeight.Normal),

    headlineLarge = cjkStyle(32, 40, FontWeight.Normal),
    headlineMedium = cjkStyle(28, 36, FontWeight.Normal),
    headlineSmall = cjkStyle(24, 32, FontWeight.Normal),

    titleLarge = cjkStyle(22, 28, FontWeight.Normal),
    titleMedium = cjkStyle(16, 24, FontWeight.Medium),
    titleSmall = cjkStyle(14, 22, FontWeight.Medium),

    bodyLarge = cjkStyle(16, 24, FontWeight.Normal),
    bodyMedium = cjkStyle(14, 22, FontWeight.Normal),
    bodySmall = cjkStyle(12, 18, FontWeight.Normal),

    labelLarge = cjkStyle(14, 20, FontWeight.Medium, 0.1),
    labelMedium = cjkStyle(12, 16, FontWeight.Medium, 0.5),
    labelSmall = cjkStyle(11, 16, FontWeight.Medium, 0.5),

    // 15 档 Emphasized (通过字重提升一级，保持同字号和行高，避免布局跳跃)
    displayLargeEmphasized = cjkStyle(57, 64, FontWeight.Medium, -0.25),
    displayMediumEmphasized = cjkStyle(45, 52, FontWeight.Medium),
    displaySmallEmphasized = cjkStyle(36, 44, FontWeight.Medium),

    headlineLargeEmphasized = cjkStyle(32, 40, FontWeight.Medium),
    headlineMediumEmphasized = cjkStyle(28, 36, FontWeight.Medium),
    headlineSmallEmphasized = cjkStyle(24, 32, FontWeight.Medium),

    titleLargeEmphasized = cjkStyle(22, 28, FontWeight.Medium),
    titleMediumEmphasized = cjkStyle(16, 24, FontWeight.SemiBold),
    titleSmallEmphasized = cjkStyle(14, 22, FontWeight.SemiBold),

    bodyLargeEmphasized = cjkStyle(16, 24, FontWeight.Medium),
    bodyMediumEmphasized = cjkStyle(14, 22, FontWeight.Medium),
    bodySmallEmphasized = cjkStyle(12, 18, FontWeight.Medium),

    labelLargeEmphasized = cjkStyle(14, 20, FontWeight.SemiBold, 0.1),
    labelMediumEmphasized = cjkStyle(12, 16, FontWeight.SemiBold, 0.5),
    labelSmallEmphasized = cjkStyle(11, 16, FontWeight.SemiBold, 0.5),
)
