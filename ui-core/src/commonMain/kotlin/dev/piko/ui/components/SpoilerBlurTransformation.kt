package dev.piko.ui.components

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import dev.piko.ui.platform.PikoPlatform

/**
 * 在 Coil 后台解码阶段对防窥小缩略图一次性执行 3 趟边缘钳位可分离盒式模糊并写入 Coil MemoryCache，
 * 消除网格滚动时每屏 20~60 个实时 GPU `Modifier.blur` 离屏 RenderEffect 图层，同时覆盖 Android 12 以下设备。
 *
 * 隐私兜底：若位图转换失败绝不返回未模糊原图，而是抛出异常交由占位遮蔽层接管。
 */
internal class SpoilerBlurTransformation(
    private val platform: PikoPlatform,
    private val radiusPx: Int,
) : Transformation() {
    override val cacheKey: String = "piko-spoiler-blur-v1:$radiusPx"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        platform.blurCoilBitmap(input, radiusPx) ?: error("Failed to pre-blur spoiler thumbnail")
}
