package dev.piko.ui.screens.settings

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.media.cache.CacheCategory
import dev.piko.shared.media.cache.CacheCategoryStats
import dev.piko.shared.media.cache.CachePolicy
import dev.piko.shared.media.cache.CacheSummary
import dev.piko.ui.components.detailLineParts
import dev.piko.ui.components.formatTreeIndexProgressLabel
import dev.piko.ui.components.resolveEffectiveFolderStyle
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.EInkPlayerColorScheme
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.islandBorderColor
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EInkAndCacheUiTest {
    @Test
    fun eInkThemeUsesPureWhiteSurfacesPureBlackInkAndCrispBorder() {
        val scheme = SeedTheme.EINK.light
        assertEquals(Color.White, scheme.background)
        assertEquals(Color.White, scheme.surface)
        assertEquals(Color.White, scheme.surfaceContainerLow)
        assertEquals(Color.White, scheme.surfaceContainerHigh)
        // surfaceContainer stays distinct from #FFFFFF dialog surfaceContainerHigh so unchecked
        // M3E ToggleButtons remain clearly visible in E-Ink mode.
        assertNotEquals(scheme.surfaceContainerHigh, scheme.surfaceContainer)
        assertEquals(Color.Black, scheme.primary)
        assertEquals(Color.Black, scheme.onSurface)
        assertEquals(Color.Black, scheme.outline)
        assertEquals(Color.Transparent, scheme.surfaceTint)
        assertEquals(Color.Black, scheme.islandBorderColor)
        assertEquals(SeedTheme.EINK.light, SeedTheme.EINK.dark)

        assertTrue(Appearance(seed = SeedTheme.EINK).isEInk)
        assertTrue(Appearance(seed = SeedTheme.RURI, einkMode = true).isEInk)
        assertEquals(SeedTheme.RURI, Appearance(seed = SeedTheme.RURI, einkMode = true).effectiveSeed)
        assertFalse(Appearance(seed = SeedTheme.RURI, einkMode = false).isEInk)
        assertFalse(SeedTheme.EINK in SeedTheme.colorEntries)

        // Player overlay stays dark monochrome in E-Ink mode with a visible non-black loading pill container.
        assertEquals(Color.Black, EInkPlayerColorScheme.background)
        assertEquals(Color.White, EInkPlayerColorScheme.onPrimaryContainer)
        assertNotEquals(Color.Black, EInkPlayerColorScheme.primaryContainer)
        assertEquals(Color.White, EInkPlayerColorScheme.outline)
        assertEquals(Color.Transparent, EInkPlayerColorScheme.surfaceTint)
    }

    @Test
    fun eInkModeDrivesMotionScaleMinimalFolderStyleAndUnclippedSegmentedCorners() {
        val motion = PikoMotionScale()
        assertFalse(motion.reduced)
        motion.einkReduced = true
        assertTrue(motion.reduced)
        assertEquals(0f, motion.scaleFactor)
        motion.einkReduced = false
        assertFalse(motion.reduced)

        assertEquals(FolderStyle.MINIMAL_ICON, resolveEffectiveFolderStyle(FolderStyle.SKEUOMORPHIC, isEInk = true))
        assertEquals(FolderStyle.SKEUOMORPHIC, resolveEffectiveFolderStyle(FolderStyle.SKEUOMORPHIC, isEInk = false))

        val style = SettingsStyle(
            horizontalPadding = 16.dp,
            verticalPadding = 10.dp,
            minHeight = 56.dp,
            iconSize = 20.dp,
            iconGap = 16.dp,
            outerCorner = 12.dp,
            innerCorner = 4.dp,
            itemGap = 2.dp,
            titleStyle = TextStyle.Default,
            supportingStyle = TextStyle.Default,
            trailingChoices = true,
            swatchTarget = 44.dp,
            swatchSize = 36.dp,
        )
        val outer = CornerSize(12.dp)
        val inner = CornerSize(4.dp)

        val single = settingsItemShape(style, topOuter = true, bottomOuter = true)
        assertEquals(outer, single.topStart)
        assertEquals(outer, single.topEnd)
        assertEquals(outer, single.bottomStart)
        assertEquals(outer, single.bottomEnd)

        val first = settingsItemShape(style, topOuter = true, bottomOuter = false)
        assertEquals(outer, first.topStart)
        assertEquals(outer, first.topEnd)
        assertEquals(inner, first.bottomStart)
        assertEquals(inner, first.bottomEnd)

        val middle = settingsItemShape(style, topOuter = false, bottomOuter = false)
        assertEquals(inner, middle.topStart)
        assertEquals(inner, middle.topEnd)
        assertEquals(inner, middle.bottomStart)
        assertEquals(inner, middle.bottomEnd)

        val last = settingsItemShape(style, topOuter = false, bottomOuter = true)
        assertEquals(inner, last.topStart)
        assertEquals(inner, last.topEnd)
        assertEquals(outer, last.bottomStart)
        assertEquals(outer, last.bottomEnd)
    }

    @Test
    fun settingsSectionsIncludeStorageAndPreserveCustomizeOrder() {
        val entries = SettingsSection.entries
        assertTrue(SettingsSection.Storage in entries)
        assertEquals(
            entries.indexOf(SettingsSection.Appearance) + 1,
            entries.indexOf(SettingsSection.Customize),
        )
    }

    @Test
    fun cachePolicyLabelsFormatBoundariesCleanly() {
        assertEquals("512 MiB", formatCacheCapLabel(512L * 1024L * 1024L))
        assertEquals("2 GiB", formatCacheCapLabel(2L * 1024L * 1024L * 1024L))
        assertTrue(formatCacheCapLabel(0L).isNotBlank())
        assertTrue(formatCacheTtlLabel(14).contains("14"))
        assertTrue(formatCacheTtlLabel(0).isNotBlank())
    }

    @Test
    fun storyboardCategoryUsesUserFriendlyWordingWithoutDevJargon() {
        val label = cacheCategoryLabel(CacheCategory.STORYBOARD)
        assertTrue(label.isNotBlank())
        assertFalse(label.contains("雪碧图"))
        assertFalse(label.contains("雪碧圖"))
        assertFalse(label.contains("Storyboard", ignoreCase = true))
        assertFalse(label.contains("Sprite", ignoreCase = true))
    }

    @Test
    fun cacheSpaceLineFormatsRemainingQuotaAndDeviceFreeSpace() {
        val gib = 1024L * 1024L * 1024L
        val capped = CacheSummary(
            totalBytes = 256L * 1024L * 1024L,
            clearableBytes = 128L * 1024L * 1024L,
            deviceFreeBytes = 128L * gib,
            policy = CachePolicy(maxTotalBytes = 2L * gib),
        )
        val cappedLine = formatCacheSpaceLine(capped)
        assertTrue(cappedLine.contains("1.88 GB") || cappedLine.contains("1.9 GB") || cappedLine.contains("1.87 GB"), cappedLine)
        assertTrue(cappedLine.contains("128"), cappedLine)

        val unlimited = CacheSummary(
            totalBytes = 256L * 1024L * 1024L,
            clearableBytes = 128L * 1024L * 1024L,
            deviceFreeBytes = 64L * gib,
            policy = CachePolicy(maxTotalBytes = 0L),
        )
        val unlimitedLine = formatCacheSpaceLine(unlimited)
        assertTrue(unlimitedLine.contains("64"), unlimitedLine)
        assertFalse(unlimitedLine.contains("·"))

        val unknown = CacheSummary(
            deviceFreeBytes = 0L,
            policy = CachePolicy(maxTotalBytes = 0L),
        )
        assertEquals("", formatCacheSpaceLine(unknown))
    }

    @Test
    fun uiFormattersContainZeroMiddleDotsAcrossAllLocales() {
        val forbidden = charArrayOf('·', '•', '・', '∙', '‧')
        fun assertZeroMiddleDots(label: String, value: String) {
            for (dot in forbidden) {
                assertFalse(
                    dot in value,
                    "$label must not contain middle-dot '$dot', got: $value",
                )
            }
        }

        val previous = PikoLocale.preference
        val gib = 1024L * 1024L * 1024L
        val capped = CacheSummary(
            totalBytes = 256L * 1024L * 1024L,
            clearableBytes = 128L * 1024L * 1024L,
            deviceFreeBytes = 128L * gib,
            policy = CachePolicy(maxTotalBytes = 2L * gib),
        )
        val file = FileStat(kind = FileKind.FILE, id = "v1", name = "Episode01.mkv", size = "104857600")
        try {
            val locales = listOf(AppLanguage.ZH_CN, AppLanguage.ZH_HANT, AppLanguage.EN, AppLanguage.JA, AppLanguage.KO)
            for (lang in locales) {
                PikoLocale.apply(lang)
                assertZeroMiddleDots("formatCacheSpaceLine[$lang]", formatCacheSpaceLine(capped))
                assertZeroMiddleDots("formatCacheClearableSubtitle[$lang]", formatCacheClearableSubtitle(capped.clearableBytes))
                for (category in CacheCategory.entries) {
                    assertZeroMiddleDots("cacheCategoryLabel($category)[$lang]", cacheCategoryLabel(category))
                    val populated = CacheCategoryStats(category = category, bytes = 64L * 1024L * 1024L, fileCount = 12)
                    val emptyCount = CacheCategoryStats(category = category, bytes = 0L, fileCount = 0)
                    assertZeroMiddleDots("formatCacheCategorySupporting($category, populated)[$lang]", formatCacheCategorySupporting(category, populated))
                    assertZeroMiddleDots("formatCacheCategorySupporting($category, empty)[$lang]", formatCacheCategorySupporting(category, emptyCount))
                }
                val treeCounts = PikoDriveRepository.TreeBuildProgress(
                    isBuilding = true,
                    foldersRead = 12,
                    filesRead = 345,
                )
                val treeManifests = treeCounts.copy(manifestsRead = 3, manifestsTotal = 8)
                assertZeroMiddleDots("formatTreeIndexProgressLabel(counts)[$lang]", formatTreeIndexProgressLabel(treeCounts))
                assertZeroMiddleDots("formatTreeIndexProgressLabel(manifests)[$lang]", formatTreeIndexProgressLabel(treeManifests))

                val detailParts = file.detailLineParts(
                    fields = listOf(DetailField.SIZE, DetailField.PARSED_TAGS, DetailField.EXTENSION),
                    tags = listOf("1080P", "HEVC", "FLAC"),
                )
                assertEquals(listOf("1080P", "HEVC", "MKV"), detailParts.drop(1))
                detailParts.forEach { part ->
                    assertZeroMiddleDots("detailLineParts[$lang]", part)
                }
            }
        } finally {
            PikoLocale.apply(previous)
        }
    }
}
