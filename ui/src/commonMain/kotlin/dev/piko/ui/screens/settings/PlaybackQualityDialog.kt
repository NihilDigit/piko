package dev.piko.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.runtime.Composable
import dev.piko.data.auth.PlayerGestureDefaults

/** 播放画质上限的名字：0 是原画，其余是画面高度。 */
internal fun playbackQualityLabel(maxHeight: Int): String = if (maxHeight <= 0) "原画" else "${maxHeight}P"

/** 设置行的说明：原画照写，其余写成上限，读的人才知道片子没有这一档时会怎样。 */
internal fun playbackQualitySummary(maxHeight: Int): String =
    if (maxHeight <= 0) "原画" else "不高于 ${maxHeight}P"

/** 默认下载画质未设置时的名字：下载视频时每次弹画质框。 */
internal const val DOWNLOAD_QUALITY_UNSET = "每次询问"

internal fun downloadQualitySummary(maxHeight: Int?): String = maxHeight?.let(::playbackQualitySummary) ?: DOWNLOAD_QUALITY_UNSET

/**
 * 画质上限的单选对话框，播放画质与默认下载画质共用，档位相同（[PlayerGestureDefaults.MaxHeightChoices]）。
 * 给了 [unsetLabel] 时在各档之前多列一项「未设置」，选它回调 null。点选即生效并关闭。
 */
@Composable
internal fun PlaybackQualityDialog(
    maxHeight: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    title: String = "播放画质",
    description: String = "超过所选画质时改放较低的转码，无合适转码则放原画。播放时可临时切换。",
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
