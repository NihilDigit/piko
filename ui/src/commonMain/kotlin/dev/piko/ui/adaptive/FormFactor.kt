package dev.piko.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** Platform identity for native integrations; it does not select a workspace owner. */
enum class FormFactor { Desktop, Mobile }

/** Space for a file header, persistent tabs and useful content below them. */
internal fun supportsWorkspaceChrome(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 840f && heightDp >= 480f

/** Kept as a source-compatible name for existing adaptive surfaces. */
@Composable
fun isDesktopLayout(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return supportsWorkspaceChrome(size.width / density, size.height / density)
}
