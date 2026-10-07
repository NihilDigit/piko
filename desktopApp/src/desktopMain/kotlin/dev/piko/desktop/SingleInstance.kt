package dev.piko.desktop

import dev.piko.shared.PikoHome
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
import kotlin.system.exitProcess

/**
 * 同一用户只跑一个 Piko。磁力链接经协议唤起时每次都会新起一个进程，后来者把参数转交给
 * 已在运行的实例后退出；两个进程同时写 settings.properties 与会话文件也会互相覆盖。
 *
 * 谁是主实例由文件锁决定，进程崩溃时系统释放锁，不会有残留的「已在运行」。消息走 Unix
 * domain socket（Windows 10 起支持）：不占端口，也不会被防火墙拦下。正常退出时删掉 socket 文件；
 * 崩溃后会残留，但只有拿到锁的一方会删掉旧文件重新绑定，后来者只连不绑。
 *
 * 便携版的数据目录可能被同一台机器上的另一个 Windows 用户同时打开：锁在共用的数据目录里，socket 却在各自的临时目录里，
 * 后来者连不上，以前就这样悄无声息地退出。主实例因此把自己的用户名记在 [OWNER_FILE]，后来者转交失败时据此说明原因。
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
        // 不写进 instance.lock：Windows 的文件锁是强制锁，别的进程读不了被锁住的区域
        private const val OWNER_FILE = "instance.owner"
        private val directory = PikoHome.root.toFile()
        private val socketFile = PikoHome.instanceSocket.toFile()
        private val currentUser: String = System.getProperty("user.name").orEmpty()

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
                if (!forward(args)) explainForwardFailure()
                return null
            }
            runCatching { directory.resolve(OWNER_FILE).writeText(currentUser) }
            val server = runCatching {
                socketFile.delete()
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    .apply { bind(UnixDomainSocketAddress.of(socketFile.toPath())) }
            }.onFailure { PikoLog.w(TAG, "无法监听实例 socket，后来的启动参数收不到", it) }.getOrNull()
            // 退出钩子在锁释放之前跑，此时不会有新的主实例已经绑上同一个路径
            if (server != null) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    runCatching { server.close() }
                    socketFile.delete()
                })
            }
            return SingleInstance(lock, server)
        }

        /**
         * 主实例刚拿到锁、还没绑好 socket 时后来者就可能连过来，所以连不上时短暂重试。
         * 转交前放开前台权限：Windows 只允许前台进程把别的窗口提到前面，此刻前台是刚被
         * 用户唤起的这个进程，主实例自己调 toFront 只会让任务栏图标闪烁。
         */
        private fun forward(args: List<String>): Boolean {
            allowAnyForeground()
            repeat(20) {
                val sent = runCatching {
                    SocketChannel.open(UnixDomainSocketAddress.of(socketFile.toPath())).use { writeArgs(it, args) }
                }.isSuccess
                if (sent) return true
                Thread.sleep(100)
            }
            PikoLog.w(TAG, "已有实例在运行，但转交启动参数失败")
            return false
        }

        /**
         * 占着锁的是另一个用户时告诉用户为什么打不开；同一用户转交失败照旧只记日志。
         * 冒烟与自测无人值守，模态框会把进程卡住，还会弹到真人桌面上，所以只记日志、以非零码退出。
         */
        private fun explainForwardFailure() {
            val owner = runCatching { directory.resolve(OWNER_FILE).readText().trim() }.getOrNull()
            if (owner.isNullOrEmpty() || owner.equals(currentUser, ignoreCase = true)) return
            PikoLog.w(TAG, "数据目录正由另一个系统用户使用")
            if (isUnattended()) exitProcess(EXIT_HELD_BY_OTHER_USER)
            runCatching {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "数据目录 ${PikoHome.root} 正由 Windows 账户 $owner 中运行的 Piko 使用，当前账户为 $currentUser。" +
                        "请先在 $owner 中退出 Piko，或为当前账户另用一份便携版。",
                    "Piko 无法打开",
                    javax.swing.JOptionPane.WARNING_MESSAGE,
                )
            }
        }

        // 安装冒烟经 piko.update.api 指向假的 Release，自测带 piko.selftest
        private fun isUnattended(): Boolean =
            System.getProperty("piko.update.api") != null || System.getProperty(SELF_TEST_PROPERTY) != null ||
                java.awt.GraphicsEnvironment.isHeadless()

        private const val EXIT_HELD_BY_OTHER_USER = 3

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
