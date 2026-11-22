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

/** 用户的外观选择。[seed] 为 null 表示系统取色；[pureBlack] 仅在深色模式下生效；[einkMode] 为设备本地水墨屏显示模式。 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val seed: SeedTheme? = null,
    /** 平台是否有系统取色，没有时 [seed] 为 null 也落到第一个内置主题上。 */
    val dynamicColorAvailable: Boolean = false,
    /** 深色模式下启用纯黑 OLED 表面阶梯（Oklab 插值，见 [toPureBlack]）。 */
    val pureBlack: Boolean = false,
    /** 水墨屏模式（纯黑白高对比度、清晰描边与自动关闭动画），仅对当前设备生效，不跨设备同步。 */
    val einkMode: Boolean = false,
    /** 动画程度显式偏好（"Reduced" / "Standard" / "Expressive"），null 时跟随平台默认档。 */
    val motionPreference: String? = null,
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

// 不分大小写：桌面端旧设置里存的是小写的 system、light、dark
fun PikoUserPreferences.appearanceFlow(dynamicColorAvailable: Boolean): Flow<Appearance> =
    combine(themeModeFlow, themeSeedFlow, pureBlackFlow, einkModeFlow, motionPreferenceFlow) { mode, seed, pureBlack, einkMode, motionPref ->
        val legacyEink = seed.equals(SeedTheme.EINK.name, ignoreCase = true)
        Appearance(
            mode = ThemeMode.entries.find { it.name.equals(mode, ignoreCase = true) } ?: ThemeMode.SYSTEM,
            seed = SeedTheme.colorEntries.find { it.name == seed },
            dynamicColorAvailable = dynamicColorAvailable,
            pureBlack = pureBlack,
            einkMode = einkMode || legacyEink,
            motionPreference = motionPref,
        )
    }

/** 当前外观是否处于水墨屏（E-Ink）模式。兼容旧测试直接构造 `Appearance(seed = SeedTheme.EINK)`。 */
val Appearance.isEInk: Boolean
    get() = einkMode || seed == SeedTheme.EINK

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

/** 没有系统取色的平台，选了系统取色也落到第一个内置彩色主题上。 */
val Appearance.effectiveSeed: SeedTheme?
    get() = seed?.takeUnless { it == SeedTheme.EINK }
        ?: SeedTheme.colorEntries.first().takeUnless { dynamicColorAvailable }

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

/** 播放器覆盖层专用配色：常规模式沿用当前种子色的深色方案，水墨屏模式使用高对比度深色单色方案以免亮白覆盖视频。 */
@Composable
fun Appearance.playerColorScheme(): ColorScheme =
    if (isEInk) EInkPlayerColorScheme else colorScheme(dark = true)
