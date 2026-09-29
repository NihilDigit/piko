package dev.piko.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 文件名输入框。网盘里的名字常是带站点前缀、画质与字幕组标签的长串，单行框里只看得到
 * 开头一截，改的时候得左右拖着找光标，所以编辑时折行显示全文。
 *
 * [collapseWhenIdle] 为真时，没有焦点就收回一行，给不以编辑为主的面板省高度；专门用来
 * 改名的对话框始终展开。
 *
 * 多行框里回车默认是换行，而文件名不能含换行：粘贴进来的换行直接去掉，键入的回车当作
 * 「完成」处理。
 */
@Composable
fun FileNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    collapseWhenIdle: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    onDone: () -> Unit = {},
    /** 为真时一出现就取得焦点，专门用来改名的对话框用。 */
    autoFocus: Boolean = false,
    /** 刚出现时选中的范围，例如重命名时照资源管理器只选主名、不选扩展名。null 时光标在末尾。 */
    initialSelection: TextRange? = null,
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    // 选区要用 TextFieldValue 才表达得了；对外仍只收发字符串。外面改了值（清空、换成修正后的名字）时光标放到末尾
    var field by remember { mutableStateOf(TextFieldValue(value, initialSelection ?: TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    val finish = {
        focusManager.clearFocus()
        onDone()
    }
    // 返回键只收起键盘，焦点还留在框里，框也就一直是展开的多行。键盘由显示转为隐藏时
    // 一并交出焦点；只认这个转变，刚点进框时键盘还没弹出，不能当成「收起了」
    val isImeVisible = LocalPikoPlatform.current.isImeVisible()
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isImeVisible) {
        if (wasImeVisible && !isImeVisible && isFocused) focusManager.clearFocus()
        wasImeVisible = isImeVisible
    }
    OutlinedTextField(
        value = shown,
        onValueChange = { input ->
            if ('\n' in input.text) {
                val text = input.text.replace("\n", "")
                field = TextFieldValue(text, TextRange(text.length))
                onValueChange(text)
                finish()
            } else {
                field = input
                onValueChange(input.text)
            }
        },
        label = { Text(label) },
        singleLine = false,
        maxLines = if (collapseWhenIdle && !isFocused) 1 else EXPANDED_MAX_LINES,
        enabled = enabled,
        isError = isError,
        // 提示只占一行：折成两行会把对话框（按内容定高的独立窗口）撑高
        supportingText = supportingText?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish() }),
        shape = MaterialTheme.shapes.largeIncreased,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            // 动画只为收起与展开之间的那一跳。对话框里的框不收起，却随输入折行长高：Android 的
            // 对话框是按内容定尺寸的独立窗口，高度逐帧变化就逐帧改窗口尺寸，窗口表面跟不上，
            // 整个对话框闪烁、上下跳动，末行还被裁掉半截（issue #9）。面板的窗口铺满屏幕，不受影响
            .then(if (collapseWhenIdle) Modifier.animateContentSize() else Modifier),
    )
}

// 超过六行的名字在框内滚动，不再把面板往下撑
private const val EXPANDED_MAX_LINES = 6
