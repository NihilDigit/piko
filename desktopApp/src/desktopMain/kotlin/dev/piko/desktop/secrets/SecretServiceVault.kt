package dev.piko.desktop.secrets

import dev.piko.shared.auth.SecretVault
import dev.piko.shared.auth.VaultUnavailableException
import dev.piko.shared.log.PikoLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * freedesktop Secret Service（gnome-keyring、KWallet、KeePassXC 都提供）里的密码项，经 FFM 调 libsecret。
 *
 * 不用 secret-tool：它在 libsecret-tools 包里，多数发行版默认不装；libsecret 本身是桌面环境的常备依赖，
 * 在 Flatpak 沙箱里还会自动改走 Secret portal。机密是文本，存 Base64。
 *
 * 用 [open] 取得：库加载不了或会话总线上没有这项服务时返回 null，交给调用方退回明文文件。
 * 之后的调用出错（钥匙串锁着、解锁被拒）一律抛 [VaultUnavailableException]，当作暂时不可用。
 */
internal class SecretServiceVault private constructor(private val lib: LibSecret) : SecretVault {
    override fun read(key: String): ByteArray? {
        val text = lib.lookup(key) ?: return null
        return runCatching { Base64.getDecoder().decode(text) }
            .onFailure { PikoLog.w(TAG, "Secret Service 里的凭据不是本应用写入的格式，当作没有") }
            .getOrNull()
    }

    override fun write(key: String, data: ByteArray) = lib.store(key, Base64.getEncoder().encodeToString(data))

    override fun delete(key: String) = lib.clear(key)

    companion object {
        private const val TAG = "credentials"

        fun open(timeoutSeconds: Long = 120): SecretServiceVault? {
            val lib = try {
                LibSecret(timeoutSeconds)
            } catch (e: Throwable) {
                PikoLog.i(TAG, "加载不了 libsecret，凭据存明文文件：${e.javaClass.simpleName}")
                return null
            }
            // 查一个不存在的项试探服务在不在。没有匹配项时 libsecret 不去解锁，不会弹解锁框
            return try {
                lib.lookup(PROBE_KEY)
                SecretServiceVault(lib)
            } catch (e: VaultUnavailableException) {
                PikoLog.i(TAG, "会话中没有 Secret Service，凭据存明文文件：${e.message}")
                null
            }
        }

        private const val PROBE_KEY = "probe"
    }
}

/**
 * libsecret 的三个同步接口。它们都是变参函数，属性以名、值成对跟在固定参数后面，以 NULL 结尾。
 *
 * 每次调用带一个 GCancellable，[timeoutSeconds] 后取消。钥匙串锁着时同步调用要等用户在解锁框里作答，
 * 而解锁框弹不出来时（没有显示器、gcr-prompter 连不上总线）它永远不返回，WSL 上实测如此；
 * 取消后调用以 G_IO_ERROR_CANCELLED 返回，当作暂时不可用。
 */
internal class LibSecret(private val timeoutSeconds: Long) {
    private val linker = Linker.nativeLinker()
    private val libsecret = SymbolLookup.libraryLookup("libsecret-1.so.0", Arena.global())
    private val glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global())
    private val gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", Arena.global())
    private val gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global())

    private val cancellableNew = linker.downcallHandle(gio.find("g_cancellable_new").orElseThrow(), FunctionDescriptor.of(ADDRESS))
    private val cancellableCancel = linker.downcallHandle(gio.find("g_cancellable_cancel").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
    private val objectUnref = linker.downcallHandle(gobject.find("g_object_unref").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))

    // 属性对：service、值、key、值、NULL
    private val attributeArgs = arrayOf(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)

    // SecretSchema *secret_schema_new(const gchar *name, SecretSchemaFlags flags, ...)，变参是「属性名、类型」对，以 NULL 结尾
    private val schemaNew = downcall(
        libsecret, "secret_schema_new",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), 2,
    )

    // gboolean secret_password_store_sync(schema, collection, label, password, cancellable, GError **error, ...)
    private val storeSync = downcall(
        libsecret, "secret_password_store_sync",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, *attributeArgs), 6,
    )

    // gchar *secret_password_lookup_sync(schema, cancellable, GError **error, ...)
    private val lookupSync = downcall(
        libsecret, "secret_password_lookup_sync",
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, *attributeArgs), 3,
    )

    // gboolean secret_password_clear_sync(schema, cancellable, GError **error, ...)
    private val clearSync = downcall(
        libsecret, "secret_password_clear_sync",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, *attributeArgs), 3,
    )

    // secret_password_free 在释放前先把内存清零，比 g_free 合适
    private val passwordFree = linker.downcallHandle(libsecret.find("secret_password_free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
    private val errorFree = linker.downcallHandle(glib.find("g_error_free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))

    // 进程内只建一次，从不释放
    private val schema: MemorySegment = Arena.global().let { arena ->
        schemaNew.invokeWithArguments(
            arena.allocateFrom(SCHEMA_NAME), SECRET_SCHEMA_NONE,
            arena.allocateFrom(ATTRIBUTE_SERVICE), SECRET_SCHEMA_ATTRIBUTE_STRING,
            arena.allocateFrom(ATTRIBUTE_KEY), SECRET_SCHEMA_ATTRIBUTE_STRING,
            MemorySegment.NULL,
        ) as MemorySegment
    }

    fun lookup(key: String): String? = call { arena, cancellable, error ->
        val result = lookupSync.invokeWithArguments(schema, cancellable, error, *attributes(arena, key)) as MemorySegment
        throwIfFailed(error, "secret_password_lookup_sync")
        if (result == MemorySegment.NULL) return@call null
        try {
            result.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            passwordFree.invokeWithArguments(result)
        }
    }

    fun store(key: String, password: String) = call { arena, cancellable, error ->
        val ok = storeSync.invokeWithArguments(
            schema, MemorySegment.NULL, arena.allocateFrom(LABEL), arena.allocateFrom(password), cancellable, error,
            *attributes(arena, key),
        ) as Int
        throwIfFailed(error, "secret_password_store_sync")
        if (ok == 0) throw VaultUnavailableException("secret_password_store_sync 失败")
    }

    // 没有匹配项时返回 FALSE 且不设 GError，与删掉了同样看待
    fun clear(key: String) = call { arena, cancellable, error ->
        clearSync.invokeWithArguments(schema, cancellable, error, *attributes(arena, key))
        throwIfFailed(error, "secret_password_clear_sync")
    }

    private fun <T> call(block: (arena: Arena, cancellable: MemorySegment, error: MemorySegment) -> T): T =
        Arena.ofConfined().use { arena ->
            val cancellable = cancellableNew.invokeWithArguments() as MemorySegment
            // 定时器与调用结束交错时，必须保证 unref 之后不再 cancel
            val guard = Any()
            var finished = false
            val timer = timeouts.schedule({
                synchronized(guard) { if (!finished) cancellableCancel.invokeWithArguments(cancellable) }
            }, timeoutSeconds, TimeUnit.SECONDS)
            try {
                block(arena, cancellable, arena.allocate(ADDRESS))
            } finally {
                synchronized(guard) { finished = true }
                timer.cancel(false)
                objectUnref.invokeWithArguments(cancellable)
            }
        }

    private fun attributes(arena: Arena, key: String): Array<Any> = arrayOf(
        arena.allocateFrom(ATTRIBUTE_SERVICE), arena.allocateFrom(SERVICE),
        arena.allocateFrom(ATTRIBUTE_KEY), arena.allocateFrom(key),
        MemorySegment.NULL,
    )

    // GError：GQuark domain（guint32）、gint code、gchar *message
    private fun throwIfFailed(error: MemorySegment, what: String) {
        val gError = error.get(ADDRESS, 0)
        if (gError == MemorySegment.NULL) return
        val struct = gError.reinterpret(16)
        val code = struct.get(JAVA_INT, 4)
        val message = struct.get(ADDRESS, 8).let { if (it == MemorySegment.NULL) "" else it.reinterpret(Long.MAX_VALUE).getString(0) }
        errorFree.invokeWithArguments(gError)
        throw VaultUnavailableException("$what 失败（$code）：$message")
    }

    private fun downcall(library: SymbolLookup, name: String, descriptor: FunctionDescriptor, firstVariadic: Int): MethodHandle =
        linker.downcallHandle(library.find(name).orElseThrow(), descriptor, Linker.Option.firstVariadicArg(firstVariadic))

    private companion object {
        val timeouts: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "secret-service-timeout").apply { isDaemon = true }
        }

        const val SCHEMA_NAME = "dev.nihildigit.Piko.Credentials"
        const val SERVICE = "dev.nihildigit.Piko"
        const val ATTRIBUTE_SERVICE = "service"
        const val ATTRIBUTE_KEY = "key"

        // 钥匙串管理器（Seahorse 一类）里显示的名字
        const val LABEL = "Piko 登录凭据"
        const val SECRET_SCHEMA_NONE = 0
        const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0
    }
}
