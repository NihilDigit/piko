package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.ui.components.PikoDialog

/** 播放画质上限的名字：0 是原画，其余是画面高度。 */
internal fun playbackQualityLabel(maxHeight: Int): String = if (maxHeight <= 0) "原画" else "${maxHeight}P"

/** 设置行的说明：原画照写，其余写成上限，读的人才知道片子没有这一档时会怎样。 */
internal fun playbackQualitySummary(maxHeight: Int): String =
    if (maxHeight <= 0) "原画" else "不高于 ${maxHeight}P"

/** 画质上限的单选对话框，播放画质与下载画质共用，选项相同（[PlayerGestureDefaults.MaxHeightChoices]）。 */
@Composable
internal fun PlaybackQualityDialog(
    maxHeight: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "播放画质",
    description: String = "超过所选画质时改放较低的转码，没有合适的转码则放原画。播放时仍可临时切换。",
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    PlayerGestureDefaults.MaxHeightChoices.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(selected = choice == maxHeight, role = Role.RadioButton) {
                                    onSelect(choice)
                                    onDismiss()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 单选钮不单独接点击，免得点钮和点行各触发一次
                            RadioButton(selected = choice == maxHeight, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Text(playbackQualityLabel(choice), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
