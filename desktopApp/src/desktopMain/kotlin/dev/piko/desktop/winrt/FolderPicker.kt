package dev.piko.desktop.winrt

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.Executors

/** [FolderPicker.pickFolder] 的结果。[Unavailable] 表示原生对话框没能弹出，调用方应退回 JFileChooser。 */
sealed interface FolderPickResult {
    data class Picked(val folder: File) : FolderPickResult
    data object Cancelled : FolderPickResult
    data object Unavailable : FolderPickResult
}

/**
 * 系统原生的目录选择框：IFileOpenDialog 加 FOS_PICKFOLDERS。
 *
 * 虚表顺序取自 Windows SDK 10.0.26100 的 shobjidl_core.h。IFileOpenDialog 继承链为
 * IUnknown（0 到 2）、IModalWindow（Show 3）、IFileDialog（SetFileTypes 4 起，
 * SetOptions 9、GetOptions 10、SetFolder 12、SetTitle 17、GetResult 20）。
 * IShellItem 直接继承 IUnknown，GetDisplayName 是 5。
 */
object FolderPicker {
    private val CLSID_FileOpenDialog = ComInterop.guid("dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7")
    private val IID_IFileOpenDialog = ComInterop.guid("d57c7288-d4ad-4768-be02-9d969532d960")
    private val IID_IShellItem = ComInterop.guid("43826d1e-e718-42ee-bc55-a1e261c37bfe")

    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val HRESULT_ERROR_CANCELLED = 0x800704C7.toInt()

    private val intArg = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
    private val intAndPointerArgs = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)

    private val shCreateItemFromParsingName by lazy {
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("shell32", Arena.global()).find("SHCreateItemFromParsingName").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
    }

    /**
     * 对话框在 Show 里跑自己的消息循环，直到关闭才返回，而对话框要求所在线程是 STA。
     * 放在 AWT 事件线程上会让整个界面停在 Show 里，所以另开一条常驻的 STA 线程。
     * 只有一条线程，并发的第二次调用会排在第一个对话框关闭之后，不会同时弹出两个。
     */
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-FolderPicker").also { it.isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * 弹出目录选择框。[owner] 在对话框打开期间被禁用，对话框模态于它；为 null 或还没有 HWND 时不设属主。
     *
     * 协程被取消时对话框不会随之关闭（IFileDialog.Close 只能在它自己的回调里调用），
     * 函数仍会等到用户关掉对话框才抛出取消异常。
     */
    suspend fun pickFolder(owner: Window?, initial: File?, title: String): FolderPickResult {
        if (!WinRTSupport.isWindows) return FolderPickResult.Unavailable
        // HWND 在调用方线程上取好：对话框线程只做 COM
        val ownerHwnd = owner?.let { runCatching { Win32Window.handleOf(it) }.getOrNull() } ?: MemorySegment.NULL
        return withContext(dispatcher) {
            runCatching { show(ownerHwnd, initial, title) }.getOrDefault(FolderPickResult.Unavailable)
        }
    }

    private fun show(ownerHwnd: MemorySegment, initial: File?, title: String): FolderPickResult =
        Arena.ofConfined().use { arena ->
            ComInterop.initializeApartmentThread()
            val dialog = ComInterop.createInstance(CLSID_FileOpenDialog, IID_IFileOpenDialog)
            try {
                val options = arena.allocate(JAVA_INT)
                ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 10, ComInterop.callWithPointer), dialog, options), "GetOptions")
                val newOptions = options.get(JAVA_INT, 0) or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM
                ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 9, intArg), dialog, newOptions), "SetOptions")
                ComInterop.check(
                    ComInterop.hresult(ComInterop.vtable(dialog, 17, ComInterop.callWithPointer), dialog, wideString(arena, title)),
                    "SetTitle",
                )
                // 初始目录不存在或无法解析时照常弹出，停在系统默认位置
                initial?.takeIf { it.isDirectory }?.let { folder -> setFolder(arena, dialog, folder) }

                val shown = ComInterop.hresult(ComInterop.vtable(dialog, 3, ComInterop.callWithPointer), dialog, ownerHwnd)
                if (shown == HRESULT_ERROR_CANCELLED) return FolderPickResult.Cancelled
                ComInterop.check(shown, "Show")

                val itemOut = arena.allocate(ADDRESS)
                ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 20, ComInterop.callWithPointer), dialog, itemOut), "GetResult")
                val item = itemOut.get(ADDRESS, 0)
                try {
                    FolderPickResult.Picked(File(fileSystemPath(arena, item)))
                } finally {
                    ComInterop.release(item)
                }
            } finally {
                ComInterop.release(dialog)
            }
        }

    private fun setFolder(arena: Arena, dialog: MemorySegment, folder: File) {
        val itemOut = arena.allocate(ADDRESS)
        val hr = ComInterop.hresult(
            shCreateItemFromParsingName,
            wideString(arena, folder.absolutePath), MemorySegment.NULL, arena.allocateFrom(JAVA_BYTE, *IID_IShellItem), itemOut,
        )
        if (hr < 0) return
        val item = itemOut.get(ADDRESS, 0)
        try {
            ComInterop.hresult(ComInterop.vtable(dialog, 12, ComInterop.callWithPointer), dialog, item)
        } finally {
            ComInterop.release(item)
        }
    }

    /** IShellItem.GetDisplayName 返回的字符串由 COM 分配，读完要 CoTaskMemFree。 */
    private fun fileSystemPath(arena: Arena, item: MemorySegment): String {
        val nameOut = arena.allocate(ADDRESS)
        ComInterop.check(ComInterop.hresult(ComInterop.vtable(item, 5, intAndPointerArgs), item, SIGDN_FILESYSPATH, nameOut), "GetDisplayName")
        val name = nameOut.get(ADDRESS, 0)
        try {
            return name.reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)
        } finally {
            ComInterop.taskMemFree(name)
        }
    }

    private fun wideString(arena: Arena, text: String): MemorySegment = arena.allocateFrom(text, Charsets.UTF_16LE)
}
