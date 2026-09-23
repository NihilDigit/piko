package dev.piko.ui.screens.player

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.colorScheme

/**
 * 播放器控件固定使用深色配色。
 *
 * 控件叠在视频画面上，背景是黑色渐变遮罩而不是应用的 surface；跟随浅色主题时
 * onSurface 为深色，在遮罩上不可读。主题色仍与应用其余部分一致。
 * 字体与形状沿用外层主题。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerTheme(content: @Composable () -> Unit) {
    // 深色方案跟随用户选的主题色，深浅则不跟随：固定取深色
    val colorScheme = LocalAppearance.current.colorScheme(dark = true)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
