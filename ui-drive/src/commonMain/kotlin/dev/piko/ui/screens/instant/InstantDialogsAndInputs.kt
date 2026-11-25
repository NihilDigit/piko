package dev.piko.ui.screens.instant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.InstantActionKind
import dev.piko.shared.state.InstantFallback
import dev.piko.shared.state.InstantPrimaryAction
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SavePlan
import dev.piko.shared.state.SaveRoute
import dev.piko.ui.components.FolderListLeadingVisual
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*

/** 一次性提示。[isError] 为 false 的是回执（如已加入下载队列）。 */
internal class PanelNotice(val text: String, val isError: Boolean)

internal class SaveMenuOption(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal class ReferenceSaveToggle(
    val checked: Boolean,
    val enabled: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

internal fun buildReferenceMenuOptions(toggle: ReferenceSaveToggle?): List<SaveMenuOption> {
    if (toggle == null) return emptyList()
    return listOf(
        SaveMenuOption(
            label = tr("保存文件", "Save files", "ファイルを保存", "파일 저장", "儲存檔案"),
            icon = Icons.Outlined.CloudDownload,
            selected = !toggle.checked,
            enabled = toggle.enabled,
            onClick = { if (toggle.checked) toggle.onCheckedChange(false) },
        ),
        SaveMenuOption(
            label = stringResourceFallbackSaveAsRef(),
            icon = Icons.Outlined.Inventory2,
            selected = toggle.checked,
            enabled = toggle.enabled,
            onClick = { if (!toggle.checked) toggle.onCheckedChange(true) },
        ),
    )
}

private fun stringResourceFallbackSaveAsRef(): String =
    tr("以引用保存", "Save as references", "参照として保存", "참조로 저장", "以引用儲存")

@Composable
internal fun NoticeBanner(notice: PanelNotice) {
    if (notice.isError) {
        ErrorBanner(message = notice.text, onRetry = null)
    } else {
        StatusBanner(
            message = notice.text,
            icon = Icons.Outlined.CheckCircle,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
internal fun LinkInput(state: InstantSheetState) {
    val platform = LocalPikoPlatform.current
    LinkInputField(
        value = state.input,
        hasResolution = state.resolution != null,
        enabled = state.canEdit,
        onValueChange = state::updateInput,
        onPaste = {
            val clip = platform.readClipboardText()
            if (!clip.isNullOrBlank()) state.updateInput(clip.trim())
        },
    )
}

@Composable
fun LinkInputField(
    value: String,
    hasResolution: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    var inputFocused by remember { mutableStateOf(false) }
    val inputCompact = hasResolution && !inputFocused
    // 始终保持 singleLine = false（纵向滚动器），仅通过 maxLines 在 1 行与 3 行间折叠：
    // CoreTextField 内部用不带 orientation 键的 rememberSaveable 缓存 TextFieldScrollerPosition，
    // 动态切换 singleLine 会在聚焦展开为 3 行时触发 Mismatching scroller orientation 炸毁 Skiko 帧循环。
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(Res.string.text_magnet_download_or_share_link)) },
        placeholder = { Text("magnet:?xt=urn:btih:…") },
        modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused },
        shape = MaterialTheme.shapes.largeIncreased,
        singleLine = false,
        maxLines = if (inputCompact) 1 else 3,
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = tr("从剪贴板粘贴", "Paste from clipboard", "クリップボードから貼り付け", "클립보드에서 붙여넣기", "從剪貼簿貼上"))
            }
        },
    )
}

/** 免费账号这次保存要不要先确认离线；会员与秒传为 null。 */
internal fun offlineConfirmOf(state: InstantSheetState, action: InstantPrimaryAction): OfflineConfirm? {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing && !state.hasInstantRetry }
    val fallback = plan?.fallback
    return when {
        !state.confirmsOffline -> null
        action.kind == InstantActionKind.SUBMIT_OFFLINE -> OfflineConfirm.WholeLink
        plan == null || plan.blocked || plan.route != SaveRoute.OFFLINE_PACK -> null
        fallback != null -> OfflineConfirm.PackOrInstant(plan, fallback)
        else -> OfflineConfirm.Pack(plan)
    }
}

/**
 * 保存栏主操作：常规与引用保存开关已固定置于上方显式开关行，右侧拆分箭头仅在存在「仅保存已收录文件」回退选项时展开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SaveBar(
    state: InstantSheetState,
    action: InstantPrimaryAction,
    onSave: () -> Unit,
    showProblems: Boolean = true,
    saveAsReference: Boolean = false,
    referenceToggle: ReferenceSaveToggle? = null,
) {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing && !state.hasInstantRetry }
    val fallback = plan?.fallback
    val menuOptions = buildList {
        addAll(buildReferenceMenuOptions(referenceToggle))
        if (plan != null && !plan.blocked && fallback != null && state.canSaveSelection) {
            add(
                SaveMenuOption(
                    label = if (fallback.skippedCount > 0) tr("仅保存已收录文件", "Save indexed files only", "収録済みファイルのみ保存", "등록된 파일만 저장", "僅儲存已收錄檔案") else tr("仅保存所选文件", "Save selected files only", "選択したファイルのみ保存", "선택한 파일만 저장", "僅儲存所選檔案"),
                    icon = Icons.Outlined.CheckCircle,
                    enabled = !state.isSaving,
                    onClick = state::saveSelectionInstantly,
                ),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showProblems && plan?.incompleteCatalog == true) {
            ErrorBanner(tr("文件清单不完整，无法在整包离线下载后自动裁剪", "Incomplete file list; automatic trimming unavailable after full cloud download", "ファイル一覧が不完全なため、一括クラウドダウンロード後の自動整理は利用できません", "파일 목록이 불완전하여 전체 클라우드 다운로드 후 자동 정리를 사용할 수 없습니다", "檔案清單不完整，無法於整包離線下載後自動裁剪"), onRetry = null)
        } else if (showProblems && plan != null && plan.lacksSpace) {
            // 秒传没有退路按钮可给，只能少选几项；整包离线的退路在下面的按钮上
            val hint = if (plan.route == SaveRoute.INSTANT) tr("，请减少所选文件", "; select fewer files", "。選択数を減らしてください", ". 선택 항목을 줄여 주세요", "，請減少所選檔案") else ""
            ErrorBanner(
                message = tr("网盘空间不足：需要 ${plan.neededBytes.toReadableSize()}，", "Insufficient cloud space: ${plan.neededBytes.toReadableSize()} required, ", "空き容量不足：${plan.neededBytes.toReadableSize()} 必要、", "클라우드 공간 부족: ${plan.neededBytes.toReadableSize()} 필요, ", "雲端硬碟空間不足：需要 ${plan.neededBytes.toReadableSize()}，") +
                    tr("剩余 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()} available$hint", "空き容量 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "남은 공간 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "剩餘 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint"),
                onRetry = null,
            )
        } else if (showProblems && plan != null && plan.lacksOfflineCount) {
            ErrorBanner(message = tr("今日离线次数已用完", "Daily cloud download limit reached", "本日のクラウドダウンロード回数を使い切りました", "오늘의 클라우드 다운로드 횟수를 모두 사용했습니다", "今日離線次數已用完"), onRetry = null)
        }
        if (plan?.blocked == true && fallback != null) {
            SaveButton(
                label = if (fallback.skippedCount > 0) tr("仅保存已收录文件", "Save indexed files only", "収録済みファイルのみ保存", "등록된 파일만 저장", "僅儲存已收錄檔案") else tr("仅保存所选文件", "Save selected files only", "選択したファイルのみ保存", "선택한 파일만 저장", "僅儲存所選檔案"),
                enabled = state.canSaveSelection,
                isSaving = state.isSaving,
                onClick = state::saveSelectionInstantly,
                options = buildReferenceMenuOptions(referenceToggle),
            )
            if (fallback.skippedCount > 0) SaveCaption(tr("将跳过 ${fallback.skippedCount} 个未收录文件", "Skip ${fallback.skippedCount} unindexed files", "未収録の ${fallback.skippedCount} ファイルをスキップ", "미등록 파일 ${fallback.skippedCount}개 건너뛰기", "將略過 ${fallback.skippedCount} 個未收錄檔案"))
        } else {
            SaveButton(
                label = when {
                    state.hasInstantRetry && state.retryFileCount > 0 -> tr("重试未保存的 ${state.retryFileCount} 个文件", "Retry ${state.retryFileCount} unsaved files", "未保存の ${state.retryFileCount} ファイルを再試行", "저장 안 된 파일 ${state.retryFileCount}개 다시 시도", "重試未儲存的 ${state.retryFileCount} 個檔案")
                    state.contentMissing -> tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載")
                    saveAsReference || referenceToggle?.checked == true -> stringResourceFallbackSaveAsRef()
                    else -> tr("保存", "Save", "保存", "저장", "儲存")
                },
                enabled = action.enabled,
                isSaving = state.isSaving,
                onClick = onSave,
                options = menuOptions,
            )
        }
    }
}

/** 免费账号建离线任务前要确认的几种情形。离线一天只有几次，每一次都写明代价。 */
internal sealed interface OfflineConfirm {
    /** 整条链接交给离线：未收录的磁力、非磁力链接、云端暂无内容的单文件。大小未知。 */
    data object WholeLink : OfflineConfirm

    /** 选中的全是未收录的，只能整包离线。 */
    data class Pack(val plan: SavePlan) : OfflineConfirm

    /** 一部分未收录：整包离线，或只秒传已收录的。 */
    data class PackOrInstant(val plan: SavePlan, val fallback: InstantFallback) : OfflineConfirm
}

/** 代价低的一项放在最右的主位，手快点错也不白占一次离线。 */
@Composable
internal fun OfflineConfirmDialog(
    confirm: OfflineConfirm,
    offlineLeft: Int?,
    onOffline: () -> Unit,
    onInstant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = offlineLeft?.let { tr("（今日剩余 $it 次）", " ($it remaining today)", "（本日の残り $it 回）", " (오늘 ${it}회 남음)", "（今日剩餘 $it 次）") }.orEmpty()
    val (title, message) = when (confirm) {
        OfflineConfirm.WholeLink -> tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載") to tr("将使用 1 次离线下载额度$count。", "Uses 1 cloud download quota$count.", "クラウドダウンロード枠を1回使用します$count。", "클라우드 다운로드 할당량 1회를 사용합니다$count.", "將使用 1 次離線下載額度$count。")
        is OfflineConfirm.Pack -> tr("整包离线", "Download entire package to cloud", "一括クラウドダウンロード", "전체 클라우드 다운로드", "整包離線下載") to
            tr("所选文件未收录，需执行整包离线下载，将占用 ${confirm.plan.packBytes.toReadableSize()} 网盘空间与 1 次离线下载额度$count。", "Selected files are not indexed. Full cloud download requires ${confirm.plan.packBytes.toReadableSize()} of storage and 1 cloud download quota$count.", "選択したファイルは未収録のため、一括クラウドダウンロードが必要です。${confirm.plan.packBytes.toReadableSize()} の容量と1回の利用枠を使用します$count。", "선택한 파일이 미등록 상태이므로 전체 클라우드 다운로드가 필요하며, ${confirm.plan.packBytes.toReadableSize()} 공간과 할당량 1회를 사용합니다$count.", "所選檔案未收錄，需執行整包離線下載，將占用 ${confirm.plan.packBytes.toReadableSize()} 雲端硬碟空間與 1 次離線下載額度$count。")
        is OfflineConfirm.PackOrInstant -> tr("部分文件未收录", "Some files are not indexed", "一部のファイルは未収録です", "일부 파일이 미등록입니다", "部分檔案未收錄") to
            tr("${confirm.fallback.skippedCount} 个文件未收录，整包离线下载将", "${enCount(confirm.fallback.skippedCount, "file", "files")} are not indexed. Full cloud download ", "${confirm.fallback.skippedCount} ファイルが未収録です。一括クラウドダウンロードでは ", "파일 ${confirm.fallback.skippedCount}개가 미등록 상태입니다. 전체 클라우드 다운로드 시 ", "${confirm.fallback.skippedCount} 個檔案未收錄，整包離線下載將") +
            tr("占用 ${confirm.plan.packBytes.toReadableSize()} 网盘空间与 1 次离线下载额度$count。", "requires ${confirm.plan.packBytes.toReadableSize()} of storage and 1 cloud download quota$count.", "${confirm.plan.packBytes.toReadableSize()} の容量と1回の利用枠を使用します$count。", "${confirm.plan.packBytes.toReadableSize()} 공간과 할당량 1회를 사용합니다$count.", "占用 ${confirm.plan.packBytes.toReadableSize()} 雲端硬碟空間與 1 次離線下載額度$count。") +
            tr("也可仅秒传已收录的 ${confirm.fallback.fileCount} 个文件。", "Alternatively, instantly save the ${confirm.fallback.fileCount} indexed files only.", "収録済みの ${confirm.fallback.fileCount} ファイルのみをインスタント保存することも可能です。", "등록된 파일 ${confirm.fallback.fileCount}개만 즉시 저장할 수도 있습니다.", "亦可僅秒傳已收錄的 ${confirm.fallback.fileCount} 個檔案。")
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            // 两条路并列时都是文字按钮：哪条更好由人按空间与次数定，给哪条底色都是替人选
            if (confirm is OfflineConfirm.PackOrInstant) {
                Row {
                    TextButton(onClick = onOffline) { Text(stringResource(Res.string.text_download_entire_package_to_cloud)) }
                    TextButton(onClick = onInstant) { Text(stringResource(Res.string.text_save_indexed_only)) }
                }
            } else {
                PikoDialogConfirm(tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載"), onClick = onOffline)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SaveButton(
    label: String,
    enabled: Boolean,
    isSaving: Boolean,
    onClick: () -> Unit,
    options: List<SaveMenuOption> = emptyList(),
) {
    val containerHeight = ButtonDefaults.MediumContainerHeight
    @Composable
    fun LeadingContent() {
        if (isSaving) {
            InlineLoadingIndicator(color = LocalContentColor.current)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(Res.string.text_saving), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (options.isEmpty()) {
        Button(
            onClick = onClick,
            enabled = enabled && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(containerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(containerHeight),
            shapes = ButtonDefaults.shapes(),
        ) {
            LeadingContent()
        }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (menuOpen) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "save_split_chevron",
    )
    SplitButtonLayout(
        modifier = Modifier.fillMaxWidth(),
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = onClick,
                enabled = enabled && !isSaving,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(containerHeight),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(containerHeight),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(containerHeight),
            ) {
                LeadingContent()
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    enabled = !isSaving && options.any { it.enabled },
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(containerHeight),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(containerHeight),
                    modifier = Modifier.height(containerHeight),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = tr("保存选项", "Save options", "保存オプション", "저장 옵션", "儲存選項"),
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .rotate(chevronRotation),
                    )
                }
                PikoDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            onClick = {
                                menuOpen = false
                                option.onClick()
                            },
                            text = {
                                Text(
                                    text = option.label,
                                    color = if (option.selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                            },
                            shape = menuItemShape(index, options.size),
                            leadingIcon = {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = if (option.selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            trailingContent = if (option.selected) ({
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }) else null,
                            enabled = option.enabled,
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun SaveCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ResolvingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InlineLoadingIndicator()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ErrorBanner(message: String, onRetry: (() -> Unit)?) {
    StatusBanner(
        message = message,
        icon = Icons.Outlined.ErrorOutline,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        actionLabel = tr("重试", "Retry", "再試行", "다시 시도", "重試").takeIf { onRetry != null },
        onAction = onRetry,
    )
}

/**
 * 面板里与内容同宽的一条说明：出错、回执、未收录提示都用它，只换颜色与图标。
 * 正文 bodyMedium，不再用计数下面那种 labelMedium 小字：这些话要据此做决定，得读得清。
 */
@Composable
internal fun StatusBanner(
    message: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text(actionLabel)
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/**
 * 保存位置。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。
 * 原先是 labelSmall 的小胶囊，挤在输入框下，不像能点的东西；改为紧挨主按钮的整行。
 */
@Composable
internal fun TargetRow(
    target: PathBreadcrumb?,
    notice: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && target != null,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderListLeadingVisual(
                name = target?.localizedName.orEmpty(),
                size = 36.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.text_save_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = target?.localizedName ?: tr("正在确认", "Confirming", "確認中", "확인 중", "正在確認"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                notice?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (target != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = tr("更换保存位置", "Change save location", "保存先を変更", "저장 위치 변경", "更換儲存位置"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
