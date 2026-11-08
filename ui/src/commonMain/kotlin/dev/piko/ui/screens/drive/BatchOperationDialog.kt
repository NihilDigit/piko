package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveClipboard

@Composable
internal fun ClipboardBanner(clip: DriveClipboard, canPaste: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val message = if (clip.cut) {
                tr("已剪切 ${clip.sources.size} 项", "Cut ${enCount(clip.sources.size, "item", "items")}", "${clip.sources.size} 項目を切り取りました", "${clip.sources.size}개 항목 잘라내기 완료", "已剪下 ${clip.sources.size} 項")
            } else {
                tr("已复制 ${clip.sources.size} 项", "Copied ${enCount(clip.sources.size, "item", "items")}", "${clip.sources.size} 項目をコピーしました", "${clip.sources.size}개 항목 복사 완료", "已複製 ${clip.sources.size} 項")
            }
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
            TextButton(onClick = onPaste, enabled = canPaste) { Text(stringResource(Res.string.text_paste_here)) }
        }
    }
}
