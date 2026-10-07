package dev.piko.shared

import java.nio.file.Files
import java.nio.file.Path

/**
 * 桌面端的数据根目录：账号与机密、设置、缓存、日志、下载缓存与单实例锁都在它下面。各处一律经它取，不再自己拼 user.home。
 *
 * 依次取：
 * 1. 系统属性 `piko.home`。`:desktopApp:run` 经它把开发版放进 `~/.piko-dev`：开发版与安装版原来共用 `~/.piko`，
 *    一边刷新了 PikPak 的 refresh token 写回共享的会话文件，另一边手里的旧令牌在服务端作废，又没有跨进程锁，
 *    两边轮流把对方踢回登录页。
 * 2. 便携版，只在 Windows：程序目录里有 [PORTABLE_MARKER] 文件时，数据放在程序目录的 `data` 下。zip 包由 release.yml
 *    放进这个文件，安装包不放。程序目录取 jpackage 启动器给的 `jpackage.app-path`（exe 的路径），`:desktopApp:run` 与测试
 *    没有这个属性，不会误判。macOS 不做：exe 在 .app 的 Contents/MacOS 里，标记放进去会破坏签名，整包更新又会连 data 一起换掉；
 *    Linux 的 AppImage 挂载目录只读，解开的 tar.gz 程序目录是 bin/，都不合适。`data` 建不出或写不进（解压在 Program Files 下）时
 *    退回 `~/.piko`，见 [portableFallback]。机密由 DPAPI 按当前 Windows 用户加密，拷到别的机器或换个用户要重新登录，其余数据照旧。
 *    不按 Windows 用户分目录：便携版常随 U 盘换机器用，换一台机器 SID 就变，分了目录设置与缓存便跟不过去；
 *    两个用户同时开同一份数据由单实例锁挡下，见 SingleInstance。
 * 3. `~/.piko`，与旧版相同，已安装的用户升级后照旧读得到原来的数据。
 *
 * 下载目录的默认值（`~/Downloads/Piko`）不跟着走：那是用户的文件，不是程序的数据。
 */
object PikoHome {
    private class Resolved(val root: Path, val isDefault: Boolean, val portableFallback: PortableFallback? = null)

    /** 便携目录 [portableData] 写不进，数据改放在了 [root]。[problem] 是写入失败的异常，可能带路径，不进日志。 */
    class PortableFallback(val portableData: Path, val problem: Throwable)

    private val resolved: Resolved by lazy { resolve() }

    val root: Path get() = resolved.root

    /** 便携版却用不了程序目录、退回了 `~/.piko`。解析时日志与界面都还没起来，由入口记日志并提示用户。 */
    val portableFallback: PortableFallback? get() = resolved.portableFallback

    /** 便携版的标记文件名，放在 Piko.exe 旁边。 */
    const val PORTABLE_MARKER = "portable"

    /**
     * 系统钥匙串里条目的命名空间。Windows 的机密是根目录下的文件，换了根目录自然分开；macOS 钥匙串与 Linux Secret Service
     * 按固定的 service 名存，不跟目录走，开发版与安装版还是同一份。走的是默认那一支时为空串，旧版存下的条目照旧读得到；
     * 其余取根目录绝对路径的摘要。按走了哪一支判断，不比较路径：`piko.home` 指向 `~/.piko` 的另一种写法时也不会误判成默认。
     */
    val secretNamespace: String by lazy {
        if (resolved.isDefault) "" else "@" + Integer.toHexString(root.toString().hashCode())
    }

    /**
     * 单实例的 Unix domain socket。默认根目录照旧放在它下面；其余放进临时目录、以命名空间区分：便携目录往往很深，
     * socket 路径超过 AF_UNIX 约 108 字节的上限时绑定失败，后来者转交不了启动参数（磁力链接）。
     */
    val instanceSocket: Path
        get() = if (resolved.isDefault) root.resolve("instance.sock")
        else Path.of(System.getProperty("java.io.tmpdir"), "piko$secretNamespace.sock")

    private fun resolve(): Resolved {
        System.getProperty("piko.home")?.takeIf { it.isNotBlank() }?.let {
            return Resolved(Path.of(it).toAbsolutePath().normalize(), isDefault = false)
        }
        val default = Path.of(System.getProperty("user.home"), ".piko")
        val portable = portableRoot() ?: return Resolved(default, isDefault = true)
        val problem = writeProblem(portable) ?: return Resolved(portable, isDefault = false)
        return Resolved(default, isDefault = true, portableFallback = PortableFallback(portable, problem))
    }

    private fun portableRoot(): Path? {
        if (!System.getProperty("os.name").startsWith("Windows")) return null
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return null
        val appDirectory = Path.of(launcher).toAbsolutePath().normalize().parent ?: return null
        return appDirectory.resolve("data").takeIf { Files.exists(appDirectory.resolve(PORTABLE_MARKER)) }
    }

    /** 建得出目录、写得进文件时为 null。 */
    private fun writeProblem(directory: Path): Throwable? = runCatching {
        Files.createDirectories(directory)
        val probe = Files.createTempFile(directory, ".write-probe", null)
        Files.delete(probe)
    }.exceptionOrNull()
}
