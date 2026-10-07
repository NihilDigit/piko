# 已发布的老版本（1.0.0、1.1.0）升级到这次构建的冒烟：真实的老版本便携 zip 与 MSI，升级到 Next，断言走的是补丁、
# 升级后能启动、版本号对、用户数据还在。
#
# 老客户端只认三个附件：-files.json、-app.zip、.msi。清单里 patch=false 的文件与本机逐字节相同才走补丁；否则 1.1.0 的
# 便携版去找已不再发布的 .zip，1.0.0 直接给下载页。所以 Next 要用这两个版本的清单作对照打（-PpikoUpdateBases），
# 与它们不同的文件都标成补丁、装进 app.zip（build.gradle.kts 的 UpdateArtifactsTask）。
#
# MSI 安装版升级后另做一次 Windows Installer 修复，补丁要原样留着、新版照常启动。
#
# 1.1.0 带 piko.update.api 与 piko.update.auto，整个流程由它自己走：查到 Next、下载、退出、交给它自带的更新脚本。
# 1.0.0 有 piko.update.api，没有自动安装，下载与暂存只能由这里照它的做法代劳：先按它的 canPatch 断言能走补丁，
# 把 app.zip 的条目逐个核对后放进暂存目录，再跑 v1.0.0 原样的 apply-update.ps1，并重现它换 exe 时留下的 Piko.exe.old，
# 断言新版首次启动时清掉它。它的检查与下载逻辑与 1.1.0 相同
# （canPatch、extractPatch 未改过），由 1.1.0 那一轮真跑覆盖。
#
# 老版本的数据在 ~/.piko（没有 PikoHome），本机跑时给 -UserHome 一个临时目录，经 -Duser.home 隔开真实的数据。
# Next 必须以默认包名 Piko 打：补丁换的是 Piko.exe 与 app\Piko.cfg，包名不同就对不上老版本的文件。
#
# 用法（pwsh）：
#   ./legacy.ps1 -Next <目录> -NextVersion 9.9.1 -Old <目录> -Work <临时目录> [-Kinds portable,msi] [-UserHome <目录>]
#   <Old> 里是老版本原样的附件：piko-windows-x64-<版本>.zip / .msi / -files.json
param(
    [Parameter(Mandatory = $true)] [string] $Next,
    [Parameter(Mandatory = $true)] [string] $NextVersion,
    [Parameter(Mandatory = $true)] [string] $Old,
    [Parameter(Mandatory = $true)] [string] $Work,
    [string[]] $OldVersions = @('1.0.0', '1.1.0'),
    [string[]] $Kinds = @('portable', 'msi'),
    [string] $UserHome = '',
    [string] $Arch = 'x64',
    [int] $Port = 8766,
    [int] $UpdateTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
# 正式版的 UpgradeCode（build.gradle.kts 的 msiUpgradeUuid 默认值），老版本的 MSI 用的就是它
$UpgradeCode = '{6D8D332E-F0F4-4EE0-BC2D-FB3EBF3D4267}'
$releases = Join-Path $Work 'releases'
$logs = Join-Path $Work 'logs'
$dataHome = if ($UserHome) { $UserHome } else { $HOME }
$homeOption = if ($UserHome) { "-Duser.home=$UserHome" } else { '' }
$script:server = $null

function Step([string] $message) { Write-Host "::group::$message" }
function EndStep { Write-Host '::endgroup::' }
function Fail([string] $message) { throw "LEGACY SMOKE FAILED: $message" }
function Write-Summary([string] $line) {
    Write-Host $line
    if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value "- $line" -Encoding utf8 }
}

function Get-Sha256([string] $path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}

function Asset([string] $dir, [string] $version, [string] $suffix) {
    $path = Join-Path $dir "piko-windows-$Arch-$version$suffix"
    if (-not (Test-Path -LiteralPath $path)) { Fail "missing asset $path" }
    return $path
}

function App-Processes([string] $dir) {
    $prefix = (Join-Path $dir '').ToLowerInvariant()
    @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.ToLowerInvariant().StartsWith($prefix) })
}

# 每一轮都重新找、重新杀，理由见 windows.ps1 的 Stop-App
function Stop-App([string] $dir) {
    for ($i = 0; $i -lt 50; $i++) {
        $left = @(App-Processes $dir)
        if ($left.Count -eq 0) { return }
        foreach ($p in $left) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 200
    }
}

function Installed-Version([string] $dir) {
    $line = Get-Content -LiteralPath (Join-Path $dir 'app\Piko.cfg') | Where-Object { $_ -match 'jpackage\.app-version=' } | Select-Object -First 1
    return ($line -replace '.*jpackage\.app-version=', '').Trim()
}

function Msi-Products {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    @($installer.RelatedProducts($UpgradeCode) | ForEach-Object { $_ })
}

# 老版本的 canPatch：清单里 patch=false 的文件本机都有且逐字节相同
function Assert-OldClientTakesPatch([string] $dir, $manifest, [string] $what) {
    $differs = @(foreach ($entry in @($manifest.files | Where-Object { -not $_.patch })) {
        $path = Join-Path $dir ($entry.path.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Sha256 $path) -ne $entry.sha256) { $entry.path }
    })
    if ($differs.Count -gt 0) { Fail "$what would not take the patch; not covered by app.zip: $($differs -join ', ')" }
}

# 升级后应用目录里清单列的文件都对（点开头的是安装器的元数据，MSI 不装 .jpackage.xml）
function Assert-Tree([string] $dir, $manifest) {
    foreach ($entry in @($manifest.files | Where-Object { -not ($_.path -split '/')[-1].StartsWith('.') })) {
        $path = Join-Path $dir ($entry.path.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "missing $($entry.path)" }
        if ((Get-Sha256 $path) -ne $entry.sha256) { Fail "content differs: $($entry.path)" }
    }
}

function Count-Log([string] $pattern) {
    $appLogs = Join-Path $dataHome '.piko\logs'
    if (-not (Test-Path -LiteralPath $appLogs)) { return 0 }
    @(Get-ChildItem -LiteralPath $appLogs -File | Select-String -Pattern $pattern -Encoding utf8).Count
}

function Save-Logs([string] $name) {
    $target = Join-Path $logs $name
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    $legacyStaging = Join-Path ([System.IO.Path]::GetTempPath()) 'piko-update'
    foreach ($dir in @($legacyStaging, (Join-Path $Work 'stage'))) {
        if (Test-Path -LiteralPath $dir) {
            Get-ChildItem -LiteralPath $dir -Recurse -File -Include *.log, failed |
                ForEach-Object { Copy-Item $_.FullName (Join-Path $target ("{0}-{1}" -f $_.Directory.Name, $_.Name)) }
        }
    }
    $appLogs = Join-Path $dataHome '.piko\logs'
    if (Test-Path -LiteralPath $appLogs) { Copy-Item -Recurse -Force $appLogs (Join-Path $target 'app-logs') }
}

function Start-Server {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { Fail "port $Port is already in use" }
    $script:server = Start-Process python -ArgumentList @((Join-Path $PSScriptRoot 'fake_release.py'), $releases, $Port) `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardError (Join-Path $logs 'server.err') -RedirectStandardOutput (Join-Path $logs 'server.out')
    for ($i = 0; $i -lt 50; $i++) {
        try { Invoke-WebRequest "http://127.0.0.1:$Port/latest" -UseBasicParsing | Out-Null; return } catch { Start-Sleep -Milliseconds 200 }
    }
    Fail 'fake release server did not start'
}

# 挂起的进程只映射了 exe 本身，还没载入任何 DLL。返回进程号：它的 ExecutablePath 读不出来，Stop-App 按路径找不到它
function Start-Suspended([string] $exe, [string] $directory) {
    if (-not ('LegacySmoke.Suspended' -as [type])) {
        Add-Type -Namespace LegacySmoke -Name Suspended -MemberDefinition @'
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
public struct STARTUPINFO { public int cb; public string lpReserved, lpDesktop, lpTitle; public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags; public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError; }
[StructLayout(LayoutKind.Sequential)]
public struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool CreateProcess(string app, string commandLine, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
'@
    }
    $si = New-Object LegacySmoke.Suspended+STARTUPINFO
    $si.cb = [System.Runtime.InteropServices.Marshal]::SizeOf($si)
    $pi = New-Object LegacySmoke.Suspended+PROCESS_INFORMATION
    # 4 = CREATE_SUSPENDED
    if (-not [LegacySmoke.Suspended]::CreateProcess($exe, "`"$exe`"", [IntPtr]::Zero, [IntPtr]::Zero, $false, 4, [IntPtr]::Zero, $directory, [ref] $si, [ref] $pi)) {
        Fail "could not start $exe suspended: $([System.ComponentModel.Win32Exception]::new([System.Runtime.InteropServices.Marshal]::GetLastWin32Error()).Message)"
    }
    return $pi.dwProcessId
}

# 1.0.0 没有自动安装：照它的 extractPatch 把 app.zip 的条目核对后放进暂存目录，再跑它原样的更新脚本
function Invoke-OldScript([string] $version, [string] $dir, $manifest) {
    $stage = Join-Path $Work "stage\$version"
    if (Test-Path -LiteralPath $stage) { Remove-Item -Recurse -Force -LiteralPath $stage }
    $files = Join-Path $stage 'files'
    New-Item -ItemType Directory -Force -Path $files | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Asset $Next $NextVersion '-app.zip'))
    try {
        $expected = @{}
        foreach ($entry in @($manifest.files | Where-Object { $_.patch })) { $expected[$entry.path] = $entry }
        foreach ($item in $archive.Entries) {
            if (-not $expected.ContainsKey($item.FullName)) { Fail "app.zip carries $($item.FullName), which $version's extractPatch rejects" }
            $out = Join-Path $files ($item.FullName.Replace('/', '\'))
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($item, $out)
            if ((Get-Sha256 $out) -ne $expected[$item.FullName].sha256) { Fail "app.zip entry differs from the manifest: $($item.FullName)" }
            (Get-Item -LiteralPath $out).LastWriteTimeUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([long] $expected[$item.FullName].mtime).UtcDateTime
            $expected.Remove($item.FullName)
        }
        if ($expected.Count -gt 0) { Fail "app.zip lacks $($expected.Keys -join ', '), which $version's extractPatch requires" }
    } finally { $archive.Dispose() }
    $script = Join-Path $stage 'apply-update.ps1'
    git -C $PSScriptRoot show "v${version}:desktopApp/src/desktopMain/resources/update/apply-update.ps1" | Set-Content -LiteralPath $script -Encoding ascii
    if ($LASTEXITCODE -ne 0) { Fail "could not read v$version's apply-update.ps1 from git (fetch the tags)" }

    # 1.0.0 的脚本只等 JVM、不等启动器：启动器还占着 Piko.exe 时，改名成 .old 能成、删不掉，.old 就留在安装目录里。
    # 照这个情形，以挂起状态起一个旧版的 Piko.exe 占住 exe 再跑脚本。不用停在 JVM 启动处的旧版：那是已载入运行时的 JVM，
    # 占着 runtime\lib\modules，补丁带着运行时文件时换不进去，而真实情形里 JVM 已经退出。脚本最后拉起的换成一个空的 .cmd：
    # 占着 exe 的旧进程要先停掉，新版首次启动时 removeUpdateLeftovers 才删得掉 .old
    $holder = Start-Suspended (Join-Path $dir 'Piko.exe') $dir
    $noop = Join-Path $dir 'legacy-smoke-noop.cmd'
    Set-Content -LiteralPath $noop -Value '@exit /b 0' -Encoding ascii
    $gone = Start-Process cmd.exe -ArgumentList '/c', 'exit' -PassThru -WindowStyle Hidden
    $gone.WaitForExit()
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $script -ProcessId $gone.Id -InstallDir $dir `
        -Mode patch -Source $files -Executable (Split-Path -Leaf $noop) -LogFile (Join-Path $stage 'update.log') | Out-Host
    if ($LASTEXITCODE -ne 0) { Save-Logs "old-$version"; Fail "v$version's apply-update.ps1 exited $LASTEXITCODE" }
    $leftover = Join-Path $dir 'Piko.exe.old'
    if (-not (Test-Path -LiteralPath $leftover)) { Write-Host "::warning::v$version's script left no Piko.exe.old this time; the cleanup is not exercised" }
    Stop-Process -Id $holder -Force
    Wait-Process -Id $holder -Timeout 10 -ErrorAction SilentlyContinue
    Stop-App $dir
    Remove-Item -LiteralPath $noop -ErrorAction SilentlyContinue

    $env:JAVA_TOOL_OPTIONS = "-Dpiko.update.api=http://127.0.0.1:$Port/latest $homeOption"
    try { Start-Process -FilePath (Join-Path $dir 'Piko.exe') -WorkingDirectory $dir }
    finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
    return $leftover
}

function Wait-Updated([string] $dir, [string] $name) {
    $deadline = (Get-Date).AddSeconds($UpdateTimeoutSeconds)
    $marker = Join-Path ([System.IO.Path]::GetTempPath()) "piko-update\$NextVersion\failed"
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $marker) { Save-Logs $name; Fail "update script reported failure: $(Get-Content -Raw -LiteralPath $marker)" }
        $version = try { Installed-Version $dir } catch { $null }
        if ($version -eq $NextVersion -and (App-Processes $dir).Count -gt 0) { return }
        Start-Sleep -Seconds 2
    }
    Save-Logs $name
    Fail "$name : not updated to $NextVersion within $UpdateTimeoutSeconds s"
}

# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $Work, $releases, $logs, $dataHome | Out-Null
$nextManifest = Get-Content -Raw -LiteralPath (Asset $Next $NextVersion '-files.json') | ConvertFrom-Json
$nextDir = Join-Path $releases $NextVersion
New-Item -ItemType Directory -Force -Path $nextDir | Out-Null
foreach ($suffix in @('-files.json', '-app.zip')) { Copy-Item -LiteralPath (Asset $Next $NextVersion $suffix) -Destination $nextDir }
# 老客户端缺 .msi 附件就当作还没有新版；走补丁时不下载它，所以 Next 没打 MSI 时放一个占位
$msi = Join-Path $Next "piko-windows-$Arch-$NextVersion.msi"
if (Test-Path -LiteralPath $msi) { Copy-Item -LiteralPath $msi -Destination $nextDir }
else { Set-Content -LiteralPath (Join-Path $nextDir "piko-windows-$Arch-$NextVersion.msi") -Value 'placeholder' -Encoding ascii }
Set-Content -LiteralPath (Join-Path $releases 'latest.txt') -Value $NextVersion -Encoding utf8
if ($Kinds -contains 'msi' -and (Msi-Products).Count -gt 0) { Fail 'Piko is already installed through its MSI; uninstall it first' }
Start-Server
try {
    foreach ($oldVersion in $OldVersions) {
        foreach ($kind in $Kinds) {
            $name = "$kind-$oldVersion"
            Step "$oldVersion $kind -> $NextVersion"
            $legacyStaging = Join-Path ([System.IO.Path]::GetTempPath()) 'piko-update'
            if (Test-Path -LiteralPath $legacyStaging) { Remove-Item -Recurse -Force -LiteralPath $legacyStaging }
            if ($kind -eq 'portable') {
                $root = Join-Path $Work "portable-$oldVersion"
                if (Test-Path -LiteralPath $root) { Remove-Item -Recurse -Force -LiteralPath $root }
                Expand-Archive -LiteralPath (Asset $Old $oldVersion '.zip') -DestinationPath $root
                $dir = Join-Path $root 'Piko'
            } else {
                $p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$(Asset $Old $oldVersion '.msi')`"", '/qn', '/norestart', '/l*v', "`"$(Join-Path $logs "install-$name.log")`"") -Wait -PassThru
                if ($p.ExitCode -ne 0) { Fail "msiexec /i $oldVersion exited $($p.ExitCode)" }
                $dir = Join-Path $env:LOCALAPPDATA 'Piko'
            }
            if ((Installed-Version $dir) -ne $oldVersion) { Fail "$name is not $oldVersion" }
            Assert-OldClientTakesPatch $dir $nextManifest $name
            $sentinel = Join-Path $dataHome '.piko\legacy-smoke-sentinel.txt'
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sentinel) | Out-Null
            Set-Content -LiteralPath $sentinel -Value $name -Encoding ascii

            $leftover = $null
            if ($oldVersion -eq '1.0.0') {
                $leftover = Invoke-OldScript $oldVersion $dir $nextManifest
            } else {
                $patchLines = Count-Log "自动安装 $([regex]::Escape($NextVersion))：(Patch|Delta)"
                $env:JAVA_TOOL_OPTIONS = "-Dpiko.update.api=http://127.0.0.1:$Port/latest -Dpiko.update.auto=true $homeOption"
                try { Start-Process -FilePath (Join-Path $dir 'Piko.exe') -WorkingDirectory $dir }
                finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
            }
            Wait-Updated $dir $name
            if ($oldVersion -ne '1.0.0' -and (Count-Log "自动安装 $([regex]::Escape($NextVersion))：(Patch|Delta)") -le $patchLines) {
                Save-Logs $name
                Fail "$name did not take the patch (see its log)"
            }
            Start-Sleep -Seconds 15
            if ((App-Processes $dir).Count -eq 0) { Save-Logs $name; Fail "$name : $NextVersion exited after the update" }
            if ((Count-Log "启动 $([regex]::Escape($NextVersion))，") -eq 0) { Save-Logs $name; Fail "$name : $NextVersion never logged its start" }
            if ($leftover -and (Test-Path -LiteralPath $leftover)) { Save-Logs $name; Fail "$name : $NextVersion did not remove the leftover $leftover on its first start" }
            Stop-App $dir
            Assert-Tree $dir $nextManifest
            if (-not (Test-Path -LiteralPath $sentinel)) { Fail "$name lost the user data in ~/.piko" }
            if ($kind -eq 'msi') {
                # Windows Installer 修复（msiexec /f 默认的 omus）后补丁仍在、新版仍起得来。这一跳用的是老版本自带的脚本，
                # 它不沿用原文件的创建时间，补丁文件靠 NTFS 文件名隧道继承；没继承到的被修复换回老版本，
                # 运行时一旦新旧混杂（带版本号的 DLL 留新、lib\modules 退旧）就起不来
                foreach ($code in (Msi-Products)) {
                    $p = Start-Process msiexec.exe -ArgumentList @('/fomus', $code, '/qn', '/norestart', '/l*v', "`"$(Join-Path $logs "repair-$name.log")`"") -Wait -PassThru
                    if ($p.ExitCode -ne 0) { Fail "msiexec /fomus after $name exited $($p.ExitCode)" }
                }
                Assert-Tree $dir $nextManifest
                $started = Count-Log "启动 $([regex]::Escape($NextVersion))，"
                $env:JAVA_TOOL_OPTIONS = "-Dpiko.update.api=http://127.0.0.1:$Port/latest $homeOption"
                try { Start-Process -FilePath (Join-Path $dir 'Piko.exe') -WorkingDirectory $dir }
                finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
                for ($i = 0; $i -lt 60 -and (Count-Log "启动 $([regex]::Escape($NextVersion))，") -le $started; $i++) { Start-Sleep -Seconds 1 }
                if ((Count-Log "启动 $([regex]::Escape($NextVersion))，") -le $started) { Save-Logs $name; Fail "$name : $NextVersion did not start after a Windows Installer repair" }
                Stop-App $dir
                foreach ($code in (Msi-Products)) {
                    $p = Start-Process msiexec.exe -ArgumentList @('/x', $code, '/qn', '/norestart', '/l*v', "`"$(Join-Path $logs "uninstall-$name.log")`"") -Wait -PassThru
                    if ($p.ExitCode -ne 0) { Fail "msiexec /x after $name exited $($p.ExitCode)" }
                }
            }
            Write-Summary "$oldVersion $kind took the patch to $NextVersion, started$(if ($kind -eq 'msi') { ', survived a repair' }), user data kept."
            EndStep
        }
    }
    Write-Host 'legacy smoke passed'
} finally {
    if ($script:server) { Stop-Process -Id $script:server.Id -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('fake_release.py') -and $_.CommandLine.Contains($releases) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
