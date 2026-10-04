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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Checkbox
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
    searchRange: IntRange? = row.source.name.indices,
) {
    if (container != null) {
        Surface(shape = container, color = LocalRenameRowColor.current, modifier = Modifier.fillMaxWidth()) {
            PreviewRowContent(row, highlights, included, onIncludedChange, wide, searchRange)
        }
    } else {
        PreviewRowContent(row, highlights, included, onIncludedChange, wide, searchRange)
    }
}

@Composable
private fun PreviewRowContent(
    row: RenameRow,
    highlights: List<MatchHighlight>,
    included: Boolean,
    onIncludedChange: ((Boolean) -> Unit)?,
    wide: Boolean,
    searchRange: IntRange?,
) {
    val colors = MaterialTheme.colorScheme
    val shown = row.isChanged || row.problem != null
    val highlight = if (row.problem != null) colors.error else colors.primary
    val palette = (0 until PALETTE_SIZE).map { blockColor(it) to blockContentColor(it) }
    val whole = wholeMatchColor() to wholeMatchContentColor()
    // 查找够不着的部分（应用于主名时的扩展名）调暗
    val outOfRange = colors.onSurfaceVariant.copy(alpha = 0.38f)
    val original = remember(row.source.name, highlights, palette, whole, searchRange, outOfRange) {
        buildAnnotatedString {
            append(row.source.name)
            val name = row.source.name
            if (searchRange == null) {
                addStyle(SpanStyle(color = outOfRange), 0, name.length)
            } else {
                if (searchRange.first > 0) addStyle(SpanStyle(color = outOfRange), 0, searchRange.first)
                if (searchRange.last + 1 < name.length) addStyle(SpanStyle(color = outOfRange), searchRange.last + 1, name.length)
            }
            for ((range, block) in highlights) {
                val (background, content) = if (block < 0) whole else palette[block % PALETTE_SIZE]
                addStyle(SpanStyle(background = background, color = content), range.first, range.last + 1)
            }
        }
    }
    val originalName: @Composable (Modifier) -> Unit = { modifier ->
        // 原名不划线：前后缀整段去掉时几乎整行都被划掉，反而读不出来。改动只在新名上标
        Text(
            text = original,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
            // 新名换到原名下面时，向下的箭头单独占一行，原名与新名左边对齐。箭头放在新名前面的话，新名比原名缩进一截
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                originalName(Modifier)
                if (shown) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "改为", tint = highlight, modifier = Modifier.size(ArrowSize))
                    newName(Modifier)
                }
            }
        }
    }
}

private val RowGap = 12.dp

private val ArrowSize = 16.dp

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
    RenameProblem.BLOCKED -> "新名称与另一项的原名称相同，该项无法重命名"
    RenameProblem.DUPLICATE -> "与其他项的新名称重复"
}
