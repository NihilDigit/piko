package dev.piko.ui.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular

data class WorkspaceSurface(val contextId: Long, val minimize: () -> Unit)
val LocalWorkspaceSurface = staticCompositionLocalOf<WorkspaceSurface?> { null }
val LocalShowOngoingWork = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkspaceControls(compact: Boolean = false) {
    val model = LocalWorkspaceContexts.current ?: return
    if (isDesktopLayout()) return
    val show = LocalShowOngoingWork.current ?: return
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val count = contexts.size
    val hasMultiple = count > 1
    val reduced = LocalPikoMotion.current.reduced
    val scale by animateFloatAsState(
        targetValue = if (model.tabButtonEmphasized && !reduced) 1.18f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "tabBadgeScale",
    )
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = show,
        modifier = Modifier.semantics { contentDescription = tr("标签总览", "Tab overview", "タブ一覧", "탭 개요", "分頁總覽") },
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            if (hasMultiple) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .offset(x = 3.dp, y = (-3).dp)
                        .border(1.5.dp, colors.primary.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                )
            }
            Surface(
                modifier = Modifier
                    .size(22.dp)
                    .then(if (hasMultiple) Modifier.offset(x = (-1).dp, y = 1.dp) else Modifier),
                shape = RoundedCornerShape(6.dp),
                color = if (hasMultiple) colors.secondaryContainer else Color.Transparent,
                contentColor = if (hasMultiple) colors.onSecondaryContainer else LocalContentColor.current,
                border = BorderStroke(
                    width = 1.8.dp,
                    color = if (hasMultiple) colors.primary else LocalContentColor.current,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelMedium.tabular,
                        fontWeight = if (hasMultiple) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
