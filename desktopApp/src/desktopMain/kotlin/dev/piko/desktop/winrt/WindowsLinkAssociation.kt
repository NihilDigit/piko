package dev.piko.desktop.winrt

import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import dev.piko.desktop.winrt.HkcuRegistry as Registry
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 Piko 登记为 magnet: 链接与 .torrent 文件的打开方式，全部写在 HKCU，无需管理员权限。
 *
 * 用户在「默认应用」里为某个协议或扩展名选过应用后，系统按 UserChoice 打开，那一项带着系统算的哈希，
 * 应用自己写进去会被判为篡改而作废，所以默认只能由用户选。这里做两件事：按 RegisteredApplications
 * 的约定登记 Capabilities，Piko 才会出现在默认应用的候选里，并有自己的一页；Classes 下的 magnet 与 .torrent
 * 没有别的应用占着时由 Piko 建立，没人选过时直接生效（见 [LinkRegistration]）。写完打开 Piko 那一页，由用户逐项选定。
 *
 * 只由用户明确同意时写入（设置页或首次启动的询问，见 LinkAssociationPrompt），不在启动时静默写：
 * 那会每次启动都抢走别的下载工具的协议。安装版与便携版都能登记，登记的是点下去的那一份的路径，
 * 后点的盖掉先点的：只登记安装版的话，只用便携版的人无从接管。便携版挪了位置，旧登记指向的 exe 已不存在，
 * state 报 Registered（只登记、不是默认），再点一次「设为默认」即改指过来。gradle run 没有 jpackage 启动器（进程是 java.exe），不给登记。
 *
 * 两个 ProgID 的名字随 1.1.0 发版，用户的 UserChoice 指着它们，所以各副本共用这一份，不按副本另起名字
 * （测试包另有一套，见 [ShellIdentity]）。
 * 归属看 ProgID 的打开命令指向哪个 exe：指向自己的算自己的；指向的 exe 已不存在（挪了位置、删了目录、
 * 卸载了）算无主，谁都可以清掉；指向另一个还在的副本时一概不碰。Capabilities 一类不带路径的登记，
 * 等两个 ProgID 都不在了才删。不在启动时自动清无主登记：便携版放在 U 盘上，拔下时 exe 也不存在。
 */
internal object WindowsLinkAssociation : LinkAssociation {
    private const val TAG = "LinkAssociation"
    private val identity = ShellIdentity.current
    private val registration = LinkRegistration(identity)
    private const val APP_NAME = LinkRegistration.APP_NAME
    private const val MUI_CACHE = "Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\Shell\\MuiCache"

    override val needsSystemConfirmation: Boolean = true
    override val canUnregister: Boolean = true

    /** jpackage 的启动器，安装版与便携版都有；jpackage 会另起一个 JVM 子进程，进程命令行未必是 Piko.exe。 */
    private fun launcher(): File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    override suspend fun state(): LinkAssociationState = withContext(Dispatchers.IO) {
        val exe = launcher() ?: return@withContext LinkAssociationState.Unavailable
        // 问系统实际会用哪条命令打开，而不是自己读 UserChoice：AssocQueryString 与资源管理器同一套判定，
        // 较新的 Windows 还多了一个 UserChoiceLatest，自己读容易漏
        val opensWithPiko = listOf(
            runCatching { openCommandOf("magnet", isProtocol = true) }.getOrNull(),
            runCatching { openCommandOf(".torrent") }.getOrNull(),
        ).all { it != null && registration.ownerOf(it, exe) == LinkRegistration.Owner.Self }
        when {
            opensWithPiko -> LinkAssociationState.Default
            runCatching { registration.removableProgIds(exe).isNotEmpty() }.getOrDefault(false) -> LinkAssociationState.Registered
            else -> LinkAssociationState.NotDefault
        }
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        if (!writeAndNotify()) return@withContext false
        // Windows 11 打开 Piko 自己的默认应用页；不认这个参数的系统落在默认应用首页
        runCatching { ProcessBuilder("cmd", "/c", "start", "", "ms-settings:defaultapps?registeredAppUser=${identity.registeredName}").start() }
            .onFailure { PikoLog.w(TAG, "打开默认应用设置失败", it) }
            .isSuccess
    }

    /**
     * 只写登记、不打开系统设置。自检（见 SelfTest）用：CI 上没人去点，而全新的 runner 没有 UserChoice，
     * Classes 下的登记当场生效，正好验证系统真会按它打开。
     */
    fun writeAndNotify(): Boolean {
        val exe = launcher() ?: return false
        val registered = runCatching { registration.register(exe).also { forgetStaleName(exe) } }
            .onFailure { PikoLog.w(TAG, "登记打开方式失败", it) }
            .getOrDefault(false)
        if (registered) notifyAssociationsChanged()
        return registered
    }

    /** 删掉属于这一份或已无主的登记，规则见类注释与 [LinkRegistration]。 */
    override suspend fun unregister(): Boolean = withContext(Dispatchers.IO) {
        val exe = launcher() ?: return@withContext false
        val removed = runCatching { registration.unregister(exe) }
            .onFailure { PikoLog.w(TAG, "取消关联失败", it) }
            .getOrDefault(false)
        notifyAssociationsChanged()
        removed
    }

    /**
     * 资源管理器把 exe 的文件描述按路径缓存在 MuiCache 里，exe 换了也不刷新。1.1.0 之前的 exe 描述是一句英文简介，
     * 从那时装上来的人在「打开方式」里看到的一直是那句简介而不是 Piko。只删这个 exe 自己的那几项，
     * 值已是 Piko 时不动；删掉后系统下次按新 exe 重新生成。启动时调一次，没登记过关联的人也不会看到旧名。
     */
    fun forgetStaleName(exe: File? = launcher()) {
        exe ?: return
        runCatching {
            val prefix = exe.absolutePath
            val cached = Registry.getString(MUI_CACHE, "$prefix.FriendlyAppName")
            if (cached != null && cached != APP_NAME) {
                Registry.deleteValue(MUI_CACHE, "$prefix.FriendlyAppName")
                Registry.deleteValue(MUI_CACHE, "$prefix.ApplicationCompany")
                PikoLog.i(TAG, "已清除过期的应用名缓存")
            }
        }.onFailure { PikoLog.w(TAG, "清除应用名缓存失败", it) }
    }

    // 资源管理器缓存了关联与图标，不通知的话 .torrent 文件要到下次登录才换成 Piko 的图标
    private const val SHCNE_ASSOCCHANGED = 0x08000000
    private const val SHCNF_IDLIST = 0

    private fun notifyAssociationsChanged() {
        runCatching {
            val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
            // void SHChangeNotify(LONG wEventId, UINT uFlags, LPCVOID dwItem1, LPCVOID dwItem2)
            Linker.nativeLinker().downcallHandle(
                shell32.find("SHChangeNotify").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
            ).invokeWithArguments(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, MemorySegment.NULL, MemorySegment.NULL)
        }
    }
}

/**
 * 打开命令里的程序路径：带引号时取引号里的，否则取第一个空格之前的。Piko 写的是带引号的形式，
 * 别的应用写的也大多如此。取不出时为 null。
 */
internal fun exePathOf(command: String?): String? {
    val text = command?.trim().orEmpty()
    val path = if (text.startsWith('"')) text.substring(1).substringBefore('"') else text.substringBefore(' ')
    return path.takeIf { it.isNotBlank() }
}
