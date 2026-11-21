package dev.piko.ui.navigation

/** File-work controls do not operate on a hidden workspace behind tasks, settings or media. */
fun isWorkspaceSurface(screen: Screen?, tab: MainTab): Boolean =
    screen == Screen.Home && tab == MainTab.FILES
