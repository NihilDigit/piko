package dev.piko.desktop.winrt

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE

// 不把无人认领时的「未知文件」处理程序（打开方式对话框）当成结果
private const val ASSOCF_INIT_IGNOREUNKNOWN = 0x400
// pszAssoc 是协议名而非扩展名，这样才按 UrlAssociations 下的 UserChoice 判定
private const val ASSOCF_IS_PROTOCOL = 0x1000
private const val ASSOCSTR_COMMAND = 1
private const val S_OK = 0
private const val S_FALSE = 1

/**
 * 系统眼下打开 [assoc]（扩展名如 ".mp4"，或 [isProtocol] 时的协议名如 "magnet"）用的命令行模板，
 * 环境变量已由系统展开。AssocQueryString 遵从用户在默认应用里的选择（UserChoice），与资源管理器双击的结果一致。
 * 没有可用命令时为 null，例如无人认领，或默认程序是只有 DelegateExecute 的商店应用。原生调用失败时抛出。
 */
internal fun openCommandOf(assoc: String, isProtocol: Boolean = false): String? = Arena.ofConfined().use { arena ->
    val shlwapi = SymbolLookup.libraryLookup("shlwapi", arena)
    // HRESULT AssocQueryStringW(ASSOCF flags, ASSOCSTR str, LPCWSTR pszAssoc, LPCWSTR pszExtra, LPWSTR pszOut, DWORD *pcchOut)
    val query = Linker.nativeLinker().downcallHandle(
        shlwapi.find("AssocQueryStringW").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    val flags = ASSOCF_INIT_IGNOREUNKNOWN or (if (isProtocol) ASSOCF_IS_PROTOCOL else 0)
    val name = arena.allocateFrom(assoc, UTF_16LE)
    val verb = arena.allocateFrom("open", UTF_16LE)
    val length = arena.allocateFrom(JAVA_INT, 0)
    // 输出缓冲传空指针时回 S_FALSE，长度（含结尾的 0）写进 length
    val sized = query.invokeWithArguments(flags, ASSOCSTR_COMMAND, name, verb, MemorySegment.NULL, length) as Int
    if (sized != S_FALSE && sized != S_OK) return@use null
    val capacity = length.get(JAVA_INT, 0)
    if (capacity <= 1) return@use null
    val buffer = arena.allocate(JAVA_CHAR, capacity.toLong())
    val result = query.invokeWithArguments(flags, ASSOCSTR_COMMAND, name, verb, buffer, length) as Int
    if (result != S_OK) return@use null
    buffer.getString(0, UTF_16LE).takeIf { it.isNotBlank() }
}
