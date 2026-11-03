package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.runtime.Composable
import dev.piko.data.auth.PlayerGestureDefaults

/** 播放画质上限的名字：0 是原画，其余是画面高度。 */
internal fun playbackQualityLabel(maxHeight: Int): String = if (maxHeight <= 0) tr("原画", "Original", "オリジナル", "원본", "原畫") else "${maxHeight}P"

/** 设置行的说明：原画照写，其余写成上限，读的人才知道片子没有这一档时会怎样。 */
internal fun playbackQualitySummary(maxHeight: Int): String =
    if (maxHeight <= 0) tr("原画", "Original", "オリジナル", "원본", "原畫") else tr("不高于 ${maxHeight}P", "Up to ${maxHeight}P", "${maxHeight}P 以下", "최대 ${maxHeight}P", "不高於 ${maxHeight}P")

internal fun downloadQualitySummary(maxHeight: Int?): String = playbackQualitySummary(maxHeight ?: 0)

/**
 * 画质上限的单选对话框，播放画质与默认下载画质共用，档位相同（[PlayerGestureDefaults.MaxHeightChoices]）。
 * 给了 [unsetLabel] 时在各档之前多列一项「未设置」，选它回调 null。点选即生效并关闭。
 */
@Composable
internal fun PlaybackQualityDialog(
    maxHeight: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    title: String = tr("播放画质", "Playback quality", "再生画質", "재생 화질", "播放畫質"),
    description: String = tr("超出所选画质上限时自动选用较低档位转码；无匹配转码时播放原画。可在播放时随时切换。", "Uses a lower transcode when the selected quality is exceeded, or plays the original if none fits. Switchable during playback.", "選択した画質を超える場合は低い画質の変換ストリームを使用し、該当がない場合はオリジナルを再生します。再生中も切り替え可能です。", "선택한 화질을 초과하면 더 낮은 변환 화질을 재생하며, 적합한 화질이 없으면 원본을 재생합니다. 재생 중에도 변경할 수 있습니다.", "超出所選畫質上限時自動選用較低檔位轉碼；無相符轉碼時播放原畫。可於播放時隨時切換。"),
    unsetLabel: String? = null,
) {
    val choices: List<Int?> = (if (unsetLabel != null) listOf(null) else emptyList()) + PlayerGestureDefaults.MaxHeightChoices
    SettingsChoiceDialog(
        icon = Icons.Outlined.HighQuality,
        title = title,
        description = description,
        onDismiss = onDismiss,
    ) {
        choices.forEach { choice ->
            SettingsChoiceOption(
                title = choice?.let(::playbackQualityLabel) ?: unsetLabel.orEmpty(),
                selected = choice == maxHeight,
                onClick = {
                    onSelect(choice)
                    onDismiss()
                },
            )
        }
    }
}
