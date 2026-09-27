package dev.piko.shared.smoke

import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SyncedSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 两台设备经同一份远端同步设置：按项合并，谁后改用谁的；新装的设备带着默认值登录，不会盖掉另一台调好的设置。
 * 网盘里的读写（DriveSettingsStore）在这里换成内存，测的是合并本身。
 */
class SettingsSyncSmokeTest {
    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }

    private class MemoryCacheStore : PikoCacheStore {
        private val map = HashMap<String, String>()
        override suspend fun read(key: String) = map[key]
        override suspend fun write(key: String, value: String) {
            map[key] = value
        }
    }

    /** 一台设备：两项设置，各自一份本机缓存。 */
    private class Device(remote: RemoteSettingsStore, server: FakePikPakServer, scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long) {
        val theme = MutableStateFlow("LIGHT")
        val blur = MutableStateFlow("true")
        val sync = PikoSettingsSync(
            clients = server.provider(),
            remote = remote,
            preferences = MemoryPreferences(),
            cacheStore = MemoryCacheStore(),
            scope = scope,
            enabled = MutableStateFlow(true),
            settings = listOf(
                SyncedSetting("theme", { theme }, { _, value -> theme.value = value }),
                SyncedSetting("blur", { blur }, { _, value -> blur.value = value }),
            ),
            now = clock,
        )
    }

    @Test
    fun `settings merge per key and a fresh device takes the synced values`() = smoke { scope ->
        val server = FakePikPakServer()
        val remote = MemoryRemote()
        var time = 1_000L
        val clock = { time++ }
        val a = Device(remote, server, scope, clock)

        a.theme.value = "DARK"
        assertTrue(a.sync.syncNow())

        // 新设备带着默认值登录：拿到的是 A 调好的深色，而不是用自己的浅色盖掉它
        val b = Device(remote, server, scope, clock)
        assertTrue(b.sync.syncNow())
        assertEquals("DARK", b.theme.value)

        // 两台各改一项，两项都留下
        a.blur.value = "false"
        b.theme.value = "LIGHT"
        assertTrue(a.sync.syncNow())
        assertTrue(b.sync.syncNow())
        assertTrue(a.sync.syncNow())
        assertEquals("LIGHT" to "false", a.theme.value to a.blur.value)
        assertEquals("LIGHT" to "false", b.theme.value to b.blur.value)
    }
}
