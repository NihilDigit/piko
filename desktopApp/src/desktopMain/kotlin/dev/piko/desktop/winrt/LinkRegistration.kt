package dev.piko.desktop.winrt

import dev.piko.desktop.winrt.HkcuRegistry as Registry
import java.io.File

/**
 * 打开方式在 HKCU 里的登记与撤销，不通知资源管理器、不打开系统设置（见 [WindowsLinkAssociation]）。
 *
 * 两类键分开对待：
 * - Piko 自己的名字（两个 ProgID、Capabilities、RegisteredApplications 下的一项，见 [ShellIdentity]）照常写，
 *   归属看 ProgID 的打开命令指向哪个 exe，规则见 [WindowsLinkAssociation]。
 * - 协议与扩展名的键（[protocol]、[extension]）是共用的，别的下载工具也写。Piko 只在它们不存在时新建，
 *   并在 vendor 键下记一笔；已存在的不改默认命令与默认值，只在 OpenWithProgids 里并列加上自己的 ProgID。
 *   谁做默认交给系统的默认应用设置与 Capabilities。撤销时只删自己建的、撤掉自己加的。1.1.0 曾直接盖掉
 *   别人的协议命令与扩展名默认值，原来的值已无从找回，不做迁移：命令指向 Piko 的协议键、默认值是 Piko 的
 *   ProgID 的扩展名，都照「是 Piko 的」处理。
 *
 * 协议键靠命令认归属：Piko 建的协议键若被别的应用改了命令，就是那个应用的了，记号不作数。
 * 扩展名键与 OpenWithProgids 没有命令可看，靠记号：只在撤掉自己那一项后变空、且是 Piko 建的时才删。
 * MSI 卸载时的清理（transactional-upgrade.ps1）照同样的规则写，两边一起改。
 *
 * [protocol] 与 [extension] 只在测试里换成不与系统冲突的名字。
 */
internal class LinkRegistration(
    private val identity: ShellIdentity,
    private val protocol: String = "magnet",
    private val extension: String = ".torrent",
) {
    private val progIds = listOf(identity.magnetProgId, identity.torrentProgId)
    private val protocolKey = "$CLASSES\\$protocol"
    private val protocolCommandKey = "$protocolKey\\shell\\open\\command"
    private val extensionKey = "$CLASSES\\$extension"
    private val openWithKey = "$extensionKey\\OpenWithProgids"

    // 值名是 Piko 新建的共用键的路径
    private val createdKeys = "${identity.vendorKey}\\CreatedKeys"

    enum class Owner {
        /** 命令指向正在运行的这一份。 */
        Self,

        /** 指向的 exe 已不存在，或 ProgID 只剩空壳。 */
        Stale,

        /** 指向另一个还在的程序。 */
        Other,
    }

    fun register(exe: File): Boolean {
        val command = "\"${exe.absolutePath}\" \"%1\""
        val icon = "\"${exe.absolutePath}\",0"
        val writes = mutableListOf(
            // 默认应用页里的两个候选
            Registry.setString("$CLASSES\\${identity.magnetProgId}", null, "磁力链接"),
            Registry.setString("$CLASSES\\${identity.magnetProgId}\\DefaultIcon", null, icon),
            Registry.setString("$CLASSES\\${identity.magnetProgId}\\shell\\open\\command", null, command),
            Registry.setString("$CLASSES\\${identity.torrentProgId}", null, "BitTorrent 种子文件"),
            Registry.setString("$CLASSES\\${identity.torrentProgId}\\DefaultIcon", null, icon),
            Registry.setString("$CLASSES\\${identity.torrentProgId}\\shell\\open\\command", null, command),
            // 「打开方式」与默认应用列表里的名字。不写时 Windows 取 exe 的文件描述，而那是按路径缓存的（见 forgetStaleName）
            Registry.setString("$CLASSES\\${identity.magnetProgId}\\Application", "ApplicationName", APP_NAME),
            Registry.setString("$CLASSES\\${identity.torrentProgId}\\Application", "ApplicationName", APP_NAME),
            Registry.setString("$CLASSES\\Applications\\${exe.name}", "FriendlyAppName", APP_NAME),
            Registry.setString(identity.capabilitiesKey, "ApplicationName", APP_NAME),
            Registry.setString(identity.capabilitiesKey, "ApplicationDescription", "PikPak 客户端"),
            Registry.setString("${identity.capabilitiesKey}\\URLAssociations", protocol, identity.magnetProgId),
            Registry.setString("${identity.capabilitiesKey}\\FileAssociations", extension, identity.torrentProgId),
            Registry.setString(REGISTERED_APPLICATIONS, identity.registeredName, identity.capabilitiesKey),
        )
        // 没有 UserChoice 时起作用的登记，只写没人占着的或本来就是 Piko 的。后点的副本盖掉先点的，同 ProgID
        if (!Registry.exists(protocolKey)) writes += markCreated(protocolKey)
        if (protocolIsPikos(exe)) {
            writes += Registry.setString(protocolKey, null, "URL:Magnet Protocol")
            writes += Registry.setString(protocolKey, "URL Protocol", "")
            writes += Registry.setString(protocolCommandKey, null, command)
        }
        if (!Registry.exists(extensionKey)) writes += markCreated(extensionKey)
        // Piko 建的扩展名键，默认值后来被别的应用改了的，也不改回来
        val extensionDefault = Registry.getString(extensionKey, null)
        if (extensionDefault == identity.torrentProgId || extensionDefault == null && isCreated(extensionKey)) {
            writes += Registry.setString(extensionKey, null, identity.torrentProgId)
        }
        // 「打开方式」菜单里列出 Piko，即便扩展名另有默认
        if (!Registry.exists(openWithKey)) writes += markCreated(openWithKey)
        writes += Registry.setEmpty(openWithKey, identity.torrentProgId)
        return writes.all { it }
    }

    /**
     * 删掉属于 [exe] 或已无主的登记，规则见类注释与 [WindowsLinkAssociation]。用户在默认应用里选过 Piko 的，
     * UserChoice 指向的 ProgID 随之没了，系统下次打开时让用户另选。
     */
    fun unregister(exe: File): Boolean {
        val results = removableProgIds(exe).map { Registry.deleteTree("$CLASSES\\$it") }.toMutableList()
        val protocolOwner = Registry.getString(protocolCommandKey, null)?.let { ownerOf(it, exe) }
        // 另一个还在的副本指着它时留给那一份。
        // 删后又冒出一个只有空 URL Protocol 的协议键，不是没删干净：协议有 UserChoice 时，任何解析这个关联的操作
        // （AssocQueryString、浏览器点链接、资源管理器、默认应用设置页）都会让系统补建这个空壳，里面没有命令（实测）
        if (protocolIsPikos(exe) && protocolOwner != Owner.Other) {
            results += Registry.deleteTree(protocolKey)
            results += unmark(protocolKey)
        }
        if (Registry.getString("$CLASSES\\${identity.torrentProgId}", null) == null) {
            results += Registry.deleteValue(openWithKey, identity.torrentProgId)
            results += removeIfCreatedAndEmpty(openWithKey)
            if (Registry.getString(extensionKey, null) == identity.torrentProgId) results += Registry.deleteValue(extensionKey, null)
            results += removeIfCreatedAndEmpty(extensionKey)
        }
        // 另一个还在的副本仍登记着时，它也用这几项
        if (progIds.none { Registry.getString("$CLASSES\\$it", null) != null }) {
            results += Registry.deleteTree("$CLASSES\\Applications\\${exe.name}")
            results += Registry.deleteTree(identity.capabilitiesKey)
            results += Registry.deleteValue(REGISTERED_APPLICATIONS, identity.registeredName)
            // 还留着的记号指的是被别人接手、或里面有了别人东西的键，Piko 已不再管它们
            results += Registry.deleteTree(createdKeys)
            if (Registry.isEmpty(identity.vendorKey)) results += Registry.deleteTree(identity.vendorKey)
        }
        return results.all { it }
    }

    /** 属于 [exe] 或已无主、因而可以由它删掉的 ProgID。 */
    fun removableProgIds(exe: File): List<String> = progIds.filter { progId ->
        Registry.getString("$CLASSES\\$progId", null) != null &&
            ownerOf(Registry.getString("$CLASSES\\$progId\\shell\\open\\command", null), exe) != Owner.Other
    }

    fun ownerOf(command: String?, exe: File): Owner {
        val path = exePathOf(command) ?: return Owner.Stale
        return when {
            path.equals(exe.absolutePath, ignoreCase = true) -> Owner.Self
            File(path).isFile -> Owner.Other
            else -> Owner.Stale
        }
    }

    /**
     * 协议键没人建，或命令指向一个同名的 exe（Piko 的某一份，1.1.0 写的也算）。键存在却没有命令时不算：
     * 那多半是别的应用只留了 URL Protocol 的壳。
     */
    private fun protocolIsPikos(exe: File): Boolean {
        if (!Registry.exists(protocolKey)) return true
        val path = exePathOf(Registry.getString(protocolCommandKey, null)) ?: return false
        return File(path).name.equals(exe.name, ignoreCase = true)
    }

    private fun isCreated(key: String) = Registry.hasValue(createdKeys, key)

    private fun markCreated(key: String) = Registry.setEmpty(createdKeys, key)

    private fun unmark(key: String) = Registry.deleteValue(createdKeys, key)

    private fun removeIfCreatedAndEmpty(key: String): Boolean {
        if (!isCreated(key) || !Registry.isEmpty(key)) return true
        return Registry.deleteTree(key) && unmark(key)
    }

    companion object {
        private const val CLASSES = "Software\\Classes"
        private const val REGISTERED_APPLICATIONS = "Software\\RegisteredApplications"
        const val APP_NAME = "Piko"
    }
}
