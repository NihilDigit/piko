# Piko desktop updater, started detached by DesktopAppUpdater right before the app exits.
# Waits for the app to exit, applies the staged update, then starts the app again.
#   patch:   copy staged files over the install dir. Every file is copied next to its target
#            first, then swapped in by rename; the replaced files are kept until all swaps
#            succeed, so a failure rolls back to the old image instead of a half-updated one.
#            Files under app\ and runtime\ that the new version no longer has are then removed.
#            The portable build uses this mode too.
#   msi:     msiexec /i. The MSI's own upgrade (remove then install) needs the app closed.
#   recover: roll back a patch that never finished (power loss, the script killed), as recorded
#            in the journal. Started by the app when it finds an unfinished journal on launch.
# The staged files are checked against the Checksums list first. The app verified them on
# download, but nothing stops the staging dir from being rewritten between that check and the
# app's exit; Bilby 0.15.1 once handed msiexec an installer re-downloaded halfway (1620).
# Kept ASCII-only: Windows PowerShell 5.1 reads a script without BOM in the ANSI code page.
param(
    # Comma-separated: the JVM and the jpackage launcher that started it as a child process.
    # The launcher outlives the JVM briefly; its exe is locked until it is gone.
    [Parameter(Mandatory = $true)] [string] $ProcessId,
    [Parameter(Mandatory = $true)] [string] $InstallDir,
    [Parameter(Mandatory = $true)] [ValidateSet('patch', 'msi', 'recover')] [string] $Mode,
    [Parameter(Mandatory = $true)] [string] $Executable,
    # Its folder also takes the failed marker.
    [Parameter(Mandatory = $true)] [string] $LogFile,
    # Patch: the staged files folder. Msi: the installer. Unused by recover.
    [string] $Source = '',
    # "<sha256>  <path relative to the staging dir>" per line, written by DesktopAppUpdater.
    # Unused by recover.
    [string] $Checksums = '',
    # Patch mode: every file of the new app image, one path per line relative to InstallDir.
    # Empty for the other modes.
    [string] $KeepList = '',
    [int] $WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'

# The long form of the install dir. Get-ChildItem reports long names, so an 8.3 path here (a
# short temp dir, a user name with spaces) matched no entry of the keep list and the stale-file
# sweep deleted the whole new app image; CI caught it with C:\Users\RUNNER~1.
$InstallDir = (Get-Item -LiteralPath $InstallDir).FullName

# Same name in DesktopAppUpdater.JOURNAL_FILE.
$JournalPath = Join-Path $InstallDir '.piko-update.journal'

function Write-Log([string] $message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss.fff} {1}' -f (Get-Date), $message
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

# Virus scanners and the search indexer open freshly written files for a moment; a copy,
# rename or delete that hits them fails with a sharing violation and works a little later.
# About 7 s in all, then the error stands.
function Invoke-Retrying([string] $what, [scriptblock] $action) {
    $delays = @(100, 300, 1000, 2000, 4000)
    for ($attempt = 0; ; $attempt++) {
        try { & $action; return }
        catch {
            if ($attempt -ge $delays.Count) { throw }
            Write-Log "retrying $what : $($_.Exception.Message)"
            Start-Sleep -Milliseconds $delays[$attempt]
        }
    }
}

function Remove-IfPresent([string] $path) {
    if (Test-Path -LiteralPath $path) {
        Invoke-Retrying "delete $path" { Remove-Item -LiteralPath $path -Force }.GetNewClosure()
    }
}

function Move-Retrying([string] $from, [string] $to) {
    Invoke-Retrying "move $from" { Move-Item -LiteralPath $from -Destination $to }.GetNewClosure()
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
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
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

# The files to install are exactly the verified ones: the checksum list names each staged file
# relative to the staging dir (files/app/x.jar), and Source is its files subfolder. Anything
# staged but not listed, or listed but not staged, stops the update: a file that appeared after
# verification would otherwise be copied over the install unchecked.
function Assert-StagedSetMatches($staged) {
    $prefix = (Split-Path -Leaf $Source) + '\'
    $expected = @{}
    foreach ($line in @(Get-Content -LiteralPath $Checksums | Where-Object { $_.Trim() })) {
        $relative = ($line -split '\s+', 2)[1].Replace('/', '\')
        if (-not $relative.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw "checksum entry outside $prefix : $relative" }
        $expected[$relative.Substring($prefix.Length).ToLowerInvariant()] = $true
    }
    $actual = @{}
    foreach ($item in $staged) { $actual[$item.Relative.ToLowerInvariant()] = $true }
    foreach ($key in $actual.Keys) { if (-not $expected.ContainsKey($key)) { throw "staged file not in the checksum list: $key" } }
    foreach ($key in $expected.Keys) { if (-not $actual.ContainsKey($key)) { throw "listed file not staged: $key" } }
}

# The journal names every file the patch touches, in swap order, and whether it existed before.
# It is written and flushed to disk before the first file is touched and deleted once the last
# swap is done, so after a power loss or a killed script it tells the next launch what to put
# back (see Restore-Journal). Without it the launch can only guess at the .old and .new files,
# and the old one deleted them, losing the only copy of the replaced files.
# Held open without sharing while this script works: a Piko started meanwhile cannot read it
# and so knows the update is in progress, not abandoned.
function New-Journal($entries) {
    $stream = [System.IO.File]::Open($JournalPath, 'CreateNew', 'ReadWrite', 'None')
    $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
    $writer.WriteLine("staging`t$(Split-Path -Parent $LogFile)")
    foreach ($entry in $entries) {
        $writer.WriteLine("$(if ($entry.Existed) { '~' } else { '+' })`t$($entry.Relative)")
    }
    $writer.Flush()
    $stream.Flush($true)
    return $stream
}

function Read-Journal($stream) {
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $false, 4096, $true)
    try {
        $entries = New-Object System.Collections.Generic.List[object]
        while ($null -ne ($line = $reader.ReadLine())) {
            $kind, $relative = $line -split "`t", 2
            if ($kind -eq '~' -or $kind -eq '+') {
                $entries.Add([pscustomobject]@{ Relative = $relative; Existed = ($kind -eq '~') })
            }
        }
        return , $entries.ToArray()
    } finally { $reader.Dispose() }
}

# Puts the old image back: every replaced file from its .old, every added file removed, every
# .new dropped. Backwards, so Piko.cfg, swapped last, is restored first. Goes on past a failed
# file to restore as much as it can, and returns whether all of it was.
function Undo-Journal($entries) {
    $complete = $true
    for ($i = $entries.Count - 1; $i -ge 0; $i--) {
        $target = Join-Path $InstallDir $entries[$i].Relative
        try {
            if ($entries[$i].Existed) {
                # No .old: the swap never reached this file, the target is still the old one.
                if (Test-Path -LiteralPath "$target.old") {
                    Remove-IfPresent $target
                    Move-Retrying "$target.old" $target
                }
            } else {
                Remove-IfPresent $target
            }
            Remove-IfPresent "$target.new"
        } catch {
            Write-Log "rollback failed for $target : $_"
            $complete = $false
        }
    }
    return $complete
}

# Added files first (nothing refers to them yet), then the replaced ones, Piko.cfg last: it
# names the class path, and up to its swap the old cfg lists jars that are all still there.
# Jars are renamed by content hash when they change, so a same-named jar only differs in mtime,
# which costs the AOT cache, not the start. An interrupted update thus leaves an image that
# still starts, and the launch can run Restore-Journal.
function Get-SwapRank($entry) {
    if ($entry.Relative -match '^app\\[^\\]+\.cfg$') { return 2 }
    if ($entry.Existed) { return 1 }
    return 0
}

function Install-Patch {
    $staged = @(Get-StagedFiles $Source '')
    if ($staged.Count -eq 0) { throw "no staged files in $Source" }
    Assert-StagedSetMatches $staged
    $entries = @(foreach ($item in $staged) {
        $target = Join-Path $InstallDir $item.Relative
        # Leftovers of an earlier run go before the journal is written: a stale .old would be
        # taken for this run's backup and restored over the current file.
        Remove-IfPresent "$target.new"
        Remove-IfPresent "$target.old"
        [pscustomobject]@{
            Source = $item.File
            Relative = $item.Relative
            Target = $target
            Existed = (Test-Path -LiteralPath $target)
        }
    })
    $entries = @($entries | Sort-Object { Get-SwapRank $_ })

    $journal = New-Journal $entries
    try {
        foreach ($entry in $entries) {
            $target = $entry.Target
            $parent = Split-Path -Parent $target
            if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
            $from = $entry.Source.FullName
            Invoke-Retrying "copy $target.new" { Copy-Item -LiteralPath $from -Destination "$target.new" -Force }.GetNewClosure()
            # The AOT cache validates the jar's mtime; restore it exactly.
            (Get-Item -LiteralPath "$target.new").LastWriteTimeUtc = $entry.Source.LastWriteTimeUtc
        }
        foreach ($entry in $entries) {
            $target = $entry.Target
            if ($entry.Existed) {
                $created = (Get-Item -LiteralPath $target).CreationTimeUtc
                Move-Retrying $target "$target.old"
            }
            Move-Retrying "$target.new" $target
            # A Windows Installer repair keeps an unversioned file whose creation time is earlier
            # than its modification time, taking it as changed by the user; otherwise it puts the
            # MSI's old version back (measured with msiexec /fomus). The replacement carries the
            # new build's mtime, so it takes over the old file's creation time. NTFS name tunneling
            # usually does this already, but it can be disabled or miss.
            if ($entry.Existed) {
                Invoke-Retrying "set the creation time of $target" { (Get-Item -LiteralPath $target).CreationTimeUtc = $created }.GetNewClosure()
            }
            Write-Log "replaced $target"
        }
    } catch {
        Write-Log "patch failed, rolling back: $_"
        $restored = Undo-Journal $entries
        $journal.Dispose()
        # Left in place when incomplete: the relaunched app finds it and has the rollback retried.
        if ($restored) { Remove-IfPresent $JournalPath } else { Write-Log 'rollback incomplete, journal kept' }
        throw
    }
    # The commit: without the journal the new image stands and any .old is a leftover.
    $journal.Dispose()
    Remove-IfPresent $JournalPath
    foreach ($entry in $entries) {
        if (-not $entry.Existed) { continue }
        try { Remove-IfPresent "$($entry.Target).old" }
        catch { Write-Log "could not remove $($entry.Target).old : $_" }
    }
    Remove-StaleFiles
}

# Recover mode. Always ends as a failed update: the new version never fully arrived.
function Restore-Journal {
    try { $journal = [System.IO.File]::Open($JournalPath, 'Open', 'ReadWrite', 'None') }
    catch [System.IO.FileNotFoundException] { Write-Log 'no journal, nothing to recover'; return }
    # Another updater holds it (a second launch also started a recovery); that one relaunches.
    catch [System.IO.IOException] { Write-Log "journal in use, leaving it: $_"; exit 0 }
    $entries = Read-Journal $journal
    Write-Log "rolling back an interrupted update of $($entries.Count) files"
    $restored = Undo-Journal $entries
    $journal.Dispose()
    # Dropped even when incomplete: kept, every launch would start another recovery that fails
    # the same way and the app would never come up. The swap order leaves a startable image.
    Remove-IfPresent $JournalPath
    if (-not $restored) { throw 'interrupted update could not be fully rolled back' }
    throw 'the update was interrupted and has been rolled back'
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

# Read by DesktopAppUpdater on the next launch, which then says the update did not finish.
function Set-Failed([string] $reason) {
    Set-Content -LiteralPath (Join-Path (Split-Path -Parent $LogFile) 'failed') -Value $reason -Encoding UTF8
}

function Start-App {
    $exe = Join-Path $InstallDir $Executable
    try {
        Start-Process -FilePath $exe -WorkingDirectory $InstallDir
        Write-Log "started $exe"
        return $true
    } catch {
        Write-Log "could not start $exe : $_"
        return $false
    }
}

# Only after success: a failed update is retried from the same download, and the logs stay
# for the update history. The checksum and keep lists go with the files they describe.
function Remove-StagedUpdate {
    foreach ($path in @($Source, $Checksums, $KeepList)) {
        if (-not $path -or -not (Test-Path -LiteralPath $path)) { continue }
        try { Invoke-Retrying "delete $path" { Remove-Item -LiteralPath $path -Recurse -Force }.GetNewClosure() }
        catch { Write-Log "could not remove staged $path : $_" }
    }
}

Write-Log "update started: mode=$Mode pid=$ProcessId dir=$InstallDir"
if (-not (Wait-AppExit)) {
    # Nothing has been touched. If the app is in fact still running, the start below only hands
    # over to it (single instance); if it hangs without a window, the user gets one back.
    Write-Log "app did not exit within $WaitSeconds s, update aborted"
    Set-Failed "the app did not exit within $WaitSeconds s"
    Start-App | Out-Null
    exit 1
}
Write-Log 'app exited'

$exitCode = 0
try {
    if ($Mode -eq 'recover') {
        Restore-Journal
    } else {
        # After the exit, not before: until then the app can still write to the staging dir.
        Assert-StagedFiles
        if ($Mode -eq 'patch') { Install-Patch } else { Install-Msi }
        Write-Log 'update applied'
        Remove-StagedUpdate
    }
} catch {
    Write-Log "update failed: $_"
    $exitCode = 1
    Set-Failed "$_"
}

# Start the app either way: after a failure the old version is still intact. For the msi mode
# that holds because the MSI removes the old version inside its install transaction (see
# desktopApp/package/windows/transactional-upgrade.ps1); Windows Installer then keeps the old
# version's files but only as advertised, so later updates offer the download page.
if (-not (Start-App)) { $exitCode = 1 }
exit $exitCode
