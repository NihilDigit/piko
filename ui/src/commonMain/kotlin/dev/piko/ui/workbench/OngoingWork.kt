package dev.piko.ui.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.theme.LocalPikoMotion

internal data class WorkspaceSurface(val contextId: Long, val minimize: () -> Unit)
internal val LocalWorkspaceSurface = staticCompositionLocalOf<WorkspaceSurface?> { null }
internal val LocalShowOngoingWork = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun WorkspaceControls(compact: Boolean = false) {
    val model = LocalWorkspaceContexts.current ?: return
    if (isDesktopLayout()) return
    val show = LocalShowOngoingWork.current ?: return
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.motionScheme
    val reduced = LocalPikoMotion.current.reduced
    var displayedCount by remember(model) {
        mutableIntStateOf(if (model.tabButtonEmphasized && !reduced) model.tabCountBeforeCreation else contexts.size)
    }
    LaunchedEffect(contexts.size, model.tabCreationRevision) {
        displayedCount = contexts.size
    }
    IconButton(
        onClick = show,
        modifier = Modifier.semantics { contentDescription = "标签总览" },
    ) {
        Box(
            Modifier.size(24.dp).border(2.dp, LocalContentColor.current, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = displayedCount,
                modifier = Modifier.size(20.dp).clipToBounds(),
                contentAlignment = Alignment.Center,
                label = "标签数量",
                transitionSpec = {
                    (if (model.tabButtonEmphasized && targetState > initialState && !reduced) {
                        slideInVertically(scheme.fastEffectsSpec()) { it } togetherWith
                            slideOutVertically(scheme.fastEffectsSpec()) { -it }
                    } else EnterTransition.None togetherWith ExitTransition.None).using(null)
                },
            ) { count -> Text("$count", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

internal fun workspaceTitle(model: WorkspaceViewModel, context: WorkspaceContext): String = when (context) {
    is WorkspaceContext.File -> "文件"
    is WorkspaceContext.Duplicate -> "查重：${context.root.name}"
    is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.summary()?.title ?: "添加链接"
}

@Composable
internal fun OngoingWorkSheet(model: WorkspaceViewModel, onDismiss: () -> Unit) {
    val wide = isDesktopLayout()
    LaunchedEffect(wide) { if (wide) onDismiss() }
    if (wide) return
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val files by model.driveWorkspace.tabsFlow.collectAsStateWithLifecycle()
    val caption = rememberCaptionSlot()
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().then(caption.modifier).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${contexts.size} 个标签页", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                TooltipIconButton(Icons.Outlined.Close, "关闭标签总览", onDismiss)
                caption.buttons?.invoke()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.newFileContext(); onDismiss() }) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建标签")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { model.requestCloseOtherContexts(active) }, enabled = contexts.size > 1) { Text("关闭其他标签") }
            }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(contexts, key = { it.id }) { context ->
                    val file = files.firstOrNull { it.id == context.id }
                    val title = file?.stack?.lastOrNull()?.name ?: workspaceTitle(model, context)
                    Surface(onClick = { model.activateContext(context.id); onDismiss() }, shape = RoundedCornerShape(20.dp),
                        border = if (context.id == active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column {
                            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (context) {
                                    is WorkspaceContext.File -> Icons.Outlined.Folder
                                    is WorkspaceContext.Duplicate -> Icons.Outlined.FileCopy
                                    is WorkspaceContext.ImportDraft -> Icons.Outlined.Link
                                }
                                Icon(icon, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (context !is WorkspaceContext.File || contexts.size > 1)
                                    TooltipIconButton(Icons.Outlined.Close, "关闭 $title", { model.requestCloseContext(context.id) })
                                else Spacer(Modifier.width(48.dp))
                            }
                            val snapshot = model.pageSnapshots[context.id]
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.75f), contentAlignment = Alignment.TopCenter) {
                                    if (snapshot != null) Image(
                                        bitmap = snapshot,
                                        contentDescription = "页面快照：$title",
                                        contentScale = ContentScale.FillWidth,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize(),
                                    ) else Text(
                                        "尚无页面快照",
                                        modifier = Modifier.padding(20.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
