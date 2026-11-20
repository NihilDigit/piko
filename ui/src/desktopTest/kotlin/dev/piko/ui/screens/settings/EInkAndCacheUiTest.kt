package dev.piko.ui.screens.settings

import androidx.compose.ui.graphics.Color
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.media.cache.CacheCategory
import dev.piko.shared.media.cache.CacheCategoryStats
import dev.piko.shared.media.cache.CachePolicy
import dev.piko.shared.media.cache.CacheSummary
import dev.piko.ui.components.detailLineParts
import dev.piko.ui.components.formatTreeIndexProgressLabel
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.SeedTheme
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
        assertFalse(Appearance(seed = SeedTheme.RURI).isEInk)
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
