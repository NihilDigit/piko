package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_shortcuts_hint
import dev.piko.ui.resources.player_shortcuts_title
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular

/**
 * 播放器内快捷键速查浮层（`?` 键唤出，`?` 或 `Esc` 关闭，不与全局 `F1` / `Ctrl+/` 冲突）：
 * 仅展示播放器热键，采用深色高光描边半透明卡片与键帽胶囊，不打断播放且保持画面感知。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerShortcutsOverlay(
    visible: Boolean,
    compactWidth: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val reduced = LocalPikoMotion.current.reduced
    val fixed = LocalFixedColors.current

    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(motion.fastEffectsSpec()) else fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.94f),
        exit = if (reduced) fadeOut(motion.fastEffectsSpec()) else fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.96f),
        modifier = modifier.fillMaxSize(),
    ) {
        val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
        val primary = if (shortcutModifier == ShortcutModifier.Command) "⌘" else "Ctrl+"
        val items = remember(primary) {
            listOf(
                "Space / K" to tr("播放或暂停", "Play / pause", "再生 / 一時停止", "재생 / 일시 중지", "播放或暫停"),
                "← / →" to tr("按步长快退或快进", "Seek by step", "巻き戻し / 早送り", "간격별 탐색", "依步長快退或快進"),
                "Shift+← / →" to tr("快退或快进 1 分钟", "Seek 1 minute", "1分移動", "1분 이동", "快退或快進 1 分鐘"),
                "0–9 / Home" to tr("跳转百分比 / 回到开头", "Jump 0%–90% / start", "割合移動 / 先頭へ", "구간 이동 / 처음으로", "跳轉百分比 / 回到開頭"),
                "↑ / ↓" to tr("调节音量（或滚轮）", "Volume (or wheel)", "音量（ホイール可）", "볼륨 (휠 가능)", "調整音量（或滾輪）"),
                "M" to tr("静音或取消静音", "Mute / unmute", "ミュート切替", "음소거 전환", "靜音或取消靜音"),
                "[ / ]" to tr("减速或加速", "Slower / faster", "再生速度を変更", "재생 속도 조절", "減速或加速"),
                "Backspace" to tr("恢复 1× 原速", "Reset speed to 1×", "1倍速に戻す", "1배속 복원", "還原 1× 原速"),
                "C / A" to tr("字幕开关 / 切换音轨", "Subtitles / audio track", "字幕 / 音声切替", "자막 / 오디오 트랙", "字幕開關 / 切換音軌"),
                "PgUp / PgDn" to tr("上一集或下一集", "Previous / next episode", "前 / 次のエピソード", "이전 / 다음 에피소드", "上一集或下一集"),
                "F / F11" to tr("全屏切换", "Toggle fullscreen", "全画面切替", "전체 화면 전환", "全螢幕切換"),
                "R" to tr("顺时针旋转 90°", "Rotate 90° clockwise", "右に90度回転", "시계 방향 90° 회전", "順時針旋轉 90°"),
                "I" to tr("播放详细信息", "Playback stats", "詳細情報", "재생 세부 정보", "播放詳細資訊"),
                "T" to tr("窗口置顶", "Pin on top", "最前面表示", "창 항상 위에 표시", "視窗置頂"),
                "${primary}W" to tr("关闭播放窗口", "Close window", "ウィンドウを閉じる", "창 닫기", "關閉播放視窗"),
                "Esc" to tr("关闭浮层或退出全屏", "Dismiss / exit fullscreen", "閉じる / 全画面終了", "닫기 / 전체 화면 종료", "關閉浮層或結束全螢幕"),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fixed.ScrimOnMedia)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, fixed.HudSpecularBorder),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = if (compactWidth) 420.dp else 640.dp)
                    .heightIn(max = 520.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.player_shortcuts_title),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                text = stringResource(Res.string.player_shortcuts_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Filled.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
                        }
                    }

                    val scrollState = rememberScrollState()
                    if (compactWidth) {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items.forEach { (key, desc) ->
                                ShortcutCheatRow(key, desc)
                            }
                        }
                    } else {
                        val half = (items.size + 1) / 2
                        val left = items.subList(0, half)
                        val right = items.subList(half, items.size)
                        Row(
                            modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                left.forEach { (key, desc) -> ShortcutCheatRow(key, desc) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                right.forEach { (key, desc) -> ShortcutCheatRow(key, desc) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutCheatRow(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            Text(
                text = keys,
                style = MaterialTheme.typography.labelSmall.tabular,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
