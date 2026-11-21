@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import dev.piko.shared.log.PikoLog
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Swift 宿主容器控制器实现的系统界面委托，由 `PikoApp.swift` 在启动时注册。
 * 使用接口而非 Kotlin 函数类型，使 Objective-C/Swift 导出直接暴露原生 `Bool` 参数。
 */
interface IosSystemUiDelegate {
    fun updateSystemUi(statusBarHidden: Boolean, lightStatusBarContent: Boolean)
    fun setLandscapeLocked(locked: Boolean)
}

/**
 * Kotlin/Compose 与 Swift `UIViewController` 容器间的进程级薄桥：
 * - 统一以引用计数维护状态栏隐藏与沉浸浅色图标（避免 `HideSystemBars` 与 `FullscreenDialog` 互相覆盖）；
 * - 将横屏锁定转发给宿主（Swift 侧在 iOS 16+ 使用 `@available` 安全调用 `UIWindowScene.requestGeometryUpdate`，避免 iOS 15 弱链接崩溃）；
 * - 提供多场景安全的最顶层控制器查找与 `AVAudioSessionCategoryPlayback` 激活。
 */
object IosHostBridge {
    private var delegate: IosSystemUiDelegate? = null
    private val hiddenTokens = LinkedHashSet<Any>()
    private val lightBarTokens = LinkedHashSet<Any>()
    private var landscapeLocked = false

    val supportsLandscapeLock: Boolean
        get() = delegate != null

    fun attachDelegate(delegate: IosSystemUiDelegate) {
        this.delegate = delegate
        dispatchSystemUi()
        if (landscapeLocked) {
            delegate.setLandscapeLocked(true)
        }
    }

    fun detachDelegate(delegate: IosSystemUiDelegate) {
        if (this.delegate === delegate) {
            this.delegate = null
        }
    }

    internal fun acquireStatusBarHidden(): () -> Unit {
        val token = Any()
        hiddenTokens += token
        dispatchSystemUi()
        return {
            if (hiddenTokens.remove(token)) dispatchSystemUi()
        }
    }

    internal fun acquireImmersiveBars(hidden: Boolean, lightContent: Boolean): () -> Unit {
        val token = Any()
        if (hidden) hiddenTokens += token
        if (lightContent) lightBarTokens += token
        dispatchSystemUi()
        return {
            val changedHidden = hiddenTokens.remove(token)
            val changedLight = lightBarTokens.remove(token)
            if (changedHidden || changedLight) dispatchSystemUi()
        }
    }

    internal fun setLandscapeLocked(locked: Boolean) {
        if (landscapeLocked == locked) return
        landscapeLocked = locked
        delegate?.setLandscapeLocked(locked)
    }

    private fun dispatchSystemUi() {
        delegate?.updateSystemUi(
            statusBarHidden = hiddenTokens.isNotEmpty(),
            lightStatusBarContent = lightBarTokens.isNotEmpty(),
        )
    }

    /** 兼容 iOS 15–27+ 与 iPadOS 多场景的最顶层可展示控制器。 */
    internal fun topViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val activeScene = scenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?: scenes.filterIsInstance<UIWindowScene>().firstOrNull()
        val keyWindow = activeScene?.keyWindow
            ?: activeScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
            ?: UIApplication.sharedApplication.keyWindow
        var controller = keyWindow?.rootViewController ?: return null
        while (true) {
            val presented = controller.presentedViewController ?: return controller
            controller = presented
        }
    }

    /** 激活 `AVAudioSessionCategoryPlayback`，确保静音拨片开启时仍正常发声并支持画中画与隔空播放。 */
    internal fun configurePlaybackAudioSession(tag: String) {
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val catError = alloc<ObjCObjectVar<NSError?>>()
            catError.value = null
            val catOk = session.setCategory(AVAudioSessionCategoryPlayback, error = catError.ptr)
            if (!catOk || catError.value != null) {
                val err = catError.value
                PikoLog.w(tag, "Failed to set AVAudioSession category: domain=${err?.domain}, code=${err?.code}")
            }
            val activeError = alloc<ObjCObjectVar<NSError?>>()
            activeError.value = null
            val activeOk = session.setActive(true, error = activeError.ptr)
            if (!activeOk || activeError.value != null) {
                val err = activeError.value
                PikoLog.w(tag, "Failed to activate AVAudioSession: domain=${err?.domain}, code=${err?.code}")
            }
        }
    }
}
