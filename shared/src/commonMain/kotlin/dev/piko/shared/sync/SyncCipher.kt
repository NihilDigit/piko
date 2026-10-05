package dev.piko.shared.sync

/**
 * 同步进网盘之前的加密，密钥由账号密码派生。两端都是 JVM，实现只有一份 JvmSyncCipher（jvmSharedMain），
 * 由入口组装 PikoServices 时传进来。
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
