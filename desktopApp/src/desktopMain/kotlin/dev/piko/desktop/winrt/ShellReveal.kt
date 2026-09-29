package dev.piko.desktop.winrt

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE

/**
 * 资源管理器里打开文件所在的文件夹并选中它（SHOpenFolderAndSelectItems）。调用线程要已初始化 COM，
 * 见 WinRTSupport.revealInExplorer。
 */
internal object ShellReveal {
    private val linker = Linker.nativeLinker()
    private val shell32 by lazy { SymbolLookup.libraryLookup("shell32", Arena.global()) }
    private val ole32 by lazy { SymbolLookup.libraryLookup("ole32", Arena.global()) }

    // HRESULT SHParseDisplayName(PCWSTR, IBindCtx*, PIDLIST_ABSOLUTE*, SFGAOF, SFGAOF*)
    private val parseDisplayName by lazy {
        linker.downcallHandle(
            shell32.find("SHParseDisplayName").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
        )
    }

    // HRESULT SHOpenFolderAndSelectItems(PCIDLIST_ABSOLUTE, UINT, PCUITEMID_CHILD_ARRAY, DWORD)
    private val openFolderAndSelect by lazy {
        linker.downcallHandle(
            shell32.find("SHOpenFolderAndSelectItems").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
        )
    }

    private val coTaskMemFree by lazy {
        linker.downcallHandle(ole32.find("CoTaskMemFree").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
    }

    /** 选中成功返回 true。失败（路径解析不了等）由调用方退回只打开文件夹。 */
    fun select(path: String): Boolean = Arena.ofConfined().use { arena ->
        val name = arena.allocateFrom(path, UTF_16LE)
        val pidlOut = arena.allocate(ADDRESS)
        val parsed = parseDisplayName.invokeWithArguments(name, MemorySegment.NULL, pidlOut, 0, MemorySegment.NULL) as Int
        if (parsed != S_OK) return@use false
        val pidl = pidlOut.get(ADDRESS, 0)
        try {
            // cidl 为 0 时 pidl 本身就是要选中的那一项，系统打开它的上级并选中它
            openFolderAndSelect.invokeWithArguments(pidl, 0, MemorySegment.NULL, 0) as Int == S_OK
        } finally {
            coTaskMemFree.invokeWithArguments(pidl)
        }
    }

    private const val S_OK = 0
}
