package dev.piko.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalFixedColors = staticCompositionLocalOf { FixedColors }

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
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> PikoDarkColors
        else -> PikoLightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = PikoTypography,
        shapes = PikoShapes,
    ) {
        CompositionLocalProvider(
            LocalFixedColors provides FixedColors,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
