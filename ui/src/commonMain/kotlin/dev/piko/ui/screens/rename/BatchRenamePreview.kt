package dev.piko.ui.screens.rename

import dev.piko.shared.i18n.tr

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
import androidx.compose.material.icons.outlined.Edit
import dev.piko.ui.components.TooltipIconButton
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
 * [location] 是一棵树里这一项所在的目录，小字写在原名下面；[onEdit] 不为 null 时行尾有修改新名称的按钮。
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
    location: String? = null,
    onEdit: (() -> Unit)? = null,
) {
    if (container != null) {
        Surface(shape = container, color = LocalRenameRowColor.current, modifier = Modifier.fillMaxWidth()) {
            PreviewRowContent(row, highlights, included, onIncludedChange, wide, searchRange, location, onEdit)
        }
    } else {
        PreviewRowContent(row, highlights, included, onIncludedChange, wide, searchRange, location, onEdit)
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
    location: String?,
    onEdit: (() -> Unit)?,
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
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 原名不划线：前后缀整段去掉时几乎整行都被划掉，反而读不出来。改动只在新名上标
            Text(
                text = original,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            location?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = outOfRange, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val newName: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (shown) {
                Text(
                    text = if (row.newName.isEmpty()) AnnotatedString(tr("（空）", "(empty)", "（空）", "(비어 있음)")) else markAdded(row.source.name, row.newName, highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    // 不勾的项写出的是勾上时的新名称（一棵树里冲突的默认不勾），调暗以示不改
                    color = when {
                        row.problem != null -> colors.error
                        !included -> outOfRange
                        else -> colors.onSurface
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.problem?.let { Text(problemLabel(row, it), style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
    val arrow: @Composable () -> Unit = {
        if (shown) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = tr("改为", "Change to", "変更先", "변경"), tint = highlight, modifier = Modifier.size(ArrowSize))
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
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = tr("改为", "Change to", "変更先", "변경"), tint = highlight, modifier = Modifier.size(ArrowSize))
                    newName(Modifier)
                }
            }
        }
        if (onEdit != null) TooltipIconButton(Icons.Outlined.Edit, tr("修改新名称", "Edit new name", "新しい名前を編集", "새 이름 수정"), onEdit)
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
    RenameProblem.EMPTY -> tr("新名称为空", "New name is empty", "新しい名前が空です", "새 이름이 비어 있습니다")
    RenameProblem.INVALID_CHARS -> tr("含 PikPak 不支持的", "Contains unsupported PikPak characters", "PikPakで使えない文字を含みます", "PikPak에서 지원하지 않는 문자 포함") + DriveNames.unsupportedParts(row.newName).joinToString("、")
    RenameProblem.TOO_LONG -> tr("超出 PikPak 的 1024 字节上限", "Exceeds PikPak’s 1024-byte limit", "PikPakの1024バイト制限を超えています", "PikPak의 1024바이트 제한 초과")
    RenameProblem.TAKEN -> tr("与同目录现有名称重复", "Conflicts with an existing name in this folder", "同じフォルダーの既存の名前と重複しています", "같은 폴더의 기존 이름과 중복")
    RenameProblem.BLOCKED -> tr("新名称与另一项的原名称相同，该项无法重命名", "New name matches an item that cannot be renamed", "新しい名前が改名できない項目の元の名前と同じです", "새 이름이 변경할 수 없는 다른 항목의 원래 이름과 같습니다")
    RenameProblem.DUPLICATE -> tr("与其他项的新名称重复", "Conflicts with another new name", "ほかの項目の新しい名前と重複しています", "다른 항목의 새 이름과 중복")
}
