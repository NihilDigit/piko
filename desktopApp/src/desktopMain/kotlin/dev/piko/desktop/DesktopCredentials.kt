package dev.piko.desktop

import dev.piko.desktop.secrets.DpapiSecretVault
import dev.piko.desktop.secrets.SecretServiceVault
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.auth.DesktopSessionStore
import dev.piko.shared.auth.platformSecretVault

/**
 * Windows 上换成 FFM 直调的 DPAPI，免去 shared 里那份每次起 powershell 的开销，密文两边互通。
 * Linux 上是 Secret Service，同样要 FFM，所以不在 shared 里。
 */
internal fun desktopSessionStore(): DesktopSessionStore = DesktopSessionStore(
    primaryVault = { directory ->
        when {
            WinRTSupport.isWindows -> DpapiSecretVault(directory)
            isMacOs -> platformSecretVault(directory)
            else -> SecretServiceVault.open()
        }
    },
)
