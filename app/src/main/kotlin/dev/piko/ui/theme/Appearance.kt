package dev.piko.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.piko.data.auth.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 用户的外观选择。[seed] 为 null 表示系统取色。 */
@Immutable
data class Appearance(val mode: ThemeMode = ThemeMode.SYSTEM, val seed: SeedTheme? = null)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** 系统取色从 Android 12 起才有。 */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun SessionManager.appearanceFlow(): Flow<Appearance> =
    combine(themeModeFlow, themeSeedFlow) { mode, seed ->
        Appearance(
            mode = ThemeMode.entries.find { it.name == mode } ?: ThemeMode.SYSTEM,
            seed = SeedTheme.entries.find { it.name == seed },
        )
    }

@Composable
fun Appearance.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 没有系统取色的设备，选了系统取色也落到第一个内置主题上。 */
val Appearance.effectiveSeed: SeedTheme?
    get() = seed ?: SeedTheme.entries.first().takeUnless { supportsDynamicColor }

/**
 * 系统取色的结果按 context 与深浅记住：dynamic*ColorScheme 每次调用都新建对象，
 * 而 ColorScheme 不覆写 equals，不记住的话每次重组都像是换了一套配色，过渡动画反复重启。
 */
@Composable
fun Appearance.colorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val theme = effectiveSeed
    return when {
        theme != null -> if (dark) theme.dark else theme.light
        else -> remember(context, dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    }
}
