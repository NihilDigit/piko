package dev.piko.shared.sync

/**
 * 平台的安全随机数来源。iOS 没有 `java.security.SecureRandom`，同步用的盐与 IV 必须真随机，
 * 所以按平台各给一份 [secureRandomBytes]。
 */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * 不依赖任何平台加密库的 [SyncCipher]（供 iOS 等 Kotlin/Native 目标使用）：PBKDF2-HMAC-SHA256 派生 256 位密钥，
 * AES-256-GCM 加密，线格式与 Android/桌面端的 `JvmSyncCipher` 完全一致（`IV(12) + 密文 + 标签(16)`），
 * 跨平台密文互通由单元测试交叉验证。
 *
 * 之所以在 Native 端自备纯 Kotlin 实现：iOS CommonCrypto 未对 Kotlin/Native 暴露稳定的公开 AES-GCM C 绑定，
 * 直接对齐 `JvmSyncCipher` 的参数与字节布局可消除平台桥接差异。
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
