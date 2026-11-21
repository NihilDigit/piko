package dev.piko.ui.screens.space

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

@Immutable
internal data class BlockPalette(
    val container: Color,
    val headerContainer: Color,
    val content: Color,
    val secondaryContent: Color,
    val border: Color,
    val hoverBorder: Color,
    val dashed: Boolean = false,
)

/**
 * 8 色和谐色环种子（兼顾明暗主题色相对比）：用于区分并列大目录与大容器内的子分支，
 * 再叠加上按体量排名的饱和度衰减，做到「大块鲜明突出、小块柔和退后、归档与可用空间一眼可辨」。
 */
private val LightBranchSeeds = listOf(
    Color(0xFF356AE6), // 钴蓝 Sapphire
    Color(0xFF0E8A7D), // 青碧 Teal
    Color(0xFFD97724), // 琥珀 Amber
    Color(0xFF7C4DFF), // 紫罗兰 Iris
    Color(0xFF2E8B57), // 翠绿 Emerald
    Color(0xFFC84B6B), // 珊瑚玫红 Rose
    Color(0xFF1F7EA8), // 湖蓝 Cerulean
    Color(0xFF9C6631), // 赭石 Ochre
)

private val DarkBranchSeeds = listOf(
    Color(0xFF6B9BFF), // 亮钴蓝
    Color(0xFF45C4B5), // 亮青碧
    Color(0xFFF3A152), // 亮琥珀
    Color(0xFFB18CFF), // 亮紫罗兰
    Color(0xFF63C78A), // 亮翠绿
    Color(0xFFF07D9A), // 亮珊瑚
    Color(0xFF56B8E6), // 亮湖蓝
    Color(0xFFD6A16A), // 亮赭石
)

internal fun resolveBlockPalette(
    hueIndex: Int,
    rankFraction: Float,
    isFolder: Boolean,
    isPurelyVaulted: Boolean,
    isOuterGroupFrame: Boolean,
    colors: ColorScheme,
    isEInk: Boolean = false,
): BlockPalette {
    if (isEInk) {
        return resolveEInkBlockPalette(
            hueIndex = hueIndex,
            rankFraction = rankFraction,
            isFolder = isFolder,
            isPurelyVaulted = isPurelyVaulted,
            isOuterGroupFrame = isOuterGroupFrame,
        )
    }

    val isDark = colors.surface.luminance() < 0.5f
    val seeds = if (isDark) DarkBranchSeeds else LightBranchSeeds
    val seed = seeds[(hueIndex and Int.MAX_VALUE) % seeds.size]

    if (isPurelyVaulted) {
        val container = if (isDark) {
            lerp(colors.tertiaryContainer, colors.surfaceContainerHigh, if (isOuterGroupFrame) 0.55f else 0.35f + rankFraction * 0.20f)
        } else {
            lerp(colors.tertiaryContainer, colors.surfaceContainerLow, if (isOuterGroupFrame) 0.52f else 0.24f + rankFraction * 0.22f)
        }
        val header = lerp(container, colors.tertiary, if (isDark) 0.16f else 0.12f)
        val fg = if (container.luminance() < 0.45f) Color(0xFFF4EFF8) else Color(0xFF2A1F33)
        return BlockPalette(
            container = container,
            headerContainer = header,
            content = fg,
            secondaryContent = fg.copy(alpha = 0.78f),
            border = colors.tertiary.copy(alpha = if (isDark) 0.42f else 0.36f),
            hoverBorder = colors.tertiary,
        )
    }

    if (isOuterGroupFrame) {
        // 含嵌套子块的外层大目录退为沉稳的结构底框，把视觉焦点让给内部彩色子块
        val frameBase = if (isDark) colors.surfaceContainer else colors.surfaceContainerHigh
        val container = lerp(frameBase, seed, if (isDark) 0.14f else 0.12f)
        val header = lerp(frameBase, seed, if (isDark) 0.28f else 0.24f)
        val fg = if (header.luminance() < 0.42f) Color(0xFFF3F5FA) else Color(0xFF171C26)
        return BlockPalette(
            container = container,
            headerContainer = header,
            content = fg,
            secondaryContent = fg.copy(alpha = 0.78f),
            border = lerp(colors.outlineVariant, seed, 0.45f),
            hoverBorder = seed,
        )
    }

    // 占配额真实块：排名越靠前色彩越饱满（主），越往长尾越向中性容器色收敛（次）；文件比文件夹略轻一档
    val rankAttenuation = rankFraction.coerceIn(0f, 1f) * 0.16f
    val fileAttenuation = if (isFolder) 0f else 0.08f
    val tintStrength = if (isDark) {
        (0.36f - rankAttenuation - fileAttenuation).coerceAtLeast(0.16f)
    } else {
        (0.32f - rankAttenuation - fileAttenuation).coerceAtLeast(0.14f)
    }
    val surfaceAnchor = if (isDark) colors.surfaceContainerHigh else Color(0xFFF8F9FC)
    val container = lerp(surfaceAnchor, seed, tintStrength)
    val fg = if (container.luminance() < 0.44f) Color(0xFFF6F8FC) else Color(0xFF141923)
    return BlockPalette(
        container = container,
        headerContainer = container,
        content = fg,
        secondaryContent = fg.copy(alpha = 0.76f),
        border = lerp(container, seed, if (isDark) 0.50f else 0.45f),
        hoverBorder = if (isDark) lerp(seed, Color.White, 0.25f) else lerp(seed, Color.Black, 0.15f),
    )
}

/**
 * 水墨屏（16 级灰阶电子纸）高对比度色板：
 * - 外层嵌套组底框采用纯白底面配浅灰页眉，内部叶块按体量排名与奇偶分支映射到浅灰阶梯（#D9D9D9..#F2F2F2），
 *   既清晰拉开相邻大块与小块的层次，又确保纯黑墨迹文字始终保持极高对比度；
 * - 全部分组框与叶块统一采用 1dp 纯黑实线描边，彻底屏蔽彩色色块。
 */
private fun resolveEInkBlockPalette(
    hueIndex: Int,
    rankFraction: Float,
    isFolder: Boolean,
    isPurelyVaulted: Boolean,
    isOuterGroupFrame: Boolean,
): BlockPalette {
    if (isOuterGroupFrame) {
        val headerGray = if (isPurelyVaulted) 0.94f else 0.90f
        return BlockPalette(
            container = Color.White,
            headerContainer = Color(headerGray, headerGray, headerGray, 1f),
            content = Color.Black,
            secondaryContent = Color(0xFF222222),
            border = Color.Black,
            hoverBorder = Color.Black,
        )
    }
    if (isPurelyVaulted) {
        val gray = (0.93f + rankFraction.coerceIn(0f, 1f) * 0.04f).coerceIn(0.90f, 0.97f)
        val fill = Color(gray, gray, gray, 1f)
        return BlockPalette(
            container = fill,
            headerContainer = fill,
            content = Color.Black,
            secondaryContent = Color(0xFF333333),
            border = Color.Black,
            hoverBorder = Color.Black,
        )
    }
    val stepOffset = ((hueIndex and 3) * 0.015f)
    val fileOffset = if (isFolder) 0f else 0.025f
    val gray = (0.83f + rankFraction.coerceIn(0f, 1f) * 0.08f + stepOffset + fileOffset).coerceIn(0.82f, 0.95f)
    val fill = Color(gray, gray, gray, 1f)
    return BlockPalette(
        container = fill,
        headerContainer = fill,
        content = Color.Black,
        secondaryContent = Color(0xFF222222),
        border = Color.Black,
        hoverBorder = Color.Black,
    )
}

internal fun freeQuotaPalette(colors: ColorScheme, isEInk: Boolean = false): BlockPalette {
    if (isEInk) {
        return BlockPalette(
            container = Color.White,
            headerContainer = Color.White,
            content = Color.Black,
            secondaryContent = Color(0xFF333333),
            border = Color.Black,
            hoverBorder = Color.Black,
            dashed = false,
        )
    }
    val isDark = colors.surface.luminance() < 0.5f
    val bg = if (isDark) colors.surfaceContainerLowest else colors.surfaceContainerLow
    return BlockPalette(
        container = bg,
        headerContainer = bg,
        content = colors.onSurfaceVariant,
        secondaryContent = colors.onSurfaceVariant.copy(alpha = 0.78f),
        border = colors.outlineVariant.copy(alpha = 0.75f),
        hoverBorder = colors.outline,
        dashed = true,
    )
}

internal fun otherGroupPalette(colors: ColorScheme, isEInk: Boolean = false): BlockPalette {
    if (isEInk) {
        val fill = Color(0xFFE6E6E6)
        return BlockPalette(
            container = fill,
            headerContainer = fill,
            content = Color.Black,
            secondaryContent = Color(0xFF222222),
            border = Color.Black,
            hoverBorder = Color.Black,
        )
    }
    return BlockPalette(
        container = colors.surfaceContainerHighest,
        headerContainer = colors.surfaceContainerHighest,
        content = colors.onSurface,
        secondaryContent = colors.onSurfaceVariant,
        border = colors.outlineVariant.copy(alpha = 0.70f),
        hoverBorder = colors.primary,
    )
}
