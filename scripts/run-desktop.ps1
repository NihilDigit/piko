param(
    [switch]$NoBuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$devLaunchDir = Join-Path $repoRoot 'desktopApp\build\dev-launch'
$javaExecFile = Join-Path $devLaunchDir 'java-exec.txt'
$argsFile = Join-Path $devLaunchDir 'piko.args'
$devSocketFile = Join-Path $devLaunchDir 'dev-socket.txt'

function Stop-RunningDevInstance {
    param([string]$SocketPathFile)
    if (-not (Test-Path -LiteralPath $SocketPathFile)) { return }
    $socketPath = (Get-Content -LiteralPath $SocketPathFile -Raw -Encoding utf8).Trim()
    if (-not $socketPath -or -not (Test-Path -LiteralPath $socketPath)) { return }
    $epType = [Type]::GetType('System.Net.Sockets.UnixDomainSocketEndPoint, System.Net.Sockets', $false)
    if ($null -eq $epType) { return }
    $socket = $null
    try {
        $endpoint = [Activator]::CreateInstance($epType, @($socketPath))
        $socket = [System.Net.Sockets.Socket]::new(
            [System.Net.Sockets.AddressFamily]::Unix,
            [System.Net.Sockets.SocketType]::Stream,
            [System.Net.Sockets.ProtocolType]::Unspecified
        )
        $socket.Connect($endpoint)
        $payload = [System.Text.Encoding]::UTF8.GetBytes('--dev-quit')
        $len = [System.BitConverter]::GetBytes([System.Net.IPAddress]::HostToNetworkOrder([int]$payload.Length))
        $frame = [byte[]]::new(4 + $payload.Length)
        [Array]::Copy($len, 0, $frame, 0, 4)
        [Array]::Copy($payload, 0, $frame, 4, $payload.Length)
        $offset = 0
        while ($offset -lt $frame.Length) {
            $sent = $socket.Send($frame, $offset, $frame.Length - $offset, [System.Net.Sockets.SocketFlags]::None)
            if ($sent -le 0) { break }
            $offset += $sent
        }
        for ($i = 0; $i -lt 40; $i++) {
            if (-not (Test-Path -LiteralPath $socketPath)) {
                Start-Sleep -Milliseconds 150
                break
            }
            Start-Sleep -Milliseconds 75
        }
    } catch {
        # Socket file may be stale after an unclean exit; ignore and let Gradle proceed.
    } finally {
        if ($null -ne $socket) { $socket.Dispose() }
    }
}

$needsBuild = -not $NoBuild -or -not (Test-Path -LiteralPath $javaExecFile) -or -not (Test-Path -LiteralPath $argsFile)
if ($needsBuild) {
    Stop-RunningDevInstance -SocketPathFile $devSocketFile
    Push-Location -LiteralPath $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') :desktopApp:prepareDevLaunch
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

$javaExec = (Get-Content -LiteralPath $javaExecFile -Raw -Encoding utf8).Trim()
if (-not (Test-Path -LiteralPath $javaExec)) {
    throw "Java executable not found: $javaExec"
}

# 直接以独立 JVM 进程启动开发版窗口，不占用 Gradle Daemon。
& $javaExec "@$argsFile" @AppArgs
exit $LASTEXITCODE
