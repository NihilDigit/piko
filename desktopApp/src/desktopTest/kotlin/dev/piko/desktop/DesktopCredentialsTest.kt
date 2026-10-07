package dev.piko.desktop

import dev.piko.desktop.secrets.DpapiSecretVault
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.auth.DesktopSessionStore
import dev.piko.shared.auth.windowsUserName
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/** 1.1.0 及更早的明文登录信息经真实的 DPAPI 迁移，只在 Windows 上跑。 */
class DesktopCredentialsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        assumeTrue(WinRTSupport.isWindows)
        root = createTempDirectory("piko-credentials")
    }

    @AfterTest
    fun cleanUp() {
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    @Test
    fun legacyPlaintextMovesIntoOwnerOnlyDpapiFiles() = runBlocking {
        root.resolve("pikpak-account.txt").writeText("user@example.com")
        root.resolve("pikpak-session.json").writeText(
            """{"access_token":"access-token-1","refresh_token":"refresh-token-1","sub":"sub","expires_at":1900000000}""",
        )
        root.resolve("pikpak-password.txt").writeText("hunter2")

        val store = desktopSessionStore(root)
        assertEquals("user@example.com", store.loadAccounts().current)
        assertEquals("refresh-token-1", store.load("user@example.com")?.refreshToken)
        assertEquals("hunter2", store.loadCredentials("user@example.com")?.password)

        listOf("pikpak-account.txt", "pikpak-session.json", "pikpak-password.txt").forEach { assertFalse(root.resolve(it).exists(), it) }
        val accounts = root.resolve("accounts")
        val stored = accounts.listDirectoryEntries()
        assertEquals(listOf("bin"), stored.map { it.fileName.toString().substringAfterLast('.') })
        val sealed = String(Files.readAllBytes(stored.single()), Charsets.ISO_8859_1)
        listOf("hunter2", "refresh-token-1", "example").forEach { assertFalse(it in sealed, it) }

        // 临时目录继承来的权限通常还给 SYSTEM 与 Administrators，只剩一条说明没有并入继承的条目
        val principals = Files.getFileAttributeView(stored.single(), AclFileAttributeView::class.java).acl.map { it.principal() }.distinct()
        assertEquals(1, principals.size, principals.toString())
        assertFalse(principals.single() is GroupPrincipal, principals.toString())

        assertEquals("hunter2", desktopSessionStore(root).loadCredentials("user@example.com")?.password)
    }

    /**
     * 便携目录先后被两个 Windows 账户用。测试进程只有一个账户：以账户名区分两份存储，再给 A 的文件加一条拒绝
     * 本进程读写的 ACL，模拟 B 打不开 A 的文件。
     */
    @Test
    fun anotherWindowsAccountKeepsItsOwnCopy() = runBlocking {
        fun storeOf(user: String) = DesktopSessionStore(root) { DpapiSecretVault(it, user) }
        val account = "user@example.com"
        storeOf("TEST\\alice").saveCredentials(account, "pw-a")
        val aliceFile = root.resolve("accounts").listDirectoryEntries().single()

        val view = Files.getFileAttributeView(aliceFile, AclFileAttributeView::class.java)
        val original = view.acl
        val me = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(windowsUserName())
        val deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me).setPermissions(
            AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
        ).build()
        view.acl = listOf(deny) + original
        try {
            // 模拟是否当真：同一个文件名时，写入撞上拒绝
            assertFailsWith<IOException> { storeOf("TEST\\alice").saveCredentials(account, "pw-x") }

            val bob = storeOf("TEST\\bob")
            assertNull(bob.loadCredentials(account), "读不到别的账户的文件时应当作没有保存")
            bob.saveCredentials(account, "pw-b")
            assertEquals("pw-b", storeOf("TEST\\bob").loadCredentials(account)?.password)
        } finally {
            view.acl = original
        }
        assertEquals("pw-a", storeOf("TEST\\alice").loadCredentials(account)?.password)
    }
}
