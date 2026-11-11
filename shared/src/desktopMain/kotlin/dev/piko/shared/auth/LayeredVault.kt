package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog
import java.nio.file.Path

/**
 * 系统保管处（[primary]）为主，明文文件（[fallback]）兜底。
 *
 * 兜底里有一项，只可能是写主时失败了：写主成功与提升成功都会删掉兜底那份。所以兜底里的一定比主里的新，
 * 读时先看兜底，而不是先读主、主里没有再读兜底。后者在「钥匙串一时锁着、刷新后的令牌落进兜底、
 * 之后钥匙串又能读了」时会读到主里那份旧令牌，refresh token 已被轮换，只能重新登录。
 *
 * 没有兜底时，主写不进直接抛给调用方。
 */
class LayeredVault(private val primary: SecretVault?, private val fallback: SecretVault?) : SecretVault {
    init {
        require(primary != null || fallback != null)
    }

    val hasPrimary: Boolean get() = primary != null

    fun inFallback(key: String): Boolean = runCatching { fallback?.read(key) != null }.getOrDefault(false)

    override fun read(key: String): ByteArray? {
        val fallbackData = fallback?.let { fb ->
            try {
                fb.read(key)
            } catch (e: Exception) {
                if (primary == null) throw e
                PikoLog.w(TAG, "Failed to read fallback secret for key=$key; falling back to system vault", e)
                null
            }
        }
        if (fallbackData != null) {
            if (primary != null) promote(key, fallbackData)
            return fallbackData
        }
        return primary?.read(key)
    }

    /** 兜底里的挪进主：写入并读回核对一致后才删兜底。主用不了时原样留着，下次读再试。 */
    private fun promote(key: String, data: ByteArray) {
        val primary = primary ?: return
        val fallback = fallback ?: return
        try {
            primary.write(key, data)
            check(primary.read(key)?.contentEquals(data) == true) { "System vault read-back did not match written bytes" }
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to promote fallback secret key=$key to system vault; keeping fallback copy", e)
            return
        }
        runCatching { fallback.delete(key) }
            .onSuccess { PikoLog.i(TAG, "Promoted secret key=$key from fallback storage to system vault") }
            .onFailure { PikoLog.w(TAG, "Promoted secret key=$key to system vault, but failed to delete fallback copy", it) }
    }

    override fun write(key: String, data: ByteArray) {
        val fallback = fallback
        if (primary == null) return fallback!!.write(key, data)
        if (fallback == null) return primary.write(key, data)
        try {
            primary.write(key, data)
        } catch (e: Exception) {
            PikoLog.w(TAG, "System vault write failed for key=$key; storing in fallback vault", e)
            fallback.write(key, data)
            return
        }
        runCatching { fallback.delete(key) }
            .onFailure { PikoLog.w(TAG, "Failed to delete stale fallback secret for key=$key", it) }
    }

    // 兜底与主删除异常相互隔离：账号已从列表移除，任一层删除失败仅记警告，不得阻断另一层清理
    override fun delete(key: String) {
        fallback?.let { fb ->
            runCatching { fb.delete(key) }.onFailure { PikoLog.w(TAG, "Failed to delete fallback secret for key=$key", it) }
        }
        primary?.let { vault ->
            runCatching { vault.delete(key) }.onFailure { PikoLog.w(TAG, "Failed to delete system vault secret for key=$key", it) }
        }
    }

    private companion object {
        const val TAG = "Credentials"
    }
}

/**
 * [directory] 下的分层保管处。兜底只配给钥匙串、Secret Service 这类会一时锁着的外部服务，锁着时写下的
 * 明文文件靠 0600 权限保护，解锁后移回；Linux 上没有 Secret Service 时只有它。
 *
 * 主本身就是本目录里的加密文件时（Windows 的 DPAPI）不设兜底：DPAPI 是系统组件，没有锁着这种暂时状态，
 * 加密失败就是出错，应当报给调用方，而不是在同一目录里改存一份明文。
 */
fun layeredSecretVault(directory: Path, primary: SecretVault?): LayeredVault =
    LayeredVault(primary, fallback = if (primary is FileSecretVault) null else PlainFileVault(directory))
