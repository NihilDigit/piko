package dev.piko.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveAppearancePrefsTest {

    @Test
    fun jsonRoundTripPreservesAllThreeViews() {
        val custom = DriveAppearancePrefs(
            list = ListViewStylePrefs(
                folderStyle = FolderStyle.SKEUOMORPHIC,
                showParsedTags = false,
                detailFields = listOf(DetailField.VAULT_STATUS, DetailField.SIZE, DetailField.EXTENSION),
                showSecondaryName = false,
                compactDensity = true,
            ),
            grid = GridViewStylePrefs(
                folderStyle = FolderStyle.MINIMAL_ICON,
                corners = CornerSlots(
                    topStart = CardBadgeKind.EXTENSION,
                    topEnd = CardBadgeKind.FILE_SIZE,
                    bottomStart = CardBadgeKind.STATUS_BADGES,
                    bottomEnd = CardBadgeKind.DURATION,
                ),
                showTitle = true,
                showDetailLine = false,
                detailFields = listOf(DetailField.ADDED_TIME, DetailField.DURATION),
            ),
            gallery = GalleryViewStylePrefs(
                folderStyle = FolderStyle.SKEUOMORPHIC,
                corners = CornerSlots(
                    topStart = CardBadgeKind.NONE,
                    topEnd = CardBadgeKind.RESOLUTION,
                    bottomStart = CardBadgeKind.FILE_SIZE,
                    bottomEnd = CardBadgeKind.DURATION,
                ),
                showTitle = true,
                showDetailLine = true,
                detailFields = listOf(DetailField.PARSED_TAGS, DetailField.SIZE),
            ),
        )

        val encoded = custom.encodeJson()
        assertEquals(custom, DriveAppearancePrefs.decodeJson(encoded))
    }

    @Test
    fun corruptAndUnknownEnumValuesFallBackGracefully() {
        assertEquals(DriveAppearancePrefs.DEFAULT, DriveAppearancePrefs.decodeJson(null))
        assertEquals(DriveAppearancePrefs.DEFAULT, DriveAppearancePrefs.decodeJson(""))
        assertEquals(DriveAppearancePrefs.DEFAULT, DriveAppearancePrefs.decodeJson("{not-json"))

        val partial = """
            {
              "v": 99,
              "list": {
                "folderStyle": "FUTURE_STYLE",
                "showParsedTags": false,
                "detailFields": ["DURATION", "UNKNOWN_FIELD", "SIZE", "DURATION"],
                "compactDensity": true
              },
              "grid": {
                "corners": {
                  "topStart": "UNKNOWN_BADGE",
                  "bottomEnd": "FILE_SIZE"
                },
                "showTitle": false
              }
            }
        """.trimIndent()

        val decoded = DriveAppearancePrefs.decodeJson(partial)
        assertEquals(FolderStyle.MINIMAL_ICON, decoded.list.folderStyle)
        assertEquals(false, decoded.list.showParsedTags)
        assertEquals(listOf(DetailField.DURATION, DetailField.SIZE), decoded.list.detailFields)
        assertEquals(true, decoded.list.compactDensity)
        assertEquals(GridViewStylePrefs.DEFAULT_CORNERS.topStart, decoded.grid.corners.topStart)
        assertEquals(CardBadgeKind.FILE_SIZE, decoded.grid.corners.bottomEnd)
        assertEquals(false, decoded.grid.showTitle)
        assertEquals(GalleryViewStylePrefs.DEFAULT, decoded.gallery)
    }

    @Test
    fun cornerAssignDeduplicatesNonEmptyBadgesAndAllowsMultipleNone() {
        val initial = CornerSlots(
            topStart = CardBadgeKind.STATUS_BADGES,
            topEnd = CardBadgeKind.RESOLUTION,
            bottomStart = CardBadgeKind.PARSED_TAGS,
            bottomEnd = CardBadgeKind.DURATION,
        )
        // Moving DURATION to topStart clears bottomEnd automatically
        val moved = initial.assign(CornerPosition.TOP_START, CardBadgeKind.DURATION)
        assertEquals(CardBadgeKind.DURATION, moved.topStart)
        assertEquals(CardBadgeKind.RESOLUTION, moved.topEnd)
        assertEquals(CardBadgeKind.PARSED_TAGS, moved.bottomStart)
        assertEquals(CardBadgeKind.NONE, moved.bottomEnd)

        // Assigning NONE only clears the target slot
        val cleared = moved.assign(CornerPosition.TOP_END, CardBadgeKind.NONE)
        assertEquals(CardBadgeKind.DURATION, cleared.topStart)
        assertEquals(CardBadgeKind.NONE, cleared.topEnd)
        assertEquals(CardBadgeKind.PARSED_TAGS, cleared.bottomStart)
        assertEquals(CardBadgeKind.NONE, cleared.bottomEnd)
    }

    @Test
    fun toggleDetailFieldPreservesOrderAndResetRestoresSingleView() {
        val start = listOf(DetailField.SIZE, DetailField.DURATION)
        val added = toggleDetailField(start, DetailField.VAULT_STATUS)
        assertEquals(listOf(DetailField.SIZE, DetailField.DURATION, DetailField.VAULT_STATUS), added)
        val removed = toggleDetailField(added, DetailField.SIZE)
        assertEquals(listOf(DetailField.DURATION, DetailField.VAULT_STATUS), removed)

        val modified = DriveAppearancePrefs(
            list = ListViewStylePrefs(compactDensity = true),
            grid = GridViewStylePrefs(showTitle = false),
            gallery = GalleryViewStylePrefs(showTitle = true),
        )
        val resetGrid = modified.reset(DriveAppearanceMode.GRID)
        assertEquals(true, resetGrid.list.compactDensity)
        assertEquals(GridViewStylePrefs.DEFAULT, resetGrid.grid)
        assertEquals(true, resetGrid.gallery.showTitle)
    }
}
