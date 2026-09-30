package dev.piko.shared.auth

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue

/**
 * 真实的钥匙串，只在 macOS 上跑（CI 的 macos runner）。本机没有 Mac，MacKeychainVault 注释里按源码推出的行为全靠这里核实。
 *
 * 测试期间新建一个临时钥匙串，设成用户域的默认与唯一搜索项，结束时恢复原值并删掉它，不碰 runner 的登录钥匙串。
 */
class MacKeychainVaultTest {
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private lateinit var directory: Path
    private lateinit var keychain: String
    private val keychainPassword = UUID.randomUUID().toString()
    private var originalDefault: String? = null
    private var originalSearchList: List<String>? = null

    private val vault = MacKeychainVault(service = "dev.piko.test", timeoutSeconds = 30)
    private val key = "test-${System.nanoTime()}"
    private val secret = """{"password":"密码 hunter2"}""".toByteArray()

    private class Output(val exitCode: Int, val text: String)

    private fun security(vararg arguments: String): Output {
        val process = ProcessBuilder(listOf("/usr/bin/security") + arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "security ${arguments.first()} 未在 30 秒内返回" }
        return Output(process.exitValue(), process.inputStream.use { String(it.readAllBytes()) })
    }

    private fun Output.orFail(what: String): Output = also { check(exitCode == 0) { "$what 失败（$exitCode）：$text" } }

    private fun keychainPaths(text: String) = text.lines().map { it.trim().trim('"') }.filter(String::isNotEmpty)

    @BeforeTest
    fun setUp() {
        assumeTrue(isMac)
        directory = createTempDirectory("piko-keychain")
        keychain = directory.resolve("piko-test.keychain-db").toString()
        originalDefault = keychainPaths(security("default-keychain", "-d", "user").orFail("default-keychain").text).firstOrNull()
        originalSearchList = keychainPaths(security("list-keychains", "-d", "user").orFail("list-keychains").text)
        security("create-keychain", "-p", keychainPassword, keychain).orFail("create-keychain")
        // 不带 -t：不按闲置时间自动上锁，测试里只有显式的 lock-keychain 会锁它
        security("set-keychain-settings", keychain).orFail("set-keychain-settings")
        security("list-keychains", "-d", "user", "-s", keychain).orFail("list-keychains -s")
        security("default-keychain", "-d", "user", "-s", keychain).orFail("default-keychain -s")
    }

    @AfterTest
    fun tearDown() {
        if (!::keychain.isInitialized) return
        try {
            originalSearchList?.let { security("list-keychains", "-d", "user", "-s", *it.toTypedArray()) }
            originalDefault?.let { security("default-keychain", "-d", "user", "-s", it) }
        } finally {
            security("delete-keychain", keychain)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun roundTripsOverwritesAndDeletes() {
        assertNull(vault.read(key))
        vault.write(key, "old".toByteArray())
        // 第二次写同一项靠 -U 覆盖，否则 add-generic-password 报项已存在
        vault.write(key, secret)
        assertContentEquals(secret, vault.read(key))

        vault.delete(key)
        assertNull(vault.read(key))
        vault.delete(key)
    }

    // security -i 一行超过 4096 字节时，多出的部分会被当成下一条命令执行；必须在写之前拒绝，旧值原样留着
    @Test
    fun oversizedSecretIsRejectedWithoutTouchingStoredItem() {
        vault.write(key, secret)
        assertFailsWith<VaultUnavailableException> { vault.write(key, ByteArray(3100) { 'a'.code.toByte() }) }
        assertContentEquals(secret, vault.read(key))
        vault.delete(key)
    }

    @Test
    fun lockedKeychainIsUnavailableNotAbsent() {
        vault.write(key, secret)
        security("lock-keychain", keychain).orFail("lock-keychain")
        // 锁定后可能等待系统解锁交互；超时与错误退出均应报暂时不可用。
        val lockedVault = MacKeychainVault(service = "dev.piko.test", timeoutSeconds = 2)
        try {
            val error = assertFailsWith<VaultUnavailableException>("钥匙串锁着时读取必须报暂时不可用，不能当作没有") { lockedVault.read(key) }
            println("锁着时读取：${error.message}")

            val writeOutcome = runCatching { lockedVault.write(key, "new".toByteArray()) }
            println("锁着时写入：${writeOutcome.exceptionOrNull()?.message ?: "成功"}")
            val deleteOutcome = runCatching { lockedVault.delete(key) }
            println("锁着时删除：${deleteOutcome.exceptionOrNull()?.message ?: "成功"}")
        } finally {
            security("unlock-keychain", "-p", keychainPassword, keychain).orFail("unlock-keychain")
            runCatching { vault.delete(key) }
        }
        vault.write(key, secret)
        assertContentEquals(secret, vault.read(key))
        vault.delete(key)
    }
}
