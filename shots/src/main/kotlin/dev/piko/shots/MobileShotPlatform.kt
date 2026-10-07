package dev.piko.shots

import androidx.compose.runtime.Composable
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.platform.LandscapeLock
import dev.piko.ui.platform.PikoPlatform

/**
 * 借桌面端的平台实现拍移动端的界面：交互模型按平台定（见 FormFactor），只改窗口尺寸拍到的是窄的桌面。
 * 系统栏、输入法、返回手势这些 Android 才有的仍拍不到，要在真机上看。
 * 横屏锁给一个空实现，信息流顶栏的「横屏」按钮才画得出来；窗口方向不会真的转。
 */
class MobileShotPlatform(base: PikoPlatform) : PikoPlatform by base {
    override val formFactor: FormFactor = FormFactor.Mobile

    @Composable
    override fun rememberLandscapeLock(): LandscapeLock = NoLandscapeLock
}

private object NoLandscapeLock : LandscapeLock {
    override fun lock() = Unit
    override fun release() = Unit
}
