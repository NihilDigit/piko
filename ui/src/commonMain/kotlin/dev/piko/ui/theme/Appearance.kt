package dev.piko.ui.theme

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class ThemeMode(private val labelText: () -> String) {
    SYSTEM({ tr("跟随系统", "System", "システムに従う", "시스템 설정 따르기", "跟隨系統") }),
    LIGHT({ tr("浅色", "Light", "ライト", "라이트", "淺色") }),
    DARK({ tr("深色", "Dark", "ダーク", "다크", "深色") }),
    ;

    val label: String get() = labelText()
}

/** 用户的外观选择。[seed] 为 null 表示系统取色；[pureBlack] 仅在深色模式下生效。 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val seed: SeedTheme? = null,
    /** 平台是否有系统取色，没有时 [seed] 为 null 也落到第一个内置主题上。 */
    val dynamicColorAvailable: Boolean = false,
    /** 深色模式下启用纯黑 OLED 表面阶梯（Oklab 插值，见 [toPureBlack]）。 */
    val pureBlack: Boolean = false,
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

// 不分大小写：桌面端旧设置里存的是小写的 system、light、dark
fun PikoUserPreferences.appearanceFlow(dynamicColorAvailable: Boolean): Flow<Appearance> =
    combine(themeModeFlow, themeSeedFlow, pureBlackFlow) { mode, seed, pureBlack ->
        Appearance(
            mode = ThemeMode.entries.find { it.name.equals(mode, ignoreCase = true) } ?: ThemeMode.SYSTEM,
            seed = SeedTheme.entries.find { it.name == seed },
            dynamicColorAvailable = dynamicColorAvailable,
            pureBlack = pureBlack,
        )
    }

/** 当前外观是否处于水墨屏（E-Ink）主题。 */
val Appearance.isEInk: Boolean
    get() = effectiveSeed == SeedTheme.EINK

@Composable
fun Appearance.isDark(): Boolean = if (isEInk) {
    false
} else {
    when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** 没有系统取色的平台，选了系统取色也落到第一个内置主题上。 */
val Appearance.effectiveSeed: SeedTheme?
    get() = seed ?: SeedTheme.entries.first().takeUnless { dynamicColorAvailable }

@Composable
fun Appearance.colorScheme(dark: Boolean): ColorScheme {
    if (isEInk) return SeedTheme.EINK.light
    val theme = effectiveSeed
    val base = when {
        theme != null -> if (dark) theme.dark else theme.light
        else -> LocalPikoPlatform.current.dynamicColorScheme(dark)
    }
    return if (dark && pureBlack) base.toPureBlack() else base
}
