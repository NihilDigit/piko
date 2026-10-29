package dev.piko.ui.screens.share

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.shared.state.ShareCreateState
import dev.piko.shared.state.SharePassCodeMode
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * 把网盘条目分享为链接。先选提取码与有效期，创建后同一个对话框换成链接与提取码，复制即关闭。
 * [onCopied] 由调用方给出回执，对话框关掉之后剪贴板本身看不见。
 */
@Composable
fun ShareDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val state = remember(files) { ShareCreateState(services.driveRepository, scope, files) }
    val created = state.created

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
        title = { Text(if (created == null) tr("分享", "Share", "共有", "공유") else tr("已创建分享链接", "Share link created", "共有リンクを作成しました", "공유 링크를 만들었습니다")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SharedItems(files)
                if (created == null) {
                    ShareOptions(state)
                } else {
                    ShareResult(created.shareUrl, created.passCode, state.expirationDays)
                }
            }
        },
        confirmButton = {
            if (created == null) {
                PikoDialogConfirm(if (state.isCreating) tr("正在创建", "Creating", "作成中", "생성 중") else tr("创建链接", "Create link", "リンクを作成", "링크 만들기"), onClick = state::create, enabled = state.canCreate)
            } else {
                // 与我的分享同一个说法：复制的总是 shareText，有提取码时连同提取码
                PikoDialogConfirm(
                    label = tr("复制链接", "Copy link", "リンクをコピー", "링크 복사"),
                    onClick = {
                        platform.copyToClipboard(tr("分享链接", "Share link", "共有リンク", "공유 링크"), state.shareText.orEmpty())
                        onDismiss()
                        onCopied()
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (created == null) tr("取消", "Cancel", "キャンセル", "취소") else tr("关闭", "Close", "閉じる", "닫기")) }
        },
    )
}

/** 分享的是什么：第一项的图标与名字，多项时另注总数。名字只占一行，长文件名不把对话框撑高。 */
@Composable
private fun SharedItems(files: List<FileStat>) {
    val first = files.first()
    // 对话框自己就是 surfaceContainerHigh，卡片取高一级才看得出边界
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(Modifier.size(24.dp)) { FileTypeIcon(file = first, iconSize = 24.dp, modifier = Modifier.fillMaxSize()) }
            Text(
                text = first.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (files.size > 1) {
                Text(
                    text = tr("等 ${files.size} 项", "and ${enCount(files.size, "item", "items")}", "ほか ${files.size} 項目", "등 ${files.size}개 항목"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShareOptions(state: ShareCreateState) {
    Option(tr("提取码", "Access code", "アクセスコード", "접근 코드")) {
        ConnectedToggle(
            options = SharePassCodeMode.entries,
            selected = state.passCodeMode,
            label = { it.label },
            enabled = !state.isCreating,
            onSelect = { state.passCodeMode = it },
        )
        // 输入框常驻，不选「自定义」时置灰：随选项出现、消失的话，Android 上按内容定高的对话框每切一次就跳一下
        val custom = state.passCodeMode == SharePassCodeMode.Custom
        val showError = custom && state.customPassCode.isNotEmpty() && !state.isCustomPassCodeValid
        OutlinedTextField(
            value = state.customPassCode,
            onValueChange = { value -> state.customPassCode = value.filter { it.isLetterOrDigit() && it.code < 128 }.take(10) },
            label = { Text(stringResource(Res.string.text_custom_access_code)) },
            supportingText = { Text(stringResource(Res.string.text_4_10_letters_or_digits)) },
            isError = showError,
            singleLine = true,
            enabled = custom && !state.isCreating,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.largeIncreased,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Option(tr("有效期", "Expiration", "有効期限", "유효 기간")) {
        ConnectedToggle(
            options = ShareCreateState.EXPIRATION_CHOICES,
            selected = state.expirationDays,
            label = { days -> if (days < 0) tr("永久", "Never expires", "無期限", "영구") else tr("$days 天", "$days days", "$days 日間", "${days}일") },
            enabled = !state.isCreating,
            onSelect = { state.expirationDays = it },
        )
    }
    // 报错行常驻，没有报错时留空占位，理由同上
    Text(
        text = state.error.orEmpty(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Option(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** 与设置页的深色模式、片段下载的选择同一种连体按钮组。 */
@Composable
private fun <T> ConnectedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = connectedToggleShapes(index, options.size),
                modifier = Modifier.weight(1f),
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/**
 * 链接与提取码分两行，各带标签：提取码常要口头或另发，放大单列，一眼能读出。
 * 两者都可选中，只要其中一样的可以自己挑。
 */
@Composable
private fun ShareResult(url: String, passCode: String, expirationDays: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    LabeledValue(tr("链接", "Link", "リンク", "링크"), url, MaterialTheme.typography.bodyLarge)
                    if (passCode.isNotEmpty()) {
                        LabeledValue(
                            label = tr("提取码", "Access code", "アクセスコード", "접근 코드"),
                            value = passCode,
                            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 2.sp),
                        )
                    }
                }
            }
        }
        Text(
            text = if (expirationDays < 0) tr("永久有效", "Never expires", "無期限", "영구 유효") else "$expirationDays 天后失效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String, style: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}
