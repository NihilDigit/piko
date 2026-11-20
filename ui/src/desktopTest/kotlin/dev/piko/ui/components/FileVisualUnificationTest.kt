package dev.piko.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.data.GridViewStylePrefs
import dev.piko.shared.data.ListViewStylePrefs
import dev.piko.ui.screens.drive.GalleryCanvasTile
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.screens.drive.PosterCard
import dev.piko.ui.screens.transfers.TaskFolderVisual
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileVisualUnificationTest {

    @Test
    fun folderStyleResolversFollowDriveAppearancePrefs() {
        val defaultPrefs = DriveAppearancePrefs.DEFAULT
        assertEquals(FolderStyle.MINIMAL_ICON, resolveLeadingFolderStyle(defaultPrefs))
        assertEquals(FolderStyle.SKEUOMORPHIC, resolvePosterFolderStyle(defaultPrefs))

        val flipped = DriveAppearancePrefs(
            list = ListViewStylePrefs(folderStyle = FolderStyle.SKEUOMORPHIC),
            grid = GridViewStylePrefs(folderStyle = FolderStyle.MINIMAL_ICON),
        )
        assertEquals(FolderStyle.SKEUOMORPHIC, resolveLeadingFolderStyle(flipped))
        assertEquals(FolderStyle.MINIMAL_ICON, resolvePosterFolderStyle(flipped))
    }

    @Test
    fun leadingMetricsScaleProportionallyAcrossRowDensities() {
        assertEquals(24.dp, leadingIconSizeFor(56.dp))
        assertEquals(22.dp, leadingIconSizeFor(44.dp))
        assertEquals(20.dp, leadingIconSizeFor(36.dp))
        assertEquals(18.dp, leadingIconSizeFor(32.dp))
        assertEquals(16.dp, leadingIconSizeFor(24.dp))

        assertTrue(leadingCornerRadiusFor(56.dp) >= leadingCornerRadiusFor(32.dp))
        assertTrue(leadingSkeuomorphicPaddingFor(56.dp) >= leadingSkeuomorphicPaddingFor(32.dp))
    }

    @Test
    fun allListSurfacesRenderMinimalFoldersByDefault() {
        val tags = renderTags(DriveAppearancePrefs.DEFAULT) {
            AllUnifiedListFolderSurfaces()
        }
        assertEquals(
            7,
            tags.count { it == FILE_LEADING_MINIMAL_FOLDER_TAG },
            "Expected all 7 list/tree/picker/share/transfer folder surfaces to use MINIMAL_ICON under default prefs; got $tags",
        )
        assertEquals(
            0,
            tags.count { it == FILE_LEADING_SKEUOMORPHIC_FOLDER_TAG },
            "Expected zero skeuomorphic folders in list surfaces under default prefs; got $tags",
        )
    }

    @Test
    fun switchingListFolderStyleToSkeuomorphicUpdatesAllListSurfacesInUnison() {
        val skeuomorphicListPrefs = DriveAppearancePrefs(
            list = ListViewStylePrefs(folderStyle = FolderStyle.SKEUOMORPHIC),
        )
        val tags = renderTags(skeuomorphicListPrefs) {
            AllUnifiedListFolderSurfaces()
        }
        assertEquals(
            7,
            tags.count { it == FILE_LEADING_SKEUOMORPHIC_FOLDER_TAG },
            "Expected all 7 list/tree/picker/share/transfer folder surfaces to switch to SKEUOMORPHIC; got $tags",
        )
        assertEquals(
            0,
            tags.count { it == FILE_LEADING_MINIMAL_FOLDER_TAG },
            "Expected zero minimal folders when list.folderStyle == SKEUOMORPHIC; got $tags",
        )
    }

    @Test
    fun gridAndGalleryFolderSurfacesFollowGridFolderStyle() {
        val defaultTags = renderTags(DriveAppearancePrefs.DEFAULT) {
            AllUnifiedGridAndGalleryFolderSurfaces()
        }
        assertEquals(
            2,
            defaultTags.count { it == FILE_POSTER_SKEUOMORPHIC_FOLDER_TAG },
            "Expected PosterCard and GalleryCanvasTile to render SKEUOMORPHIC folders by default; got $defaultTags",
        )
        assertEquals(0, defaultTags.count { it == FILE_POSTER_MINIMAL_FOLDER_TAG })

        val minimalGridPrefs = DriveAppearancePrefs(
            grid = GridViewStylePrefs(folderStyle = FolderStyle.MINIMAL_ICON),
        )
        val minimalTags = renderTags(minimalGridPrefs) {
            AllUnifiedGridAndGalleryFolderSurfaces()
        }
        assertEquals(
            2,
            minimalTags.count { it == FILE_POSTER_MINIMAL_FOLDER_TAG },
            "Expected PosterCard and GalleryCanvasTile to switch to MINIMAL_ICON; got $minimalTags",
        )
        assertEquals(0, minimalTags.count { it == FILE_POSTER_SKEUOMORPHIC_FOLDER_TAG })
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun AllUnifiedListFolderSurfaces() {
        val folder = FileStat(kind = FileKind.FOLDER, id = "f1", name = "Anime Season 1")
        val treePresentation = FilePresentation(
            key = "tree:f1",
            name = FileItemName("Anime Season 1"),
            visual = FileVisual("Anime Season 1", isFolder = true),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Drive list row (FileListItem)
            FileListItem(
                file = folder,
                isSelectionMode = false,
                isSelected = false,
                onClick = {},
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = false,
            )
            // 2. Direct authoritative leading visual
            FileListLeadingVisual(file = folder, size = 40.dp)
            // 3. Name-only folder leading visual (TargetRow / AddressBar)
            FolderListLeadingVisual(name = "Downloads", size = 36.dp)
            // 4. MySharesScreen leading visual (FileLeadingVisual)
            FileLeadingVisual(file = folder, isSpoilerBlurred = false, size = 56.dp)
            // 5. InstantSheet / ShareTreeList / ImportWorkspace / FolderMap tree row
            WorkspaceTreeRow(
                name = "Anime Season 1",
                depth = 0,
                icon = Icons.Outlined.Folder,
                expanded = false,
                onExpand = {},
                onOpen = {},
                item = treePresentation,
            )
            // 6. MoveTargetDialog folder picker row
            FolderPickerRow(
                folder = folder,
                enabled = true,
                hint = null,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = {},
            )
            // 7. TransfersScreen folder visual
            TaskFolderVisual(name = "Anime Season 1")
        }
    }

    @Composable
    private fun AllUnifiedGridAndGalleryFolderSurfaces() {
        val folder = FileStat(kind = FileKind.FOLDER, id = "f2", name = "Movies")
        Column(modifier = Modifier.width(240.dp)) {
            PosterCard(
                file = folder,
                isSelectionMode = false,
                isSelected = false,
                isSpoilerBlurred = false,
                isHighlighted = false,
                onClick = {},
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = false,
            )
            GalleryCanvasTile(
                file = folder,
                coverAspectRatio = 16f / 9f,
            )
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun renderTags(
        prefs: DriveAppearancePrefs,
        content: @Composable () -> Unit,
    ): List<String> {
        val scene = ImageComposeScene(width = 480, height = 960, density = Density(1f)) {
            MaterialExpressiveTheme {
                CompositionLocalProvider(LocalDriveAppearancePrefs provides prefs) {
                    content()
                }
            }
        }
        return try {
            scene.render()
            collectTestTags(scene)
        } finally {
            scene.close()
        }
    }

    private fun collectTestTags(scene: ImageComposeScene): List<String> = buildList {
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.TestTag)?.let(::add)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { owner ->
            visit(owner.unmergedRootSemanticsNode)
        }
    }
}
