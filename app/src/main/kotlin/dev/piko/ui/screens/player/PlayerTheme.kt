package dev.piko.ui.screens.player

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.piko.ui.theme.PikoDarkColors

/**
 * 播放器控件固定使用深色配色。
 *
 * 控件叠在视频画面上，背景是黑色渐变遮罩而不是应用的 surface；跟随浅色主题时
 * onSurface 为深色，在遮罩上不可读。动态取色仍然保留，主色与应用其余部分一致。
 * 字体与形状沿用外层主题。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            PikoDarkColors
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
