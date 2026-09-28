package dev.piko.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 应用图标，路径照抄 docs/icon.svg。ui 模块没有图片资源，为一张图接上资源插件不值得，
 * 两端又都要画它，所以写成矢量。改图标时两处一起改。
 */
val PikoLogo: ImageVector by lazy {
    val white = SolidColor(Color.White)
    fun ImageVector.Builder.fill(d: String) = addPath(PathParser().parsePathString(d).toNodes(), fill = white)
    fun ImageVector.Builder.stroke(d: String, width: Float) = addPath(
        PathParser().parsePathString(d).toNodes(),
        stroke = white,
        strokeLineWidth = width,
        strokeLineJoin = StrokeJoin.Round,
        strokeLineCap = StrokeCap.Round,
    )
    ImageVector.Builder("PikoLogo", 48.dp, 48.dp, 48f, 48f).apply {
        addPath(
            PathParser().parsePathString("M12,0 H36 A12,12 0 0 1 48,12 V36 A12,12 0 0 1 36,48 H12 A12,12 0 0 1 0,36 V12 A12,12 0 0 1 12,0 Z").toNodes(),
            fill = SolidColor(Color(0xFF306EFF)),
        )
        fill("M10.5,16.8 C10.5,14.0 12.2,11.0 13.5,9.8 C14.2,9.0 15.5,9.2 16.0,10.2 L20.8,15.2 Z")
        fill("M37.5,16.8 C37.5,14.0 35.8,11.0 34.5,9.8 C33.8,9.0 32.5,9.2 32.0,10.2 L27.2,15.2 Z")
        stroke("M15,15 H33 A7.5,7.5 0 0 1 40.5,22.5 V32.5 A7.5,7.5 0 0 1 33,40 H15 A7.5,7.5 0 0 1 7.5,32.5 V22.5 A7.5,7.5 0 0 1 15,15 Z", 2.5f)
        fill("M17.5,22.5 H17.5 A1.7,1.7 0 0 1 19.2,24.2 V27.0 A1.7,1.7 0 0 1 17.5,28.7 H17.5 A1.7,1.7 0 0 1 15.8,27.0 V24.2 A1.7,1.7 0 0 1 17.5,22.5 Z")
        fill("M30.5,22.5 H30.5 A1.7,1.7 0 0 1 32.2,24.2 V27.0 A1.7,1.7 0 0 1 30.5,28.7 H30.5 A1.7,1.7 0 0 1 28.8,27.0 V24.2 A1.7,1.7 0 0 1 30.5,22.5 Z")
        stroke("M20.8,31.8 Q24,35.5 27.2,31.8", 2.2f)
    }.build()
}

/** 图标加应用名，放在侧边栏左上角。 */
@Composable
fun PikoBrand(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(PikoLogo, contentDescription = null, modifier = Modifier.size(28.dp))
        Text("Piko", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
