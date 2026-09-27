package dev.piko.desktop.winrt

import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * 系统原生的保存框：IFileSaveDialog，与 [FolderPicker] 同一条 STA 线程。
 *
 * 不用 AWT 的 FileDialog：它的原生对话框跑在 AWT 工具包线程上，事件线程停在模态循环里处理焦点事件时，
 * WComponentPeer.setFocus 要等工具包线程回话，而工具包线程正忙着对话框。2026-09-26 导出日志时实测，
 * 事件线程在 setFocus 里一停数秒、不占 CPU，Compose 窗口这段时间无法重绘，看上去就是卡死。
 *
 * IFileSaveDialog 继承 IFileDialog，虚表顺序同 [FolderPicker] 的说明，另用到 SetFileTypes 4、
 * SetFileName 15、SetDefaultExtension 22。
 */
object SaveFilePicker {
    private val CLSID_FileSaveDialog = ComInterop.guid("c0b4e2f3-ba21-4773-8dba-335ec946eb8b")
    private val IID_IFileSaveDialog = ComInterop.guid("84bccd23-5fde-4cdb-aea4-af64b83d78ab")

    /**
     * 弹出保存框，初始停在 [folder]、文件名为 [fileName]。[filterName] 与 [pattern] 组成唯一一项类型筛选，
     * 例如「文本文件」与 *.txt，扩展名也据 [pattern] 补全。结果与 [FolderPicker.pickFolder] 同一套：
     * [FolderPickResult.Unavailable] 表示原生对话框没能弹出，调用方退回 AWT 的 FileDialog。
     */
    suspend fun pickSaveFile(
        owner: Window?,
        folder: File?,
        fileName: String,
        title: String,
        filterName: String,
        pattern: String,
    ): FolderPickResult {
        if (!WinRTSupport.isWindows) return FolderPickResult.Unavailable
        val ownerHwnd = owner?.let { runCatching { Win32Window.handleOf(it) }.getOrNull() } ?: MemorySegment.NULL
        return withContext(FolderPicker.dispatcher) {
            runCatching { show(ownerHwnd, folder, fileName, title, filterName, pattern) }.getOrDefault(FolderPickResult.Unavailable)
        }
    }

    private fun show(
        ownerHwnd: MemorySegment,
        folder: File?,
        fileName: String,
        title: String,
        filterName: String,
        pattern: String,
    ): FolderPickResult = Arena.ofConfined().use { arena ->
        ComInterop.initializeApartmentThread()
        val dialog = ComInterop.createInstance(CLSID_FileSaveDialog, IID_IFileSaveDialog)
        try {
            val options = arena.allocate(JAVA_INT)
            ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 10, ComInterop.callWithPointer), dialog, options), "GetOptions")
            // 保存框的默认选项已含覆盖确认，只补上必须是文件系统路径
            val newOptions = options.get(JAVA_INT, 0) or FolderPicker.FOS_FORCEFILESYSTEM
            ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 9, FolderPicker.intArg), dialog, newOptions), "SetOptions")

            // COMDLG_FILTERSPEC：名称与通配符两个宽字符串指针
            val filter = arena.allocate(ADDRESS, 2)
            filter.setAtIndex(ADDRESS, 0, FolderPicker.wideString(arena, filterName))
            filter.setAtIndex(ADDRESS, 1, FolderPicker.wideString(arena, pattern))
            ComInterop.hresult(ComInterop.vtable(dialog, 4, FolderPicker.intAndPointerArgs), dialog, 1, filter)
            ComInterop.hresult(
                ComInterop.vtable(dialog, 22, ComInterop.callWithPointer),
                dialog, FolderPicker.wideString(arena, pattern.substringAfterLast('.')),
            )

            ComInterop.check(
                ComInterop.hresult(ComInterop.vtable(dialog, 17, ComInterop.callWithPointer), dialog, FolderPicker.wideString(arena, title)),
                "SetTitle",
            )
            ComInterop.check(
                ComInterop.hresult(ComInterop.vtable(dialog, 15, ComInterop.callWithPointer), dialog, FolderPicker.wideString(arena, fileName)),
                "SetFileName",
            )
            folder?.takeIf { it.isDirectory }?.let { FolderPicker.setFolder(arena, dialog, it) }

            val shown = ComInterop.hresult(ComInterop.vtable(dialog, 3, ComInterop.callWithPointer), dialog, ownerHwnd)
            if (shown == FolderPicker.HRESULT_ERROR_CANCELLED) return FolderPickResult.Cancelled
            ComInterop.check(shown, "Show")

            val itemOut = arena.allocate(ADDRESS)
            ComInterop.check(ComInterop.hresult(ComInterop.vtable(dialog, 20, ComInterop.callWithPointer), dialog, itemOut), "GetResult")
            val item = itemOut.get(ADDRESS, 0)
            try {
                FolderPickResult.Picked(File(FolderPicker.fileSystemPath(arena, item)))
            } finally {
                ComInterop.release(item)
            }
        } finally {
            ComInterop.release(dialog)
        }
    }
}
