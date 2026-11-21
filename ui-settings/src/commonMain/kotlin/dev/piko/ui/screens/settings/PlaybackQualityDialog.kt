package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.runtime.Composable
import dev.piko.data.auth.PlayerGestureDefaults

/** 播放画质档位名称：-1 是自动，0 是原画，其余是画面高度。 */
internal fun playbackQualityLabel(maxHeight: Int): String = when {
    maxHeight == PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT -> tr("自动", "Auto", "自動", "자동", "自動")
    maxHeight <= 0 -> tr("原画", "Original", "オリジナル", "원본", "原畫")
    else -> "${maxHeight}P"
}

/** 设置行的说明：自动说明随网速与倍速调节，原画照写，其余写成上限，读的人才知道片子没有这一档时会怎样。 */
internal fun playbackQualitySummary(maxHeight: Int): String = when {
    maxHeight == PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT -> tr(
        "自动（按网速与倍速调节）",
        "Auto (adapts to network & speed)",
        "自動（回線速度と倍速に応じて調整）",
        "자동 (네트워크 및 배속에 따라 조절)",
        "自動（依網速與倍速調節）",
    )
    maxHeight <= 0 -> tr("原画", "Original", "オリジナル", "원본", "原畫")
    else -> tr("不高于 ${maxHeight}P", "Up to ${maxHeight}P", "${maxHeight}P 以下", "최대 ${maxHeight}P", "不高於 ${maxHeight}P")
}

internal fun downloadQualitySummary(maxHeight: Int?): String =
    playbackQualitySummary((maxHeight ?: 0).coerceAtLeast(0))

/**
 * 画质上限的单选对话框，播放画质与默认下载画质共用固定档位（[PlayerGestureDefaults.MaxHeightChoices]）。
 * 仅在线播放画质通过 [showAutoOption] 额外提供首项「自动」（[PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT]）；
 * 给了 [unsetLabel] 时在各档之前多列一项「未设置」，选它回调 null。点选即生效并关闭。
 */
@Composable
internal fun PlaybackQualityDialog(
    maxHeight: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    showAutoOption: Boolean = false,
    title: String = tr("播放画质", "Playback quality", "再生画質", "재생 화질", "播放畫質"),
    description: String = if (showAutoOption) {
        tr(
            "「自动」会按当前网速与倍速动态升降画质；选择原画或固定画质上限时不会自动切档。超出所选上限时选用较低档转码，无匹配转码时播放原画。可在播放时随时切换。",
            "Auto adapts quality to network throughput and playback speed. Original or a fixed cap never switches automatically; when a cap is exceeded, a lower transcode is used (or Original if none fits). Switchable during playback.",
            "「自動」は回線速度と再生速度に応じて画質を自動調整します。オリジナルや上限を指定した場合は自動切替しません。上限を超える場合は低い変換ストリームを、該当がない場合はオリジナルを再生します。再生中も切り替え可能です。",
            "'자동'은 네트워크 속도와 재생 속도에 맞춰 화질을 조절합니다. 원본 또는 고정 한도를 선택하면 자동 전환되지 않습니다. 한도를 초과하면 더 낮은 변환 화질을, 적합한 화질이 없으면 원본을 재생합니다. 재생 중에도 변경할 수 있습니다.",
            "「自動」會依目前網速與倍速動態升降畫質；選擇原畫或固定上限時不會自動切換。超出所選上限時選用較低檔轉碼，無相符轉碼時播放原畫。可於播放時隨時切換。",
        )
    } else {
        tr("超出所选画质上限时自动选用较低档位转码；无匹配转码时播放原画。可在播放时随时切换。", "Uses a lower transcode when the selected quality is exceeded, or plays the original if none fits. Switchable during playback.", "選択した画質を超える場合は低い画質の変換ストリームを使用し、該当がない場合はオリジナルを再生します。再生中も切り替え可能です。", "선택한 화질을 초과하면 더 낮은 변환 화질을 재생하며, 적합한 화질이 없으면 원본을 재생합니다. 재생 중에도 변경할 수 있습니다.", "超出所選畫質上限時自動選用較低檔位轉碼；無相符轉碼時播放原畫。可於播放時隨時切換。")
    },
    unsetLabel: String? = null,
) {
    val choices: List<Int?> = buildList {
        if (unsetLabel != null) add(null)
        if (showAutoOption) add(PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT)
        addAll(PlayerGestureDefaults.MaxHeightChoices)
    }
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
