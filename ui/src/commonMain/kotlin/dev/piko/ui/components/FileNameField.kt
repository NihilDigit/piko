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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
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
        value = value,
        onValueChange = { input ->
            if ('\n' in input) {
                onValueChange(input.replace("\n", ""))
                finish()
            } else {
                onValueChange(input)
            }
        },
        label = { Text(label) },
        singleLine = false,
        maxLines = if (collapseWhenIdle && !isFocused) 1 else EXPANDED_MAX_LINES,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish() }),
        shape = MaterialTheme.shapes.largeIncreased,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .animateContentSize(),
    )
}

// 超过六行的名字在框内滚动，不再把面板往下撑
private const val EXPANDED_MAX_LINES = 6
