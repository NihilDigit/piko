package dev.piko.ui.screens.clips

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 信息流留边背景的小图预模糊计算与有界缓存。
 *
 * 将原本挂在全屏渲染节点上的实时 `Modifier.blur(32.dp)` 转为小缩略图解码后的一次性后台 3 趟可分离盒式模糊（近似高斯核，
 * 边缘钳位防止黑边/透明边向内晕染），播放期间仅做静态小纹理双线性放大与半透明黑底混合，省去全屏离屏模糊渲染目标。
 */
object BackdropBlurKernel {
    const val DECODE_MAX_SIDE_PX = 64
    private const val PASSES = 3
    private const val MIN_RADIUS_PX = 2

    /**
     * 按目标物理模糊半径与 `ContentScale.Crop` 实际放大倍率折算小图上的等效盒式模糊半径。
     */
    fun equivalentRadiusPx(
        bitmapWidth: Int,
        bitmapHeight: Int,
        containerWidthPx: Int,
        containerHeightPx: Int,
        targetBlurPx: Float,
    ): Int {
        if (bitmapWidth <= 1 || bitmapHeight <= 1) return 1
        val maxAllowed = max(MIN_RADIUS_PX, min(bitmapWidth, bitmapHeight) / 2)
        if (containerWidthPx <= 0 || containerHeightPx <= 0 || targetBlurPx <= 0f) {
            return min(3, maxAllowed)
        }
        val cropScale = max(
            containerWidthPx.toFloat() / bitmapWidth.toFloat(),
            containerHeightPx.toFloat() / bitmapHeight.toFloat(),
        ).coerceAtLeast(1f)
        return (targetBlurPx / cropScale).roundToInt().coerceIn(MIN_RADIUS_PX, maxAllowed)
    }

    /**
     * 对 `0xAARRGGBB` 像素数组原地/双缓冲执行 3 趟边缘钳位（Clamp-to-Edge）可分离盒式模糊，返回新像素数组。
     */
    fun blurArgb(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size >= width * height) {
            "Invalid bitmap dimensions ${width}x$height for buffer size ${pixels.size}"
        }
        val r = radius.coerceIn(0, max(1, min(width, height) / 2))
        if (r <= 0 || (width == 1 && height == 1)) return pixels.copyOf(width * height)

        var src = pixels.copyOf(width * height)
        var dst = IntArray(width * height)
        val kernelSize = r * 2 + 1

        repeat(PASSES) {
            // 横向一维滑动窗口（边缘钳位）
            for (y in 0 until height) {
                val rowOffset = y * width
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (dx in -r..r) {
                    val c = src[rowOffset + dx.coerceIn(0, width - 1)]
                    sumA += c ushr 24
                    sumR += (c ushr 16) and 0xFF
                    sumG += (c ushr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (x in 0 until width) {
                    dst[rowOffset + x] =
                        ((sumA / kernelSize) shl 24) or
                            ((sumR / kernelSize) shl 16) or
                            ((sumG / kernelSize) shl 8) or
                            (sumB / kernelSize)
                    val outIdx = (x - r).coerceIn(0, width - 1)
                    val inIdx = (x + r + 1).coerceIn(0, width - 1)
                    val outC = src[rowOffset + outIdx]
                    val inC = src[rowOffset + inIdx]
                    sumA += (inC ushr 24) - (outC ushr 24)
                    sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
                    sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
                    sumB += (inC and 0xFF) - (outC and 0xFF)
                }
            }
            // 纵向一维滑动窗口（边缘钳位）
            for (x in 0 until width) {
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (dy in -r..r) {
                    val c = dst[dy.coerceIn(0, height - 1) * width + x]
                    sumA += c ushr 24
                    sumR += (c ushr 16) and 0xFF
                    sumG += (c ushr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (y in 0 until height) {
                    src[y * width + x] =
                        ((sumA / kernelSize) shl 24) or
                            ((sumR / kernelSize) shl 16) or
                            ((sumG / kernelSize) shl 8) or
                            (sumB / kernelSize)
                    val outIdx = (y - r).coerceIn(0, height - 1)
                    val inIdx = (y + r + 1).coerceIn(0, height - 1)
                    val outC = dst[outIdx * width + x]
                    val inC = dst[inIdx * width + x]
                    sumA += (inC ushr 24) - (outC ushr 24)
                    sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
                    sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
                    sumB += (inC and 0xFF) - (outC and 0xFF)
                }
            }
        }
        return src
    }
}

/**
 * 进程级有界预模糊背景小图 LRU（至多 32 张，每张不超过 64×64 RGBA ≈ 16 KiB，总计封顶约 512 KiB）。
 * 键包含算法版本、模型标识与等效半径档位，横竖屏切换或换尺寸时不会串用错误半径。
 */
internal object PreblurredBackdropCache {
    private const val VERSION = "v1"
    private const val MAX_ENTRIES = 32
    private val lock = dev.piko.shared.util.PikoLock()
    private val map = LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES)

    fun keyOf(model: Any, containerWidthPx: Int, containerHeightPx: Int, targetBlurPx: Float): String {
        // 将容器长宽比与模糊比例量化到粗粒度桶，避免窗口拖拽缩放时逐像素生成不同缓存键
        val shortSide = min(containerWidthPx, containerHeightPx).coerceAtLeast(1)
        val longSide = max(containerWidthPx, containerHeightPx).coerceAtLeast(1)
        val aspectBucket = ((longSide.toFloat() / shortSide.toFloat()) * 4f).roundToInt()
        val blurRatioBucket = ((targetBlurPx / shortSide.toFloat()) * 32f).roundToInt()
        val landscape = containerWidthPx > containerHeightPx
        return "$VERSION|$landscape|$aspectBucket|$blurRatioBucket|$model"
    }

    fun get(key: String): ImageBitmap? = dev.piko.shared.util.withPikoLock(lock) {
        map.remove(key)?.also { map[key] = it }
    }

    fun put(key: String, bitmap: ImageBitmap) = dev.piko.shared.util.withPikoLock(lock) {
        map.remove(key)
        map[key] = bitmap
        while (map.size > MAX_ENTRIES) {
            val eldest = map.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            } else break
        }
    }

    fun clear() = dev.piko.shared.util.withPikoLock(lock) { map.clear() }
}
