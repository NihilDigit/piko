package dev.piko.desktop.secrets

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue

/** 真实的 Secret Service，只在 Linux 且会话里有这项服务时跑；CI 的 runner 上没有钥匙串服务，跳过。 */
class SecretServiceVaultTest {
    private lateinit var vault: SecretServiceVault
    private val key = "test-${System.nanoTime()}"
    private val secret = """{"password":"密码 hunter2"}""".toByteArray()

    @BeforeTest
    fun setUp() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        vault = SecretServiceVault.open().also { assumeNotNull(it) }!!
    }

    @AfterTest
    fun cleanUp() {
        if (::vault.isInitialized) vault.delete(key)
    }

    @Test
    fun roundTripsOverwritesAndDeletes() {
        assertNull(vault.read(key))
        vault.write(key, "old".toByteArray())
        vault.write(key, secret)
        assertContentEquals(secret, vault.read(key))

        vault.delete(key)
        assertNull(vault.read(key))
        // 删一个不存在的项不算失败
        vault.delete(key)
    }
}
