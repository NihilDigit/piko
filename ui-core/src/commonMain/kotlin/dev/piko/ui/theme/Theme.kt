package dev.piko.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.platform.LocalPikoPlatform

val LocalFixedColors = staticCompositionLocalOf { FixedColors }
val LocalStatusColors = staticCompositionLocalOf { PikoLightStatusColors }
val LocalPikoStatusColors = LocalStatusColors

/** 当前宿主窗口是否处于可见态（未最小化/未藏入托盘），供无限循环动画与骨架屏降频停表。 */
val LocalWindowVisible = staticCompositionLocalOf { true }

/**
 * Piko 全局主题，使用 [MaterialExpressiveTheme] 作为统一入口。
 * 遵循 Material 3 Expressive 规范，配置 10 档形状与 30 档排版。动效按平台取 motionScheme 与 [PikoMotion]，
 * 见 [MotionStyle]：Android 是 expressive，桌面是 standard。
 *
 * Documentation references:
 * - Material 3 Expressive Theming: `m3-material-mirror/pages/styles/`
 * - Material 3 Dynamic Color: `m3-material-mirror/pages/styles/color.md`
 * - Compose Theming in Android: `android-docs-mirror/pages/develop/ui/compose/designsystems/material3.md`
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikoTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val isEInk = appearance.isEInk
    val darkTheme = appearance.isDark()
    val platform = LocalPikoPlatform.current
    SideEffect {
        if (platform.motionScale.einkReduced != isEInk) {
            platform.motionScale.einkReduced = isEInk
        }
    }
    val motionLevel = MotionLevel.fromPreference(appearance.motionPreference, platform.motionStyle)
    val effectiveMotionStyle = when (motionLevel) {
        MotionLevel.Expressive -> MotionStyle.Expressive
        MotionLevel.Standard, MotionLevel.Reduced -> MotionStyle.Standard
    }
    val reduced = platform.motionScale.reduced || isEInk || motionLevel == MotionLevel.Reduced
    val motion = remember(effectiveMotionStyle, reduced) { PikoMotion.of(effectiveMotionStyle, reduced) }
    val motionScheme = when (effectiveMotionStyle) {
        MotionStyle.Expressive -> MotionScheme.expressive()
        MotionStyle.Standard -> MotionScheme.standard()
    }
    val targetScheme = appearance.colorScheme(darkTheme)
    val colorScheme = if (isEInk) targetScheme else animateColorScheme(targetScheme, motionScheme)
    val effectiveLanguage = PikoLocale.effectiveLanguageState.value
    val systemLanguage = PikoLocale.systemLanguage
    val localeList = remember(effectiveLanguage, systemLanguage) {
        pikoLocaleList(effectiveLanguage, systemLanguage)
    }
    val fontFamily = remember(platform, effectiveLanguage, systemLanguage) {
        platform.fontFamily
    }
    val typography = remember(fontFamily, localeList, effectiveLanguage) {
        pikoTypography(fontFamily, localeList)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme,
        typography = typography,
        shapes = PikoShapes,
    ) {
        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalPikoMotion provides motion,
            LocalFixedColors provides FixedColors,
            LocalStatusColors provides when {
                isEInk -> PikoEInkStatusColors
                darkTheme -> PikoDarkStatusColors
                else -> PikoLightStatusColors
            },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
