package dev.piko.shared.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** iOS 的系统随机数。取不到就抛：宁可让同步失败，也不要用可预测的盐或 IV。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        check(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status" }
    }
    return bytes
}
