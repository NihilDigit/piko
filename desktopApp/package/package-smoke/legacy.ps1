# 已发布的老版本（1.0.0、1.1.0）升级到这次构建的冒烟：真实的老版本便携 zip 与 MSI，升级到 Next，断言各走预定的路、
# 升级后能启动、版本号对、用户数据还在。
#
# 老客户端只认三个附件：-files.json、-app.zip、.msi。清单里 patch=false 的文件与本机逐字节相同才走补丁；否则 MSI 安装版
# 跑 msiexec 整包重装，1.1.0 的便携版去找已不再发布的 .zip，1.0.0 的便携版直接给下载页。Next 要照 release.yml 打：
# 1.1.0 的清单作对照（-PpikoUpdateBases），与它不同的文件都标成补丁、装进 app.zip；1.0.0 的清单只作核对
# （-PpikoUpdateRetired），它必须走不了补丁（build.gradle.kts 的 UpdateArtifactsTask）。
#
# 1.1.0 带 piko.update.api 与 piko.update.auto，整个流程由它自己走：查到 Next、下载、退出、交给它自带的更新脚本，
# 断言走的是补丁。带 -Delta 时另跑一轮 1.1.0 便携版，假 Release 多挂 -from-1.1.0.zip，断言它用差分还原、没有下完整的 app.zip。
#
# 1.0.0 的更新脚本在 8.3 临时目录下把补丁写错位置、仍报成功，所以不再给它补丁（理由见 shared/.../update/CLAUDE.md）。
# 它有 piko.update.api，没有自动安装，这里照它的 resolve 判断：先断言它的 canPatch 对 Next 失败。便携版到此为止，
# 它只给下载页；MSI 版照它的做法把 Next 的 MSI 放进暂存目录，再以 msi 模式跑 v1.0.0 原样的 apply-update.ps1。
#
# MSI 安装版升级后另做一次 Windows Installer 修复，新版照常启动。
#
# 老版本的数据在 ~/.piko（没有 PikoHome），本机跑时给 -UserHome 一个临时目录，经 -Duser.home 隔开真实的数据。
# Next 必须以默认包名 Piko 与正式的 UpgradeCode 打：补丁换的是 Piko.exe 与 app\Piko.cfg，包名不同就对不上老版本的文件；
# UpgradeCode 不同，msiexec 就不升级老版本，而是另装一份。
#
# 用法（pwsh）：
#   ./legacy.ps1 -Next <目录> -NextVersion 9.9.1 -Old <目录> -Work <临时目录> [-Kinds portable,msi] [-UserHome <目录>] [-Delta]
#   <Next> 里是 piko-windows-x64-<版本>-files.json / -app.zip / .msi，带 -Delta 时另有 -from-1.1.0.zip
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
    [int] $UpdateTimeoutSeconds = 300,
    [switch] $Delta
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

# 老版本的 canPatch：清单里 patch=false 的文件本机都有且逐字节相同。返回对不上的那些
function Unpatchable-Files([string] $dir, $manifest) {
    @(foreach ($entry in @($manifest.files | Where-Object { -not $_.patch })) {
        $path = Join-Path $dir ($entry.path.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Sha256 $path) -ne $entry.sha256) { $entry.path }
    })
}

function Assert-OldClientTakesPatch([string] $dir, $manifest, [string] $what) {
    $differs = @(Unpatchable-Files $dir $manifest)
    if ($differs.Count -gt 0) { Fail "$what would not take the patch; not covered by app.zip: $($differs -join ', ')" }
}

# 返回挡住补丁的文件，写进摘要
function Assert-OldClientRejectsPatch([string] $dir, $manifest, [string] $what) {
    $differs = @(Unpatchable-Files $dir $manifest)
    if ($differs.Count -eq 0) { Fail "$what would take the patch, whose script fails under an 8.3 temp directory" }
    return $differs
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

# 1.0.0 没有自动安装：它的 MSI 版补丁对不上时下载新版 MSI，退出后以 msi 模式交给自带的更新脚本，
# 脚本跑完 msiexec 再拉起 Piko.exe。这里把 MSI 放进暂存目录、跑它原样的脚本；拉起的新版经继承的 JAVA_TOOL_OPTIONS 指向假 Release
function Invoke-OldInstaller([string] $version, [string] $dir) {
    $stage = Join-Path $Work "stage\$version"
    if (Test-Path -LiteralPath $stage) { Remove-Item -Recurse -Force -LiteralPath $stage }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    $msi = Join-Path $stage "piko-windows-$Arch-$NextVersion.msi"
    Copy-Item -LiteralPath (Asset $Next $NextVersion '.msi') -Destination $msi
    $script = Join-Path $stage 'apply-update.ps1'
    git -C $PSScriptRoot show "v${version}:desktopApp/src/desktopMain/resources/update/apply-update.ps1" | Set-Content -LiteralPath $script -Encoding ascii
    if ($LASTEXITCODE -ne 0) { Fail "could not read v$version's apply-update.ps1 from git (fetch the tags)" }

    $gone = Start-Process cmd.exe -ArgumentList '/c', 'exit' -PassThru -WindowStyle Hidden
    $gone.WaitForExit()
    $env:JAVA_TOOL_OPTIONS = "-Dpiko.update.api=http://127.0.0.1:$Port/latest $homeOption"
    try {
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $script -ProcessId $gone.Id -InstallDir $dir `
            -Mode msi -Source $msi -Executable 'Piko.exe' -LogFile (Join-Path $stage 'update.log') | Out-Host
    } finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
    if ($LASTEXITCODE -ne 0) { Save-Logs "old-$version"; Fail "v$version's apply-update.ps1 exited $LASTEXITCODE" }
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
# 老客户端缺 .msi 附件就当作还没有新版；走补丁时不下载它，所以 Next 没打 MSI 时放一个占位，只是 1.0.0 的 MSI 版那一轮跑不了
$msi = Join-Path $Next "piko-windows-$Arch-$NextVersion.msi"
$hasMsi = Test-Path -LiteralPath $msi
if ($hasMsi) { Copy-Item -LiteralPath $msi -Destination $nextDir }
else { Set-Content -LiteralPath (Join-Path $nextDir "piko-windows-$Arch-$NextVersion.msi") -Value 'placeholder' -Encoding ascii }
Set-Content -LiteralPath (Join-Path $releases 'latest.txt') -Value $NextVersion -Encoding utf8
if ($Kinds -contains 'msi' -and (Msi-Products).Count -gt 0) { Fail 'Piko is already installed through its MSI; uninstall it first' }

# 每一轮的老版本、安装方式与预期的路：patch 补丁，delta 差分，msiexec 整包重装，page 只给下载页
$rounds = @(foreach ($oldVersion in $OldVersions) {
    foreach ($kind in $Kinds) {
        $route = if ($oldVersion -ne '1.0.0') { 'patch' } elseif ($kind -eq 'msi') { 'msiexec' } else { 'page' }
        [pscustomobject]@{ Version = $oldVersion; Kind = $kind; Route = $route; Name = "$kind-$oldVersion" }
    }
})
$deltaAsset = "piko-windows-$Arch-$NextVersion-from-1.1.0.zip"
if ($Delta) {
    if (-not (Test-Path -LiteralPath (Join-Path $Next $deltaAsset))) { Fail "missing $deltaAsset in $Next" }
    $rounds += [pscustomobject]@{ Version = '1.1.0'; Kind = 'portable'; Route = 'delta'; Name = 'portable-1.1.0-delta' }
}
$bytesLog = Join-Path $releases 'bytes.log'

Start-Server
try {
    foreach ($round in $rounds) {
        $oldVersion = $round.Version
        $kind = $round.Kind
        $route = $round.Route
        $name = $round.Name
        Step "$oldVersion $kind -> $NextVersion ($route)"
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
        if ($route -in @('patch', 'delta')) {
            Assert-OldClientTakesPatch $dir $nextManifest $name
        } else {
            $blocking = Assert-OldClientRejectsPatch $dir $nextManifest $name
        }
        if ($route -eq 'page') {
            # 1.0.0 的 resolve：补丁对不上、又不是 MSI 安装的，只给下载页，没有可跑的
            Write-Summary "$oldVersion $kind does not take the patch to $NextVersion (lacks $($blocking -join ', ')); it offers the download page."
            EndStep
            continue
        }
        if ($route -eq 'msiexec' -and -not $hasMsi) { Fail "$name updates through msiexec; build $NextVersion's MSI" }
        $sentinel = Join-Path $dataHome '.piko\legacy-smoke-sentinel.txt'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sentinel) | Out-Null
        Set-Content -LiteralPath $sentinel -Value $name -Encoding ascii

        $plan = if ($route -eq 'delta') { 'Delta' } else { 'Patch' }
        $planPattern = "自动安装 $([regex]::Escape($NextVersion))：$plan"
        $fallbackPattern = '差分还原失败'
        if ($route -eq 'msiexec') {
            Invoke-OldInstaller $oldVersion $dir
        } else {
            if ($route -eq 'delta') { Copy-Item -LiteralPath (Join-Path $Next $deltaAsset) -Destination $nextDir }
            $planLines = Count-Log $planPattern
            $fallbackLines = Count-Log $fallbackPattern
            $bytesBefore = if (Test-Path -LiteralPath $bytesLog) { @(Get-Content -LiteralPath $bytesLog).Count } else { 0 }
            $env:JAVA_TOOL_OPTIONS = "-Dpiko.update.api=http://127.0.0.1:$Port/latest -Dpiko.update.auto=true $homeOption"
            try { Start-Process -FilePath (Join-Path $dir 'Piko.exe') -WorkingDirectory $dir }
            finally { Remove-Item Env:JAVA_TOOL_OPTIONS }
        }
        Wait-Updated $dir $name
        if ($route -ne 'msiexec' -and (Count-Log $planPattern) -le $planLines) {
            Save-Logs $name
            Fail "$name did not take the $plan plan (see its log)"
        }
        if ($route -eq 'delta') {
            Remove-Item -LiteralPath (Join-Path $nextDir $deltaAsset)
            # 差分还原失败时 1.1.0 改下完整的 app.zip，照样更新成功，只看结果分不出来
            $fetched = @(Get-Content -LiteralPath $bytesLog | Select-Object -Skip $bytesBefore)
            if ((Count-Log $fallbackPattern) -gt $fallbackLines -or ($fetched -match '-app\.zip ')) {
                Save-Logs $name
                Fail "$name fell back from the delta to the full app.zip (see its log)"
            }
            if (-not ($fetched -match [regex]::Escape("$deltaAsset "))) { Save-Logs $name; Fail "$name never downloaded $deltaAsset" }
        }
        Start-Sleep -Seconds 15
        if ((App-Processes $dir).Count -eq 0) { Save-Logs $name; Fail "$name : $NextVersion exited after the update" }
        if ((Count-Log "启动 $([regex]::Escape($NextVersion))，") -eq 0) { Save-Logs $name; Fail "$name : $NextVersion never logged its start" }
        Stop-App $dir
        Assert-Tree $dir $nextManifest
        if (-not (Test-Path -LiteralPath $sentinel)) { Fail "$name lost the user data in ~/.piko" }
        if ($kind -eq 'msi') {
            if ($route -eq 'msiexec' -and (Msi-Products).Count -ne 1) { Fail "$name : Windows Installer lists $((Msi-Products).Count) Piko products after msiexec" }
            # Windows Installer 修复（msiexec /f 默认的 omus）后补丁仍在、新版仍起得来。补丁那一跳用的是老版本自带的脚本，
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
        $how = switch ($route) {
            'msiexec' { "does not take the patch (lacks $($blocking -join ', ')), reinstalled $NextVersion through msiexec" }
            'delta' { "restored $NextVersion from $deltaAsset" }
            default { "took the patch to $NextVersion" }
        }
        Write-Summary "$oldVersion $kind $how, started$(if ($kind -eq 'msi') { ', survived a repair' }), user data kept."
        EndStep
    }
    Write-Host 'legacy smoke passed'
} finally {
    if ($script:server) { Stop-Process -Id $script:server.Id -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('fake_release.py') -and $_.CommandLine.Contains($releases) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
