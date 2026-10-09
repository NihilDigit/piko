param(
    [Parameter(Mandatory = $true)] [string] $Dist,
    [Parameter(Mandatory = $true)] [string] $Prefix,
    [Parameter(Mandatory = $true)] [string] $Launcher,
    [Parameter(Mandatory = $true)] [string] $Work
)
$ErrorActionPreference = 'Stop'
$expected = (Get-FileHash -LiteralPath $Launcher -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $Launcher).Length
$manifest = Get-Content -LiteralPath "$Dist/$Prefix-files.json" -Raw | ConvertFrom-Json
$entry = @($manifest.files | Where-Object path -eq 'Piko.exe')
if ($entry.Count -ne 1 -or $entry[0].sha256 -ne $expected -or $entry[0].size -ne $size) {
    throw 'Update manifest does not match the signed launcher'
}
if (-not $entry[0].patch) { throw 'Signed launcher must be included in the patch' }

foreach ($suffix in @('app', 'image')) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path "$Dist/$Prefix-$suffix.zip"))
    try {
        $entry = $zip.GetEntry('Piko.exe')
        if (-not $entry) { throw "No launcher in $suffix.zip" }
        $stream = $entry.Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $actual = [Convert]::ToHexString($sha.ComputeHash($stream)) }
        finally { $sha.Dispose(); $stream.Dispose() }
        if ($actual -ne $expected) { throw "Different launcher in $suffix.zip" }
    } finally { $zip.Dispose() }
}

if (Test-Path -LiteralPath $Work) { throw "Verification directory already exists: $Work" }
$portable = New-Item -ItemType Directory -Path "$Work/portable" -Force
& tar.exe -xf "$Dist/$Prefix.7z" -C $portable.FullName
if ($LASTEXITCODE -ne 0) { throw "Portable extraction failed: $LASTEXITCODE" }
if ((Get-FileHash "$portable/Piko/Piko.exe" -Algorithm SHA256).Hash -ne $expected) {
    throw 'Different launcher in portable archive'
}

$msi = (Resolve-Path "$Dist/$Prefix.msi").Path
$target = New-Item -ItemType Directory -Path "$Work/msi" -Force
$log = Join-Path $target.Parent.FullName 'msi-extraction.log'
$process = Start-Process msiexec.exe -ArgumentList @('/a', "`"$msi`"", '/qn', "TARGETDIR=`"$($target.FullName)`"", '/L*v', "`"$log`"") -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -notin @(0, 3010)) { throw "MSI extraction failed: $($process.ExitCode), see $log" }
$installed = @(Get-ChildItem -LiteralPath $target.FullName -Recurse -Filter Piko.exe)
if ($installed.Count -ne 1 -or (Get-FileHash $installed[0].FullName -Algorithm SHA256).Hash -ne $expected) {
    throw 'Different or missing launcher in MSI'
}
Write-Host "MSI, portable archive, app.zip, image.zip and files.json all match signed launcher SHA256 $expected"
