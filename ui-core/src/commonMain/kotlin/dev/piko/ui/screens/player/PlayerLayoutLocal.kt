package dev.piko.ui.screens.player

import androidx.compose.runtime.compositionLocalOf
import dev.piko.shared.media.player.PlayerLayoutPrefs

/**
 * 播放器四态自定义布局配置（横屏视频 / 竖屏视频 / 横屏信息流 / 竖屏信息流 + 听音频 + 手势）。
 * 由顶层应用与独立播放窗口统一注入，避免深层参数透传。
 */
val LocalPlayerLayoutPrefs = compositionLocalOf { PlayerLayoutPrefs.Default }
