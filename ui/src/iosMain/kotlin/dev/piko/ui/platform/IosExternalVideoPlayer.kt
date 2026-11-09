@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.ui.platform

import dev.piko.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** Use an expiring CDN URL: iOS may suspend Piko as soon as the other player opens. */
internal class IosExternalVideoPlayer : ExternalVideoPlayer {
    override val requiresDirectUrl = true

    override suspend fun open(url: String, fileName: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { result ->
            var host = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (host?.presentedViewController != null) host = host?.presentedViewController
            if (host == null) {
                result.resume(false)
                return@suspendCancellableCoroutine
            }
            val dialog = UIAlertController.alertControllerWithTitle(
                title = tr("选择播放器", "Choose player", "プレーヤーを選択", "플레이어 선택", "選擇播放器"),
                message = fileName,
                preferredStyle = UIAlertControllerStyleAlert,
            )
            listOf("SenPlayer", "Rex", "VLC", "Outplayer").forEach { player ->
                dialog.addAction(UIAlertAction.actionWithTitle(player, UIAlertActionStyleDefault) {
                    if (!result.isActive) return@actionWithTitle
                    val destination = externalPlayerUrl(player, url)?.let { NSURL.URLWithString(it) }
                    if (destination == null) {
                        result.resume(false)
                    } else {
                        UIApplication.sharedApplication.openURL(destination, options = emptyMap<Any?, Any>()) { opened ->
                            if (result.isActive) result.resume(opened)
                        }
                    }
                })
            }
            dialog.addAction(UIAlertAction.actionWithTitle(
                tr("复制链接（其他播放器）", "Copy link for another player", "リンクをコピー", "링크 복사", "複製連結（其他播放器）"),
                UIAlertActionStyleDefault,
            ) {
                if (!result.isActive) return@actionWithTitle
                UIPasteboard.generalPasteboard.string = url
                result.resume(true)
            })
            dialog.addAction(UIAlertAction.actionWithTitle(tr("取消", "Cancel", "キャンセル", "취소", "取消"), UIAlertActionStyleCancel) {
                if (result.isActive) result.resume(true)
            })
            result.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) {
                    dialog.dismissViewControllerAnimated(false, completion = null)
                }
            }
            host.presentViewController(dialog, animated = true, completion = null)
        }
    }
}
