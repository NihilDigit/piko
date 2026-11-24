package dev.piko.ui.workbench

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
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
import dev.piko.shared.presentation.localizedName
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular

internal fun workspaceTitle(model: WorkspaceViewModel, context: WorkspaceContext): String = when (context) {
    is WorkspaceContext.File -> tr("文件", "Files", "ファイル", "파일", "檔案")
    is WorkspaceContext.Duplicate -> tr("查重：${context.root.localizedName}", "Duplicates: ${context.root.localizedName}", "重複検索：${context.root.localizedName}", "중복 검색: ${context.root.localizedName}", "查重：${context.root.localizedName}")
    is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.summary()?.title ?: tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                Text(
                    text = tr("${contexts.size} 个标签页", "${contexts.size} tabs", "${contexts.size} タブ", "탭 ${contexts.size}개", "${contexts.size} 個分頁"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLargeEmphasized.tabular,
                    maxLines = 1,
                )
                TooltipIconButton(Icons.Outlined.Close, tr("关闭标签总览", "Close tab overview", "タブ一覧を閉じる", "탭 개요 닫기", "關閉分頁總覽"), onDismiss)
                caption.buttons?.invoke()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { model.newFileContext(); onDismiss() },
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.text_new_tab_alt))
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { model.requestCloseOtherContexts(active) },
                    enabled = contexts.any { it.id != active && model.canCloseContext(it.id) },
                ) { Text(stringResource(Res.string.text_close_other_tabs)) }
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
                    val pinned = file?.isPinned == true
                    val note = file?.note.orEmpty()
                    val canEditNote = model.canEditContextNote(context.id)
                    val title = file?.stack?.lastOrNull()?.localizedName ?: workspaceTitle(model, context)
                    val icon = when (context) {
                        is WorkspaceContext.File -> Icons.Outlined.Folder
                        is WorkspaceContext.Duplicate -> Icons.Outlined.FileCopy
                        is WorkspaceContext.ImportDraft -> Icons.Outlined.Link
                    }
                    val canClose = !pinned && model.canCloseContext(context.id)
                    val cardBorderColor by animateColorAsState(
                        targetValue = if (isActive) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
                        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "tab_card_border",
                    )
                    val cardColor by animateColorAsState(
                        targetValue = if (isActive) colors.secondaryContainer else colors.surfaceContainerHighest,
                        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "tab_card_bg",
                    )
                    val cardContent: @Composable () -> Unit = {
                        Surface(
                            onClick = { model.activateContext(context.id); onDismiss() },
                            shape = MaterialTheme.shapes.largeIncreased,
                            border = BorderStroke(if (isActive) 2.5.dp else 1.dp, cardBorderColor),
                            color = cardColor,
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
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                        color = if (isActive) colors.onSecondaryContainer else colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.canPinContext(context.id)) {
                                        TooltipIconButton(
                                            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            if (pinned) tr("取消固定标签页", "Unpin tab", "タブの固定を解除", "탭 고정 해제", "取消固定分頁")
                                            else tr("固定标签页", "Pin tab", "タブを固定", "탭 고정", "固定分頁"),
                                            { model.togglePinContext(context.id) },
                                            tint = if (pinned) colors.primary else colors.onSurfaceVariant,
                                        )
                                    }
                                    if (canClose) {
                                        TooltipIconButton(Icons.Outlined.Close, tr("关闭 $title", "Close $title", "$title を閉じる", "$title 닫기", "關閉 $title"), { model.requestCloseContext(context.id) })
                                    }
                                }
                                if (canEditNote) {
                                    val noteColor = if (note.isNotBlank()) {
                                        if (isActive) colors.onSecondaryContainer.copy(alpha = 0.82f) else colors.onSurfaceVariant
                                    } else {
                                        if (isActive) colors.onSecondaryContainer.copy(alpha = 0.58f) else colors.onSurfaceVariant.copy(alpha = 0.65f)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { model.requestEditContextNote(context.id) }
                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.EditNote,
                                            contentDescription = tr("编辑备注", "Edit note", "メモを編集", "메모 편집", "編輯備註"),
                                            tint = noteColor,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = note.ifBlank { tr("编辑备注", "Edit note", "メモを編集", "메모 편집", "編輯備註") },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = noteColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
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
                                                contentDescription = tr("页面快照：$title", "Page snapshot: $title", "ページスナップショット：$title", "페이지 스냅샷: $title", "頁面快照：$title"),
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
