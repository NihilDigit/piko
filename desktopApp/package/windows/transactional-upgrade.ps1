# 打包后改写 jpackage 生成的 MSI：把 RemoveExistingProducts 挪进安装事务；卸载时连增量更新换进来的文件一起删；
# 开始菜单快捷方式带上 AUMID；卸载时撤掉这一份写的打开方式与通知登记；卸载条目补上项目链接。各段说明在段前。
#
# jpackage 生成的 MSI 把它排在 InstallInitialize 之前，卸载旧版单独提交：新版随后装失败时，
# 旧版已经没了，应用内更新拉起的只是一个空目录。排在 InstallInitialize 之后，卸载与安装同属
# 一个事务，任一步失败都整体回滚，旧版原样留下。这正是 WiX MajorUpgrade 的
# afterInstallInitialize 排法；卸载仍在装新文件之前，组件规则上与原来没有区别。
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    # 写进 HKCU 的名字由它派生，与应用里的 ShellIdentity 同一规则：正式包是 Piko，测试包是它的包名。
    # 不叫 ShellId：那是 PowerShell 的只读自动变量
    [string]$Identity = 'Piko'
)
$ErrorActionPreference = 'Stop'

$installer = New-Object -ComObject WindowsInstaller.Installer
# 1 = msiOpenDatabaseModeTransact：改动在 Commit 之前不落盘
$db = $installer.OpenDatabase($Msi, 1)

function Invoke-Query([string]$sql) {
    $view = $db.OpenView($sql)
    # 不丢弃的话 Execute 与 Close 的返回值也混进函数输出，拿到的是数组而不是那条记录
    [void]$view.Execute()
    $record = $view.Fetch()
    [void]$view.Close()
    return $record
}
function Invoke-Update([string]$sql) {
    $view = $db.OpenView($sql)
    [void]$view.Execute()
    [void]$view.Close()
}
function Get-Sequence([string]$action) {
    $record = Invoke-Query "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action`` = '$action'"
    if ($null -eq $record) { throw "InstallExecuteSequence 里没有 $action" }
    return [int]$record.GetType().InvokeMember('IntegerData', 'GetProperty', $null, $record, @(1))
}

function Get-String($record, [int]$field) {
    return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @($field))
}

$target = (Get-Sequence 'InstallInitialize') + 1
if ((Get-Sequence 'RemoveExistingProducts') -eq $target) {
    Write-Output "RemoveExistingProducts already at $target"
} else {
    $occupied = Invoke-Query "SELECT ``Action`` FROM ``InstallExecuteSequence`` WHERE ``Sequence`` = $target"
    if ($null -ne $occupied) { throw "序号 $target 已被占用，jpackage 的模板可能变了" }
    Invoke-Update "UPDATE ``InstallExecuteSequence`` SET ``Sequence`` = $target WHERE ``Action`` = 'RemoveExistingProducts'"
    Write-Output "RemoveExistingProducts -> $target"
}

# jpackage 登记了一条 RemoveFolderEx，卸载时递归删掉整个安装目录，连用户放在里面的文件一起删（卸载 1.1.0 实测，
# 安装根下的种子与压缩包都没了）。它无条件执行，整包升级时卸旧版那一步同样会删。改成只递归删 app 与 runtime：
# 两处只放应用自己的文件，增量更新换进来的、不在 MSI 文件表里的文件（改了名的 jar、.jpackage.xml、新增的库）也都在这里，
# 卸载时一并清掉；安装根下 MSI 自己装的只有启动器，按文件表删。
# RemoveFolderEx 在卸载会话开头运行，那时还没算出 INSTALLDIR，路径取自安装时写进注册表、经 AppSearch 读回的属性，
# 这里由 51 类动作在它之后、WixRemoveFoldersEx 之前把两个子目录拼出来。属性为空（首次安装）时不拼，
# 否则拼出的相对路径会落在当前目录。
$installDirProperty = $null
$view = $db.OpenView("SELECT ``Property`` FROM ``AppSearch``")
[void]$view.Execute()
while ($null -ne ($row = $view.Fetch())) {
    if ((Get-String $row 1).StartsWith('RM_RF')) { $installDirProperty = Get-String $row 1 }
}
[void]$view.Close()
if (-not $installDirProperty) { throw "AppSearch 里没有 RemoveFolderEx 的安装目录属性，jpackage 的模板可能变了" }
$folderEx = Invoke-Query "SELECT ``WixRemoveFolderEx``, ``Component_`` FROM ``WixRemoveFolderEx`` WHERE ``Property`` = '$installDirProperty'"
if ($null -ne $folderEx) {
    $folderExId = Get-String $folderEx 1
    $folderExComponent = Get-String $folderEx 2
    $removeFolders = (Get-Sequence 'WixRemoveFoldersEx')
    $subfolders = @(@('PIKO_RM_RF_APP', 'app'), @('PIKO_RM_RF_RUNTIME', 'runtime'))
    for ($i = 0; $i -lt $subfolders.Count; $i++) {
        $property = $subfolders[$i][0]
        $sequence = $removeFolders - $subfolders.Count + $i
        if ($null -ne (Invoke-Query "SELECT ``Action`` FROM ``InstallExecuteSequence`` WHERE ``Sequence`` = $sequence")) { throw "序号 $sequence 已被占用，jpackage 的模板可能变了" }
        Invoke-Update "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('Set$property', 51, '$property', '[$installDirProperty]$($subfolders[$i][1])\')"
        Invoke-Update "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('Set$property', '$installDirProperty', $sequence)"
        Invoke-Update "INSERT INTO ``WixRemoveFolderEx`` (``WixRemoveFolderEx``, ``Component_``, ``Property``, ``InstallMode``) VALUES ('Piko$property', '$folderExComponent', '$property', 2)"
    }
    Invoke-Update "DELETE FROM ``WixRemoveFolderEx`` WHERE ``WixRemoveFolderEx`` = '$folderExId'"
    Write-Output "RemoveFolderEx narrowed from [$installDirProperty] to its app and runtime folders"
}

# 快捷方式带上与进程相同的 AUMID（ShellIdentity.appUserModelId）。不带时任务栏把固定的快捷方式与运行中的
# 窗口当成两个应用，固定后再打开会多出一个按钮。键是 System.AppUserModel.ID 的 PROPERTYKEY
$aumidKey = '{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3} 5'
$aumid = "dev.piko.$Identity"
$magnetProgId = "$Identity.Magnet"
$torrentProgId = "$Identity.Torrent"
$vendorKey = "Software\$Identity"
if ($null -eq (Invoke-Query "SELECT ``Name`` FROM ``_Tables`` WHERE ``Name`` = 'MsiShortcutProperty'")) {
    Invoke-Update 'CREATE TABLE `MsiShortcutProperty` (`MsiShortcutProperty` CHAR(72) NOT NULL, `Shortcut_` CHAR(72) NOT NULL, `PropertyKey` CHAR(0) NOT NULL, `PropVariantValue` CHAR(0) NOT NULL PRIMARY KEY `MsiShortcutProperty`)'
}
$shortcuts = @()
$view = $db.OpenView("SELECT ``Shortcut`` FROM ``Shortcut``")
[void]$view.Execute()
while ($null -ne ($row = $view.Fetch())) { $shortcuts += Get-String $row 1 }
[void]$view.Close()
if ($shortcuts.Count -eq 0) { throw "Shortcut 表是空的，jpackage 的模板可能变了" }
$index = 0
foreach ($shortcut in $shortcuts) {
    $id = "PikoAumid$index"
    $index++
    if ($null -eq (Invoke-Query "SELECT ``MsiShortcutProperty`` FROM ``MsiShortcutProperty`` WHERE ``MsiShortcutProperty`` = '$id'")) {
        Invoke-Update "INSERT INTO ``MsiShortcutProperty`` (``MsiShortcutProperty``, ``Shortcut_``, ``PropertyKey``, ``PropVariantValue``) VALUES ('$id', '$shortcut', '$aumidKey', '$aumid')"
        Write-Output "AUMID $aumid on shortcut $shortcut"
    }
}

# 卸载时撤掉应用自己写的打开方式（WindowsLinkAssociation）与通知登记（WinRTSupport），只撤命令或图标指向本安装目录的，
# 规则与应用里的「取消关联」相同：另一个副本（便携版、装在别处的）登记的不碰。不写进 MSI 的 Registry 表：
# 那样安装时就会无条件写入、抢走别的副本或别的下载工具的登记，而打开方式要等用户同意才写。
# magnet 与 .torrent 是共用的键，规则同 LinkRegistration：命令指向本安装目录的协议键整个删；扩展名一侧只撤自己那一项，
# Piko 建的键（记在 vendor 键的 CreatedKeys 下）撤空了才删。
# 整包升级时旧版也走一遍卸载，这时 UPGRADINGPRODUCTCODE 有值，跳过。
# 安装目录从上面那个 RemoveFolderEx 属性的注册表值里取（随 RemoveRegistryValues 删，所以排在它之前），不经命令行传入：
# 路径里可以有单引号与 $，拼进 PowerShell 源码要转义。脚本以 -EncodedCommand 传：源码里的引号放进命令行要再转义一层，
# 方括号还会被 MSI 的格式化当成属性引用。
# 经 WixCA 的 WixQuietExec 跑，不弹控制台窗口；失败不中断卸载（类型里的 64）
$installDirLocator = Invoke-Query "SELECT ``Key``, ``Name`` FROM ``RegLocator`` WHERE ``Name`` = '$installDirProperty'"
if ($null -eq $installDirLocator) { throw "找不到 $installDirProperty 的注册表位置" }
$installDirKey = Get-String $installDirLocator 1
# 启动器的文件名随 packageName 变，从快捷方式指向的文件取
$launcherFile = Invoke-Query "SELECT ``Target`` FROM ``Shortcut`` WHERE ``Shortcut`` = '$($shortcuts[0])'"
$launcherKey = (Get-String $launcherFile 1).Trim('[', '#', ']')
$launcherName = (Get-String (Invoke-Query "SELECT ``FileName`` FROM ``File`` WHERE ``File`` = '$launcherKey'") 1).Split('|')[-1]
if (-not $launcherName.EndsWith('.exe')) { throw "快捷方式指向的不是启动器：$launcherName" }
$cleanup = @"
`$ErrorActionPreference = 'SilentlyContinue'
`$hkcu = [Microsoft.Win32.Registry]::CurrentUser
`$dir = `$hkcu.OpenSubKey('$installDirKey')
if (-not `$dir) { exit 0 }
`$installDir = [string]`$dir.GetValue('$installDirProperty')
if (-not `$installDir) { exit 0 }
`$exe = Join-Path `$installDir '$launcherName'
function Get-Value([string]`$key, [string]`$name) { `$k = `$hkcu.OpenSubKey(`$key); if (`$k) { `$k.GetValue(`$name) } }
function Test-Ours([string]`$key) {
    `$command = [string](Get-Value "`$key\shell\open\command" '')
    `$path = if (`$command.StartsWith('"')) { `$command.Substring(1).Split('"')[0] } else { `$command.Split(' ')[0] }
    return `$path -and (`$path -ieq `$exe)
}
`$classes = 'Software\Classes'
`$created = '$vendorKey\CreatedKeys'
function Test-Created([string]`$key) { `$k = `$hkcu.OpenSubKey(`$created); return [bool](`$k -and (`$k.GetValueNames() -contains `$key)) }
function Unmark([string]`$key) { `$k = `$hkcu.OpenSubKey(`$created, `$true); if (`$k) { `$k.DeleteValue(`$key, `$false) } }
function Remove-IfCreatedAndEmpty([string]`$key) {
    `$k = `$hkcu.OpenSubKey(`$key)
    if (`$k -and (Test-Created `$key) -and `$k.SubKeyCount -eq 0 -and `$k.ValueCount -eq 0) { `$k.Close(); `$hkcu.DeleteSubKey(`$key, `$false); Unmark `$key }
}
foreach (`$progId in '$magnetProgId', '$torrentProgId') {
    if (Test-Ours "`$classes\`$progId") { `$hkcu.DeleteSubKeyTree("`$classes\`$progId", `$false) }
}
if (Test-Ours "`$classes\magnet") { `$hkcu.DeleteSubKeyTree("`$classes\magnet", `$false); Unmark "`$classes\magnet" }
if (-not `$hkcu.OpenSubKey("`$classes\$torrentProgId")) {
    `$openWith = `$hkcu.OpenSubKey("`$classes\.torrent\OpenWithProgids", `$true)
    if (`$openWith) { `$openWith.DeleteValue('$torrentProgId', `$false); `$openWith.Close() }
    Remove-IfCreatedAndEmpty "`$classes\.torrent\OpenWithProgids"
    `$torrent = `$hkcu.OpenSubKey("`$classes\.torrent", `$true)
    if (`$torrent -and `$torrent.GetValue('') -eq '$torrentProgId') { `$torrent.DeleteValue('', `$false) }
    if (`$torrent) { `$torrent.Close() }
    Remove-IfCreatedAndEmpty "`$classes\.torrent"
}
if (-not `$hkcu.OpenSubKey("`$classes\$magnetProgId") -and -not `$hkcu.OpenSubKey("`$classes\$torrentProgId")) {
    `$hkcu.DeleteSubKeyTree("`$classes\Applications\$launcherName", `$false)
    `$hkcu.DeleteSubKeyTree('$vendorKey\Capabilities', `$false)
    `$hkcu.DeleteSubKeyTree(`$created, `$false)
    `$registered = `$hkcu.OpenSubKey('Software\RegisteredApplications', `$true)
    if (`$registered) { `$registered.DeleteValue('$Identity', `$false) }
    `$vendor = `$hkcu.OpenSubKey('$vendorKey')
    if (`$vendor -and `$vendor.SubKeyCount -eq 0 -and `$vendor.ValueCount -eq 0) { `$vendor.Close(); `$hkcu.DeleteSubKey('$vendorKey', `$false) }
}
`$toast = 'Software\Classes\AppUserModelId\$aumid'
`$icon = [string](Get-Value `$toast 'IconUri')
if (`$icon -and `$icon.StartsWith(`$installDir, [StringComparison]::OrdinalIgnoreCase)) { `$hkcu.DeleteSubKeyTree(`$toast, `$false) }
"@
$cleanupAction = 'PikoForgetShellRegistration'
# 编码后的脚本有几 KB，放进属性表；CustomAction 的 Target 列只有 255 个字符宽
$cleanupScriptProperty = 'PIKO_FORGET_SHELL_SCRIPT'
$encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($cleanup))
if ($null -eq (Invoke-Query "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$cleanupScriptProperty'")) {
    Invoke-Update "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('$cleanupScriptProperty', '$encoded')"
}
$cleanupCondition = 'REMOVE~="ALL" AND NOT UPGRADINGPRODUCTCODE'
$cleanupSequence = (Get-Sequence 'RemoveRegistryValues') - 2
if ($null -eq (Invoke-Query "SELECT ``Action`` FROM ``CustomAction`` WHERE ``Action`` = '$cleanupAction'")) {
    foreach ($sequence in @($cleanupSequence, ($cleanupSequence + 1))) {
        if ($null -ne (Invoke-Query "SELECT ``Action`` FROM ``InstallExecuteSequence`` WHERE ``Sequence`` = $sequence")) { throw "序号 $sequence 已被占用，jpackage 的模板可能变了" }
    }
    # 延迟执行的动作只读得到与它同名的属性（CustomActionData），先由一个 51 类动作把命令行放进去
    $commandLine = "`"[System64Folder]WindowsPowerShell\v1.0\powershell.exe`" -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand [$cleanupScriptProperty]"
    $view = $db.OpenView("INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES (?, ?, ?, ?)")
    foreach ($action in @(
            @("Set$cleanupAction", 51, $cleanupAction, $commandLine),
            # 1 = DLL 在 Binary 表里，64 = 失败照常继续，1024 = 延迟执行（按用户安装，以当前用户身份跑）
            @($cleanupAction, (1 + 64 + 1024), 'WixCA', 'WixQuietExec'))) {
        $record = $installer.CreateRecord(4)
        $record.StringData(1) = $action[0]
        $record.IntegerData(2) = $action[1]
        $record.StringData(3) = $action[2]
        $record.StringData(4) = $action[3]
        [void]$view.Execute($record)
    }
    [void]$view.Close()
    Invoke-Update "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('Set$cleanupAction', '$cleanupCondition', $cleanupSequence)"
    Invoke-Update "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('$cleanupAction', '$cleanupCondition', $($cleanupSequence + 1))"
    Write-Output "$cleanupAction at $($cleanupSequence + 1) on uninstall"
}

# 「应用」设置与控制面板里的卸载条目链接到项目主页
foreach ($property in @('ARPURLINFOABOUT', 'ARPHELPLINK')) {
    if ($null -eq (Invoke-Query "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$property'")) {
        Invoke-Update "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('$property', 'https://github.com/NihilDigit/piko')"
    }
}

$db.Commit()
