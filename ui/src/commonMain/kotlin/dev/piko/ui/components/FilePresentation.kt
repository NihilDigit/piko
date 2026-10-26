package dev.piko.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isVaulted
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import io.github.nihildigit.pikpak.imageThumbnailUrlOf

internal data class FileItemName(val title: String, val original: String? = null, val fullName: String = title)

internal data class FileVisual(
    val name: String,
    val isFolder: Boolean = false,
    val thumbnail: Any? = null,
    val isVideo: Boolean = false,
    val isEmptyFolder: Boolean = false,
    val category: FileCategory = name.fileCategory(),
)

internal enum class FileItemMark { Starred, Vaulted, ContainsVault, ImportPreview }
internal data class FileItemStatus(val text: String, val needsAttention: Boolean = false)

/** Presentation only: a source entry does not need a remote file id or an executable drive command. */
internal data class FilePresentation(
    val key: String,
    val name: FileItemName,
    val visual: FileVisual,
    val metadata: List<String> = emptyList(),
    val marks: Set<FileItemMark> = emptySet(),
    val tags: List<String> = emptyList(),
    val code: String? = null,
    val resolution: String? = null,
    val status: FileItemStatus? = null,
)

internal fun FileStat.fileVisual(emptyFolder: Boolean = false): FileVisual {
    val isVideo = !isFolder && isPlayableVideo()
    val cover = thumbnailLink.ifBlank {
        when {
            hash.isBlank() || isFolder -> ""
            isVideo -> videoThumbnailUrlOf(hash)
            fileCategory() == FileCategory.IMAGE -> imageThumbnailUrlOf(hash)
            else -> ""
        }
    }
    return FileVisual(
        name, isFolder, cover.takeIf { it.isNotEmpty() }, isVideo, emptyFolder, fileCategory(),
    )
}

@Composable
internal fun FileStat.filePresentation(
    title: String? = null, original: String? = null, tags: List<String> = emptyList(), code: String? = null,
    resolution: String? = null, folderHasVault: Boolean = false, emptyFolder: Boolean = false,
): FilePresentation {
    val showExt = LocalShowExtensions.current
    val resolvedTitle = title ?: displayTitle()
    return remember(this, resolvedTitle, original, tags, code, resolution, folderHasVault, emptyFolder, showExt) {
        FilePresentation(
            key = id, name = FileItemName(resolvedTitle, original, name), visual = fileVisual(emptyFolder),
            metadata = metaParts(), tags = tags, code = code, resolution = resolution,
            marks = fileItemMarks(folderHasVault),
        )
    }
}

internal fun FileStat.fileItemMarks(folderHasVault: Boolean): Set<FileItemMark> = buildSet {
    if (isImportPreview) add(FileItemMark.ImportPreview)
    if (isStarred) add(FileItemMark.Starred)
    if (isVaulted || (isFolder && folderHasVault)) add(if (isFolder) FileItemMark.ContainsVault else FileItemMark.Vaulted)
}

internal fun ResolvedFile.filePresentation(key: String): FilePresentation = FilePresentation(
    key, FileItemName(name), FileVisual(name, isVideo = name.isPlayableVideo()),
    metadata = listOf(size.toReadableSize()),
    status = if (gcid == null) FileItemStatus("未收录", needsAttention = true) else null,
)

@Composable
internal fun FileItemTitle(
    name: FileItemName, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 2, fontWeight: FontWeight? = null,
) {
    Text(name.title, modifier, style = style, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun FileItemOriginal(name: FileItemName, style: TextStyle = MaterialTheme.typography.labelSmall) {
    name.original?.let {
        Text(it, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
internal fun FileItemInformation(
    item: FilePresentation,
    showMetadata: Boolean = item.tags.isEmpty() || !item.visual.isFolder,
    showOriginal: Boolean = true,
) {
    Column {
        if (showOriginal) FileItemOriginal(item.name, MaterialTheme.typography.labelMedium)
        if (item.tags.isNotEmpty() || item.code != null) MediaTagRow(item.tags, lead = item.code, modifier = Modifier.padding(vertical = 2.dp))
        if (showMetadata && item.metadata.isNotEmpty()) MetaRow(item.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.status?.let { status ->
            Text(status.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (status.needsAttention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FileItemMarks(marks: Set<FileItemMark>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (FileItemMark.ImportPreview in marks) ImportPreviewMark()
        if (FileItemMark.Starred in marks) StarMark()
        if (FileItemMark.Vaulted in marks || FileItemMark.ContainsVault in marks) VaultMark(FileItemMark.ContainsVault in marks)
    }
}

/** Batch selection and inclusion in a save plan bind independently to the same control. */
internal class FileItemSelection(
    val state: ToggleableState, val onToggle: () -> Unit, val enabled: Boolean = true, val label: String? = null,
)

internal class FileItemHover(val modifier: Modifier, val mouseHovered: Boolean)

@Composable
internal fun fileItemHover(): FileItemHover {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    return FileItemHover(Modifier.hoverable(interactions), hovered && !LocalPointerSource.current.isTouchLike)
}

@Composable
internal fun FileItemSelectionControl(selection: FileItemSelection, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val semantics = if (!interactive) Modifier.clearAndSetSemantics {} else Modifier.semantics {
        selection.label?.let { contentDescription = it }
    }
    TriStateCheckbox(selection.state, if (interactive) selection.onToggle else null, modifier.then(semantics), enabled = selection.enabled)
}

@Composable
internal fun FileOverlaySelectionControl(
    selection: FileItemSelection,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val interaction = if (interactive) Modifier.triStateToggleable(
        state = selection.state,
        enabled = selection.enabled,
        role = Role.Checkbox,
        interactionSource = interactions,
        indication = null,
        onClick = selection.onToggle,
    ).semantics { selection.label?.let { contentDescription = it } }
    else Modifier.clearAndSetSemantics {}
    val selected = selection.state != ToggleableState.Off
    val fill = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.38f)
    val mark = MaterialTheme.colorScheme.onPrimary
    val focusRing = MaterialTheme.colorScheme.primary
    Box(modifier.size(24.dp).then(interaction), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val alpha = if (selection.enabled) 1f else 0.38f
            drawRoundRect(fill, cornerRadius = CornerRadius(4.dp.toPx()), alpha = alpha)
            if (!selected) {
                val stroke = 1.5.dp.toPx()
                val ringColor = if (focused) focusRing else Color.White
                drawRoundRect(
                    ringColor, topLeft = Offset(stroke / 2, stroke / 2),
                    size = size.copy(width = size.width - stroke, height = size.height - stroke),
                    cornerRadius = CornerRadius(4.dp.toPx() - stroke / 2), style = Stroke(stroke), alpha = alpha,
                )
            } else {
                val path = Path().apply {
                    if (selection.state == ToggleableState.Indeterminate) {
                        moveTo(size.width * 0.25f, size.height * 0.5f)
                        lineTo(size.width * 0.75f, size.height * 0.5f)
                    } else {
                        moveTo(size.width * 0.22f, size.height * 0.5f)
                        lineTo(size.width * 0.43f, size.height * 0.71f)
                        lineTo(size.width * 0.78f, size.height * 0.29f)
                    }
                }
                drawPath(path, mark, alpha = alpha, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Only executable actions are bound; layouts choose their position without inventing capabilities. */
internal class FileItemActions(val primary: () -> Unit, val secondary: List<SheetAction> = emptyList())

@Composable
internal fun FileItemActionButtons(actions: FileItemActions) {
    Row { actions.secondary.forEach { action -> TooltipIconButton(action.icon, action.label, action.onClick) } }
}

@Composable
internal fun fileItemLongPress(action: (() -> Unit)?): (() -> Unit)? {
    val current by rememberUpdatedState(action)
    val pointers = LocalPointerSource.current
    val haptic = LocalHapticFeedback.current
    return if (action == null) null else remember(pointers, haptic) {
        {
            if (pointers.isTouchLike) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                current?.invoke()
            }
        }
    }
}
