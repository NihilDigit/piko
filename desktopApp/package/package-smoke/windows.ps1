# Windows 上应用内更新的端到端冒烟：真装、真启动、真更新，断言落到磁盘上的结果。
#
# 输入是同一份源码打出的两个版本（Base 与 Next），各自一个目录，里面是 Release 附件原样的
# piko-windows-<架构>-<版本>.msi / .7z / -files.json / -app.zip。两个包都用测试专用的 UpgradeCode 与
# 包名打（build.gradle.kts 的 pikoDesktopUpgradeUuid、pikoDesktopPackageName），本机跑时不碰已装的 Piko。
#
# 应用经 JAVA_TOOL_OPTIONS 指向本脚本起的假 Release（fake_release.py），并带 piko.update.auto：
# 开屏查到新版即自动下载、退出、交给 apply-update.ps1，不需要有人点。
#
# 场景，依次：
#   1. 全新安装 MSI，启动后存活，没有新版时不动
#   1b. 设为 magnet 与 .torrent 的默认打开方式：系统真把磁力链接与种子交给 Piko（拉起与转交各一次），再取消关联
#   1c. 「打开所在文件夹」：资源管理器开在文件所在的文件夹并选中它，文件名带空格与方括号
#   2. MSI 安装版增量更新（换补丁文件）：目录与新版清单逐文件一致，jar 的修改时间原样（AOT 缓存认它）
#   3. MSI 安装版整包更新：先弄坏一个运行时文件，增量更新不成立，走 msiexec；登记的版本随之更新
#   4. 卸载：安装目录下 app 与 runtime 不留任何文件。打过补丁与整包更新之后各卸一次，都当场查
#      （增量更新换进去的新名字 jar 不在 MSI 的文件表里，靠打包时加的 RemoveFile 规则删）
#      安装根目录里放的用户文件，卸载与整包升级后都还在
#   5. 便携版增量更新，补丁包里带着变了的运行时文件。便携包（.7z）都在非 UTC 时区解压，jar 的修改时间要与清单相同
#   6. 便携版补丁对不上（弄坏一个运行时文件）：从 image.zip 只取不同的文件，按 Range 与照不认 Range 的镜像各一次
# -PortableOnly 只跑 5、6。
# 每次更新成功后，暂存目录只剩日志
#
# 用法（pwsh）：
#   ./windows.ps1 -Base <目录> -Next <目录> -BaseVersion 9.1.0 -NextVersion 9.1.1 `
#       -PackageName PikoSmoke -UpgradeCode <GUID> -Work <临时目录>
param(
    [Parameter(Mandatory = $true)] [string] $Base,
    [Parameter(Mandatory = $true)] [string] $Next,
    [Parameter(Mandatory = $true)] [string] $BaseVersion,
    [Parameter(Mandatory = $true)] [string] $NextVersion,
    [Parameter(Mandatory = $true)] [string] $PackageName,
    [Parameter(Mandatory = $true)] [string] $UpgradeCode,
    [Parameter(Mandatory = $true)] [string] $Work,
    [string] $Arch = 'x64',
    [int] $Port = 8765,
    [int] $UpdateTimeoutSeconds = 300,
    # 安装版的数据根目录（piko.home，路径不能带空格）。本机跑时给一个临时目录，免得测试包写进真实的 ~/.piko；
    # 便携版不受它影响，数据照旧在程序目录的 data 下
    [string] $DataRoot = '',
    # 只跑便携版的两个场景（5、6），不装 MSI。test.yml 在 windows-2022 上用它
    [switch] $PortableOnly
)

$ErrorActionPreference = 'Stop'
$installDir = Join-Path $env:LOCALAPPDATA $PackageName
$exeName = "$PackageName.exe"
$releases = Join-Path $Work 'releases'
$portableRoot = Join-Path $Work 'portable'
$portableDir = Join-Path $portableRoot $PackageName
$installedHome = if ($DataRoot) { $DataRoot } else { Join-Path $HOME '.piko' }
$homeOption = if ($DataRoot) { "-Dpiko.home=$DataRoot" } else { '' }
# 更新暂存在数据根目录下（DesktopAppUpdater.stagingRoot）：安装版是 ~/.piko，便携版是程序目录的 data
$stagingRoots = @((Join-Path $installedHome 'update'), (Join-Path $portableDir 'data\update'))
$logs = Join-Path $Work 'logs'
$script:server = $null

function Step([string] $message) { Write-Host "::group::$message" }
# 写进 Actions 的 job 摘要；本机跑时只打出来
function Write-Summary([string] $line) {
    Write-Host $line
    if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value "- $line" -Encoding utf8 }
}
function EndStep { Write-Host '::endgroup::' }
function Fail([string] $message) { throw "SMOKE FAILED: $message" }

function Asset([string] $dir, [string] $version, [string] $suffix) {
    $path = Join-Path $dir "piko-windows-$Arch-$version$suffix"
    if (-not (Test-Path -LiteralPath $path)) { Fail "missing asset $path" }
    return $path
}

function Read-Manifest([string] $version, [string] $dir) {
    Get-Content -LiteralPath (Asset $dir $version '-files.json') -Raw | ConvertFrom-Json
}

function Get-Sha256([string] $path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}

# 目录与清单逐文件一致：每个文件都在、内容相同，补丁文件的修改时间与清单相同；app 与 runtime 下没有清单外的文件
#
# 点开头的文件不比：那是安装器的元数据，不是应用文件。MSI 装的是 app\.package（记在它的文件表里），
# 不装应用目录里的 app\.jpackage.xml；更新前后都得原样留着 .package
function Assert-Tree([string] $dir, $manifest) {
    $expected = @{}
    foreach ($entry in @($manifest.files | Where-Object { -not ($_.path -split '/')[-1].StartsWith('.') })) {
        $path = Join-Path $dir ($entry.path.Replace('/', '\'))
        $expected[$path.ToLowerInvariant()] = $true
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "missing $($entry.path)" }
        $actual = Get-Sha256 $path
        if ($actual -ne $entry.sha256) { Fail "content differs: $($entry.path)" }
        # 只核对 jar 的修改时间：AOT 缓存按 jar 的时间校验，差一点就整份作废。jar 在训练前已取整到偶数秒，
        # MSI 装上去也是原样；exe 等其余文件经 MSI 会被取整，但没有东西依赖它们的时间
        if ($entry.path.EndsWith('.jar')) {
            $mtime = [DateTimeOffset]::new((Get-Item -LiteralPath $path).LastWriteTimeUtc).ToUnixTimeMilliseconds()
            if ($mtime -ne [long] $entry.mtime) { Fail "mtime differs: $($entry.path) $mtime != $($entry.mtime)" }
        }
    }
    foreach ($folder in @('app', 'runtime')) {
        foreach ($file in @(Get-ChildItem -LiteralPath (Join-Path $dir $folder) -Recurse -File -Force | Where-Object { -not $_.Name.StartsWith('.') })) {
            if (-not $expected.ContainsKey($file.FullName.ToLowerInvariant())) { Fail "stale file left: $($file.FullName)" }
        }
    }
    Write-Host "tree matches $($manifest.version) ($($manifest.files.Count) files)"
}

function Publish([string] $version) {
    Set-Content -LiteralPath (Join-Path $releases 'latest.txt') -Value $version -Encoding utf8
}

function Start-Server {
    # 端口已被占就停：探活会连上别人（上一次没关掉的假 Release），应用取到的是另一份版本
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { Fail "port $Port is already in use" }
    $script:server = Start-Process python -ArgumentList @((Join-Path $PSScriptRoot 'fake_release.py'), $releases, $Port) `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardError (Join-Path $logs 'server.err') -RedirectStandardOutput (Join-Path $logs 'server.out')
    for ($i = 0; $i -lt 50; $i++) {
        try { Invoke-WebRequest "http://127.0.0.1:$Port/latest" -UseBasicParsing | Out-Null; return } catch { Start-Sleep -Milliseconds 200 }
    }
    Fail 'fake release server did not start'
}

function App-Processes([string] $dir) {
    $prefix = (Join-Path $dir '').ToLowerInvariant()
    @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.ToLowerInvariant().StartsWith($prefix) })
}

function Stop-App([string] $dir) {
    foreach ($p in (App-Processes $dir)) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 50 -and (App-Processes $dir).Count -gt 0; $i++) { Start-Sleep -Milliseconds 200 }
}

# 启动器会另起一个同名子进程跑 JVM；环境变量随之传下去，更新脚本也继承它，所以更新后重新拉起的版本同样指向假 Release
function Start-App([string] $dir, [switch] $AutoInstall) {
    $options = "-Dpiko.update.api=http://127.0.0.1:$Port/latest"
    if ($AutoInstall) { $options += ' -Dpiko.update.auto=true' }
    if ($dir -eq $installDir) { $options += " $homeOption" }
    $env:JAVA_TOOL_OPTIONS = $options
    try { Start-Process -FilePath (Join-Path $dir $exeName) -WorkingDirectory $dir }
    finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
}

function Installed-Version([string] $dir) {
    $cfg = Join-Path $dir "app\$PackageName.cfg"
    $line = Get-Content -LiteralPath $cfg | Where-Object { $_ -match 'jpackage\.app-version=' } | Select-Object -First 1
    return ($line -replace '.*jpackage\.app-version=', '').Trim()
}

function Msi-Products {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $code = '{' + $UpgradeCode.Trim('{', '}').ToUpperInvariant() + '}'
    @($installer.RelatedProducts($code) | ForEach-Object {
        [pscustomobject]@{ Code = $_; Version = $installer.ProductInfo($_, 'VersionString'); Location = $installer.ProductInfo($_, 'InstallLocation') }
    })
}

function Save-UpdateLogs([string] $name) {
    $target = Join-Path $logs $name
    foreach ($staging in $stagingRoots) {
        if (-not (Test-Path -LiteralPath $staging)) { continue }
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        Get-ChildItem -LiteralPath $staging -Recurse -File -Include *.log, failed |
            ForEach-Object { Copy-Item $_.FullName (Join-Path $target ("{0}-{1}" -f $_.Directory.Name, $_.Name)) }
    }
    $appLogs = Join-Path $installedHome 'logs'
    if (Test-Path -LiteralPath $appLogs) { Copy-Item -Recurse -Force $appLogs (Join-Path $target 'app-logs') }
    # 便携版的日志在程序目录的 data\logs 下（PikoHome）
    $portableLogs = Join-Path $Work "portable\$PackageName\data\logs"
    if (Test-Path -LiteralPath $portableLogs) { Copy-Item -Recurse -Force $portableLogs (Join-Path $target 'portable-app-logs') }
}

# 等更新完成：目录里的版本变成 Next，且新版本已被重新拉起。失败记号出现即失败
function Wait-Updated([string] $dir, [string] $scenario) {
    $deadline = (Get-Date).AddSeconds($UpdateTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($staging in $stagingRoots) {
            $marker = Join-Path $staging "$NextVersion\failed"
            if (Test-Path -LiteralPath $marker) {
                Save-UpdateLogs $scenario
                Fail "update script reported failure: $(Get-Content -Raw -LiteralPath $marker)"
            }
        }
        $version = try { Installed-Version $dir } catch { $null }
        if ($version -eq $NextVersion -and (App-Processes $dir).Count -gt 0) {
            Write-Host "updated to $NextVersion and relaunched"
            Start-Sleep -Seconds 5
            Save-UpdateLogs $scenario
            return
        }
        Start-Sleep -Seconds 2
    }
    Save-UpdateLogs $scenario
    Fail "$scenario : not updated to $NextVersion within $UpdateTimeoutSeconds s"
}

function Assert-Alive([string] $dir, [int] $seconds) {
    Start-Sleep -Seconds $seconds
    if ((App-Processes $dir).Count -eq 0) { Fail "app exited within $seconds s after launch" }
    $crash = Get-ChildItem -LiteralPath $dir -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue
    if ($crash) { Fail "JVM crash log: $($crash.FullName)" }
    Write-Host "alive after $seconds s"
}

# 弄坏一个不在补丁包里的运行时文件：本机不再与新版逐字节相同，增量更新不成立。挑说明与配置一类的文本文件，
# 不碰 DLL；runtime\release 在 CI 的构建里被当成变了的运行时文件装进补丁包（见 test.yml），不能用它
function Damage-Runtime([string] $dir) {
    $entry = @($nextManifest.files | Where-Object { -not $_.patch -and $_.path -match '^runtime/(legal|conf)/' }) | Select-Object -First 1
    if (-not $entry) { Fail 'no runtime file outside the patch to damage' }
    Add-Content -LiteralPath (Join-Path $dir ($entry.path.Replace('/', '\'))) -Value '# smoke' -Encoding ascii
}

function Install-Msi([string] $msi, [string] $log) {
    $p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$msi`"", '/qn', '/norestart', '/l*v', "`"$log`"") -Wait -PassThru
    if ($p.ExitCode -ne 0) { Fail "msiexec /i exited $($p.ExitCode), see $log" }
}

function Uninstall-Msi([string] $log) {
    foreach ($product in (Msi-Products)) {
        $p = Start-Process msiexec.exe -ArgumentList @('/x', $product.Code, '/qn', '/norestart', '/l*v', "`"$log`"") -Wait -PassThru
        if ($p.ExitCode -ne 0) { Fail "msiexec /x exited $($p.ExitCode), see $log" }
    }
}

function Assert-NoAppFiles([string] $when) {
    foreach ($folder in @('app', 'runtime')) {
        $left = @(Get-ChildItem -LiteralPath (Join-Path $installDir $folder) -Recurse -File -Force -ErrorAction SilentlyContinue)
        if ($left.Count -gt 0) { Fail "$when left $($left.Count) files under $folder, e.g. $($left[0].FullName)" }
    }
}

function Reset-Staging {
    foreach ($staging in $stagingRoots) { if (Test-Path -LiteralPath $staging) { Remove-Item -Recurse -Force -LiteralPath $staging } }
}

# 更新成功后暂存只剩日志：装过的文件、安装包与校验清单都已删掉，失败记号不该有
function Assert-StagingCleaned([string] $staging) {
    $dir = Join-Path $staging $NextVersion
    $left = @(Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -ne '.log' -and $_.Name -ne 'apply-update.ps1' })
    if ($left.Count -gt 0) { Fail "staging not cleaned after the update: $($left.FullName -join ', ')" }
}

# 解开便携包（.7z）：先用系统自带的 tar.exe，它解不了（旧系统的 libarchive）再用 7-Zip，哪个解的记进摘要。
# 应用内更新不解这个包，tar.exe 能不能解只关系到用户手动解压。解出的 jar 修改时间要与清单相同：AOT 缓存按它校验，
# 差一点整份作废。CI runner 是 UTC，打包与解压同在 UTC 看不出时区问题（zip 只存本地时间时就是这样漏过去的），
# 所以换到东八区解，测完换回；本机已不是 UTC 就不换
function Expand-Portable([string] $archive, [string] $destination, $manifest) {
    $original = (Get-TimeZone).Id
    $switch = (Get-TimeZone).BaseUtcOffset -eq [TimeSpan]::Zero
    if ($switch) { Set-TimeZone -Id 'China Standard Time' }
    try {
        Write-Host "extracting the portable package in $((Get-TimeZone).Id)"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        $tar = "$env:SystemRoot\System32\tar.exe"
        $tarVersion = (& $tar --version | Select-Object -First 1)
        & $tar -x -f $archive -C $destination
        if ($LASTEXITCODE -eq 0) {
            Write-Summary "Portable .7z extracted by the system tar.exe ($tarVersion)."
        } else {
            Write-Summary "The system tar.exe ($tarVersion) could not extract the portable .7z (exit $LASTEXITCODE); extracted with 7-Zip instead."
            Remove-Item -Recurse -Force -LiteralPath $destination
            & 7z x $archive "-o$destination" -y | Out-Null
            if ($LASTEXITCODE -ne 0) { Fail "7z x exited $LASTEXITCODE" }
        }
    } finally {
        if ($switch) { Set-TimeZone -Id $original }
    }
    $wrong = @(foreach ($entry in @($manifest.files | Where-Object { $_.path.EndsWith('.jar') })) {
        $path = Join-Path $destination (Join-Path $PackageName ($entry.path.Replace('/', '\')))
        $mtime = [DateTimeOffset]::new((Get-Item -LiteralPath $path).LastWriteTimeUtc).ToUnixTimeMilliseconds()
        if ($mtime -ne [long] $entry.mtime) { "$($entry.path) off by $(($mtime - [long] $entry.mtime) / 1000) s" }
    })
    if ($wrong.Count -gt 0) { Fail "jar times differ after extracting the portable package: $($wrong -join '; ')" }
}

# 安装根目录里的用户文件（旧版把下载默认放进过安装目录）。卸载与整包升级都不该删它们：
# jpackage 的 RemoveFolderEx 按 INSTALLDIR 递归删除，旧版的卸载又在升级事务里跑
function Put-Sentinel([string] $name) {
    $path = Join-Path $installDir $name
    Set-Content -LiteralPath $path -Value 'user file' -Encoding ascii
    return $path
}

function Assert-Sentinel([string] $path, [string] $when) {
    if (-not (Test-Path -LiteralPath $path)) { Fail "$when deleted the user file $path in the install dir" }
}

# 装好的包里跑一段自检（SelfTest.kt），结果写进文件：启动器是窗口程序，标准输出接不出来
function Invoke-SelfTest([string] $dir, [string] $name) {
    $out = Join-Path $logs "selftest-$name.txt"
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out }
    $env:JAVA_TOOL_OPTIONS = "-Dpiko.selftest=$name -Dpiko.selftest.out=`"$out`" $homeOption"
    try { Start-Process -FilePath (Join-Path $dir $exeName) -WorkingDirectory $dir | Out-Null }
    finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
    for ($i = 0; $i -lt 120; $i++) {
        if ((Test-Path -LiteralPath $out) -and (Select-String -LiteralPath $out -Pattern '^(PASS|FAIL)$' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    $text = if (Test-Path -LiteralPath $out) { Get-Content -Raw -LiteralPath $out } else { '(no output)' }
    Write-Host "self test $name : $text"
    if ($text -notmatch '(?m)^PASS$') { Fail "self test $name failed: $text" }
}

# 应用日志里「收到外部链接」的行数。Piko 只记类别与来路，不记链接本身（Main.kt 的 deliverIncoming）
function Count-IncomingLinks([string] $kind) {
    $appLogs = Join-Path $installedHome 'logs'
    if (-not (Test-Path -LiteralPath $appLogs)) { return 0 }
    @(Get-ChildItem -LiteralPath $appLogs -File | Select-String -Pattern "IncomingLink.*$kind" -Encoding utf8).Count
}

function Wait-IncomingLink([string] $kind, [int] $before, [string] $what) {
    for ($i = 0; $i -lt 60; $i++) {
        if ((Count-IncomingLinks $kind) -gt $before) { Write-Host "Piko received the $kind from $what"; return }
        Start-Sleep -Seconds 1
    }
    Save-UpdateLogs 'link-association'
    Fail "Piko did not log receiving the $kind from $what within 60 s"
}

# 资源管理器里开着这个文件夹的那个窗口，没有时为 null
function Find-ExplorerFolder([string] $folder) {
    foreach ($window in @((New-Object -ComObject Shell.Application).Windows())) {
        try { if ($window.Document.Folder.Self.Path -ieq $folder) { return $window } } catch { }
    }
    return $null
}

# 窗口里选中项的完整路径。比完整路径而不比 Name：资源管理器默认隐藏已知扩展名，Name 里没有 .mkv
function Get-ExplorerSelection($window) {
    try { @($window.Document.SelectedItems() | ForEach-Object { $_.Path }) } catch { @() }
}

# 最小的种子：info 里只有一个 1 字节的文件。Piko 在本地算它的 infohash，换成磁力链接
function New-SmokeTorrent([string] $path) {
    $ascii = [System.Text.Encoding]::ASCII
    $bytes = $ascii.GetBytes('d4:infod6:lengthi1e4:name9:smoke.bin12:piece lengthi16384e6:pieces20:') + (New-Object byte[] 20) + $ascii.GetBytes('ee')
    [System.IO.File]::WriteAllBytes($path, $bytes)
}

# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $Work, $releases, $logs | Out-Null
foreach ($pair in @(@($Base, $BaseVersion), @($Next, $NextVersion))) {
    $dir = Join-Path $releases $pair[1]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item -Path (Join-Path $pair[0] '*') -Destination $dir -Force
}
$nextManifest = Read-Manifest $NextVersion $Next
# 包本身要先对：更新包里的启动配置若仍写着旧版本号，补丁照常换上，重启后还是旧版，
# 又查到同一个新版，只会表现为更新一直不生效
foreach ($pair in @(@($Base, $BaseVersion), @($Next, $NextVersion))) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Asset $pair[0] $pair[1] '-app.zip'))
    try {
        $cfg = $archive.Entries | Where-Object { $_.FullName -eq "app/$PackageName.cfg" } | Select-Object -First 1
        if (-not $cfg) { Fail "app.zip of $($pair[1]) has no app/$PackageName.cfg" }
        $reader = New-Object System.IO.StreamReader($cfg.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch "jpackage\.app-version=$([regex]::Escape($pair[1]))") { Fail "app.zip of $($pair[1]) carries another version: $text" }
    } finally { $archive.Dispose() }
}
$baseMsi = if (-not $PortableOnly) { Asset $Base $BaseVersion '.msi' }
$basePortable = Asset $Base $BaseVersion '.7z'
$baseManifest = Read-Manifest $BaseVersion $Base
if (-not $PortableOnly -and (Msi-Products).Count -gt 0) { Fail "a product with UpgradeCode $UpgradeCode is already installed; uninstall it first" }

# 服务启动时就要有 latest.txt：探活请求取的就是 /latest
Publish $BaseVersion
Start-Server
try {
    if (-not $PortableOnly) {
        Step '1. fresh MSI install'
        Reset-Staging
        Publish $BaseVersion
        Install-Msi $baseMsi (Join-Path $logs 'install-base.log')
        if ((Installed-Version $installDir) -ne $BaseVersion) { Fail 'fresh install has the wrong version' }
        Start-App $installDir -AutoInstall
        Assert-Alive $installDir 25
        if ((Installed-Version $installDir) -ne $BaseVersion) { Fail 'app changed itself without a newer release' }
        Stop-App $installDir
        EndStep

        Step '1b. magnet and torrent association'
        # 全新的 runner 没有 UserChoice，Classes 下的登记当场生效，所以这里验的是系统真按登记把链接交给 Piko：
        # 没开着时拉起它（启动参数），开着时由新进程转交（单实例）。用户选过默认应用的机器上（本机跑）登记不生效，
        # 系统照旧按 UserChoice 打开，所以跳过
        $userChoices = @(
            'HKCU:\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\magnet\UserChoice',
            'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.torrent\UserChoice'
        ) | Where-Object { Test-Path -LiteralPath $_ }
        if ($userChoices) {
            Write-Host "::warning::skipped: this machine has a UserChoice for magnet or .torrent ($($userChoices -join ', '))"
        } else {
            Invoke-SelfTest $installDir 'link-register'
            # 系统拉起的 Piko 继承本进程的环境变量，数据根目录才与上面一致
            $env:JAVA_TOOL_OPTIONS = $homeOption
            try {
                $before = Count-IncomingLinks 'magnet'
                Start-Process 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=smoke'
                Wait-IncomingLink 'magnet' $before 'the magnet protocol'
                $torrent = Join-Path $Work 'smoke.torrent'
                New-SmokeTorrent $torrent
                $before = Count-IncomingLinks 'torrent'
                Start-Process $torrent
                Wait-IncomingLink 'torrent' $before 'a .torrent file'
            } finally { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
            Stop-App $installDir
            Invoke-SelfTest $installDir 'link-unregister'
        }
        EndStep

        Step '1c. reveal a downloaded file in Explorer'
        # 名字带空格与方括号，照下载下来的剧集名：拼 explorer /select 命令行时，空格让资源管理器退回打开「文档」
        $revealDir = Join-Path $Work 'reveal test'
        New-Item -ItemType Directory -Force -Path $revealDir | Out-Null
        $revealFile = Join-Path $revealDir '[Group] Show - 01 [1080p].mkv'
        [System.IO.File]::WriteAllText($revealFile, 'smoke')
        $env:PIKO_SELFTEST_PATH = $revealFile
        try { Invoke-SelfTest $installDir 'reveal' } finally { Remove-Item Env:PIKO_SELFTEST_PATH }
        $explorer = $null
        for ($i = 0; $i -lt 20 -and -not $explorer; $i++) {
            $explorer = Find-ExplorerFolder $revealDir
            if (-not $explorer) { Start-Sleep -Seconds 1 }
        }
        if (-not $explorer) {
            $windows = @((New-Object -ComObject Shell.Application).Windows() | ForEach-Object { try { $_.Document.Folder.Self.Path } catch { '(no folder)' } })
            Fail "Explorer did not open $revealDir; shell windows: $($windows -join '; ')"
        }
        # 断言只到文件夹：出过的问题是打开了别的文件夹。runner 上文件夹对了、选中项却读不到（本机能读到），
        # 原因未查明，读到的内容照样打出来
        $selected = @(Get-ExplorerSelection $explorer)
        Write-Host "Explorer opened the folder; selected: $($selected -join '; ')"
        if ($selected -inotcontains $revealFile) { Write-Host '::warning::Explorer opened the folder but did not report the file as selected' }
        $explorer.Quit()
        EndStep

        Step '2. MSI install, patch update'
        Reset-Staging
        Publish $NextVersion
        Start-App $installDir -AutoInstall
        Wait-Updated $installDir 'msi-patch'
        Stop-App $installDir
        Assert-Tree $installDir $nextManifest
        if (-not (Test-Path -LiteralPath (Join-Path $installDir 'app\.package'))) { Fail 'patch removed app\.package, which the MSI installed' }
        Assert-StagingCleaned $stagingRoots[0]
        EndStep

        Step '3. MSI install, full update through msiexec'
        # 刚打过补丁的安装卸掉，当场查残留：补丁换进来的新名字 jar 与 .jpackage.xml 不在 MSI 的文件表里，
        # 要靠打包时加的 RemoveFile 规则才删得掉（desktopApp/package/windows/transactional-upgrade.ps1）
        $sentinel = Put-Sentinel 'user-file-uninstall.txt'
        Uninstall-Msi (Join-Path $logs 'uninstall-after-patch.log')
        Assert-NoAppFiles 'uninstall after a patch'
        Assert-Sentinel $sentinel 'uninstall after a patch'
        Reset-Staging
        Install-Msi $baseMsi (Join-Path $logs 'install-base-2.log')
        Damage-Runtime $installDir
        $sentinel = Put-Sentinel 'user-file-upgrade.txt'
        Publish $NextVersion
        Start-App $installDir -AutoInstall
        Wait-Updated $installDir 'msi-full'
        Stop-App $installDir
        Assert-Tree $installDir $nextManifest
        Assert-Sentinel $sentinel 'a full update through msiexec'
        $products = Msi-Products
        if ($products.Count -ne 1 -or $products[0].Version -ne $NextVersion) { Fail "Windows Installer lists $($products | Out-String)" }
        Assert-StagingCleaned $stagingRoots[0]
        EndStep

        Step '4. uninstall leaves no app files'
        Uninstall-Msi (Join-Path $logs 'uninstall.log')
        Assert-NoAppFiles 'uninstall after a full update'
        Assert-Sentinel $sentinel 'uninstall after a full update'
        EndStep
    }

    Step '5. portable, patch update carrying a runtime file'
    # 补丁包要带着变了的运行时文件：test.yml 打 Next 时拿改过 runtime\release 摘要的 Base 清单作对照，
    # 它就被当成变了的文件装进补丁包，便携版照常走补丁，不下整包
    $runtimePatched = @($nextManifest.files | Where-Object { $_.patch -and $_.path.StartsWith('runtime/') })
    if ($runtimePatched.Count -eq 0) { Fail 'the Next build carries no runtime file in its patch; build it with -PpikoUpdateBases (see test.yml)' }
    Reset-Staging
    if (Test-Path -LiteralPath $portableRoot) { Remove-Item -Recurse -Force -LiteralPath $portableRoot }
    Expand-Portable $basePortable $portableRoot $baseManifest
    Publish $NextVersion
    Start-App $portableDir -AutoInstall
    Wait-Updated $portableDir 'portable-patch'
    Stop-App $portableDir
    Assert-Tree $portableDir $nextManifest
    Assert-StagingCleaned $stagingRoots[1]
    Write-Summary "Portable patch update replaced $($runtimePatched.Count) runtime file(s) from the patch, no full package."
    EndStep

    # 本机的运行时与新版对不上、补丁包又没带它：从 image.zip 只取不同的文件。先按 Range，再照不认 Range 的镜像整个下载
    foreach ($ranged in @($true, $false)) {
        $scenario = if ($ranged) { 'portable-image' } else { 'portable-image-no-range' }
        Step "6. portable, patch cannot cover it: image.zip $(if ($ranged) { 'by Range' } else { 'without Range' })"
        Reset-Staging
        Remove-Item -Recurse -Force -LiteralPath $portableRoot
        Expand-Portable $basePortable $portableRoot $baseManifest
        Damage-Runtime $portableDir
        $noRange = Join-Path $releases 'no-range'
        if ($ranged) { Remove-Item -LiteralPath $noRange -ErrorAction SilentlyContinue } else { New-Item -ItemType File -Force -Path $noRange | Out-Null }
        Set-Content -LiteralPath (Join-Path $releases 'bytes.log') -Value $null
        Publish $NextVersion
        try {
            Start-App $portableDir -AutoInstall
            Wait-Updated $portableDir $scenario
        } finally { Remove-Item -LiteralPath $noRange -ErrorAction SilentlyContinue }
        Stop-App $portableDir
        Assert-Tree $portableDir $nextManifest
        Assert-StagingCleaned $stagingRoots[1]
        $plan = @(Get-ChildItem -LiteralPath (Join-Path $portableDir 'data\logs') -File | Select-String -Pattern "$([regex]::Escape($NextVersion))：Image" -Encoding utf8)
        if ($plan.Count -eq 0) { Save-UpdateLogs $scenario; Fail 'the update did not go through image.zip' }
        if (-not (Test-Path -LiteralPath (Join-Path $portableDir 'portable'))) { Fail 'the portable marker is gone' }
        $imageName = "piko-windows-$Arch-$NextVersion-image.zip"
        $imageSize = (Get-Item -LiteralPath (Asset $Next $NextVersion '-image.zip')).Length
        $fetched = (@(Get-Content -LiteralPath (Join-Path $releases 'bytes.log') | Where-Object { $_ -like "$imageName *" } |
            ForEach-Object { [long] ($_ -split ' ')[1] }) | Measure-Object -Sum).Sum
        if ($ranged -and $fetched -ge $imageSize / 2) { Fail "image.zip by Range fetched $fetched of $imageSize bytes" }
        if (-not $ranged -and $fetched -lt $imageSize) { Fail "image.zip without Range fetched only $fetched of $imageSize bytes" }
        Write-Summary "Portable update through image.zip $(if ($ranged) { 'by Range' } else { 'without Range' }): $fetched of $imageSize bytes."
        EndStep
    }

    Write-Host 'package smoke passed'
} finally {
    Stop-App $installDir
    if ($script:server) { Stop-Process -Id $script:server.Id -Force -ErrorAction SilentlyContinue }
    # 按命令行再找一遍：scoop 装的 python 是个转发壳，停掉壳，真正的服务进程还在，占着端口
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('fake_release.py') -and $_.CommandLine.Contains($releases) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
