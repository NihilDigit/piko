package dev.piko.shared.auth

import io.github.nihildigit.pikpak.Session
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopSessionStoreTest {
    private val root: Path = createTempDirectory("piko-credentials")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** 内存里的系统保管处。[available] 为 false 时每个操作都抛，模拟钥匙串锁着。 */
    private class FakeVault : SecretVault {
        val items = HashMap<String, ByteArray>()
        var available = true
        var dropWrites = false

        override fun read(key: String): ByteArray? {
            if (!available) throw VaultUnavailableException("locked")
            return items[key]
        }

        override fun write(key: String, data: ByteArray) {
            if (!available) throw VaultUnavailableException("locked")
            if (!dropWrites) items[key] = data
        }

        override fun delete(key: String) {
            if (!available) throw VaultUnavailableException("locked")
            items.remove(key)
        }
    }

    private val fallback = PlainFileVault(root.resolve("accounts"))

    @Test
    fun unavailablePrimaryKeepsFallbackCopy() {
        val primary = FakeVault()
        val layered = LayeredVault(primary, fallback)
        fallback.write("k", "secret".toByteArray())
        primary.available = false

        assertContentEquals("secret".toByteArray(), layered.read("k"))
        assertContentEquals("secret".toByteArray(), fallback.read("k"))
    }

    @Test
    fun unavailablePrimaryWithoutFallbackIsNotAbsence() {
        val primary = FakeVault().apply { available = false }
        assertFailsWith<VaultUnavailableException> { LayeredVault(primary, fallback).read("k") }
    }

    @Test
    fun promotionRequiresMatchingReadBack() {
        val primary = FakeVault().apply { dropWrites = true }
        val layered = LayeredVault(primary, fallback)
        fallback.write("k", "secret".toByteArray())

        assertContentEquals("secret".toByteArray(), layered.read("k"))
        assertContentEquals("secret".toByteArray(), fallback.read("k"))

        primary.dropWrites = false
        layered.read("k")
        assertContentEquals("secret".toByteArray(), primary.items["k"])
        assertNull(fallback.read("k"))
    }

    // 刷新后的令牌因钥匙串锁着落进兜底，钥匙串之后又能用了：主里那份是轮换前的旧令牌，不能读它
    @Test
    fun fallbackWrittenDuringOutageWinsOverStalePrimary() {
        val primary = FakeVault()
        val layered = LayeredVault(primary, fallback)
        layered.write("k", "old".toByteArray())
        primary.available = false
        layered.write("k", "new".toByteArray())
        primary.available = true

        assertContentEquals("new".toByteArray(), layered.read("k"))
        assertContentEquals("new".toByteArray(), primary.items["k"])
        assertNull(fallback.read("k"))
    }

    private val session = Session(accessToken = "access", refreshToken = "refresh", sub = "sub", expiresAt = 1_900_000_000)

    private fun writeLegacyFiles(account: String = "user@example.com") {
        root.resolve("pikpak-account.txt").writeText(account)
        root.resolve("pikpak-session.json").writeText(
            """{"access_token":"access","refresh_token":"refresh","sub":"sub","expires_at":1900000000}""",
        )
        root.resolve("pikpak-password.txt").writeText("hunter2")
    }

    private val legacyFileNames = listOf("pikpak-account.txt", "pikpak-session.json", "pikpak-password.txt")

    @Test
    fun migratesLegacyFilesIntoVault() = runBlocking {
        writeLegacyFiles()
        val primary = FakeVault()
        val store = DesktopSessionStore(root) { primary }

        val accounts = store.loadAccounts()
        assertEquals("user@example.com", accounts.current)
        assertEquals(listOf("user@example.com"), accounts.accounts.map { it.account })
        assertEquals(session, store.load("user@example.com"))
        assertEquals("hunter2", store.loadCredentials("user@example.com")?.password)
        legacyFileNames.forEach { assertFalse(root.resolve(it).exists(), it) }
        assertEquals(1, primary.items.size)

        // 新进程从新格式读到同样的内容
        val reopened = DesktopSessionStore(root) { primary }
        assertEquals("user@example.com", reopened.loadAccounts().current)
        assertEquals(session, reopened.load("user@example.com"))
        // 账号名不进文件名
        val names = root.listDirectoryEntries().map { it.fileName.toString() } +
            (if (Files.isDirectory(root.resolve("accounts"))) root.resolve("accounts").listDirectoryEntries().map { it.fileName.toString() } else emptyList())
        assertTrue(names.none { "example" in it }, names.toString())
    }

    @Test
    fun failedMigrationKeepsLegacyFilesAndStillLogsIn() = runBlocking {
        writeLegacyFiles()
        // 写入不报错却没存下：读回核对不一致，迁移作废
        val primary = FakeVault().apply { dropWrites = true }
        val store = DesktopSessionStore(root) { primary }

        assertEquals("user@example.com", store.loadAccounts().current)
        assertEquals(session, store.load("user@example.com"))
        assertEquals("hunter2", store.loadCredentials("user@example.com")?.password)
        legacyFileNames.forEach { assertTrue(root.resolve(it).exists(), it) }
        assertFalse(root.resolve("accounts.json").exists())

        // 下次启动主保管处正常了，迁移完成
        primary.dropWrites = false
        val retried = DesktopSessionStore(root) { primary }
        assertEquals(session, retried.load("user@example.com"))
        assertEquals("user@example.com", retried.loadAccounts().current)
        legacyFileNames.forEach { assertFalse(root.resolve(it).exists(), it) }
    }

    // 钥匙串锁着时判断不了新存储里是否已有更新的令牌，迁移推迟；这期间刷新的令牌落进兜底，下次迁移以它为准
    @Test
    fun lockedPrimaryDefersMigrationAndKeepsRefreshedSession() = runBlocking {
        writeLegacyFiles()
        val primary = FakeVault().apply { available = false }
        val store = DesktopSessionStore(root) { primary }

        assertEquals(session, store.load("user@example.com"))
        legacyFileNames.forEach { assertTrue(root.resolve(it).exists(), it) }
        val refreshed = session.copy(accessToken = "access2", refreshToken = "refresh2")
        store.save("user@example.com", refreshed)

        primary.available = true
        val reopened = DesktopSessionStore(root) { primary }
        assertEquals(refreshed, reopened.load("user@example.com"))
        assertEquals("hunter2", reopened.loadCredentials("user@example.com")?.password)
        assertEquals("user@example.com", reopened.loadAccounts().current)
        legacyFileNames.forEach { assertFalse(root.resolve(it).exists(), it) }
        assertEquals(1, primary.items.size)
        assertTrue(root.resolve("accounts").listDirectoryEntries().isEmpty())
    }

    /** 本目录里的加密文件，加密一律失败：模拟 Windows 上 DPAPI 出错。 */
    private class FailingFileVault(directory: Path) : FileSecretVault(directory, "bin") {
        override fun seal(data: ByteArray): ByteArray = throw VaultUnavailableException("CryptProtectData 失败")
        override fun unseal(data: ByteArray): ByteArray = data
    }

    private fun plainFiles(): List<String> =
        Files.walk(root).use { paths -> paths.map { it.fileName.toString() }.filter { it.endsWith(".plain") }.toList() }

    @Test
    fun fileBackedPrimaryFailureIsReportedNotStoredInPlain() = runBlocking {
        val store = DesktopSessionStore(root) { FailingFileVault(it) }

        assertFailsWith<VaultUnavailableException> { store.saveCredentials("a", "pw") }
        assertEquals(emptyList(), plainFiles())
        assertTrue(store.encryptedAtRest("a"))
    }

    // 旧明文原样留着、本次照样能登录，但不能把它们挪成另一份明文后删掉
    @Test
    fun fileBackedPrimaryFailureKeepsLegacyFilesWithoutPlainCopy() = runBlocking {
        writeLegacyFiles()
        val store = DesktopSessionStore(root) { FailingFileVault(it) }

        assertEquals("user@example.com", store.loadAccounts().current)
        assertEquals(session, store.load("user@example.com"))
        assertEquals("hunter2", store.loadCredentials("user@example.com")?.password)
        legacyFileNames.forEach { assertTrue(root.resolve(it).exists(), it) }
        assertEquals(emptyList(), plainFiles())
    }

    @Test
    fun clearingSessionKeepsPasswordAndClearingBothRemovesEntry() = runBlocking {
        val primary = FakeVault()
        val store = DesktopSessionStore(root) { primary }
        store.saveCredentials("a", "pw")
        store.save("a", session)
        store.clear("a")

        val reopened = DesktopSessionStore(root) { primary }
        assertNull(reopened.load("a"))
        assertEquals("pw", reopened.loadCredentials("a")?.password)

        reopened.clearCredentials("a")
        assertTrue(primary.items.isEmpty())
    }

    // 读不出时返回空串的话，记下一个新密码就会把锁着的那份整个盖掉
    @Test
    fun archivePasswordsOutliveLogoutAndALockedVaultThrows() = runBlocking {
        val primary = FakeVault()
        val store = DesktopSessionStore(root) { primary }
        store.save("a", session)
        store.saveCredentials("a", "pw")
        store.saveArchivePasswords("a", """{"zip-pw":{"at":1}}""")
        store.clear("a")
        store.clearCredentials("a")

        primary.available = false
        assertFailsWith<VaultUnavailableException> { DesktopSessionStore(root) { primary }.loadArchivePasswords("a") }
        primary.available = true
        assertEquals("""{"zip-pw":{"at":1}}""", DesktopSessionStore(root) { primary }.loadArchivePasswords("a"))
        assertEquals("", DesktopSessionStore(root) { primary }.loadArchivePasswords("b"))
    }
}
