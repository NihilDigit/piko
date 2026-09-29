package dev.piko.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.ui.platform.LocalPikoPlatform

val LocalFixedColors = staticCompositionLocalOf { FixedColors }
val LocalStatusColors = staticCompositionLocalOf { PikoLightStatusColors }

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
    val darkTheme = appearance.isDark()
    val platform = LocalPikoPlatform.current
    val reduced = platform.motionScale.reduced
    val motion = remember(platform.motionStyle, reduced) { PikoMotion.of(platform.motionStyle, reduced) }
    val motionScheme = when (platform.motionStyle) {
        MotionStyle.Expressive -> MotionScheme.expressive()
        MotionStyle.Standard -> MotionScheme.standard()
    }
    val colorScheme = animateColorScheme(appearance.colorScheme(darkTheme), motionScheme)
    val fontFamily = platform.fontFamily
    val typography = remember(fontFamily) { pikoTypography(fontFamily) }

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
            LocalStatusColors provides if (darkTheme) PikoDarkStatusColors else PikoLightStatusColors,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
