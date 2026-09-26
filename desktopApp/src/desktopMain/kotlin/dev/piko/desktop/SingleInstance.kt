package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_INT
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption

/**
 * 同一用户只跑一个 Piko。磁力链接经协议唤起时每次都会新起一个进程，后来者把参数转交给
 * 已在运行的实例后退出；两个进程同时写 settings.properties 与会话文件也会互相覆盖。
 *
 * 谁是主实例由文件锁决定，进程崩溃时系统释放锁，不会有残留的「已在运行」。消息走 Unix
 * domain socket（Windows 10 起支持）：不占端口，也不会被防火墙拦下。socket 文件崩溃后会
 * 残留，但只有拿到锁的一方会删掉旧文件重新绑定，后来者只连不绑。
 */
class SingleInstance private constructor(
    // 只为持有：锁对象被回收时锁随之释放
    @Suppress("unused") private val lock: FileLock?,
    private val server: ServerSocketChannel?,
) {
    /** 在后台线程上接收后来者的启动参数，每次连接回调一次 [onArgs]。 */
    fun listen(onArgs: (List<String>) -> Unit) {
        val server = server ?: return
        Thread(
            {
                while (server.isOpen) {
                    val args = runCatching { server.accept().use(::readArgs) }.getOrNull() ?: continue
                    onArgs(args)
                }
            },
            "Piko-Single-Instance",
        ).apply { isDaemon = true; start() }
    }

    companion object {
        private const val TAG = "SingleInstance"
        private val directory = File(System.getProperty("user.home"), ".piko")
        private val socketFile = directory.resolve("instance.sock")

        /**
         * 成为主实例则返回它；已有实例在运行时把 [args] 转交过去并返回 null，调用方应直接退出。
         * 锁都拿不到（目录不可写之类）时也按主实例启动，宁可多开也不能打不开。
         */
        fun acquireOrForward(args: List<String>): SingleInstance? {
            directory.mkdirs()
            val channel = runCatching {
                FileChannel.open(directory.resolve("instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }.getOrElse {
                PikoLog.w(TAG, "无法创建实例锁，按独立实例启动", it)
                return SingleInstance(lock = null, server = null)
            }
            // 同一 JVM 里重复加锁抛 OverlappingFileLockException，别的进程持有时返回 null
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                channel.close()
                forward(args)
                return null
            }
            val server = runCatching {
                socketFile.delete()
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    .apply { bind(UnixDomainSocketAddress.of(socketFile.toPath())) }
            }.onFailure { PikoLog.w(TAG, "无法监听实例 socket，后来的启动参数收不到", it) }.getOrNull()
            return SingleInstance(lock, server)
        }

        /**
         * 主实例刚拿到锁、还没绑好 socket 时后来者就可能连过来，所以连不上时短暂重试。
         * 转交前放开前台权限：Windows 只允许前台进程把别的窗口提到前面，此刻前台是刚被
         * 用户唤起的这个进程，主实例自己调 toFront 只会让任务栏图标闪烁。
         */
        private fun forward(args: List<String>) {
            allowAnyForeground()
            repeat(20) {
                val sent = runCatching {
                    SocketChannel.open(UnixDomainSocketAddress.of(socketFile.toPath())).use { writeArgs(it, args) }
                }.isSuccess
                if (sent) return
                Thread.sleep(100)
            }
            PikoLog.w(TAG, "已有实例在运行，但转交启动参数失败")
        }

        private fun writeArgs(channel: SocketChannel, args: List<String>) {
            val bytes = args.joinToString("\u0000").toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).flip()
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun readArgs(channel: SocketChannel): List<String> {
            val header = ByteBuffer.allocate(4)
            while (header.hasRemaining()) if (channel.read(header) < 0) return emptyList()
            val body = ByteBuffer.allocate(header.flip().int.coerceIn(0, 1 shl 20))
            while (body.hasRemaining()) if (channel.read(body) < 0) break
            val text = String(body.array(), 0, body.position(), Charsets.UTF_8)
            return if (text.isEmpty()) emptyList() else text.split('\u0000')
        }

        private fun allowAnyForeground() {
            if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
            runCatching {
                val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
                val handle = Linker.nativeLinker().downcallHandle(
                    user32.find("AllowSetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT),
                )
                handle.invokeWithArguments(ASFW_ANY)
            }
        }

        private const val ASFW_ANY = -1
    }
}
