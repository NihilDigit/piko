package dev.piko.desktop.winrt

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsLaunchAtStartupTest {
    @Test fun isolatedRegistryValueCanBeEnabledAndRemoved() = runBlocking {
        if (!WinRTSupport.isWindows) return@runBlocking
        val name = "Piko.StartupTest.${UUID.randomUUID()}"
        val executable = "C:\\Program Files\\Piko Test\\Piko.exe"
        val startup = WindowsLaunchAtStartup(executable, name)
        try {
            assertFalse(startup.isEnabled())
            assertTrue(startup.setEnabled(true))
            assertTrue(startup.isEnabled())
            assertEquals(WindowsLaunchAtStartup.command(executable), HkcuRegistry.getString(WindowsLaunchAtStartup.RUN_KEY, name))
            assertTrue(startup.setEnabled(false))
            assertFalse(startup.isEnabled())
        } finally {
            HkcuRegistry.deleteValue(WindowsLaunchAtStartup.RUN_KEY, name)
        }
    }

    @Test fun executablePathIsQuotedWithoutProtocolArguments() {
        assertEquals("\"C:\\Program Files\\Piko\\Piko.exe\"",
            WindowsLaunchAtStartup.command("C:\\Program Files\\Piko\\Piko.exe"))
    }

    @Test fun invalidCommandIsRejected() {
        assertFailsWith<IllegalArgumentException> { WindowsLaunchAtStartup.command("") }
        assertFailsWith<IllegalArgumentException> { WindowsLaunchAtStartup.command("Piko.exe\" --arg") }
    }
}
