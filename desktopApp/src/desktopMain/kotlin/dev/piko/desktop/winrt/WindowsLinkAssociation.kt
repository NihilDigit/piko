package dev.piko.desktop.winrt

import dev.piko.desktop.update.WindowsInstaller
import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 Piko 登记为 magnet: 链接与 .torrent 文件的打开方式，全部写在 HKCU，无需管理员权限。
 *
 * 用户在「默认应用」里为某个协议或扩展名选过应用后，系统按 UserChoice 打开，那一项带着系统算的哈希，
 * 应用自己写进去会被判为篡改而作废，所以默认只能由用户选。这里做两件事：按 RegisteredApplications
 * 的约定登记 Capabilities，Piko 才会出现在默认应用的候选里，并有自己的一页；同时写 Classes 下的
 * magnet 与 .torrent，没人选过时直接生效。写完打开 Piko 那一页，由用户逐项选定。
 *
 * 只由用户明确同意时写入（设置页或首次启动的询问，见 LinkAssociationPrompt），不在启动时静默写：
 * 那会每次启动都抢走别的下载工具的协议。也只登记 MSI 装的那份：gradle run 的启动器是 java.exe，
 * 便携版的路径会与安装版互相覆盖。
 */
internal object WindowsLinkAssociation : LinkAssociation {
    private const val TAG = "LinkAssociation"
    private const val MAGNET_PROG_ID = "Piko.Magnet"
    private const val TORRENT_PROG_ID = "Piko.Torrent"
    private const val REGISTERED_NAME = "Piko"
    private const val CAPABILITIES = "Software\\Piko\\Capabilities"
    private const val CLASSES = "Software\\Classes"

    override suspend fun state(): LinkAssociationState = withContext(Dispatchers.IO) {
        val exe = WindowsInstaller.installedExecutable() ?: return@withContext LinkAssociationState.Unavailable
        // 问系统实际会用哪条命令打开，而不是自己读 UserChoice：AssocQueryString 与资源管理器同一套判定，
        // 较新的 Windows 还多了一个 UserChoiceLatest，自己读容易漏
        val opensWithPiko = listOf(
            runCatching { openCommandOf("magnet", isProtocol = true) }.getOrNull(),
            runCatching { openCommandOf(".torrent") }.getOrNull(),
        ).all { it?.contains(exe.absolutePath, ignoreCase = true) == true }
        if (opensWithPiko) LinkAssociationState.Default else LinkAssociationState.NotDefault
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        val exe = WindowsInstaller.installedExecutable() ?: return@withContext false
        val registered = runCatching { writeRegistration(exe) }
            .onFailure { PikoLog.w(TAG, "登记打开方式失败", it) }
            .getOrDefault(false)
        if (!registered) return@withContext false
        notifyAssociationsChanged()
        // Windows 11 打开 Piko 自己的默认应用页；不认这个参数的系统落在默认应用首页
        runCatching { ProcessBuilder("cmd", "/c", "start", "", "ms-settings:defaultapps?registeredAppUser=$REGISTERED_NAME").start() }
            .onFailure { PikoLog.w(TAG, "打开默认应用设置失败", it) }
            .isSuccess
    }

    private fun writeRegistration(exe: File): Boolean {
        val command = "\"${exe.absolutePath}\" \"%1\""
        val icon = "\"${exe.absolutePath}\",0"
        val writes = listOf(
            // 默认应用页里的两个候选
            Registry.setString("$CLASSES\\$MAGNET_PROG_ID", null, "磁力链接"),
            Registry.setString("$CLASSES\\$MAGNET_PROG_ID\\DefaultIcon", null, icon),
            Registry.setString("$CLASSES\\$MAGNET_PROG_ID\\shell\\open\\command", null, command),
            Registry.setString("$CLASSES\\$TORRENT_PROG_ID", null, "BitTorrent 种子文件"),
            Registry.setString("$CLASSES\\$TORRENT_PROG_ID\\DefaultIcon", null, icon),
            Registry.setString("$CLASSES\\$TORRENT_PROG_ID\\shell\\open\\command", null, command),
            // 没有 UserChoice 时起作用的登记
            Registry.setString("$CLASSES\\magnet", null, "URL:Magnet Protocol"),
            Registry.setString("$CLASSES\\magnet", "URL Protocol", ""),
            Registry.setString("$CLASSES\\magnet\\shell\\open\\command", null, command),
            Registry.setString("$CLASSES\\.torrent", null, TORRENT_PROG_ID),
            // 「打开方式」菜单里列出 Piko，即便 .torrent 另有默认
            Registry.setEmpty("$CLASSES\\.torrent\\OpenWithProgids", TORRENT_PROG_ID),
            Registry.setString(CAPABILITIES, "ApplicationName", "Piko"),
            Registry.setString(CAPABILITIES, "ApplicationDescription", "PikPak 客户端"),
            Registry.setString("$CAPABILITIES\\URLAssociations", "magnet", MAGNET_PROG_ID),
            Registry.setString("$CAPABILITIES\\FileAssociations", ".torrent", TORRENT_PROG_ID),
            Registry.setString("Software\\RegisteredApplications", REGISTERED_NAME, CAPABILITIES),
        )
        return writes.all { it }
    }

    // 资源管理器缓存了关联与图标，不通知的话 .torrent 文件要到下次登录才换成 Piko 的图标
    private const val SHCNE_ASSOCCHANGED = 0x08000000
    private const val SHCNF_IDLIST = 0

    private fun notifyAssociationsChanged() {
        runCatching {
            val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
            // void SHChangeNotify(LONG wEventId, UINT uFlags, LPCVOID dwItem1, LPCVOID dwItem2)
            Linker.nativeLinker().downcallHandle(
                shell32.find("SHChangeNotify").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
            ).invokeWithArguments(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, MemorySegment.NULL, MemorySegment.NULL)
        }
    }

    /** HKCU 的最小写入。不像 WinRTSupport 那样调 reg.exe：这里有十几条，每条都要起一个进程。 */
    private object Registry {
        // HKEY_CURRENT_USER 定义为 (HKEY)(ULONG_PTR)(LONG)0x80000001，按符号扩展到指针宽度
        private val HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L.toInt().toLong())
        private const val REG_NONE = 0
        private const val REG_SZ = 1
        private const val ERROR_SUCCESS = 0

        // LSTATUS RegSetKeyValueW(HKEY hKey, LPCWSTR lpSubKey, LPCWSTR lpValueName, DWORD dwType, LPCVOID lpData, DWORD cbData)
        // 子键不存在时一并建出来
        private val setKeyValue by lazy {
            val advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global())
            Linker.nativeLinker().downcallHandle(
                advapi32.find("RegSetKeyValueW").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
            )
        }

        /** [name] 为 null 时写键的默认值。 */
        fun setString(subKey: String, name: String?, data: String): Boolean = Arena.ofConfined().use { arena ->
            val bytes = (data + "\u0000").toByteArray(UTF_16LE)
            val value = arena.allocate(bytes.size.toLong())
            value.copyFrom(MemorySegment.ofArray(bytes))
            set(arena, subKey, name, REG_SZ, value, bytes.size)
        }

        /** OpenWithProgids 一类只看值名的登记。 */
        fun setEmpty(subKey: String, name: String): Boolean = Arena.ofConfined().use { arena ->
            set(arena, subKey, name, REG_NONE, MemorySegment.NULL, 0)
        }

        private fun set(arena: Arena, subKey: String, name: String?, type: Int, data: MemorySegment, size: Int): Boolean {
            val key = arena.allocateFrom(subKey, UTF_16LE)
            val valueName = name?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL
            val status = setKeyValue.invokeWithArguments(HKEY_CURRENT_USER, key, valueName, type, data, size) as Int
            if (status != ERROR_SUCCESS) PikoLog.w(TAG, "写入注册表失败：$subKey，错误码 $status")
            return status == ERROR_SUCCESS
        }
    }
}
