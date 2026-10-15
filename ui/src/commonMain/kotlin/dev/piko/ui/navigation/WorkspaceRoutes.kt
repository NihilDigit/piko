package dev.piko.ui.navigation

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
