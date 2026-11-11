package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 经 Windows PowerShell 调 DPAPI（ProtectedData，当前用户范围，不带附加熵），密文存 `<key>.<账户标记>.bin`，
 * 文件名的规则与 DpapiSecretVault 相同。
 *
 * 桌面端用 FFM 直调 crypt32，这一份给命令行工具：shared 与 CLI 按 JDK 21 编译，java.lang.foreign 在 21 上
 * 还是预览 API。两者的密文互通：ProtectedData 底下就是 CryptProtectData，描述串不参与解密，
 * CLI 刷新令牌后写回的正是桌面端读的那个文件。每次调用要起一个 powershell 进程，约几百毫秒。
 */
class PowerShellDpapiVault(directory: Path) : FileSecretVault(directory, "${windowsUserTag()}.bin") {
    override fun seal(data: ByteArray): ByteArray {
        val result = run("Protect", data)
        if (result.exitCode != 0) {
            val detail = result.errorSummary.let { if (it.isEmpty()) "" else " ($it)" }
            throw VaultUnavailableException("DPAPI Protect failed: PowerShell exitCode=${result.exitCode}$detail")
        }
        return result.output
    }

    override fun unseal(data: ByteArray): ByteArray? {
        val result = run("Unprotect", data)
        return when (result.exitCode) {
            0 -> result.output
            CRYPTOGRAPHIC_FAILURE -> {
                PikoLog.w(TAG, "DPAPI failed to unprotect stored secret (different Windows user or machine); treating as absent")
                null
            }
            else -> {
                val detail = result.errorSummary.let { if (it.isEmpty()) "" else " ($it)" }
                throw VaultUnavailableException("DPAPI Unprotect failed: PowerShell exitCode=${result.exitCode}$detail")
            }
        }
    }

    private class Result(val exitCode: Int, val output: ByteArray, val errorSummary: String)

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
            .start()
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.use { String(it.readAllBytes()) }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.use { String(it.readNBytes(4096)) }
        }
        runCatching {
            process.outputStream.use { it.write(Base64.getEncoder().encode(data)) }
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            PikoLog.w(TAG, "PowerShell DPAPI $operation timed out after ${TIMEOUT_SECONDS}s")
            throw VaultUnavailableException("PowerShell DPAPI $operation timed out after ${TIMEOUT_SECONDS}s")
        }
        val exitCode = process.exitValue()
        val stdout = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
        val outputBytes = if (exitCode == 0) {
            runCatching { Base64.getDecoder().decode(stdout.trim()) }.getOrElse { e ->
                throw VaultUnavailableException("PowerShell DPAPI $operation returned invalid Base64 (${e::class.simpleName})", e)
            }
        } else {
            ByteArray(0)
        }
        return Result(exitCode, outputBytes, stderr)
    }

    // 写全路径：PATH 里的 powershell 可能被别的同名程序顶替
    private fun powershell(): String =
        (System.getenv("SystemRoot") ?: "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

    private companion object {
        const val TAG = "Credentials"
        const val CRYPTOGRAPHIC_FAILURE = 3
        const val TIMEOUT_SECONDS = 30L
    }
}
