package dev.piko.ui.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val count = contexts.size
    val hasMultiple = count > 1
    val reduced = LocalPikoMotion.current.reduced
    val scale by animateFloatAsState(
        targetValue = if (model.tabButtonEmphasized && !reduced) 1.18f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "tabBadgeScale",
    )
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = show,
        modifier = Modifier.semantics { contentDescription = "标签总览" },
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
                color = if (hasMultiple) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (hasMultiple) colors.onSecondaryContainer else LocalContentColor.current,
                border = BorderStroke(
                    width = 1.8.dp,
                    color = if (hasMultiple) colors.primary else LocalContentColor.current,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (hasMultiple) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
    val colors = MaterialTheme.colorScheme
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = colors.surfaceContainerHigh) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().then(caption.modifier).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${contexts.size} 个标签页", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                TooltipIconButton(Icons.Outlined.Close, "关闭标签总览", onDismiss)
                caption.buttons?.invoke()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.newFileContext(); onDismiss() }) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建标签")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { model.requestCloseOtherContexts(active) },
                    enabled = contexts.any { it.id != active && model.canCloseContext(it.id) },
                ) { Text("关闭其他标签") }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(contexts, key = { it.id }) { context ->
                    val isActive = context.id == active
                    val file = files.firstOrNull { it.id == context.id }
                    val title = file?.stack?.lastOrNull()?.name ?: workspaceTitle(model, context)
                    val icon = when (context) {
                        is WorkspaceContext.File -> Icons.Outlined.Folder
                        is WorkspaceContext.Duplicate -> Icons.Outlined.FileCopy
                        is WorkspaceContext.ImportDraft -> Icons.Outlined.Link
                    }
                    val canClose = model.canCloseContext(context.id)
                    val cardContent: @Composable () -> Unit = {
                        Surface(
                            onClick = { model.activateContext(context.id); onDismiss() },
                            shape = RoundedCornerShape(20.dp),
                            border = if (isActive) BorderStroke(2.5.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
                            color = if (isActive) colors.secondaryContainer else colors.surfaceContainerHighest,
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isActive) colors.primary else colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isActive) colors.onSecondaryContainer else colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (canClose) {
                                        TooltipIconButton(Icons.Outlined.Close, "关闭 $title", { model.requestCloseContext(context.id) })
                                    } else {
                                        Spacer(Modifier.width(40.dp))
                                    }
                                }
                                val snapshot = model.pageSnapshots[context.id]
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = colors.surface,
                                ) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(.75f), contentAlignment = Alignment.Center) {
                                        if (snapshot != null) {
                                            Image(
                                                bitmap = snapshot,
                                                contentDescription = "页面快照：$title",
                                                contentScale = ContentScale.FillWidth,
                                                alignment = Alignment.TopCenter,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = colors.outlineVariant,
                                                modifier = Modifier.size(36.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (canClose) {
                        var swipeRequested by remember { mutableStateOf(false) }
                        LaunchedEffect(swipeRequested) {
                            if (swipeRequested) {
                                swipeRequested = false
                                model.requestCloseContext(context.id)
                            }
                        }
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    swipeRequested = true
                                }
                                false
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {},
                        ) {
                            cardContent()
                        }
                    } else {
                        cardContent()
                    }
                }
            }
        }
    }
}
