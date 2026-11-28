"""Generates ui-core/src/commonMain/kotlin/dev/piko/ui/theme/SeedColorSchemes.kt.

Compose Material 3 has no public API for building a scheme from a seed colour:
TonalPalette and the HCT utilities are internal, and dynamic*ColorScheme only
read the wallpaper. So the built-in themes are computed here once and checked
in as constants.

Run from the repository root:
    uv run --with materialyoucolor python app/scripts/generate_color_schemes.py
"""

from pathlib import Path

from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors
from materialyoucolor.hct import Hct
from materialyoucolor.scheme.scheme_tonal_spot import SchemeTonalSpot

# TonalSpot is what Android derives from the wallpaper, so the built-in themes
# sit next to system colours without looking like a different design language.
SPEC = "2025"
DARK_SURFACE_SPEC = "2021"

# Dark neutral surface/outline roles stay on ColorSpec2021 (N4/N6/N10/N12/N17/N22/N24)
# so standard dark themes remain distinct from pure-black OLED mode and avoid
# ColorSpec2025's #000000 surfaceContainerLowest / Tone 4 surface compression.
DARK_SURFACE_2021_ROLES = {
    "background", "onBackground",
    "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "outline", "outlineVariant",
    "surfaceBright", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest", "surfaceDim",
}

# Traditional Japanese colours, values as nipponcolors.com serves them
# (/php/io.php?color=<romaji>, read 2026-09-23).
SEEDS = [
    ("RURI", "瑠璃", "Lapis blue", "瑠璃", "청금석", "琉璃", 0xFF005CAF),
    ("TOKIWA", "常磐", "Evergreen", "常磐", "상록", "常磐", 0xFF1B813E),
    ("KIKYO", "桔梗", "Bellflower", "桔梗", "도라지", "桔梗", 0xFF6A4C9C),
    ("YAMABUKI", "山吹", "Kerria gold", "山吹", "황매화", "山吹", 0xFFFFB11B),
    ("NADESHIKO", "撫子", "Dianthus pink", "撫子", "패랭이", "撫子", 0xFFDC9FB4),
    ("BENIHI", "紅緋", "Crimson", "紅緋", "홍비", "紅緋", 0xFFF75C2F),
]

ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "surfaceTint", "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    "surfaceContainerLow", "surfaceContainerLowest", "surfaceDim",
    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
]

INDENT = " " * 4

EINK_TAIL = '''    /**
     * 水墨屏主题（Anx Reader 风格）：纯白底面、纯黑墨迹、清晰 1dp 描边、零灰阶浑浊与零阴影，
     * 选中后自动等效开启减少动画以贴合电子墨水屏刷新特性。
     */
    EINK(
        labelText = { tr("水墨屏", "E-Ink", "E-Ink", "E-Ink", "水墨螢幕") },
        seed = Color(0xFF000000),
        light = EInkColorScheme,
        dark = EInkColorScheme,
    ),
    ;

    val label: String get() = labelText()

    companion object {
        /** 供「主题色」色板展示的彩色种子集合，不含已独立为设备本地显示开关的水墨屏模式。 */
        val colorEntries: List<SeedTheme> = entries.filter { it != EINK }
    }
}

/** 播放器覆盖层在水墨屏模式下的高对比度纯黑单色配色：保留视频画面的深色遮罩语义，同时避免亮白卡片压在黑色视频上。 */
val EInkPlayerColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF222222),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFE6E6E6),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFD9D9D9),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    error = Color(0xFFFFFFFF),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF262626),
    onErrorContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFF555555),
    scrim = Color(0xCC000000),
    surfaceBright = Color(0xFF242424),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
)

private val EInkColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF000000),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF1A1A1A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDEDED),
    onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF222222),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF000000),
    inverseOnSurface = Color(0xFFFFFFFF),
    error = Color(0xFF000000),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE6E6E6),
    onErrorContainer = Color(0xFF000000),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFF222222),
    scrim = Color(0x99000000),
    surfaceBright = Color(0xFFFFFFFF),
    // Subtle neutral ink tint so M3E unchecked ToggleButtons (defaulting to surfaceContainer)
    // remain distinct on #FFFFFF dialogs (surfaceContainerHigh) without gray muddiness.
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF2F2F2),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFFFFFFF),
    primaryFixed = Color(0xFF000000),
    primaryFixedDim = Color(0xFF222222),
    onPrimaryFixed = Color(0xFFFFFFFF),
    onPrimaryFixedVariant = Color(0xFFFFFFFF),
    secondaryFixed = Color(0xFFEBEBEB),
    secondaryFixedDim = Color(0xFFDCDCDC),
    onSecondaryFixed = Color(0xFF000000),
    onSecondaryFixedVariant = Color(0xFF222222),
    tertiaryFixed = Color(0xFFEDEDED),
    tertiaryFixedDim = Color(0xFFDDDDDD),
    onTertiaryFixed = Color(0xFF000000),
    onTertiaryFixedVariant = Color(0xFF222222),
)'''


def scheme_kotlin(seed: int, dark: bool) -> str:
    hct = Hct.from_int(seed)
    scheme_2025 = SchemeTonalSpot(hct, dark, 0.0, spec_version=SPEC)
    scheme_2021 = SchemeTonalSpot(hct, dark, 0.0, spec_version=DARK_SURFACE_SPEC) if dark else None
    builder = "darkColorScheme" if dark else "lightColorScheme"
    lines = [f"{builder}("]
    for role in ROLES:
        source = scheme_2021 if (dark and role in DARK_SURFACE_2021_ROLES) else scheme_2025
        argb = getattr(MaterialDynamicColors, role).get_argb(source) & 0xFFFFFFFF
        lines.append(f"{INDENT * 3}{role} = Color(0x{argb:08X}),")
    lines.append(f"{INDENT * 2})")
    return "\n".join(lines)


def entry_kotlin(
    key: str,
    zh_hans: str,
    en: str,
    ja: str,
    ko: str,
    zh_hant: str,
    seed: int,
) -> str:
    return "\n".join([
        f"{INDENT}{key}(",
        f'{INDENT * 2}labelText = {{ tr("{zh_hans}", "{en}", "{ja}", "{ko}", "{zh_hant}") }},',
        f"{INDENT * 2}seed = Color(0x{seed:08X}),",
        f"{INDENT * 2}light = {scheme_kotlin(seed, False)},",
        f"{INDENT * 2}dark = {scheme_kotlin(seed, True)},",
        f"{INDENT}),",
    ])


def main() -> None:
    body = "\n".join(entry_kotlin(*seed) for seed in SEEDS)
    out = f"""// 由 app/scripts/generate_color_schemes.py 生成，不要手改。改种子色后重新运行该脚本。
package dev.piko.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.piko.shared.i18n.tr

/**
 * 系统取色之外的内置主题。TonalSpot 方案：亮色与强调色遵循 Material 色彩规范 {SPEC} 版，
 * 深色表面与中性色阶梯校准为标准 Material 3 深色灰阶（ColorSpec{DARK_SURFACE_SPEC}：N4/N6/N10/N12/N17/N22/N24），
 * 与 Android `dynamicDarkColorScheme()` 及 `toPureBlack()` 纯黑 OLED 模式保持清晰视觉区隔。
 */
enum class SeedTheme(private val labelText: () -> String, val seed: Color, val light: ColorScheme, val dark: ColorScheme) {{
{body}
{EINK_TAIL}
"""
    target = Path("ui-core/src/commonMain/kotlin/dev/piko/ui/theme/SeedColorSchemes.kt")
    target.write_text(out, encoding="utf-8", newline="\n")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
