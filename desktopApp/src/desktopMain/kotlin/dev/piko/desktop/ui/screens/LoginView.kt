package dev.piko.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoClientManager
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Key
import io.github.composefluent.icons.regular.Person
import dev.piko.shared.state.LoginState
import io.github.composefluent.surface.Card

@Composable
fun LoginView(manager: PikoClientManager) {
    val scope = rememberCoroutineScope()
    val state = remember(manager) { LoginState(manager, scope) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(420.dp).padding(24.dp),
            shape = FluentTheme.shapes.overlay,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Piko", style = FluentTheme.typography.titleLarge)
                Text(
                    "Windows 平台 PikPak 客户端",
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                )

                TextField(
                    value = state.account,
                    onValueChange = state::updateAccount,
                    header = { Text("账号") },
                    placeholder = { Text("邮箱 / 用户名") },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = "账号")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                TextField(
                    value = state.password,
                    onValueChange = state::updatePassword,
                    header = { Text("密码") },
                    placeholder = { Text("请输入密码") },
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = "密码")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                state.errorMessage?.let { err ->
                    InfoBar(
                        title = { Text("登录失败") },
                        message = { Text(err) },
                        severity = io.github.composefluent.component.InfoBarSeverity.Critical,
                        modifier = Modifier.fillMaxWidth(),
                        closeAction = {
                            InfoBarDefaults.CloseActionButton(onClick = state::dismissError)
                        },
                    )
                }

                AccentButton(
                    disabled = !state.canSubmit,
                    onClick = state::login,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isLoggingIn) {
                        ProgressRing(size = ProgressRingSize.Small)
                        Text("正在登录…", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text("登录")
                    }
                }
            }
        }
    }
}
