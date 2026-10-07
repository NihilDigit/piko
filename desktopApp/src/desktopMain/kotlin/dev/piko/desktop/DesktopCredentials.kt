package dev.piko.desktop

import dev.piko.desktop.secrets.DpapiSecretVault
import dev.piko.desktop.secrets.SecretServiceVault
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.PikoHome
import dev.piko.shared.auth.DesktopSessionStore
import dev.piko.shared.auth.SecretVault
import dev.piko.shared.auth.layeredSecretVault
import dev.piko.shared.auth.platformSecretVault
import java.nio.file.Path

/**
 * Windows 上换成 FFM 直调的 DPAPI，免去 shared 里那份每次起 powershell 的开销，密文两边互通。
 * Linux 上是 Secret Service，同样要 FFM，所以不在 shared 里。
 */
private fun primarySecretVault(directory: Path): SecretVault? = when {
    WinRTSupport.isWindows -> DpapiSecretVault(directory)
    isMacOs -> platformSecretVault(directory)
    else -> SecretServiceVault.open()
}

internal fun desktopSessionStore(root: Path = PikoHome.root): DesktopSessionStore = DesktopSessionStore(root, ::primarySecretVault)

/**
 * 不属于某个账号会话的机密（解压密码、上传凭据），与登录态用同一处系统保管处，兜底文件放在 `secrets` 目录。
 * 钥匙串与 Secret Service 里和账号的条目同一个 service，靠 key 区分：账号的 key 是 16 位十六进制摘要，这边的带连字符。
 */
internal fun desktopPreferenceSecrets(): SecretVault {
    val directory = PikoHome.root.resolve("secrets")
    return layeredSecretVault(directory, primarySecretVault(directory))
}
