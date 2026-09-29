package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog

/**
 * 系统保管处（[primary]）为主，明文文件（[fallback]）兜底。
 *
 * 兜底里有一项，只可能是写主时失败了：写主成功与提升成功都会删掉兜底那份。所以兜底里的一定比主里的新，
 * 读时先看兜底，而不是先读主、主里没有再读兜底。后者在「钥匙串一时锁着、刷新后的令牌落进兜底、
 * 之后钥匙串又能读了」时会读到主里那份旧令牌，refresh token 已被轮换，只能重新登录。
 */
class LayeredVault(private val primary: SecretVault?, private val fallback: SecretVault) : SecretVault {
    val hasPrimary: Boolean get() = primary != null

    fun inFallback(key: String): Boolean = fallback.read(key) != null

    override fun read(key: String): ByteArray? {
        fallback.read(key)?.let { data ->
            if (primary != null) promote(key, data)
            return data
        }
        return primary?.read(key)
    }

    /** 兜底里的挪进主：写入并读回核对一致后才删兜底。主用不了时原样留着，下次读再试。 */
    private fun promote(key: String, data: ByteArray) {
        val primary = primary ?: return
        try {
            primary.write(key, data)
            check(primary.read(key)?.contentEquals(data) == true) { "读回的内容与写入的不一致" }
        } catch (e: Exception) {
            PikoLog.w(TAG, "兜底层的凭据未能移入系统保管处，暂留原处", e)
            return
        }
        runCatching { fallback.delete(key) }.onFailure { PikoLog.w(TAG, "凭据已移入系统保管处，删除兜底副本失败", it) }
    }

    override fun write(key: String, data: ByteArray) {
        if (primary == null) return fallback.write(key, data)
        try {
            primary.write(key, data)
        } catch (e: Exception) {
            PikoLog.w(TAG, "系统保管处写入失败，凭据改存兜底层", e)
            fallback.write(key, data)
            return
        }
        runCatching { fallback.delete(key) }.onFailure { PikoLog.w(TAG, "删除兜底层的旧凭据失败", it) }
    }

    // 主里删不掉只记日志：账号已从列表里去掉，留下的那份不会再被读到，下次以同一账号登录时被覆盖
    override fun delete(key: String) {
        fallback.delete(key)
        primary?.let { vault ->
            runCatching { vault.delete(key) }.onFailure { PikoLog.w(TAG, "系统保管处的凭据删除失败", it) }
        }
    }

    private companion object {
        const val TAG = "credentials"
    }
}
