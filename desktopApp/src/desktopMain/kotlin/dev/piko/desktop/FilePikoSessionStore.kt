package dev.piko.desktop

import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.PikoCredentials
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FilePikoSessionStore(
    private val root: File = File(System.getProperty("user.home"), ".piko"),
) : PikoSessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionFile get() = File(root, "pikpak-session.json")
    private val accountFile get() = File(root, "pikpak-account.txt")
    private val passwordFile get() = File(root, "pikpak-password.txt")

    override suspend fun load(account: String): Session? = withContext(Dispatchers.IO) {
        if (loadLastAccount() != account || !sessionFile.isFile) return@withContext null
        runCatching {
            json.decodeFromString(Session.serializer(), sessionFile.readText())
        }.getOrNull()
    }

    override suspend fun save(account: String, session: Session) = withContext(Dispatchers.IO) {
        root.mkdirs()
        // 先写临时文件再原子替换：SDK 每次 refresh 都会调 save，直接覆盖写到一半进程退出，
        // 留下半截 JSON，下次启动解析失败，只能用密码重新登录
        val staging = File(root, "${sessionFile.name}.tmp")
        staging.writeText(json.encodeToString(Session.serializer(), session))
        Files.move(
            staging.toPath(),
            sessionFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        accountFile.writeText(account)
    }

    override suspend fun clear(account: String) = withContext(Dispatchers.IO) {
        if (loadLastAccount() == account) {
            sessionFile.delete()
            accountFile.delete()
        }
    }

    override suspend fun loadLastAccount(): String? = withContext(Dispatchers.IO) {
        accountFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    }

    override suspend fun saveLastAccount(account: String) = withContext(Dispatchers.IO) {
        root.mkdirs()
        accountFile.writeText(account)
    }

    override suspend fun clearLastAccount() = withContext(Dispatchers.IO) {
        accountFile.delete()
        Unit
    }

    override suspend fun loadCredentials(account: String): PikoCredentials? = withContext(Dispatchers.IO) {
        if (loadLastAccount() != account || !passwordFile.isFile) null
        else PikoCredentials(account, passwordFile.readText())
    }

    override suspend fun saveCredentials(account: String, password: String) = withContext(Dispatchers.IO) {
        root.mkdirs()
        passwordFile.writeText(password)
    }

    override suspend fun clearCredentials(account: String) = withContext(Dispatchers.IO) {
        if (loadLastAccount() == account) passwordFile.delete()
    }
}
