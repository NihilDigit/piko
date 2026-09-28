# 打包后改写 MSI 的两处：把 RemoveExistingProducts 挪进安装事务；卸载时连增量更新换进来的文件一起删（见文末）。
#
# jpackage 生成的 MSI 把它排在 InstallInitialize 之前，卸载旧版单独提交：新版随后装失败时，
# 旧版已经没了，应用内更新拉起的只是一个空目录。排在 InstallInitialize 之后，卸载与安装同属
# 一个事务，任一步失败都整体回滚，旧版原样留下。这正是 WiX MajorUpgrade 的
# afterInstallInitialize 排法；卸载仍在装新文件之前，组件规则上与原来没有区别。
param([Parameter(Mandatory = $true)][string]$Msi)
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

# 卸载时连应用内增量更新换进来的文件一起删。补丁把改了名的 jar（名字里带内容哈希）与 .jpackage.xml
# 写进 app 目录，它们不在 MSI 的文件表里，Windows Installer 卸载时只删自己装的，这些就一直留着。
# RemoveFile 表按通配符登记 app 下的 *.jar 与 *.xml，InstallMode 2 表示卸载时删。
# app 目录只放应用自己的文件，通配不会误删用户的东西；exe、cfg、aot 名字不变，本就在文件表里
$appDir = $null
$view = $db.OpenView("SELECT ``Directory``, ``Directory_Parent``, ``DefaultDir`` FROM ``Directory``")
[void]$view.Execute()
while ($null -ne ($row = $view.Fetch())) {
    $defaultDir = (Get-String $row 3).Split('|')[-1]
    if ((Get-String $row 2) -eq 'INSTALLDIR' -and $defaultDir -eq 'app') { $appDir = Get-String $row 1 }
}
[void]$view.Close()
if (-not $appDir) { throw "找不到 app 目录，jpackage 的模板可能变了" }
$component = Invoke-Query "SELECT ``Component`` FROM ``Component`` WHERE ``Directory_`` = '$appDir'"
if ($null -eq $component) { throw "app 目录下没有组件" }
$componentKey = Get-String $component 1
foreach ($rule in @(@('PikoPatchedJars', '*.jar'), @('PikoPatchedXml', '*.xml'))) {
    if ($null -eq (Invoke-Query "SELECT ``FileKey`` FROM ``RemoveFile`` WHERE ``FileKey`` = '$($rule[0])'")) {
        Invoke-Update "INSERT INTO ``RemoveFile`` (``FileKey``, ``Component_``, ``FileName``, ``DirProperty``, ``InstallMode``) VALUES ('$($rule[0])', '$componentKey', '$($rule[1])', '$appDir', 2)"
        Write-Output "RemoveFile $($rule[1]) in $appDir on uninstall"
    }
}

$db.Commit()
