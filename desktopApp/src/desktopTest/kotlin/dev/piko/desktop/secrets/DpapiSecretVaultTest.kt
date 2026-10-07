package dev.piko.desktop.secrets

import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.auth.PowerShellDpapiVault
import dev.piko.shared.auth.windowsUserTag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue

/** 真实的 DPAPI，只在 Windows 上跑。 */
class DpapiSecretVaultTest {
    private lateinit var directory: Path
    private val secret = """{"password":"密码 hunter2"}""".toByteArray()

    @BeforeTest
    fun setUp() {
        assumeTrue(WinRTSupport.isWindows)
        directory = createTempDirectory("piko-dpapi")
    }

    @AfterTest
    fun cleanUp() {
        if (::directory.isInitialized) directory.toFile().deleteRecursively()
    }

    @Test
    fun roundTripsAndDoesNotStorePlaintext() {
        val vault = DpapiSecretVault(directory)
        vault.write("k", secret)

        assertContentEquals(secret, vault.read("k"))
        val stored = Files.readAllBytes(directory.resolve("k.${windowsUserTag()}.bin"))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun tamperedCiphertextReadsAsAbsent() {
        val vault = DpapiSecretVault(directory)
        vault.write("k", secret)
        val file = directory.resolve("k.${windowsUserTag()}.bin")
        val stored = Files.readAllBytes(file)
        stored[stored.size - 1] = (stored.last().toInt() xor 0x5A).toByte()
        Files.write(file, stored)

        assertNull(vault.read("k"))
        assertNull(PowerShellDpapiVault(directory).read("k"))
    }

    // CLI 经 PowerShell 读写同一批文件，刷新后写回的令牌桌面端要能读
    @Test
    fun interoperatesWithPowerShellVault() {
        val ffm = DpapiSecretVault(directory)
        val powershell = PowerShellDpapiVault(directory)

        ffm.write("a", secret)
        assertContentEquals(secret, powershell.read("a"))
        powershell.write("b", secret)
        assertContentEquals(secret, ffm.read("b"))
    }
}
