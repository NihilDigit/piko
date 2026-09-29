package dev.piko.shared.auth

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * 按 key 存取一段机密字节。方法都是阻塞的，调用方自己换到 IO 线程。
 *
 * 约定：没有这一项，或有但解不开（换了机器、系统重装后恢复的数据），[read] 返回 null；
 * 保管处暂时用不了（钥匙串锁着、用户拒绝授权、系统组件调用失败）抛异常，
 * 调用方据此区分「没有」与「现在拿不到」，后者不能当作已退出登录。
 */
interface SecretVault {
    fun read(key: String): ByteArray?
    fun write(key: String, data: ByteArray)
    fun delete(key: String)
}

class VaultUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 每个 key 一个文件，内容经 [seal] 加工后落盘。写入先写临时文件再原子替换，令牌刷新写到一半退出也不留半截。 */
abstract class FileSecretVault(private val directory: Path, private val extension: String) : SecretVault {
    protected abstract fun seal(data: ByteArray): ByteArray

    /** 解不开返回 null。 */
    protected abstract fun unseal(data: ByteArray): ByteArray?

    override fun read(key: String): ByteArray? {
        val sealed = try {
            Files.readAllBytes(fileOf(key))
        } catch (_: NoSuchFileException) {
            return null
        }
        return unseal(sealed)
    }

    override fun write(key: String, data: ByteArray) {
        val sealed = seal(data)
        createPrivateDirectories(directory)
        val staging = createPrivateTempFile(directory)
        try {
            Files.write(staging, sealed)
            Files.move(staging, fileOf(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    override fun delete(key: String) {
        Files.deleteIfExists(fileOf(key))
    }

    private fun fileOf(key: String): Path = directory.resolve("$key.$extension")
}

/** 明文，只靠文件权限保护。没有系统保管处的平台用它，也是系统保管处写不进时的兜底。 */
class PlainFileVault(directory: Path) : FileSecretVault(directory, "plain") {
    override fun seal(data: ByteArray): ByteArray = data
    override fun unseal(data: ByteArray): ByteArray = data
}

private val isPosix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

// 权限在创建时就带上：先建后改的话，中间有一段时间文件按 umask 对别人可读
private fun createPrivateDirectories(directory: Path) {
    if (isPosix) {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createDirectories(directory)
    }
}

private fun createPrivateTempFile(directory: Path): Path =
    if (isPosix) {
        Files.createTempFile(directory, "secret", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(directory, "secret", ".tmp")
    }
