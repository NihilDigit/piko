package dev.piko.desktop.winrt

import dev.piko.shared.log.PikoLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE

/**
 * HKCU 的最小读写，经 FFM 直调 advapi32。不调 reg.exe：每条都要起一个进程，关联登记有十几条，
 * 通知登记每次启动都要核对一遍。
 */
internal object HkcuRegistry {
    private const val TAG = "Registry"

    // HKEY_CURRENT_USER 定义为 (HKEY)(ULONG_PTR)(LONG)0x80000001，按符号扩展到指针宽度
    private val HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L.toInt().toLong())
    private const val REG_NONE = 0
    private const val REG_SZ = 1
    private const val ERROR_SUCCESS = 0
    private const val ERROR_FILE_NOT_FOUND = 2
    private const val RRF_RT_REG_SZ = 0x00000002

    // 命令行、路径与 ProgID 远短于此
    private const val MAX_VALUE_BYTES = 4096

    private val advapi32 by lazy { SymbolLookup.libraryLookup("advapi32", Arena.global()) }

    // LSTATUS RegSetKeyValueW(HKEY hKey, LPCWSTR lpSubKey, LPCWSTR lpValueName, DWORD dwType, LPCVOID lpData, DWORD cbData)
    // 子键不存在时一并建出来
    private val setKeyValue by lazy {
        Linker.nativeLinker().downcallHandle(
            advapi32.find("RegSetKeyValueW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
        )
    }

    // LSTATUS RegGetValueW(HKEY, LPCWSTR lpSubKey, LPCWSTR lpValue, DWORD dwFlags, LPDWORD pdwType, PVOID pvData, LPDWORD pcbData)
    private val getValueHandle by lazy {
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

    // LSTATUS RegOpenKeyExW(HKEY, LPCWSTR lpSubKey, DWORD ulOptions, REGSAM samDesired, PHKEY phkResult)
    private val openKeyHandle by lazy {
        Linker.nativeLinker().downcallHandle(
            advapi32.find("RegOpenKeyExW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        )
    }

    // LSTATUS RegQueryInfoKeyW(HKEY, LPWSTR lpClass, LPDWORD lpcchClass, LPDWORD lpReserved, LPDWORD lpcSubKeys,
    //   LPDWORD lpcbMaxSubKeyLen, LPDWORD lpcbMaxClassLen, LPDWORD lpcValues, LPDWORD lpcbMaxValueNameLen,
    //   LPDWORD lpcbMaxValueLen, LPDWORD lpcbSecurityDescriptor, PFILETIME lpftLastWriteTime)
    private val queryInfoKeyHandle by lazy {
        Linker.nativeLinker().downcallHandle(
            advapi32.find("RegQueryInfoKeyW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
    }

    // LSTATUS RegCloseKey(HKEY)
    private val closeKeyHandle by lazy {
        Linker.nativeLinker().downcallHandle(advapi32.find("RegCloseKey").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    private const val KEY_READ = 0x20019
    private const val RRF_RT_ANY = 0x0000ffff

    fun exists(subKey: String): Boolean = counts(subKey) != null

    /** 键不存在，或其下既没有值（含默认值）也没有子键。 */
    fun isEmpty(subKey: String): Boolean = counts(subKey)?.let { (subKeys, values) -> subKeys == 0 && values == 0 } ?: true

    /** 子键数与值数，键不存在时为 null。 */
    private fun counts(subKey: String): Pair<Int, Int>? = Arena.ofConfined().use { arena ->
        val handle = arena.allocate(ADDRESS)
        val opened = openKeyHandle.invokeWithArguments(HKEY_CURRENT_USER, arena.allocateFrom(subKey, UTF_16LE), 0, KEY_READ, handle) as Int
        if (opened != ERROR_SUCCESS) return@use null
        val key = handle.get(ADDRESS, 0)
        try {
            val subKeys = arena.allocate(JAVA_INT)
            val values = arena.allocate(JAVA_INT)
            val nul = MemorySegment.NULL
            val status = queryInfoKeyHandle.invokeWithArguments(key, nul, nul, nul, subKeys, nul, nul, values, nul, nul, nul, nul) as Int
            check(status == ERROR_SUCCESS) { "查询注册表项失败：$subKey，错误码 $status" }
            subKeys.get(JAVA_INT, 0) to values.get(JAVA_INT, 0)
        } finally {
            closeKeyHandle.invokeWithArguments(key)
        }
    }

    /** 不论类型，值在不在。 */
    fun hasValue(subKey: String, name: String): Boolean = Arena.ofConfined().use { arena ->
        val nul = MemorySegment.NULL
        val status = getValueHandle.invokeWithArguments(
            HKEY_CURRENT_USER, arena.allocateFrom(subKey, UTF_16LE), arena.allocateFrom(name, UTF_16LE), RRF_RT_ANY, nul, nul, nul,
        ) as Int
        status == ERROR_SUCCESS
    }

    /** [name] 为 null 时写键的默认值。 */
    fun setString(subKey: String, name: String?, data: String): Boolean = Arena.ofConfined().use { arena ->
        val bytes = (data + "\u0000").toByteArray(UTF_16LE)
        val value = arena.allocate(bytes.size.toLong())
        value.copyFrom(MemorySegment.ofArray(bytes))
        set(arena, subKey, name, REG_SZ, value, bytes.size)
    }

    /** 值已是 [data] 时不写，返回值同 [setString]。 */
    fun ensureString(subKey: String, name: String?, data: String): Boolean =
        getString(subKey, name) == data || setString(subKey, name, data)

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

    /** 读一个字符串值，不存在时为 null。[name] 为 null 读键的默认值。 */
    fun getString(subKey: String, name: String?): String? = Arena.ofConfined().use { arena ->
        val key = arena.allocateFrom(subKey, UTF_16LE)
        val valueName = name?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL
        val size = arena.allocate(JAVA_INT)
        size.set(JAVA_INT, 0, MAX_VALUE_BYTES)
        val buffer = arena.allocate(MAX_VALUE_BYTES.toLong())
        val status = getValueHandle.invokeWithArguments(HKEY_CURRENT_USER, key, valueName, RRF_RT_REG_SZ, MemorySegment.NULL, buffer, size) as Int
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
}
