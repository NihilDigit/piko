package dev.piko.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.piko.shared.log.PikoLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感凭据落盘前的对称加密（用于登录会话、登录密码、压缩包密码、分片上传凭据与 MetaTube 令牌）。
 * 密钥在 AndroidKeyStore 里生成且不可导出，DataStore 文件被拷走也解不开。
 *
 * 解密失败（系统升级清了密钥库、刷机后恢复的数据）一律返回 null 由调用方按未保存处理，
 * 不能让它变成启动崩溃。
 */
internal object CredentialCipher {
    private const val TAG = "Credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "piko_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val VERSION_PREFIX = "v1:"

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    fun isEncrypted(stored: String): Boolean = stored.startsWith(VERSION_PREFIX)

    fun encrypt(plain: String): String = try {
        encryptWithKey(plain, secretKey(forceReload = false))
    } catch (_: Exception) {
        // 密钥库句柄失效或系统升级重置后清掉进程内缓存重试一次，保持与每次现取 KeyStore 相同的恢复行为
        try {
            encryptWithKey(plain, secretKey(forceReload = true))
        } catch (e: Exception) {
            PikoLog.w(TAG, "AndroidKeyStore encryption failed (${e.javaClass.simpleName})")
            throw e
        }
    }

    private fun encryptWithKey(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return VERSION_PREFIX + encode(cipher.iv) + ":" + encode(sealed)
    }

    fun decrypt(stored: String): String? = runCatching {
        val parts = stored.removePrefix(VERSION_PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "Malformed encrypted payload" }
        val iv = decode(parts[0])
        val sealed = decode(parts[1])
        try {
            decryptWithKey(iv, sealed, secretKey(forceReload = false))
        } catch (_: Exception) {
            decryptWithKey(iv, sealed, secretKey(forceReload = true))
        }
    }.onFailure { e ->
        PikoLog.w(TAG, "AndroidKeyStore decryption failed (${e.javaClass.simpleName})")
    }.getOrNull()

    private fun decryptWithKey(iv: ByteArray, sealed: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private fun secretKey(forceReload: Boolean): SecretKey {
        if (!forceReload) {
            cachedSecretKey?.let { return it }
        }
        return synchronized(this) {
            if (forceReload) {
                cachedSecretKey = null
            } else {
                cachedSecretKey?.let { return@synchronized it }
            }
            loadOrCreateSecretKey().also { cachedSecretKey = it }
        }
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
