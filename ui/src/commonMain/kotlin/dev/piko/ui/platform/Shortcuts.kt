package dev.piko.ui.platform

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed as isCtrlHeld
import androidx.compose.ui.input.pointer.isMetaPressed as isMetaHeld

/** 快捷键的主修饰键。macOS 的惯例是 Command，Ctrl 在那里另有用途；其余平台是 Ctrl。 */
enum class ShortcutModifier {
    Ctrl,
    Command,
    ;

    fun isPressed(event: KeyEvent): Boolean = when (this) {
        Ctrl -> event.isCtrlPressed
        Command -> event.isMetaPressed
    }

    /** 指针事件时按着的修饰键，Ctrl（⌘）点选用。 */
    fun isPressed(modifiers: PointerKeyboardModifiers): Boolean = when (this) {
        Ctrl -> modifiers.isCtrlHeld
        Command -> modifiers.isMetaHeld
    }

    /** 提示里的写法：Ctrl+F，或 macOS 惯用的 ⌘F。 */
    fun label(key: String): String = when (this) {
        Ctrl -> "Ctrl+$key"
        Command -> "⌘$key"
    }

    /** 移入回收站。Mac 键盘上的 delete 键实为退格，已用于返回上级，所以照 Finder 用 ⌘⌫。 */
    val trashLabel: String
        get() = when (this) {
            Ctrl -> "Delete"
            Command -> "⌘⌫"
        }
}
