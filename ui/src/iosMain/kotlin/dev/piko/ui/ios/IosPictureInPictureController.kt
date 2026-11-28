@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import kotlinx.cinterop.COpaquePointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVPlayerLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingOptions
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 画中画控制器：直接绑定 [IosPlaybackBackend.playerLayer]，
 * 支持播放中上滑回桌面自动进入小窗，以及切回前台自动无缝收回小窗。
 *
 * 规避 iOS AVKit 常见坑点：
 * - 强持有 [delegate]，防止弱引用被提前回收；
 * - KVO 监听 `isPictureInPicturePossible` 并通过主线程蹦床，同时在策略刷新时主动重读兜底；
 * - 销毁时严格按序移除通知、移除 KVO、解除 `delegate` 并关闭自动进小窗。
 */
internal class IosPictureInPictureController private constructor(
    private val pipController: AVPictureInPictureController,
) {
    private val _isInPictureInPicture = MutableStateFlow(false)
    val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    private val _isPictureInPicturePossible = MutableStateFlow(false)
    val isPictureInPicturePossible: StateFlow<Boolean> = _isPictureInPicturePossible.asStateFlow()

    private val delegate = PiPDelegate(
        onActiveChanged = { active -> _isInPictureInPicture.value = active },
    )

    private var disposed = false

    private val possibleObserver: NSObject
    private val foregroundObserver: platform.darwin.NSObjectProtocol

    init {
        pipController.delegate = delegate
        pipController.canStartPictureInPictureAutomaticallyFromInline = false
        possibleObserver = pipController.observeKeyPathOnMain(
            keyPath = KEY_IS_PICTURE_IN_PICTURE_POSSIBLE,
            options = NSKeyValueObservingOptionInitial or NSKeyValueObservingOptionNew,
        ) {
            refreshPossible()
        }
        foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
            usingBlock = {
                if (!disposed && pipController.isPictureInPictureActive()) {
                    pipController.stopPictureInPicture()
                }
            },
        )
        refreshPossible()
    }

    fun refreshPossible() {
        if (disposed) return
        _isPictureInPicturePossible.value = pipController.isPictureInPicturePossible()
    }

    fun updatePolicy(autoEnterEnabled: Boolean) {
        if (disposed) return
        pipController.canStartPictureInPictureAutomaticallyFromInline = autoEnterEnabled
        refreshPossible()
    }

    fun enterPictureInPicture() {
        if (disposed) return
        refreshPossible()
        if (pipController.isPictureInPicturePossible() && !pipController.isPictureInPictureActive()) {
            pipController.startPictureInPicture()
        }
    }

    fun exitPictureInPicture() {
        if (disposed) return
        if (pipController.isPictureInPictureActive()) {
            pipController.stopPictureInPicture()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pipController.canStartPictureInPictureAutomaticallyFromInline = false
        NSNotificationCenter.defaultCenter.removeObserver(foregroundObserver)
        pipController.removeObserver(possibleObserver, forKeyPath = KEY_IS_PICTURE_IN_PICTURE_POSSIBLE)
        pipController.delegate = null
        _isInPictureInPicture.value = false
        _isPictureInPicturePossible.value = false
    }

    companion object {
        private const val KEY_IS_PICTURE_IN_PICTURE_POSSIBLE = "isPictureInPicturePossible"

        fun create(layer: AVPlayerLayer): IosPictureInPictureController? {
            if (!AVPictureInPictureController.isPictureInPictureSupported()) return null
            val native = AVPictureInPictureController(playerLayer = layer)
            return IosPictureInPictureController(native)
        }
    }
}

private class PiPDelegate(
    private val onActiveChanged: (Boolean) -> Unit,
) : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    override fun pictureInPictureControllerDidStartPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) {
        onActiveChanged(true)
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) {
        onActiveChanged(false)
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) {
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(true)
    }
}

private class MainQueueKvoObserver(
    private val onChange: () -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        dispatch_async(dispatch_get_main_queue()) { onChange() }
    }
}

private fun NSObject.observeKeyPathOnMain(
    keyPath: String,
    options: NSKeyValueObservingOptions = NSKeyValueObservingOptionNew,
    onChange: () -> Unit,
): NSObject {
    val observer = MainQueueKvoObserver(onChange)
    addObserver(observer, forKeyPath = keyPath, options = options, context = null)
    return observer
}
