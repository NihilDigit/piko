# Windows portable package: the whole app image as a .7z that extracts to the app folder.
# Shared by release.yml and test.yml so both pack it the same way.
#
# 7z, not zip: zip keeps only the packing machine's local DOS time. CI runs in UTC, so in any other
# time zone the jars come out hours off and the AOT cache, which checks their mtime, is rejected
# whole. Explorer and Expand-Archive ignore the zip's UTC extra timestamps (measured); 7z stores
# UTC times itself.
# The portable marker next to Piko.exe puts the data under the app folder (PikoHome). The MSI and
# the in-app update files are built before this, so only this package carries it.
# Kept ASCII-only: Windows PowerShell 5.1 reads a script without BOM in the ANSI code page.
param(
    [Parameter(Mandatory = $true)] [string] $AppDir,
    [Parameter(Mandatory = $true)] [string] $Output
)
$ErrorActionPreference = 'Stop'

$sevenZip = Get-Command 7z -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (-not $sevenZip) {
    $sevenZip = Join-Path $env:ProgramFiles '7-Zip\7z.exe'
    # windows-latest ships 7-Zip; the ARM64 runner image may not.
    if (-not (Test-Path -LiteralPath $sevenZip)) { choco install 7zip -y --no-progress }
    if (-not (Test-Path -LiteralPath $sevenZip)) { throw "7-Zip not found at $sevenZip" }
}

$app = Get-Item -LiteralPath $AppDir
# Join-Path would glue an absolute -Output onto the current folder (C:\x\C:\y).
$archive = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$marker = Join-Path $app.FullName 'portable'
New-Item -ItemType File -Force -Path $marker | Out-Null
# From the parent folder: 7z keeps a relative argument's leading folders in the entry names.
Push-Location -LiteralPath $app.Parent.FullName
try {
    # -mf=off: none of the executable filters (BCJ, ARM64, ...) 7-Zip picks by file type. The
    # in-app update unpacks this with the system tar.exe (libarchive), and older libarchive builds
    # need not know the newer filters. -mtm=on stores mtimes (the default, spelled out).
    & $sevenZip a -t7z -m0=lzma2 -mf=off -mtm=on -bd $archive $app.Name
    if ($LASTEXITCODE -ne 0) { throw "7z exited $LASTEXITCODE" }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $marker
}
