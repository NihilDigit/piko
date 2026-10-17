package dev.piko.ui.navigation

/** File-work controls do not operate on a hidden workspace behind tasks, settings or media. */
internal fun isWorkspaceSurface(screen: Screen?, tab: MainTab): Boolean = when (screen) {
    Screen.Home -> tab == MainTab.FILES
    is Screen.Workspace, is Screen.Browse, is Screen.Library -> true
    else -> false
}

internal fun openWorkspaceRoute(stack: List<Screen>, contextId: Long): List<Screen> {
    val target = Screen.Workspace(contextId)
    val existing = stack.indexOf(target)
    return when {
        existing >= 0 -> stack.take(existing + 1)
        stack.lastOrNull() is Screen.Workspace -> stack.dropLast(1) + target
        else -> stack + target
    }
}

/** A completed import can disappear while its media preview is still open. */
internal fun retainWorkspaceRoutes(stack: List<Screen>, liveIds: Set<Long>): List<Screen> =
    stack.filter { it !is Screen.Workspace || it.contextId in liveIds }.ifEmpty { listOf(Screen.Home) }

/** The wide workspace replaces its source page while keeping an already open media page above it. */
internal fun wideWorkspaceRoutes(stack: List<Screen>): List<Screen> {
    val workIndex = stack.indexOfLast { it is Screen.Workspace }
    return if (workIndex < 0) stack else listOf(Screen.Home) + stack.drop(workIndex + 1)
}
