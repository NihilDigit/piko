package dev.piko.shared.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 可移植加密实现的对照测试：桌面端手里就有 JDK 的 javax.crypto，拿它当标准答案，
 * 逐项比 SHA-256、HMAC、PBKDF2、AES-GCM 的输出字节。只要这些都一致，iOS 侧算出来的同步包
 * 桌面上就一定能解开。
 */
class PortableSyncCipherTest {
    private val random = SecureRandom()

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PortableDigest.sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PortableDigest.sha256("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            PortableDigest.sha256("a".repeat(1_000_000).encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256MatchesJdkOnRandomLengths() {
        for (size in intArrayOf(0, 1, 3, 55, 56, 63, 64, 65, 119, 120, 128, 129, 1000, 4096)) {
            val data = ByteArray(size).also(random::nextBytes)
            assertContentEquals(
                java.security.MessageDigest.getInstance("SHA-256").digest(data),
                PortableDigest.sha256(data),
                "size=$size",
            )
        }
    }

    @Test
    fun hmacMatchesJdkOnRandomKeys() {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        for (keySize in intArrayOf(1, 16, 32, 63, 64, 65, 100)) {
            val key = ByteArray(keySize).also(random::nextBytes)
            val message = ByteArray(37).also(random::nextBytes)
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            assertContentEquals(mac.doFinal(message), PortableDigest.hmacSha256(key, message), "keySize=$keySize")
        }
    }

    @Test
    fun pbkdf2MatchesJdk() {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        for (keyLength in intArrayOf(16, 20, 32, 48, 70)) {
            val secret = "piko-同步-${keyLength}"
            val salt = ByteArray(16).also(random::nextBytes)
            val iterations = 1200
            val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, keyLength * 8)
            val expected = factory.generateSecret(spec).encoded
            assertContentEquals(
                expected,
                PortableDigest.pbkdf2HmacSha256(secret.encodeToByteArray(), salt, iterations, keyLength),
                "keyLength=$keyLength",
            )
        }
    }

    @Test
    fun aes256MatchesNistBlockVector() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plain = hex("00112233445566778899aabbccddeeff")
        val out = ByteArray(16)
        PortableAes(key).encryptBlock(plain, 0, out, 0)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", out.toHex())
    }

    @Test
    fun gcmMatchesJdkOnRandomPlaintexts() {
        for (size in intArrayOf(0, 1, 15, 16, 17, 1000, 65_537)) {
            val key = ByteArray(32).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val plain = ByteArray(size).also(random::nextBytes)
            assertContentEquals(jvmSeal(key, iv, plain), PortableGcm.seal(PortableAes(key), iv, plain), "size=$size")
        }
    }

    @Test
    fun portableCipherInteroperatesWithJvmCipher() {
        val portable = PortableSyncCipher()
        val jvm = JvmSyncCipher()
        val salt = portable.newSalt()
        assertEquals(16, salt.size)
        val key = portable.deriveKey("hunter2", salt, 800)
        assertContentEquals(jvm.deriveKey("hunter2", salt, 800), key)

        val message = "同步载荷：账号 42 的下载任务表".encodeToByteArray()

        // iOS 写、桌面读
        val sealedByPortable = portable.seal(key, message)
        assertContentEquals(message, jvm.open(key, sealedByPortable))
        assertContentEquals(message, portable.open(key, sealedByPortable))

        // 桌面写、iOS 读
        val sealedByJvm = jvm.seal(key, message)
        assertContentEquals(message, portable.open(key, sealedByJvm))
        assertContentEquals(message, jvm.open(key, sealedByJvm))
    }

    @Test
    fun openRejectsTamperedOrWrongKey() {
        val portable = PortableSyncCipher()
        val key = portable.deriveKey("hunter2", portable.newSalt(), 400)
        val sealed = portable.seal(key, "载荷".encodeToByteArray())

        val flipped = sealed.copyOf()
        flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 0x01).toByte()
        assertNull(portable.open(key, flipped))

        val otherKey = portable.deriveKey("hunter3", portable.newSalt(), 400)
        assertNull(portable.open(otherKey, sealed))

        assertNull(portable.open(key, ByteArray(12)))
        assertNull(portable.open(key, ByteArray(0)))
        assertTrue(sealed.size > 12 + 16)
        assertNotNull(portable.open(key, sealed))
    }

    @Test
    fun saltAndIvAreFreshEveryTime() {
        val portable = PortableSyncCipher()
        assertTrue(portable.newSalt().size == 16)
        assertTrue(!portable.newSalt().contentEquals(portable.newSalt()))
        val key = ByteArray(32)
        val first = portable.seal(key, ByteArray(4))
        val second = portable.seal(key, ByteArray(4))
        assertTrue(!first.contentEquals(second), "每次都要换 IV")
    }

    private fun jvmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plain)
    }

    private fun ByteArray.toHex(): String = joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
