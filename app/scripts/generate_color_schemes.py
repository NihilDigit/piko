"""Generates app/src/main/kotlin/dev/piko/ui/theme/SeedColorSchemes.kt.

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

# Traditional Japanese colours, values as nipponcolors.com serves them
# (/php/io.php?color=<romaji>, read 2026-09-23). The kanji names double as the
# Chinese labels.
SEEDS = [
    ("RURI", "瑠璃", 0xFF005CAF),
    ("TOKIWA", "常磐", 0xFF1B813E),
    ("KIKYO", "桔梗", 0xFF6A4C9C),
    ("YAMABUKI", "山吹", 0xFFFFB11B),
    ("NADESHIKO", "撫子", 0xFFDC9FB4),
    ("BENIHI", "紅緋", 0xFFF75C2F),
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


def scheme_kotlin(seed: int, dark: bool) -> str:
    scheme = SchemeTonalSpot(Hct.from_int(seed), dark, 0.0, spec_version=SPEC)
    builder = "darkColorScheme" if dark else "lightColorScheme"
    lines = [f"{builder}("]
    for role in ROLES:
        argb = getattr(MaterialDynamicColors, role).get_argb(scheme) & 0xFFFFFFFF
        lines.append(f"{INDENT * 3}{role} = Color(0x{argb:08X}),")
    lines.append(f"{INDENT * 2})")
    return "\n".join(lines)


def entry_kotlin(key: str, label: str, seed: int) -> str:
    return "\n".join([
        f"{INDENT}{key}(",
        f'{INDENT * 2}label = "{label}",',
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

/** 系统取色之外的内置主题。TonalSpot 方案，Material 色彩规范 {SPEC} 版。 */
enum class SeedTheme(val label: String, val seed: Color, val light: ColorScheme, val dark: ColorScheme) {{
{body}
}}
"""
    target = Path("app/src/main/kotlin/dev/piko/ui/theme/SeedColorSchemes.kt")
    target.write_text(out, encoding="utf-8", newline="\n")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
