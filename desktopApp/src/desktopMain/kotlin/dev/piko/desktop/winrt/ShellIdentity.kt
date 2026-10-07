package dev.piko.desktop.winrt

/**
 * Piko 写进 HKCU 的名字：通知用的 AUMID、打开方式的两个 ProgID、Capabilities 与 RegisteredApplications 下的名字。
 * 全部由 [id] 派生。正式包不传，取 [DEFAULT]，派生出的名字与 1.1.0 写进注册表的逐字相同（用户的 UserChoice 与
 * 通知设置都认这些名字，不能变）；测试包由 build.gradle.kts 以 -Dpiko.shell-id 传入包名。只隔开 MSI 产品
 * （UpgradeCode）不够：本机跑安装冒烟时，测试包会把装着的 Piko 的通知登记改成指向自己，卸载时再整个删掉。
 * MSI 卸载时的清理（transactional-upgrade.ps1）按同一规则派生，名字两边要一起改。
 *
 * magnet 协议与 .torrent 扩展名本身不在其中：它们是共用的键，归属规则见 [LinkRegistration]。
 */
internal class ShellIdentity(val id: String) {
    val appUserModelId = "dev.piko.$id"
    val magnetProgId = "$id.Magnet"
    val torrentProgId = "$id.Torrent"
    val vendorKey = "Software\\$id"
    val capabilitiesKey = "$vendorKey\\Capabilities"
    val registeredName = id

    companion object {
        const val PROPERTY = "piko.shell-id"
        const val DEFAULT = "Piko"

        val current = ShellIdentity(System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT)
    }
}
