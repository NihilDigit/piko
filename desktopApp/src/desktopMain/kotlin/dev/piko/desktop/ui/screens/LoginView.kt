package dev.piko.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.composefluent.surface.Card
import kotlinx.coroutines.launch

@Composable
fun LoginView(manager: PikoClientManager) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

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
                    value = account,
                    onValueChange = { account = it },
                    header = { Text("账号") },
                    placeholder = { Text("邮箱 / 用户名") },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = "账号")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                TextField(
                    value = password,
                    onValueChange = { password = it },
                    header = { Text("密码") },
                    placeholder = { Text("请输入密码") },
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = "密码")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                error?.let { err ->
                    InfoBar(
                        title = { Text("登录失败") },
                        message = { Text(err) },
                        severity = io.github.composefluent.component.InfoBarSeverity.Critical,
                        modifier = Modifier.fillMaxWidth(),
                        closeAction = {
                            InfoBarDefaults.CloseActionButton(onClick = { error = null })
                        },
                    )
                }

                AccentButton(
                    disabled = account.isBlank() || password.isEmpty() || isLoggingIn,
                    onClick = {
                        isLoggingIn = true
                        error = null
                        scope.launch {
                            manager.login(account.trim()) { password }.onFailure {
                                error = it.message ?: "登录失败，请检查账号密码"
                            }
                            isLoggingIn = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoggingIn) {
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
