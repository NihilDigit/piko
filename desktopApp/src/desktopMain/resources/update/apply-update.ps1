# Piko desktop updater, started detached by DesktopAppUpdater right before the app exits.
# Waits for the app to exit, applies the staged update, then starts the app again.
#   patch: copy staged files over the install dir. Every file is copied next to its target
#          first, then swapped in by rename; the replaced files are kept until all swaps
#          succeed, so a failure rolls back to the old image instead of a half-updated one.
#          Files under app\ and runtime\ that the new version no longer has are then removed.
#          The portable build's full update uses this mode too, with every changed file staged.
#   msi:   msiexec /i. The MSI's own upgrade (remove then install) needs the app closed.
# Either way the staged files are checked against the Checksums list first. The app verified
# them on download, but nothing stops the staging dir from being rewritten between that check
# and the app's exit; Bilby 0.15.1 once handed msiexec an installer re-downloaded halfway (1620).
# Kept ASCII-only: Windows PowerShell 5.1 reads a script without BOM in the ANSI code page.
param(
    # Comma-separated: the JVM and the jpackage launcher that started it as a child process.
    # The launcher outlives the JVM briefly; its exe is locked until it is gone.
    [Parameter(Mandatory = $true)] [string] $ProcessId,
    [Parameter(Mandatory = $true)] [string] $InstallDir,
    [Parameter(Mandatory = $true)] [ValidateSet('patch', 'msi')] [string] $Mode,
    [Parameter(Mandatory = $true)] [string] $Source,
    [Parameter(Mandatory = $true)] [string] $Executable,
    [Parameter(Mandatory = $true)] [string] $LogFile,
    # "<sha256>  <path relative to the staging dir>" per line, written by DesktopAppUpdater.
    [Parameter(Mandatory = $true)] [string] $Checksums,
    # Patch mode: every file of the new app image, one path per line relative to InstallDir.
    # Empty for msi mode.
    [string] $KeepList = ''
)

$ErrorActionPreference = 'Stop'

function Write-Log([string] $message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss.fff} {1}' -f (Get-Date), $message
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

# Not Get-FileHash: it lives in a module that Windows PowerShell 5.1 autoloads through
# PSModulePath, and a PSModulePath inherited from PowerShell 7 (Piko started from a pwsh
# terminal) makes the cmdlet unknown.
function Get-Sha256([string] $path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $sha.Dispose() }
}

function Assert-StagedFiles {
    $stagingDir = Split-Path -Parent $Checksums
    $lines = @(Get-Content -LiteralPath $Checksums | Where-Object { $_.Trim() })
    if ($lines.Count -eq 0) { throw "empty checksum list $Checksums" }
    foreach ($line in $lines) {
        $expected, $relative = $line -split '\s+', 2
        $path = Join-Path $stagingDir $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "staged file missing: $relative" }
        $actual = Get-Sha256 $path
        # -ne compares strings case-insensitively; the list is lower-case hex, BitConverter upper.
        if ($actual -ne $expected) { throw "staged file changed: $relative ($actual != $expected)" }
    }
    Write-Log "verified $($lines.Count) staged files"
}

function Wait-AppExit {
    $deadline = (Get-Date).AddSeconds(120)
    foreach ($id in ($ProcessId -split ',')) {
        $process = Get-Process -Id ([int] $id) -ErrorAction SilentlyContinue
        if ($null -eq $process) { continue }
        $left = [int] ($deadline - (Get-Date)).TotalMilliseconds
        if ($left -le 0 -or -not $process.WaitForExit($left)) { return $false }
    }
    return $true
}

# Relative paths are built from the names while walking down, never by cutting a prefix off
# FullName: the staging dir may come as an 8.3 short path (C:\Users\RUNNER~1\...) while
# Get-ChildItem reports long names, so the prefix length is wrong and every file lands in a
# made-up subfolder (on CI it was ...\PikoSmoke\es\PikoSmoke.exe). Short temp paths are common
# for user names with spaces or non-ASCII characters.
function Get-StagedFiles([string] $dir, [string] $prefix) {
    foreach ($item in @(Get-ChildItem -LiteralPath $dir -Force)) {
        $relative = if ($prefix) { "$prefix\$($item.Name)" } else { $item.Name }
        if ($item.PSIsContainer) {
            Get-StagedFiles $item.FullName $relative
        } else {
            [pscustomobject]@{ File = $item; Relative = $relative }
        }
    }
}

function Install-Patch {
    $staged = @(Get-StagedFiles $Source '')
    if ($staged.Count -eq 0) { throw "no staged files in $Source" }
    $entries = foreach ($item in $staged) {
        [pscustomobject]@{
            Source = $item.File
            Target = Join-Path $InstallDir $item.Relative
        }
    }

    $replaced = New-Object System.Collections.Generic.List[string]
    $placed = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($entry in $entries) {
            $target = $entry.Target
            foreach ($stale in @("$target.new", "$target.old")) {
                if (Test-Path -LiteralPath $stale) { Remove-Item -LiteralPath $stale -Force }
            }
            $parent = Split-Path -Parent $target
            if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
            Copy-Item -LiteralPath $entry.Source.FullName -Destination "$target.new"
            # The AOT cache validates the jar's mtime; restore it exactly.
            (Get-Item -LiteralPath "$target.new").LastWriteTimeUtc = $entry.Source.LastWriteTimeUtc
        }
        foreach ($entry in $entries) {
            $target = $entry.Target
            if (Test-Path -LiteralPath $target) {
                Move-Item -LiteralPath $target -Destination "$target.old"
                $replaced.Add($target)
            }
            Move-Item -LiteralPath "$target.new" -Destination $target
            $placed.Add($target)
            Write-Log "replaced $target"
        }
    } catch {
        Write-Log "patch failed, rolling back: $_"
        foreach ($target in $placed) { Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue }
        foreach ($target in $replaced) { Move-Item -LiteralPath "$target.old" -Destination $target -ErrorAction SilentlyContinue }
        foreach ($entry in $entries) { Remove-Item -LiteralPath "$($entry.Target).new" -Force -ErrorAction SilentlyContinue }
        throw
    }
    foreach ($target in $replaced) {
        Remove-Item -LiteralPath "$target.old" -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath "$target.old") { Write-Log "could not remove $target.old" }
    }
    Remove-StaleFiles
}

# Files under app\ and runtime\ that the new app image does not have: a module jar renamed by
# its content hash, a dependency upgraded or dropped, a runtime file gone after a JDK update.
# They are off the class path and not in the MSI's file table either, so nothing else ever
# removes them: each update would leave more behind, and uninstalling would leave them too.
# Only those two folders: the install dir itself may hold files the user put there (old
# versions defaulted downloads to the working directory, which is the install dir).
# Runs only after every swap succeeded.
function Remove-StaleFiles {
    if (-not $KeepList) { return }
    $keep = @{}
    foreach ($line in (Get-Content -LiteralPath $KeepList)) {
        if ($line.Trim()) { $keep[(Join-Path $InstallDir $line.Trim().Replace('/', '\')).ToLowerInvariant()] = $true }
    }
    # A list without the app folder is not one this script expects; leave everything alone.
    if ($keep.Count -eq 0) { return }
    foreach ($folder in @('app', 'runtime')) {
        $dir = Join-Path $InstallDir $folder
        # Dot files are installer metadata, not app files: the MSI installs app\.package (in its
        # file table) and leaves out the image's app\.jpackage.xml, so the list never matches them.
        $files = @(Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { -not $_.Name.StartsWith('.') })
        foreach ($file in $files) {
            if (-not $keep.ContainsKey($file.FullName.ToLowerInvariant())) {
                Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
                Write-Log "removed stale $($file.FullName)"
            }
        }
    }
}

function Install-Msi {
    $msiLog = Join-Path (Split-Path -Parent $LogFile) 'msiexec.log'
    $arguments = @('/i', "`"$Source`"", '/passive', '/norestart', '/l*v', "`"$msiLog`"")
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru
    # 3010: success, reboot required.
    if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 3010) {
        throw "msiexec exited with $($process.ExitCode), see $msiLog"
    }
}

Write-Log "update started: mode=$Mode pid=$ProcessId dir=$InstallDir"
if (-not (Wait-AppExit)) {
    Write-Log 'app did not exit within 120 s, update aborted'
    exit 1
}
Write-Log 'app exited'

$exitCode = 0
try {
    # After the exit, not before: until then the app can still write to the staging dir.
    Assert-StagedFiles
    if ($Mode -eq 'patch') { Install-Patch } else { Install-Msi }
    Write-Log 'update applied'
} catch {
    Write-Log "update failed: $_"
    $exitCode = 1
    # Read by DesktopAppUpdater on the next launch, which then says the update did not finish.
    Set-Content -LiteralPath (Join-Path (Split-Path -Parent $LogFile) 'failed') -Value "$_" -Encoding UTF8
}

# Start the app either way: after a failure the old version is still intact. For the msi mode
# that holds because the MSI removes the old version inside its install transaction (see
# desktopApp/package/windows/transactional-upgrade.ps1); Windows Installer then keeps the old
# version's files but only as advertised, so later updates offer the download page.
$exe = Join-Path $InstallDir $Executable
try {
    Start-Process -FilePath $exe -WorkingDirectory $InstallDir
    Write-Log "started $exe"
} catch {
    Write-Log "could not start $exe : $_"
    $exitCode = 1
}
exit $exitCode
