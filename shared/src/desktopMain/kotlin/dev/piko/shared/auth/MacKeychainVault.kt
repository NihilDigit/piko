package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 登录钥匙串里的通用密码项，经 /usr/bin/security 存取，service 固定，account 属性是 key。
 *
 * 不直调 SecItem：应用是 ad-hoc 签名，每个版本的签名都不同，钥匙串按签名认访问者，直调的话每次更新后
 * 都要弹授权框。项由 security 建出，访问控制列表信任的是 security 本身，它的签名随系统走，不会变。
 *
 * 以下取自 Apple 开源的 SecurityTool/macOS 源码（security.c、keychain_add.c、keychain_find.c），未在 Mac 上实测：
 * - 机密不能放进命令行参数，ps 看得到。-w 省略取值时由 getpass 从终端读，不读标准输入，
 *   所以写入走 security -i：从标准输入逐行读命令执行，进程的退出码是最后一条命令的结果。
 * - -i 的一行上限 4096 字节（MAX_LINE_LEN），超出的部分不报错，而被当作下一行命令执行，写入前自己检查长度。
 *   分词只认空白、引号与反斜杠，内容存 Base64，不需要转义。
 * - find-generic-password -w 把密码原样打印再加换行，但只要有一个字节不可打印就整段改印十六进制；
 *   存 Base64 就总是原样。
 * - 找不到时命令返回 errSecItemNotFound（-25300），进程退出码取低八位为 44。钥匙串锁着且不许交互、
 *   用户在授权框里点了拒绝，返回的是别的错误码，一律当作暂时不可用。
 */
class MacKeychainVault(
    private val service: String = "dev.piko.desktop",
    private val timeoutSeconds: Long = 120,
) : SecretVault {
    override fun read(key: String): ByteArray? {
        val result = security(listOf("find-generic-password", "-s", service, "-a", key, "-w"))
        return when (result.exitCode) {
            0 -> runCatching { Base64.getDecoder().decode(result.stdout.trimEnd('\n')) }
                .onFailure { PikoLog.w(TAG, "钥匙串里的凭据不是本应用写入的格式，当作没有") }
                .getOrNull()
            ITEM_NOT_FOUND -> null
            else -> throw result.failure("读取钥匙串")
        }
    }

    override fun write(key: String, data: ByteArray) {
        val line = "add-generic-password -U -s $service -a $key -w ${Base64.getEncoder().encodeToString(data)}\n"
        if (line.length >= MAX_LINE_LENGTH) throw VaultUnavailableException("凭据过长，security -i 一行放不下")
        val result = security(listOf("-i"), stdin = line)
        if (result.exitCode != 0) throw result.failure("写入钥匙串")
    }

    override fun delete(key: String) {
        val result = security(listOf("delete-generic-password", "-s", service, "-a", key))
        if (result.exitCode != 0 && result.exitCode != ITEM_NOT_FOUND) throw result.failure("删除钥匙串项")
    }

    // stderr 只有 security 自己的错误描述（如 User interaction is not allowed），不含机密
    private class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        fun failure(action: String) =
            VaultUnavailableException("${action}失败：security 退出码 $exitCode，${stderr.trim().lines().firstOrNull().orEmpty()}")
    }

    private fun security(arguments: List<String>, stdin: String? = null): Result {
        val process = ProcessBuilder(listOf(SECURITY) + arguments).start()
        process.outputStream.use { input -> stdin?.let { input.write(it.toByteArray()) } }
        // 钥匙串锁着时系统会弹解锁框，等用户作答；一直不答就当作暂时不可用。
        // 先等退出再读输出：输出只有几 KB，放得进管道缓冲，先读的话读取会一直阻塞到进程退出，超时形同虚设
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw VaultUnavailableException("security 未在 $timeoutSeconds 秒内返回")
        }
        val stdout = process.inputStream.use { String(it.readAllBytes()) }
        val stderr = process.errorStream.use { String(it.readAllBytes()) }
        return Result(process.exitValue(), stdout, stderr)
    }

    private companion object {
        const val TAG = "credentials"
        const val SECURITY = "/usr/bin/security"
        const val ITEM_NOT_FOUND = 44
        const val MAX_LINE_LENGTH = 4096
    }
}
