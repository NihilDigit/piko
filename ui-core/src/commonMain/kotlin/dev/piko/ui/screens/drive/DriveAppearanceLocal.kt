package dev.piko.ui.screens.drive

import androidx.compose.runtime.compositionLocalOf
import dev.piko.shared.data.DriveAppearancePrefs

/** 网盘三视图（列表 / 网格 / 瀑布流）文件外观偏好，供列表行、网格海报卡与瀑布流画布就近读取而无需层层透传。 */
val LocalDriveAppearancePrefs = compositionLocalOf { DriveAppearancePrefs.DEFAULT }
