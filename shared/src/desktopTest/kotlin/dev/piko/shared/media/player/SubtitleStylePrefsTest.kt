package dev.piko.shared.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStylePrefsTest {

    @Test
    fun defaultsMapToCleanOutlineWithoutBackground() {
        val prefs = SubtitleStylePrefs.DEFAULT
        assertTrue(prefs.isDefault)
        val props = prefs.toMpvProperties(platformDefaultFont = "Microsoft YaHei")
        assertEquals("Microsoft YaHei", props["sub-font"])
        assertEquals("38", props["sub-font-size"])
        assertEquals("100", props["sub-pos"])
        assertEquals("yes", props["sub-ass-override"])
        assertEquals("outline-and-shadow", props["sub-border-style"])
        assertEquals("#00000000", props["sub-back-color"])
        assertEquals("#FF000000", props["sub-border-color"])
        assertEquals("2", props["sub-border-size"])
        assertEquals("0", props["sub-shadow-offset"])
    }

    @Test
    fun backgroundToggleSwitchesCleanlyBetweenBoxAndOutline() {
        val withBg = SubtitleStylePrefs(showBackground = true)
        assertFalse(withBg.isDefault)
        val bgProps = withBg.toMpvProperties(platformDefaultFont = "sans-serif")
        assertEquals("background-box", bgProps["sub-border-style"])
        assertEquals("#99000000", bgProps["sub-back-color"])
        assertEquals("#00000000", bgProps["sub-border-color"])
        assertEquals("0", bgProps["sub-border-size"])
        assertEquals("3", bgProps["sub-shadow-offset"])

        val restored = withBg.copy(showBackground = false)
        assertTrue(restored.isDefault)
        val outlineProps = restored.toMpvProperties(platformDefaultFont = "sans-serif")
        assertEquals("outline-and-shadow", outlineProps["sub-border-style"])
        assertEquals("#00000000", outlineProps["sub-back-color"])
        assertEquals("#FF000000", outlineProps["sub-border-color"])
        assertEquals("2", outlineProps["sub-border-size"])
        assertEquals("0", outlineProps["sub-shadow-offset"])
    }

    @Test
    fun customBackgroundColorAndOpacityFormatToMpvArgbs() {
        val midnight80 = SubtitleStylePrefs(
            showBackground = true,
            backgroundColorRgb = SubtitleBackgroundPreset.MidnightBlue.rgb,
            backgroundOpacity = 80,
        )
        assertEquals("#CC102038", midnight80.toMpvProperties("sans-serif")["sub-back-color"])

        val espresso100 = SubtitleStylePrefs(
            showBackground = true,
            backgroundColorRgb = SubtitleBackgroundPreset.WarmEspresso.rgb,
            backgroundOpacity = 100,
        )
        assertEquals("#FF2D1E14", espresso100.toMpvProperties("sans-serif")["sub-back-color"])

        val clamped = SubtitleStylePrefs(
            showBackground = true,
            backgroundColorRgb = 0x7F122920,
            backgroundOpacity = 2,
        ).sanitize()
        assertEquals(0x122920, clamped.backgroundColorRgb)
        assertEquals(SubtitleStylePrefs.MIN_BACKGROUND_OPACITY, clamped.backgroundOpacity)
        assertEquals("#1A122920", clamped.toMpvProperties("sans-serif")["sub-back-color"])

        val tooHigh = SubtitleStylePrefs(showBackground = true, backgroundOpacity = 250).sanitize()
        assertEquals(SubtitleStylePrefs.MAX_BACKGROUND_OPACITY, tooHigh.backgroundOpacity)
    }

    @Test
    fun sanitizeClampsRangeAndCleansCustomFont() {
        val raw = SubtitleStylePrefs(
            showBackground = true,
            fontPreset = SubtitleFontPreset.Custom,
            customFontFamily = "   ",
            fontSize = 999,
            position = -25,
            overrideAssStyles = true,
        )
        val sanitized = raw.sanitize()
        assertEquals(SubtitleFontPreset.Default, sanitized.fontPreset)
        assertNull(sanitized.customFontFamily)
        assertEquals(SubtitleStylePrefs.MAX_FONT_SIZE, sanitized.fontSize)
        assertEquals(SubtitleStylePrefs.MIN_POSITION, sanitized.position)
        assertTrue(sanitized.overrideAssStyles)

        val tooSmall = SubtitleStylePrefs(fontSize = 5, position = 180).sanitize()
        assertEquals(SubtitleStylePrefs.MIN_FONT_SIZE, tooSmall.fontSize)
        assertEquals(SubtitleStylePrefs.MAX_POSITION, tooSmall.position)

        val nonCustomClearsFamily = SubtitleStylePrefs(
            fontPreset = SubtitleFontPreset.Serif,
            customFontFamily = "LXGW WenKai",
        ).sanitize()
        assertEquals(SubtitleFontPreset.Serif, nonCustomClearsFamily.fontPreset)
        assertNull(nonCustomClearsFamily.customFontFamily)
    }

    @Test
    fun fontResolutionAndFallbackAreDeterministic() {
        val custom = SubtitleStylePrefs(
            fontPreset = SubtitleFontPreset.Custom,
            customFontFamily = "  LXGW WenKai  ",
        )
        val defaultResolved = custom.toMpvProperties(platformDefaultFont = "PingFang SC")
        assertEquals("LXGW WenKai", defaultResolved["sub-font"])

        val fallbackResolved = custom.toMpvProperties(
            platformDefaultFont = "sans-serif",
            resolveFontFamily = { prefs ->
                val installed = setOf("Roboto", "Noto Sans CJK SC")
                val target = prefs.customFontFamily
                if (prefs.fontPreset == SubtitleFontPreset.Custom && target != null && target in installed) {
                    target
                } else {
                    "sans-serif"
                }
            },
        )
        assertEquals("sans-serif", fallbackResolved["sub-font"])

        val blankResolverFallback = custom.toMpvProperties(
            platformDefaultFont = "Microsoft YaHei",
            resolveFontFamily = { "   " },
        )
        assertEquals("Microsoft YaHei", blankResolverFallback["sub-font"])
    }

    @Test
    fun assOverrideTogglesSubAssOverrideProperty() {
        val preserved = SubtitleStylePrefs(overrideAssStyles = false).toMpvProperties("sans-serif")
        assertEquals("yes", preserved["sub-ass-override"])

        val forced = SubtitleStylePrefs(overrideAssStyles = true).toMpvProperties("sans-serif")
        assertEquals("force", forced["sub-ass-override"])
    }

    @Test
    fun jsonRoundTripPreservesFieldsAndIgnoresCorruptOrUnknownInput() {
        val original = SubtitleStylePrefs(
            showBackground = true,
            fontPreset = SubtitleFontPreset.Custom,
            customFontFamily = "Noto Sans CJK SC",
            fontSize = 46,
            position = 88,
            overrideAssStyles = true,
            backgroundColorRgb = SubtitleBackgroundPreset.DeepPlum.rgb,
            backgroundOpacity = 75,
        )
        val encoded = original.encodeJson()
        val decoded = SubtitleStylePrefs.decodeJson(encoded)
        assertEquals(original, decoded)

        assertEquals(SubtitleStylePrefs.DEFAULT, SubtitleStylePrefs.decodeJson(null))
        assertEquals(SubtitleStylePrefs.DEFAULT, SubtitleStylePrefs.decodeJson(""))
        assertEquals(SubtitleStylePrefs.DEFAULT, SubtitleStylePrefs.decodeJson("{not-valid-json"))
        // Legacy JSON without backgroundColorRgb/backgroundOpacity falls back cleanly to default #000000 @ 60%
        val withUnknownFields = """{"showBackground":true,"fontSize":500,"position":-10,"futureField":123}"""
        val parsed = SubtitleStylePrefs.decodeJson(withUnknownFields)
        assertTrue(parsed.showBackground)
        assertEquals(SubtitleStylePrefs.MAX_FONT_SIZE, parsed.fontSize)
        assertEquals(SubtitleStylePrefs.MIN_POSITION, parsed.position)
        assertEquals(SubtitleStylePrefs.DEFAULT_BACKGROUND_COLOR_RGB, parsed.backgroundColorRgb)
        assertEquals(SubtitleStylePrefs.DEFAULT_BACKGROUND_OPACITY, parsed.backgroundOpacity)
        assertEquals("#99000000", parsed.formattedBackgroundBoxColor())
    }
}
