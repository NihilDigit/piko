package dev.piko.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.darkColorScheme
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DarkColorSchemeCalibrationTest {

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun standardDarkSurfaceLadderFollowsM3TonesAndAvoidsPureBlack() {
        for (theme in SeedTheme.colorEntries) {
            val dark = theme.dark
            assertNotEquals(
                Color.Black,
                dark.surfaceContainerLowest,
                "${theme.name} surfaceContainerLowest should be Tone 4, not #000000",
            )
            assertEquals(dark.surface, dark.background, "${theme.name} background should match surface")
            assertEquals(dark.surface, dark.surfaceDim, "${theme.name} surfaceDim should match surface")
            assertTrue(
                dark.surface.luminance() >= 0.005f,
                "${theme.name} surface luminance (${dark.surface.luminance()}) should be standard Tone 6 (>= 0.005)",
            )
            assertNull(
                dark.islandBorderColor,
                "${theme.name} standard dark theme should rely on surface elevation rather than OLED hairline border",
            )

            val ladder = listOf(
                "black" to Color.Black,
                "surfaceContainerLowest" to dark.surfaceContainerLowest,
                "surface" to dark.surface,
                "surfaceContainerLow" to dark.surfaceContainerLow,
                "surfaceContainer" to dark.surfaceContainer,
                "surfaceContainerHigh" to dark.surfaceContainerHigh,
                "surfaceContainerHighest" to dark.surfaceContainerHighest,
                "surfaceBright" to dark.surfaceBright,
            )
            for (i in 0 until ladder.lastIndex) {
                val (lowName, lowColor) = ladder[i]
                val (highName, highColor) = ladder[i + 1]
                assertTrue(
                    lowColor.luminance() < highColor.luminance(),
                    "${theme.name}: $lowName (${lowColor.luminance()}) must be darker than $highName (${highColor.luminance()})",
                )
            }

            // Desktop island hierarchy: islandSurface (surface) < islandHeader (Low) < frame (High)
            assertTrue(dark.islandSurface.luminance() < dark.islandHeader.luminance())
            assertTrue(dark.islandHeader.luminance() < dark.frame.luminance())
        }
    }

    @Test
    fun pureBlackOledIsClearlyDarkerThanStandardDark() {
        for (theme in SeedTheme.colorEntries) {
            val standard = theme.dark
            val oled = standard.toPureBlack()

            assertEquals(Color.Black, oled.background)
            assertEquals(Color.Black, oled.surface)
            assertEquals(Color.Black, oled.surfaceDim)
            assertEquals(Color.Black, oled.surfaceContainerLowest)
            assertNotNull(
                oled.islandBorderColor,
                "${theme.name} pureBlack OLED theme should enable subtle island hairline border",
            )

            assertTrue(oled.surfaceContainerLow.luminance() < standard.surfaceContainerLow.luminance())
            assertTrue(oled.surfaceContainer.luminance() < standard.surfaceContainer.luminance())
            assertTrue(oled.surfaceContainerHigh.luminance() < standard.surfaceContainerHigh.luminance())
            assertTrue(oled.surfaceContainerHighest.luminance() < standard.surfaceContainerHighest.luminance())
            assertTrue(oled.surfaceBright.luminance() < standard.surfaceBright.luminance())

            // Ensure 8-bit sRGB quantization does not collapse surfaceContainerLow into #000000
            assertNotEquals(
                Color.Black.toArgb(),
                oled.surfaceContainerLow.toArgb(),
                "${theme.name} OLED surfaceContainerLow must stay distinguishable from #000000 in 8-bit sRGB",
            )
            assertTrue(oled.islandSurface.luminance() < oled.islandHeader.luminance())
            assertTrue(oled.islandHeader.luminance() < oled.frame.luminance())

            val oledContainers = listOf(
                oled.surface,
                oled.surfaceContainerLow,
                oled.surfaceContainer,
                oled.surfaceContainerHigh,
                oled.surfaceContainerHighest,
                oled.surfaceBright,
            )
            for (i in 0 until oledContainers.lastIndex) {
                assertNotEquals(
                    oledContainers[i].toArgb(),
                    oledContainers[i + 1].toArgb(),
                    "${theme.name} OLED step $i must remain distinct in 8-bit sRGB",
                )
                assertTrue(
                    oledContainers[i].luminance() < oledContainers[i + 1].luminance(),
                    "${theme.name} OLED container step $i must be strictly ascending",
                )
            }
        }
    }

    @Test
    fun colorSpec2025DynamicDarkAvoidsPureBlackCanvasAndPreservesOledLadder() {
        // Android 16+ ColorSpec2025 TonalSpot dark surface ladder (surfaceContainerLowest = #000000, surface = Tone 4)
        val dynamic2025 = darkColorScheme(
            background = Color(0xFF0C0E12),
            surface = Color(0xFF0C0E12),
            surfaceDim = Color(0xFF0C0E12),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF111318),
            surfaceContainer = Color(0xFF171A1F),
            surfaceContainerHigh = Color(0xFF1C2026),
            surfaceContainerHighest = Color(0xFF22262D),
            surfaceBright = Color(0xFF282C34),
            outlineVariant = Color(0xFF43474E),
        )

        // Standard dynamic dark keeps non-black page canvas and does not trigger OLED hairline border
        assertNotEquals(Color.Black.toArgb(), dynamic2025.islandSurface.toArgb())
        assertNull(
            dynamic2025.islandBorderColor,
            "ColorSpec2025 standard dark surface (Tone 4) should not falsely trigger OLED hairline border",
        )

        val oled = dynamic2025.toPureBlack()
        assertEquals(Color.Black, oled.surface)
        assertNotNull(oled.islandBorderColor)
        assertNotEquals(Color.Black.toArgb(), oled.surfaceContainerLow.toArgb())

        val steps = listOf(
            oled.surface,
            oled.surfaceContainerLow,
            oled.surfaceContainer,
            oled.surfaceContainerHigh,
            oled.surfaceContainerHighest,
            oled.surfaceBright,
        )
        for (i in 0 until steps.lastIndex) {
            assertNotEquals(steps[i].toArgb(), steps[i + 1].toArgb(), "Spec 2025 OLED step $i must remain distinct in 8-bit sRGB")
            assertTrue(steps[i].luminance() < steps[i + 1].luminance(), "Spec 2025 OLED step $i must strictly ascend")
        }
    }

    @Test
    fun standardDarkTextOutlineAndStatusColorsMeetWcagContrast() {
        for (theme in SeedTheme.colorEntries) {
            val dark = theme.dark
            val surfaces = listOf(
                "surface" to dark.surface,
                "surfaceContainer" to dark.surfaceContainer,
                "surfaceContainerHigh" to dark.surfaceContainerHigh,
                "surfaceContainerHighest" to dark.surfaceContainerHighest,
                "surfaceBright" to dark.surfaceBright,
            )
            for ((name, bg) in surfaces) {
                val onSurfaceCr = contrastRatio(dark.onSurface, bg)
                assertTrue(
                    onSurfaceCr >= 7.0f,
                    "${theme.name} onSurface on $name contrast ($onSurfaceCr) must meet WCAG AAA (>= 7.0)",
                )
                val onSurfaceVariantCr = contrastRatio(dark.onSurfaceVariant, bg)
                val requiredVariantCr = if (name == "surfaceBright") 4.5f else 7.0f
                assertTrue(
                    onSurfaceVariantCr >= requiredVariantCr,
                    "${theme.name} onSurfaceVariant on $name contrast ($onSurfaceVariantCr) must be >= $requiredVariantCr",
                )
            }

            assertTrue(
                contrastRatio(dark.outline, dark.surface) >= 3.0f,
                "${theme.name} outline on surface must meet WCAG 3:1 non-text contrast",
            )
            assertTrue(
                contrastRatio(dark.outline, dark.surfaceContainerHighest) >= 3.0f,
                "${theme.name} outline on surfaceContainerHighest must meet WCAG 3:1 non-text contrast",
            )

            val statusColors = listOf(
                "success" to PikoDarkStatusColors.success,
                "info" to PikoDarkStatusColors.info,
                "warning" to PikoDarkStatusColors.warning,
            )
            for ((name, color) in statusColors) {
                assertTrue(
                    contrastRatio(color, dark.surface) >= 7.0f,
                    "${theme.name} status $name on surface must meet WCAG AAA (>= 7.0)",
                )
                assertTrue(
                    contrastRatio(color, dark.surfaceContainer) >= 7.0f,
                    "${theme.name} status $name on surfaceContainer must meet WCAG AAA (>= 7.0)",
                )
            }
        }
    }

    @Test
    fun einkThemeRemainsPureWhiteHighContrast() {
        assertEquals(SeedTheme.EINK.light, SeedTheme.EINK.dark)
        assertEquals(Color.White, SeedTheme.EINK.dark.surface)
        assertEquals(Color.Black, SeedTheme.EINK.dark.onSurface)
    }
}
