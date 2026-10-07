package dev.piko.desktop.winrt

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * 真写 HKCU，只在 Windows 上跑。身份、协议名与扩展名都换成测试专用的，不碰本机的 magnet、.torrent 与 Piko 的登记。
 */
class LinkRegistrationTest {
    private val suffix = System.nanoTime().toString(36)
    private val identity = ShellIdentity("PikoLinkTest$suffix")
    private val protocol = "piko-link-test-$suffix"
    private val extension = ".pikolinktest$suffix"
    private val registration = LinkRegistration(identity, protocol, extension)
    private val protocolKey = "Software\\Classes\\$protocol"
    private val extensionKey = "Software\\Classes\\$extension"
    private val root: File = Files.createTempDirectory("piko-link").toFile()
    private val exe = root.resolve("PikoLinkTest.exe").apply { writeText("") }

    // 别的下载工具：路径要真的存在，否则算无主
    private val foreignExe = File(System.getenv("SystemRoot"), "System32\\notepad.exe")

    @BeforeTest
    fun windowsOnly() {
        Assume.assumeTrue("注册表只在 Windows 上有", System.getProperty("os.name").startsWith("Windows"))
    }

    @AfterTest
    fun cleanUp() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        for (key in listOf(protocolKey, extensionKey, "Software\\Classes\\${identity.magnetProgId}", "Software\\Classes\\${identity.torrentProgId}",
            "Software\\Classes\\Applications\\${exe.name}", identity.vendorKey)) {
            HkcuRegistry.deleteTree(key)
        }
        HkcuRegistry.deleteValue("Software\\RegisteredApplications", identity.registeredName)
        root.deleteRecursively()
    }

    /** 键及其下全部内容的文本，比对前后用。 */
    private fun dump(key: String): String {
        val process = ProcessBuilder("reg.exe", "query", "HKCU\\$key", "/s").redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    private fun assertNothingOfPikoLeft() {
        assertFalse(HkcuRegistry.exists("Software\\Classes\\${identity.magnetProgId}"))
        assertFalse(HkcuRegistry.exists("Software\\Classes\\${identity.torrentProgId}"))
        assertFalse(HkcuRegistry.exists("Software\\Classes\\Applications\\${exe.name}"))
        assertFalse(HkcuRegistry.exists(identity.vendorKey), "vendor 键与其下的记号没删")
        assertFalse(HkcuRegistry.hasValue("Software\\RegisteredApplications", identity.registeredName))
    }

    @Test
    fun anotherAppsKeysSurviveRegisterAndUnregister() {
        HkcuRegistry.setString(protocolKey, "URL Protocol", "")
        HkcuRegistry.setString("$protocolKey\\shell\\open\\command", null, "\"${foreignExe.absolutePath}\" \"%1\"")
        HkcuRegistry.setString(extensionKey, null, "Foreign.Torrent")
        HkcuRegistry.setEmpty("$extensionKey\\OpenWithProgids", "Foreign.Torrent")
        val protocolBefore = dump(protocolKey)
        val extensionBefore = dump(extensionKey)

        assertTrue(registration.register(exe))
        assertEquals(protocolBefore, dump(protocolKey), "登记改了别人的协议键")
        assertEquals("Foreign.Torrent", HkcuRegistry.getString(extensionKey, null), "登记改了别人的扩展名默认值")
        assertTrue(HkcuRegistry.hasValue("$extensionKey\\OpenWithProgids", identity.torrentProgId), "OpenWithProgids 里没有并列加上 Piko")

        assertTrue(registration.unregister(exe))
        assertEquals(protocolBefore, dump(protocolKey))
        assertEquals(extensionBefore, dump(extensionKey))
        assertNothingOfPikoLeft()
    }

    @Test
    fun keysPikoCreatedAreRemovedWhole() {
        assertTrue(registration.register(exe))
        assertEquals("\"${exe.absolutePath}\" \"%1\"", HkcuRegistry.getString("$protocolKey\\shell\\open\\command", null))
        assertEquals(identity.torrentProgId, HkcuRegistry.getString(extensionKey, null))

        assertTrue(registration.unregister(exe))
        assertFalse(HkcuRegistry.exists(protocolKey), "协议键没删干净：${dump(protocolKey)}")
        assertFalse(HkcuRegistry.exists(extensionKey), "扩展名键没删干净：${dump(extensionKey)}")
        assertNothingOfPikoLeft()
    }

    // 1.1.0 不看归属，直接写在已有的键上；命令指向 Piko 的照「是 Piko 的」处理，整键删掉
    @Test
    fun protocolKeyLeftBy110IsTakenAsPikos() {
        val stale = root.resolve("old\\${exe.name}")
        HkcuRegistry.setString(protocolKey, "URL Protocol", "")
        HkcuRegistry.setString("$protocolKey\\shell\\open\\command", null, "\"${stale.absolutePath}\" \"%1\"")
        HkcuRegistry.setString(extensionKey, null, identity.torrentProgId)

        assertTrue(registration.register(exe))
        assertEquals("\"${exe.absolutePath}\" \"%1\"", HkcuRegistry.getString("$protocolKey\\shell\\open\\command", null))
        assertTrue(registration.unregister(exe))
        assertFalse(HkcuRegistry.exists(protocolKey))
        assertEquals(null, HkcuRegistry.getString(extensionKey, null))
        assertNothingOfPikoLeft()
    }
}
