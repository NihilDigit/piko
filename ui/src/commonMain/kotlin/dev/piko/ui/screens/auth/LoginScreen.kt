package dev.piko.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.LoginState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PikoLoadingIndicator

/**
 * Login screen supporting account (email/username) and password authentication.
 *
 * Documentation references:
 * - Material 3 Text Fields: `m3-material-mirror/pages/components/text-fields.md`
 * - Material 3 Buttons: `m3-material-mirror/pages/components/buttons.md`
 * - Compose Form and IME padding: `android-docs-mirror/pages/develop/ui/compose/layouts/insets.md`
 * - Structured coroutine cancellation: `kotlin-docs-mirror/pages/docs/coroutines-cancellation.md`
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clientManager = LocalPikoServices.current.clientManager
    val state = remember { LoginState(clientManager, scope) }
    var passwordVisible by remember { mutableStateOf(false) }
    // SecureTextField 只收 TextFieldState，而 shared 里的 LoginState 存的是 String，
    // 所以在这里单向同步过去，不把 TextFieldState 推进 shared
    val passwordField = rememberTextFieldState(state.password)
    LaunchedEffect(passwordField) {
        snapshotFlow { passwordField.text.toString() }.collect(state::updatePassword)
    }

    val account = state.account
    val isLoading = state.isLoggingIn
    val errorMessage = state.errorMessage

    val performLogin = { state.login() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 平板与横屏上表单不铺满：一行输入框拉到上千 dp 宽，眼睛要来回扫
            Column(
                modifier = Modifier.widthIn(max = FormMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = PikoBrandIcons.Logo,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(MaterialTheme.shapes.extraLarge),
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "登录 Piko",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "使用 PikPak 账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // contentType 让密码管理器认出这两个框，能自动填充，登录后也会提示保存
                OutlinedTextField(
                    value = account,
                    onValueChange = state::updateAccount,
                    label = { Text("邮箱或用户名") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = errorMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                    shape = MaterialTheme.shapes.largeIncreased,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 错误放在密码框的辅助文字里，不再单独一行飘在框与按钮之间
                OutlinedSecureTextField(
                    state = passwordField,
                    label = { Text("密码") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    textObfuscationMode = if (passwordVisible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                    enabled = !isLoading,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Password },
                    shape = MaterialTheme.shapes.largeIncreased,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { performLogin() },
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { performLogin() },
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (isLoading) {
                        PikoLoadingIndicator(size = 20.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在登录")
                    } else {
                        Text("登录")
                    }
                }
            }
        }
    }
}

private val FormMaxWidth = 400.dp
