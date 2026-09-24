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
 * 遵循 Material 3 Expressive 规范，激活组件物理弹性响应，配置 10 档形状与 30 档排版。
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
    val motionScheme = MotionScheme.expressive()
    val colorScheme = animateColorScheme(appearance.colorScheme(darkTheme), motionScheme)
    val fontFamily = LocalPikoPlatform.current.fontFamily
    val typography = remember(fontFamily) { pikoTypography(fontFamily) }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme,
        typography = typography,
        shapes = PikoShapes,
    ) {
        CompositionLocalProvider(
            LocalAppearance provides appearance,
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
