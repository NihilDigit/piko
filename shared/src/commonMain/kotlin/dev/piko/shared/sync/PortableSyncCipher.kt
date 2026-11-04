package dev.piko.shared.sync

/**
 * 平台的安全随机数来源。iOS 没有 `java.security.SecureRandom`，同步用的盐与 IV 必须真随机，
 * 所以按平台各给一份 [secureRandomBytes]。
 */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * 不依赖任何平台加密库的 [SyncCipher]：PBKDF2-HMAC-SHA256 派生 256 位密钥，AES-256-GCM 加密，
 * 结果布局与 [JvmSyncCipher] 完全一致（IV(12) + 密文 + 标签(16)），所以 iOS 写的同步包桌面能解、
 * 桌面包 iOS 也能解。
 *
 * 之所以不用平台实现：桌面与 Android 走 JDK，iOS 走 CommonCrypto，两边参数一旦有细微差别（比如
 * 标签长度或 IV 布局），用户就会在两台设备之间丢数据。同一份代码三端跑，差异面为零。
 */
class PortableSyncCipher : SyncCipher {
    override fun newSalt(): ByteArray = secureRandomBytes(SALT_BYTES)

    override fun deriveKey(secret: String, salt: ByteArray, iterations: Int): ByteArray =
        PortableDigest.pbkdf2HmacSha256(secret.encodeToByteArray(), salt, iterations, KEY_BYTES)

    override fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = secureRandomBytes(IV_BYTES)
        return iv + PortableGcm.seal(PortableAes(key), iv, plain)
    }

    override fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val iv = sealed.copyOfRange(0, IV_BYTES)
        return PortableGcm.open(PortableAes(key), iv, sealed.copyOfRange(IV_BYTES, sealed.size))
    }

    private companion object {
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
    }
}
