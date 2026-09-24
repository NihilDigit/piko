package dev.piko.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 用户的外观选择。[seed] 为 null 表示系统取色。 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val seed: SeedTheme? = null,
    /** 平台是否有系统取色，没有时 [seed] 为 null 也落到第一个内置主题上。 */
    val dynamicColorAvailable: Boolean = false,
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

// 不分大小写：桌面端旧设置里存的是小写的 system、light、dark
fun PikoUserPreferences.appearanceFlow(dynamicColorAvailable: Boolean): Flow<Appearance> =
    combine(themeModeFlow, themeSeedFlow) { mode, seed ->
        Appearance(
            mode = ThemeMode.entries.find { it.name.equals(mode, ignoreCase = true) } ?: ThemeMode.SYSTEM,
            seed = SeedTheme.entries.find { it.name == seed },
            dynamicColorAvailable = dynamicColorAvailable,
        )
    }

@Composable
fun Appearance.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 没有系统取色的平台，选了系统取色也落到第一个内置主题上。 */
val Appearance.effectiveSeed: SeedTheme?
    get() = seed ?: SeedTheme.entries.first().takeUnless { dynamicColorAvailable }

@Composable
fun Appearance.colorScheme(dark: Boolean): ColorScheme {
    val theme = effectiveSeed
    return when {
        theme != null -> if (dark) theme.dark else theme.light
        else -> LocalPikoPlatform.current.dynamicColorScheme(dark)
    }
}
