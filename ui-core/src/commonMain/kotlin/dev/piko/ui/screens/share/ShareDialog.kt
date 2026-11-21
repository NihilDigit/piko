package dev.piko.ui.screens.share

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.ShareCreateState
import dev.piko.shared.state.SharePassCodeMode
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileArtwork
import dev.piko.ui.components.FileArtworkFormat
import dev.piko.ui.components.FileListLeadingVisual
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.fileVisual
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ShareCarouselScrim = Brush.verticalGradient(
    0.35f to Color.Transparent,
    1.0f to Color(0xC7000000),
)

/**
 * 把网盘条目分享为链接。先选提取码与有效期，创建后同一个对话框换成链接与提取码，复制即关闭。
 * [onCopied] 由调用方给出回执，对话框关掉之后剪贴板本身看不见。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = services.preferences.currentSnapshot.spoilerBlurEnabled,
    )
    val created = state.created
    var copyMenuOpen by remember { mutableStateOf(false) }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
        title = { Text(if (created == null) tr("分享", "Share", "共有", "공유", "分享") else tr("已创建分享链接", "Share link created", "共有リンクを作成しました", "공유 링크를 만들었습니다", "已建立分享連結")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SharedItems(files = files, isSpoilerBlurred = isSpoilerBlurEnabled)
                if (created == null) {
                    ShareOptions(state)
                } else {
                    ShareResult(created.shareUrl, created.passCode, state.expirationDays)
                }
            }
        },
        confirmButton = {
            if (created == null) {
                PikoDialogConfirm(if (state.isCreating) tr("正在创建", "Creating", "作成中", "생성 중", "正在建立") else tr("创建链接", "Create link", "リンクを作成", "링크 만들기", "建立連結"), onClick = state::create, enabled = state.canCreate)
            } else {
                val hasPassCode = created.passCode.isNotEmpty()
                val menuCount = if (hasPassCode) 3 else 2
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.LeadingButton(
                            onClick = {
                                platform.copyToClipboard(tr("分享链接", "Share link", "共有リンク", "공유 링크", "分享連結"), state.shareText.orEmpty())
                                onDismiss()
                                onCopied()
                            },
                        ) {
                            Text(tr("复制链接", "Copy link", "リンクをコピー", "링크 복사", "複製連結"))
                        }
                    },
                    trailingButton = {
                        Box {
                            SplitButtonDefaults.TrailingButton(
                                checked = copyMenuOpen,
                                onCheckedChange = { copyMenuOpen = it },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"),
                                    modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                                )
                            }
                            PikoDropdownMenu(
                                expanded = copyMenuOpen,
                                onDismissRequest = { copyMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(tr("仅复制链接地址", "Copy URL only", "URLのみコピー", "링크 주소만 복사", "僅複製連結網址")) },
                                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    shape = menuItemShape(0, menuCount),
                                    onClick = {
                                        copyMenuOpen = false
                                        platform.copyToClipboard(tr("分享链接", "Share link", "共有リンク", "공유 링크", "分享連結"), created.shareUrl)
                                        onDismiss()
                                        onCopied()
                                    },
                                )
                                if (hasPassCode) {
                                    DropdownMenuItem(
                                        text = { Text(tr("仅复制提取码", "Copy access code only", "アクセスコードのみコピー", "접근 코드만 복사", "僅複製提取碼")) },
                                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        shape = menuItemShape(1, menuCount),
                                        onClick = {
                                            copyMenuOpen = false
                                            platform.copyToClipboard(tr("提取码", "Access code", "アクセスコード", "접근 코드", "提取碼"), created.passCode)
                                            onDismiss()
                                            onCopied()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(tr("在浏览器中打开", "Open in browser", "ブラウザーで開く", "브라우저에서 열기", "在瀏覽器中開啟")) },
                                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    shape = menuItemShape(menuCount - 1, menuCount),
                                    onClick = {
                                        copyMenuOpen = false
                                        platform.openUrl(created.shareUrl)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(if (created == null) tr("取消", "Cancel", "キャンセル", "취소", "取消") else tr("关闭", "Close", "閉じる", "닫기", "關閉"))
            }
        },
    )
}

/** 单项展示带缩略图行卡，多项使用 [HorizontalMultiBrowseCarousel] 横向浏览。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedItems(files: List<FileStat>, isSpoilerBlurred: Boolean) {
    if (files.isEmpty()) return
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme
    if (files.size == 1) {
        val first = files.first()
        val sizeText = first.size.toLongOrNull()?.takeIf { it > 0L }?.toReadableSize()
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isEInk) Color.White else colors.surfaceContainerHighest,
            border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                FileListLeadingVisual(
                    file = first,
                    size = 40.dp,
                    isSpoilerBlurred = isSpoilerBlurred && first.thumbnailLink.isNotBlank(),
                    allowThumbnail = true,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = first.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sizeText != null) {
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        return
    }

    val carouselState = rememberCarouselState { files.size }
    val wheelScope = rememberCoroutineScope()
    var pendingWheelPx by remember(carouselState) { mutableFloatStateOf(0f) }
    var wheelAnimJob by remember(carouselState) { mutableStateOf<Job?>(null) }
    val totalKnownBytes = remember(files) {
        files.sumOf { it.size.toLongOrNull()?.coerceAtLeast(0L) ?: 0L }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr(
                    "共 ${files.size} 项",
                    enCount(files.size, "item", "items"),
                    "計 ${files.size} 項目",
                    "총 ${files.size}개 항목",
                    "共 ${files.size} 項",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            if (totalKnownBytes > 0L) {
                Text(
                    text = totalKnownBytes.toReadableSize(),
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = 148.dp,
            itemSpacing = 8.dp,
            flingBehavior = CarouselDefaults.noSnapFlingBehavior(),
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .pointerInput(carouselState) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                                val raw = if (abs(delta.x) > abs(delta.y)) delta.x else delta.y
                                if (raw != 0f) {
                                    val canAdvance = (raw > 0f && carouselState.canScrollForward) ||
                                        (raw < 0f && carouselState.canScrollBackward)
                                    if (canAdvance) {
                                        event.changes.forEach { it.consume() }
                                        if (abs(raw) < 0.45f) {
                                            carouselState.dispatchRawDelta(raw * 64f)
                                        } else {
                                            pendingWheelPx = (pendingWheelPx + raw * 136f).coerceIn(-1200f, 1200f)
                                            if (wheelAnimJob?.isActive != true) {
                                                wheelAnimJob = wheelScope.launch {
                                                    var lastNanos = 0L
                                                    carouselState.scroll {
                                                        while (abs(pendingWheelPx) > 0.5f) {
                                                            val frameNanos = withFrameNanos { it }
                                                            val dtSec = if (lastNanos == 0L) {
                                                                1f / 60f
                                                            } else {
                                                                ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                                                            }
                                                            lastNanos = frameNanos
                                                            val step = pendingWheelPx * (1f - exp(-dtSec * 18f))
                                                            val actual = scrollBy(step)
                                                            pendingWheelPx -= step
                                                            if (abs(actual) < 0.05f) {
                                                                pendingWheelPx = 0f
                                                                break
                                                            }
                                                        }
                                                        pendingWheelPx = 0f
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) { index ->
            val item = files[index]
            val shape = MaterialTheme.shapes.large
            val visual = remember(item) { item.fileVisual() }
            val hasThumb = !isEInk && visual.thumbnail != null
            Box(
                modifier = Modifier
                    .height(104.dp)
                    .maskClip(shape)
                    .then(
                        if (isEInk) Modifier.maskBorder(BorderStroke(1.dp, Color.Black), shape) else Modifier,
                    ),
            ) {
                FileArtwork(
                    file = visual,
                    format = FileArtworkFormat.Poster,
                    isSpoilerBlurred = isSpoilerBlurred && hasThumb,
                    showMinimalFolderBadge = false,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (hasThumb) {
                                ShareCarouselScrim
                            } else {
                                Brush.verticalGradient(
                                    0.45f to Color.Transparent,
                                    1.0f to (if (isEInk) Color.White.copy(alpha = 0.92f) else colors.surfaceContainerHighest.copy(alpha = 0.88f)),
                                )
                            },
                        ),
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasThumb) Color.White else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareOptions(state: ShareCreateState) {
    Option(tr("提取码", "Access code", "アクセスコード", "접근 코드", "提取碼")) {
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
    Option(tr("有效期", "Expiration", "有効期限", "유효 기간", "有效期")) {
        ConnectedToggle(
            options = ShareCreateState.EXPIRATION_CHOICES,
            selected = state.expirationDays,
            label = { days -> if (days < 0) tr("永久", "Never expires", "無期限", "영구", "永久") else tr("$days 天", "$days days", "$days 日間", "${days}일", "$days 天") },
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
                    LabeledValue(tr("链接", "Link", "リンク", "링크", "連結"), url, MaterialTheme.typography.bodyLarge)
                    if (passCode.isNotEmpty()) {
                        LabeledValue(
                            label = tr("提取码", "Access code", "アクセスコード", "접근 코드", "提取碼"),
                            value = passCode,
                            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 2.sp),
                        )
                    }
                }
            }
        }
        Text(
            text = if (expirationDays < 0) tr("永久有效", "Never expires", "無期限", "영구 유효", "永久有效") else tr("$expirationDays 天后过期", "Expires in $expirationDays days", "$expirationDays 日後に期限切れ", "${expirationDays}일 후 만료", "$expirationDays 天後過期"),
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
