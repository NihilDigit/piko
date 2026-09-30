package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat

/** 只隐藏根目录里的应用内部文件夹；其他位置同名的文件夹仍属于用户。 */
fun isPikoInternalFolder(file: FileStat, parentId: String = file.parentId): Boolean =
    parentId.isEmpty() && file.parentId.isEmpty() && file.isFolder && isPikoInternalFolderName(file.name)

fun isPikoInternalFolderName(name: String): Boolean = name == PreviewTempFolder.FOLDER_NAME || name == ".piko"
