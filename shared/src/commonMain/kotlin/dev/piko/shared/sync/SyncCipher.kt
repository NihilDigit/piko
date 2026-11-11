package dev.piko.shared.sync

/**
 * 网盘机密同步载荷的加解密接口，密钥由账号密码经 PBKDF2 派生。
 * Android 与桌面端使用基于 JDK 标准库的 `JvmSyncCipher`，iOS 端使用零平台外部依赖的 [PortableSyncCipher]，
 * 两者密文线格式完全一致（`IV(12) + 密文 + 标签(16)`），由平台入口在组装 `PikoServices` 时注入。
 */
interface SyncCipher {
    fun newSalt(): ByteArray

    /** 由 [secret] 与 [salt] 经 [iterations] 轮派生密钥。故意算得慢（几百毫秒到一两秒），调用方留着结果。 */
    fun deriveKey(secret: String, salt: ByteArray, iterations: Int): ByteArray

    /** 加密并带上校验，每次用新的随机 IV，结果里含 IV。 */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray

    /** [seal] 的逆。密钥不对或内容被改过时为 null。 */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray?
}
