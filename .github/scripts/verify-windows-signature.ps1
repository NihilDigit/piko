param(
    [Parameter(Mandatory = $true)] [string[]] $Path,
    [switch] $TestCertificate
)
$ErrorActionPreference = 'Stop'

$signatures = @($Path | ForEach-Object { Get-AuthenticodeSignature -LiteralPath $_ })
$signer = $signatures[0].SignerCertificate
if (-not $signer) { throw "No signing certificate: $($Path[0])" }
foreach ($signature in $signatures) {
    if (-not $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $signer.Thumbprint) {
        throw "Missing or different signer: $($signature.Path)"
    }
}

$store = $null
$added = $false
try {
    if ($TestCertificate) {
        if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Test certificate trust is only permitted on the CI runner' }
        if ($signer.Subject -ne $signer.Issuer) { throw 'Expected a self-signed test certificate' }
        # CurrentUser 根存储会弹确认窗口；临时 runner 用机器存储，验证后移除。不能把 NotTrusted 当通过。
        $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'LocalMachine')
        $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $existing = $store.Certificates.Find('FindByThumbprint', $signer.Thumbprint, $false)
        if ($existing.Count -eq 0) {
            Write-Host "Temporarily trusting test certificate $($signer.Thumbprint) on the CI runner"
            $store.Add($signer)
            $added = $true
            Write-Host 'Test certificate added'
        }
    }
    foreach ($file in $Path) {
        Write-Host "Verifying Authenticode signature: $file"
        $signature = Get-AuthenticodeSignature -LiteralPath $file
        if ($signature.Status -ne 'Valid') { throw "Invalid signature on ${file}: $($signature.Status) $($signature.StatusMessage)" }
        if (-not $TestCertificate -and -not $signature.TimeStamperCertificate) { throw "Missing timestamp: $file" }
        Write-Host "$file : valid signature, signer $($signer.Subject), thumbprint $($signer.Thumbprint)"
    }
} finally {
    if ($store) {
        if ($added) { $store.Remove($signer) }
        $store.Close()
    }
}
