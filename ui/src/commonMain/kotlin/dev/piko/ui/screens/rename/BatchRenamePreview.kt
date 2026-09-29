package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveNames
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.BatchRenameState.Phase
import dev.piko.shared.rename.MatchHighlight
import androidx.compose.runtime.remember
import dev.piko.shared.rename.RenameProblem
import dev.piko.shared.rename.RenameRow

/** 预览的计数与「仅显示变更项」，放在同一行：过滤的是什么、过滤后剩多少，一眼对得上。 */
@Composable
internal fun PreviewHeader(state: BatchRenameState, changedOnly: Boolean, onChangedOnlyChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val plan = state.plan
    val title = when {
        state.phase == Phase.DONE -> if (state.failures.isEmpty()) return else "${state.failures.size} 项重命名失败"
        plan.problemCount > 0 -> "${state.sources.size} 项中 ${plan.problemCount} 项存在问题"
        else -> "${state.sources.size} 项中 ${plan.changeCount} 项将改名"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (state.phase == Phase.DONE || plan.problemCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (state.phase != Phase.DONE) {
            FilterChip(
                selected = changedOnly,
                onClick = { onChangedOnlyChange(!changedOnly) },
                label = { Text("仅显示变更项") },
                leadingIcon = if (changedOnly) {
                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/** 两栏预览的列头，与 [PreviewRow] 的两栏对齐。 */
@Composable
internal fun PreviewColumnHeader(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(RowGap), modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp)) {
        Spacer(Modifier.width(CheckboxSize))
        Text("原名", style = style, color = color, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(ArrowSize))
        Text("新名", style = style, color = color, modifier = Modifier.weight(1f))
    }
}

/**
 * 预览的一行：勾选框、原名、新名。[wide] 时原名与新名左右并排，照 PowerRename 的两列；窄时新名换到原名下面。
 * 只有会改名的行才写出新名，新名里换上的一段加粗标色，几百项里一眼看出改了哪里。
 * 不改的行只留原名，不必每行都写一遍「不改」。
 * 原名里被查找匹配到的各段（[highlights]）按积木的颜色铺底，与查找条上的积木、替换条上的 ①② 同色。
 * [onIncludedChange] 为 null 时不画勾选框（执行结束后列失败项时）。[container] 不为 null 时这一行自带分段底色。
 */
@Composable
internal fun PreviewRow(
    row: RenameRow,
    highlights: List<MatchHighlight>,
    included: Boolean,
    onIncludedChange: ((Boolean) -> Unit)?,
    wide: Boolean,
    container: Shape?,
    selectionActions: SelectionActions? = null,
) {
    if (container != null) {
        Surface(shape = container, color = LocalRenameRowColor.current, modifier = Modifier.fillMaxWidth()) {
            PreviewRowContent(row, highlights, included, onIncludedChange, wide, selectionActions)
        }
    } else {
        PreviewRowContent(row, highlights, included, onIncludedChange, wide, selectionActions)
    }
}

@Composable
private fun PreviewRowContent(
    row: RenameRow,
    highlights: List<MatchHighlight>,
    included: Boolean,
    onIncludedChange: ((Boolean) -> Unit)?,
    wide: Boolean,
    selectionActions: SelectionActions?,
) {
    val colors = MaterialTheme.colorScheme
    val shown = row.isChanged || row.problem != null
    val highlight = if (row.problem != null) colors.error else colors.primary
    val palette = (0 until PALETTE_SIZE).map { blockColor(it) }
    val whole = wholeMatchColor()
    val original = remember(row.source.name, highlights, palette, whole) {
        buildAnnotatedString {
            append(row.source.name)
            for ((range, block) in highlights) {
                val background = if (block < 0) whole else palette[block % PALETTE_SIZE]
                addStyle(SpanStyle(background = background, color = colors.onSurface), range.first, range.last + 1)
            }
        }
    }
    val originalName: @Composable (Modifier) -> Unit = { modifier ->
        // 原名不划线：前后缀整段去掉时几乎整行都被划掉，反而读不出来。改动只在新名上标
        SelectableName(
            text = original,
            style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurfaceVariant),
            maxLines = 3,
            actions = selectionActions,
            modifier = modifier,
        )
    }
    val newName: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (shown) {
                Text(
                    text = if (row.newName.isEmpty()) AnnotatedString("（空）") else markAdded(row.source.name, row.newName, highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.problem != null) colors.error else colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.problem?.let { Text(problemLabel(row, it), style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
    val arrow: @Composable () -> Unit = {
        if (shown) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "改为", tint = highlight, modifier = Modifier.size(ArrowSize))
        } else {
            Spacer(Modifier.width(ArrowSize))
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onIncludedChange == null) 16.dp else 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (onIncludedChange != null) Checkbox(checked = included, onCheckedChange = onIncludedChange)
        if (wide) {
            originalName(Modifier.weight(1f))
            arrow()
            newName(Modifier.weight(1f))
        } else {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                originalName(Modifier)
                if (shown) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        arrow()
                        newName(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private val RowGap = 12.dp

// 与 blockColor 的色板一样长
private const val PALETTE_SIZE = 5
private val ArrowSize = 16.dp
private val CheckboxSize = 48.dp

/** 去掉两端与原名相同的部分，剩下中间那段就是换上的，只标这一段。 */
private fun markAdded(old: String, new: String, color: Color): AnnotatedString {
    val prefix = old.commonPrefixWith(new).length
    val suffix = old.commonSuffixWith(new).length.coerceAtMost(minOf(old.length, new.length) - prefix)
    return buildAnnotatedString {
        append(new, 0, prefix)
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(new, prefix, new.length - suffix) }
        append(new, new.length - suffix, new.length)
    }
}

private fun problemLabel(row: RenameRow, problem: RenameProblem): String = when (problem) {
    RenameProblem.EMPTY -> "新名称为空"
    RenameProblem.INVALID_CHARS -> "含 PikPak 不支持的" + DriveNames.unsupportedParts(row.newName).joinToString("、")
    RenameProblem.TOO_LONG -> "超出 PikPak 的 1024 字节上限"
    RenameProblem.TAKEN -> "与同目录现有名称重复"
    RenameProblem.BLOCKED -> "新名称是另一项的原名，那一项无法改名"
    RenameProblem.DUPLICATE -> "与其他项的新名称重复"
}
