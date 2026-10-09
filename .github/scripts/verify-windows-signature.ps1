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
        # 只在临时 runner 上信任本次测试证书，再让 WinVerifyTrust 验证文件内容；不能把 NotTrusted 当通过。
        $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('Root', 'CurrentUser')
        $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $existing = $store.Certificates.Find('FindByThumbprint', $signer.Thumbprint, $false)
        if ($existing.Count -eq 0) {
            $store.Add($signer)
            $added = $true
        }
    }
    foreach ($file in $Path) {
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
