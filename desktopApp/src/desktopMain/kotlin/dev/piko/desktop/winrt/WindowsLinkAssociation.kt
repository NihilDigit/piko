package dev.piko.desktop.winrt

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
import java.lang.foreign.ValueLayout.JAVA_BYTE
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
 * 那会每次启动都抢走别的下载工具的协议。安装版与便携版都能登记，登记的是点下去的那一份的路径，
 * 后点的盖掉先点的：只登记安装版的话，只用便携版的人无从接管。便携版挪了位置，登记指向的路径不再是
 * 眼下这一份，state 随之报 NotDefault，再点一次即可。gradle run 没有 jpackage 启动器（进程是 java.exe），不给登记。
 */
internal object WindowsLinkAssociation : LinkAssociation {
    private const val TAG = "LinkAssociation"
    private const val MAGNET_PROG_ID = "Piko.Magnet"
    private const val TORRENT_PROG_ID = "Piko.Torrent"
    private const val REGISTERED_NAME = "Piko"
    private const val CAPABILITIES = "Software\\Piko\\Capabilities"
    private const val CLASSES = "Software\\Classes"

    override val needsSystemConfirmation: Boolean = true
    override val canUnregister: Boolean = true

    /** jpackage 的启动器，安装版与便携版都有；jpackage 会另起一个 JVM 子进程，进程命令行未必是 Piko.exe。 */
    private fun launcher(): File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    override suspend fun state(): LinkAssociationState = withContext(Dispatchers.IO) {
        val exe = launcher() ?: return@withContext LinkAssociationState.Unavailable
        // 问系统实际会用哪条命令打开，而不是自己读 UserChoice：AssocQueryString 与资源管理器同一套判定，
        // 较新的 Windows 还多了一个 UserChoiceLatest，自己读容易漏
        val opensWithPiko = listOf(
            runCatching { openCommandOf("magnet", isProtocol = true) }.getOrNull(),
            runCatching { openCommandOf(".torrent") }.getOrNull(),
        ).all { it?.contains(exe.absolutePath, ignoreCase = true) == true }
        if (opensWithPiko) LinkAssociationState.Default else LinkAssociationState.NotDefault
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        if (!writeAndNotify()) return@withContext false
        // Windows 11 打开 Piko 自己的默认应用页；不认这个参数的系统落在默认应用首页
        runCatching { ProcessBuilder("cmd", "/c", "start", "", "ms-settings:defaultapps?registeredAppUser=$REGISTERED_NAME").start() }
            .onFailure { PikoLog.w(TAG, "打开默认应用设置失败", it) }
            .isSuccess
    }

    /**
     * 只写登记、不打开系统设置。自检（见 SelfTest）用：CI 上没人去点，而全新的 runner 没有 UserChoice，
     * Classes 下的登记当场生效，正好验证系统真会按它打开。
     */
    fun writeAndNotify(): Boolean {
        val exe = launcher() ?: return false
        val registered = runCatching { writeRegistration(exe) }
            .onFailure { PikoLog.w(TAG, "登记打开方式失败", it) }
            .getOrDefault(false)
        if (registered) notifyAssociationsChanged()
        return registered
    }

    /**
     * 删掉 Piko 写的登记。用户在默认应用里选过 Piko 的，UserChoice 指向的 ProgID 随之没了，系统下次打开时
     * 让用户另选；magnet 与 .torrent 本身的键只在仍指向 Piko 时才删，别的应用后来写的不动。
     */
    override suspend fun unregister(): Boolean = withContext(Dispatchers.IO) {
        val removed = runCatching {
            val ours = launcher()?.absolutePath
            val magnetCommand = Registry.getString("$CLASSES\\magnet\\shell\\open\\command", null)
            val magnetIsOurs = ours != null && magnetCommand?.contains(ours, ignoreCase = true) == true
            listOf(
                Registry.deleteTree("$CLASSES\\$MAGNET_PROG_ID"),
                Registry.deleteTree("$CLASSES\\$TORRENT_PROG_ID"),
                if (magnetIsOurs) Registry.deleteTree("$CLASSES\\magnet") else true,
                if (Registry.getString("$CLASSES\\.torrent", null) == TORRENT_PROG_ID) Registry.deleteValue("$CLASSES\\.torrent", null) else true,
                Registry.deleteValue("$CLASSES\\.torrent\\OpenWithProgids", TORRENT_PROG_ID),
                Registry.deleteTree("Software\\Piko\\Capabilities"),
                Registry.deleteValue("Software\\RegisteredApplications", REGISTERED_NAME),
            ).all { it }
        }.onFailure { PikoLog.w(TAG, "取消关联失败", it) }.getOrDefault(false)
        notifyAssociationsChanged()
        removed
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

        private const val ERROR_FILE_NOT_FOUND = 2
        private const val RRF_RT_REG_SZ = 0x00000002

        private val advapi32 by lazy { SymbolLookup.libraryLookup("advapi32", Arena.global()) }

        // LSTATUS RegGetValueW(HKEY, LPCWSTR lpSubKey, LPCWSTR lpValue, DWORD dwFlags, LPDWORD pdwType, PVOID pvData, LPDWORD pcbData)
        private val getValue by lazy {
            Linker.nativeLinker().downcallHandle(
                advapi32.find("RegGetValueW").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )
        }

        // LSTATUS RegDeleteTreeW(HKEY, LPCWSTR lpSubKey)：连同子键一起删
        private val deleteTreeHandle by lazy {
            Linker.nativeLinker().downcallHandle(advapi32.find("RegDeleteTreeW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        }

        // LSTATUS RegDeleteKeyValueW(HKEY, LPCWSTR lpSubKey, LPCWSTR lpValueName)
        private val deleteValueHandle by lazy {
            Linker.nativeLinker().downcallHandle(
                advapi32.find("RegDeleteKeyValueW").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )
        }

        /** 读一个字符串值，不存在时为 null。[name] 为 null 读键的默认值。 */
        fun getString(subKey: String, name: String?): String? = Arena.ofConfined().use { arena ->
            val key = arena.allocateFrom(subKey, UTF_16LE)
            val valueName = name?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL
            val size = arena.allocate(JAVA_INT)
            size.set(JAVA_INT, 0, MAX_VALUE_BYTES)
            val buffer = arena.allocate(MAX_VALUE_BYTES.toLong())
            val status = getValue.invokeWithArguments(HKEY_CURRENT_USER, key, valueName, RRF_RT_REG_SZ, MemorySegment.NULL, buffer, size) as Int
            if (status != ERROR_SUCCESS) return@use null
            // 字节数含结尾的 \0
            val bytes = buffer.asSlice(0, size.get(JAVA_INT, 0).toLong()).toArray(JAVA_BYTE)
            String(bytes, UTF_16LE).trimEnd('\u0000')
        }

        /** 本来就没有算删掉了。 */
        fun deleteTree(subKey: String): Boolean = Arena.ofConfined().use { arena ->
            val status = deleteTreeHandle.invokeWithArguments(HKEY_CURRENT_USER, arena.allocateFrom(subKey, UTF_16LE)) as Int
            reportDelete(subKey, status)
        }

        fun deleteValue(subKey: String, name: String?): Boolean = Arena.ofConfined().use { arena ->
            val valueName = name?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL
            val status = deleteValueHandle.invokeWithArguments(HKEY_CURRENT_USER, arena.allocateFrom(subKey, UTF_16LE), valueName) as Int
            reportDelete(subKey, status)
        }

        private fun reportDelete(subKey: String, status: Int): Boolean {
            val ok = status == ERROR_SUCCESS || status == ERROR_FILE_NOT_FOUND
            if (!ok) PikoLog.w(TAG, "删除注册表项失败：$subKey，错误码 $status")
            return ok
        }

        // 命令行与 ProgID 远短于此
        private const val MAX_VALUE_BYTES = 4096
    }
}
