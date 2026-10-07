package dev.piko.shared.auth

import dev.piko.shared.log.PikoLog
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet

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

/** 明文，只靠文件权限保护。没有系统保管处的平台用它，也是钥匙串一类外部保管处锁着时的兜底，见 [layeredSecretVault]。 */
class PlainFileVault(directory: Path) : FileSecretVault(directory, "plain") {
    override fun seal(data: ByteArray): ByteArray = data
    override fun unseal(data: ByteArray): ByteArray = data
}

private val isPosix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

// 权限在创建时就带上：先建后改的话，中间有一段时间文件按 umask 对别人可读。
// Windows 上目录沿用继承的权限、只收紧文件：便携目录会先后被几个 Windows 账户用，目录只许第一个账户访问的话，
// 后来的账户连自己那份都建不出来
private fun createPrivateDirectories(directory: Path) {
    if (isPosix) {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createDirectories(directory)
    }
}

private fun createPrivateTempFile(directory: Path): Path {
    val ownerOnly = windowsOwnerOnly
    return when {
        isPosix -> Files.createTempFile(directory, "secret", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        ownerOnly != null -> Files.createTempFile(directory, "secret", ".tmp", ownerOnly)
        else -> Files.createTempFile(directory, "secret", ".tmp")
    }
}

/**
 * 当前 Windows 账户，写作「域\用户名」。只给用户名时，计算机名与用户名相同的机器上查到的是计算机本身。
 * 环境变量与 user.name 都取自本进程，桌面端与 CLI 得出的相同。
 */
fun windowsUserName(): String = System.getenv("USERDOMAIN")?.let { "$it\\" }.orEmpty() + System.getProperty("user.name")

/** 按 Windows 账户区分文件名的短标记。账户名不区分大小写，先转小写；不写原名，免得账户名出现在文件名里。 */
fun windowsUserTag(user: String = windowsUserName()): String =
    MessageDigest.getInstance("SHA-256").digest(user.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

/**
 * Windows 上只许当前用户访问的 ACL。DPAPI 的密文换个用户解不开，但便携目录可能放在共享盘上，
 * 别人仍能拷走密文离线猜密码，或者删改它；继承来的权限常给 Users 读甚至写。
 *
 * 创建时就给出完整的 DACL，系统不再并入上级目录可继承的条目。查不到当前用户时退回继承的权限，只记日志：
 * 机密仍由 DPAPI 加密，不该因此存不下。
 */
private val windowsOwnerOnly: FileAttribute<List<AclEntry>>? by lazy {
    if (isPosix || "acl" !in FileSystems.getDefault().supportedFileAttributeViews()) return@lazy null
    runCatching {
        val user = FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(windowsUserName())
        val entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(user)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
        object : FileAttribute<List<AclEntry>> {
            override fun name() = "acl:acl"
            override fun value() = listOf(entry)
        }
    }.onFailure { PikoLog.w("credentials", "查不到当前用户，机密文件沿用继承的权限", it) }.getOrNull()
}
