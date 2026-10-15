package dev.piko.ui

import dev.piko.ui.navigation.Screen
import dev.piko.ui.navigation.openWorkspaceRoute
import dev.piko.ui.navigation.retainWorkspaceRoutes
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceRoutesTest {
    private val library = Screen.Library("starred", 10L)
    private val firstWork = Screen.Workspace(11L)
    private val secondWork = Screen.Workspace(12L)
    private val player = Screen.VideoPlayer("episode-01", "第一集.mkv")
    private val browse = Screen.Browse(listOf("movies" to "电影"), "movie-01", 20L)

    @Test
    fun openingWorkKeepsTheFileOrLibraryOriginAsItsBackDestination() {
        for (origin in listOf(listOf(Screen.Home), listOf(Screen.Home, library))) {
            assertEquals(origin + firstWork, openWorkspaceRoute(origin, firstWork.contextId))
        }
    }

    @Test
    fun switchingWorkReplacesOnlyTheTopWorkRoute() {
        val source = listOf(Screen.Home, library)
        val first = openWorkspaceRoute(source, firstWork.contextId)
        val second = openWorkspaceRoute(first, secondWork.contextId)
        assertEquals(source + secondWork, second)
        assertEquals(source, second.dropLast(1))
    }

    @Test
    fun openingAnotherWorkFromMediaKeepsThePlayerAndItsOrigin() {
        val source = listOf(Screen.Home, firstWork, player)
        val opened = openWorkspaceRoute(source, secondWork.contextId)
        assertEquals(source + secondWork, opened)
        assertEquals(source, opened.dropLast(1))
    }

    @Test
    fun resumingAnExistingWorkPopsToItsOriginalKeyInsteadOfDuplicatingIt() {
        val source = listOf(Screen.Home, library, firstWork)
        assertEquals(source, openWorkspaceRoute(source, firstWork.contextId))
        val detour = source + listOf(player, secondWork)
        val resumed = openWorkspaceRoute(detour, firstWork.contextId)
        assertEquals(source, resumed)
        assertEquals(1, resumed.count { it == firstWork })
    }

    @Test
    fun automaticWorkRemovalKeepsTheMediaAndBrowseRoutesAboveIt() {
        val source = listOf(Screen.Home, firstWork, player, browse)
        assertEquals(listOf(Screen.Home, player, browse), retainWorkspaceRoutes(source, emptySet()))
    }

    @Test
    fun closingInactiveWorkDoesNotDismissTheCurrentlyOpenWork() {
        val source = listOf(Screen.Home, firstWork, player, secondWork)
        val retained = retainWorkspaceRoutes(source, setOf(secondWork.contextId))
        assertEquals(listOf(Screen.Home, player, secondWork), retained)
        assertEquals(secondWork, retained.last())
        assertEquals(source, retainWorkspaceRoutes(source, setOf(firstWork.contextId, secondWork.contextId)))
    }

    @Test
    fun removingTheOnlyStaleRouteLeavesAValidHomeDestination() {
        assertEquals(listOf(Screen.Home), retainWorkspaceRoutes(listOf(firstWork), emptySet()))
    }
}
