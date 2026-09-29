package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog
import java.nio.file.Path
import java.util.Base64

/**
 * 经 Windows PowerShell 调 DPAPI（ProtectedData，当前用户范围，不带附加熵），密文存 `<key>.bin`。
 *
 * 桌面端用 FFM 直调 crypt32，这一份给命令行工具：shared 与 CLI 按 JDK 21 编译，java.lang.foreign 在 21 上
 * 还是预览 API。两者的密文互通：ProtectedData 底下就是 CryptProtectData，描述串不参与解密，
 * CLI 刷新令牌后写回的正是桌面端读的那个文件。每次调用要起一个 powershell 进程，约几百毫秒。
 */
class PowerShellDpapiVault(directory: Path) : FileSecretVault(directory, "bin") {
    override fun seal(data: ByteArray): ByteArray {
        val result = run("Protect", data)
        if (result.exitCode != 0) throw VaultUnavailableException("DPAPI 加密失败：powershell 退出码 ${result.exitCode}")
        return result.output
    }

    override fun unseal(data: ByteArray): ByteArray? {
        val result = run("Unprotect", data)
        return when (result.exitCode) {
            0 -> result.output
            CRYPTOGRAPHIC_FAILURE -> {
                PikoLog.w(TAG, "DPAPI 解不开这份凭据（换了机器或用户），当作没有")
                null
            }
            else -> throw VaultUnavailableException("DPAPI 解密失败：powershell 退出码 ${result.exitCode}")
        }
    }

    private class Result(val exitCode: Int, val output: ByteArray)

    // 机密经标准输入进出，不放进命令行；脚本本身不含机密，走 -EncodedCommand 免去引号转义
    private fun run(operation: String, data: ByteArray): Result {
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            try {
                Add-Type -AssemblyName System.Security
                ${'$'}data = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim())
                ${'$'}out = [Security.Cryptography.ProtectedData]::$operation(${'$'}data, ${'$'}null, 'CurrentUser')
                [Console]::Out.Write([Convert]::ToBase64String(${'$'}out))
            } catch [Security.Cryptography.CryptographicException] {
                exit $CRYPTOGRAPHIC_FAILURE
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(powershell(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.use { it.write(Base64.getEncoder().encode(data)) }
        val output = process.inputStream.use { String(it.readAllBytes()) }
        val exitCode = process.waitFor()
        return Result(exitCode, if (exitCode == 0) Base64.getDecoder().decode(output.trim()) else ByteArray(0))
    }

    // 写全路径：PATH 里的 powershell 可能被别的同名程序顶替
    private fun powershell(): String =
        (System.getenv("SystemRoot") ?: "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

    private companion object {
        const val TAG = "credentials"
        const val CRYPTOGRAPHIC_FAILURE = 3
    }
}
