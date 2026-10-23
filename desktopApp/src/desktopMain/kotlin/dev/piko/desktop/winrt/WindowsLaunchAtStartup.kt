package dev.piko.desktop.winrt

import dev.piko.ui.platform.LaunchAtStartup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只登记打包启动器，不把开发环境的 java.exe 加入登录启动项。 */
internal class WindowsLaunchAtStartup(
    private val executable: String,
    private val valueName: String = ShellIdentity.current.registeredName,
) : LaunchAtStartup {
    override suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) {
        HkcuRegistry.getString(RUN_KEY, valueName) == command(executable)
    }

    override suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (enabled) HkcuRegistry.setString(RUN_KEY, valueName, command(executable))
        else HkcuRegistry.deleteValue(RUN_KEY, valueName)
    }

    companion object {
        internal const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        internal fun command(executable: String): String {
            require(executable.isNotBlank() && '"' !in executable && '\n' !in executable && '\r' !in executable)
            return "\"$executable\""
        }
    }
}
