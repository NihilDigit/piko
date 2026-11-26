<#
.SYNOPSIS
    生成 Piko README 顶部动态 Banner 与五语 × 浅/深双色真实截图（含 30fps feed*.avif）。

.DESCRIPTION
    基于 :shots 模块无头渲染引擎（只读隔离克隆 ~/.piko 登录态与本地目录树，不修改原目录），
    调用本机 ffmpeg (libsvtav1) 封装动态 AVIF 并生成 JPG 回退图与本地浏览器预览页。

.EXAMPLE
    # 仅重新渲染并预览动态 Banner（浅/深色 AVIF + JPG）
    pwsh -File scripts/render-readme-assets.ps1 -Mode banner -Install -OpenPreview

.EXAMPLE
    # 快速预览简体中文浅色单套（或仅重出其中几张）
    pwsh -File scripts/render-readme-assets.ps1 -Mode shots -Lang zh-cn -Theme light -Only hero,feed -OpenPreview

.EXAMPLE
    # 全量生成五语 × 浅/深双色并同步到 docs/ 与 docs/screenshots/
    pwsh -File scripts/render-readme-assets.ps1 -Mode all -Lang all -Theme all -Install
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'banner', 'social', 'shots')]
    [string]$Mode = 'all',

    [ValidateSet('all', 'zh-cn', 'zh-hant', 'en', 'ja', 'ko')]
    [string]$Lang = 'all',

    [ValidateSet('all', 'light', 'dark')]
    [string]$Theme = 'all',

    [string]$Only = '',

    [switch]$Install,

    [switch]$OpenPreview
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

function Invoke-AvifEncode {
    param(
        [string]$InputPattern,
        [string]$OutputAvif,
        [int]$Fps = 30,
        [int]$Crf = 34,
        [int]$Preset = 5,
        [switch]$Alpha
    )
    Write-Host "Encoding $OutputAvif ..." -ForegroundColor Cyan
    if ($Alpha) {
        & ffmpeg -y -framerate $Fps -i $InputPattern `
            -filter_complex "[0:v]split[c][a];[c]format=yuv420p[col];[a]alphaextract,format=gray,setparams=colorspace=bt709:range=pc[al]" `
            -map "[col]" -map "[al]" `
            -c:v:0 libsvtav1 -crf:v:0 42 -preset:v:0 4 `
            -c:v:1 libaom-av1 -b:v:1 0 -crf:v:1 38 -cpu-used:v:1 6 `
            -f avif $OutputAvif
    } else {
        & ffmpeg -y -framerate $Fps -i $InputPattern -c:v libsvtav1 -crf $Crf -preset $Preset -pix_fmt yuv420p -f avif $OutputAvif
    }
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $OutputAvif" }
}

function Write-PreviewHtml {
    param([string]$OutFile)
    $html = @'
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>Piko README Assets Preview</title>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; background: #ffffff; color: #1b2230; margin: 24px auto; max-width: 1280px; padding: 0 20px; }
  body.dark { background: #0d1117; color: #e5e9f2; }
  .toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 20px; }
  button { padding: 6px 14px; border-radius: 8px; border: 1px solid #8895; background: transparent; color: inherit; cursor: pointer; }
  h2 { margin-top: 28px; font-size: 18px; }
  img { max-width: 100%; border-radius: 14px; display: block; margin: 10px 0 24px; box-shadow: 0 6px 24px rgba(0,0,0,0.08); }
  img#banner { border-radius: 0; box-shadow: none; }
</style>
</head>
<body>
<div class="toolbar">
  <strong>Piko README Assets Preview</strong>
  <button onclick="toggleTheme()">切换浅色 / 深色</button>
</div>
<h2>Banner (AVIF 30fps)</h2>
<img id="banner" src="../../../docs/banner.avif" alt="Banner">
<h2>1. Hero</h2>
<img id="hero" src="hero.jpg" alt="Hero">
<h2>2. Feed (AVIF 30fps)</h2>
<img id="feed" src="feed.avif" alt="Feed">
<h2>3. Links</h2>
<img id="links" src="links.jpg" alt="Links">
<h2>4. Customize</h2>
<img id="customize" src="customize.jpg" alt="Customize">
<h2>5. Tools</h2>
<img id="tools" src="tools.jpg" alt="Tools">
<script>
let dark = false;
function toggleTheme() {
  dark = !dark;
  document.body.classList.toggle('dark', dark);
  const s = dark ? '-dark' : '';
  document.getElementById('banner').src = `../../../docs/banner${s}.avif`;
  document.getElementById('hero').src = `hero${s}.jpg`;
  document.getElementById('feed').src = `feed${s}.avif`;
  document.getElementById('links').src = `links${s}.jpg`;
  document.getElementById('customize').src = `customize${s}.jpg`;
  document.getElementById('tools').src = `tools${s}.jpg`;
}
</script>
</body>
</html>
'@
    Set-Content -Path $OutFile -Value $html -Encoding UTF8
}

try {
    if ($Mode -eq 'all' -or $Mode -eq 'banner') {
        Write-Host "==> Rendering Banner frames (light + dark) ..." -ForegroundColor Green
        & ./gradlew :shots:run "--args=banner" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Banner render failed" }

        $bannerOutDir = if ($Install) { "docs" } else { "build/shots/readme" }
        New-Item -ItemType Directory -Force -Path $bannerOutDir | Out-Null

        Invoke-AvifEncode -InputPattern "build/shots/banner-light-frames/frame_%04d.png" -OutputAvif "$bannerOutDir/banner.avif" -Alpha
        Invoke-AvifEncode -InputPattern "build/shots/banner-dark-frames/frame_%04d.png" -OutputAvif "$bannerOutDir/banner-dark.avif" -Alpha
        & ffmpeg -y -f lavfi -i "color=c=#ffffff:s=1200x320" -i "build/shots/banner-light-frames/frame_0000.png" -filter_complex "[0][1]overlay=shortest=1" -frames:v 1 -update 1 -q:v 3 "$bannerOutDir/banner.jpg"
        & ffmpeg -y -f lavfi -i "color=c=#0d1117:s=1200x320" -i "build/shots/banner-dark-frames/frame_0000.png" -filter_complex "[0][1]overlay=shortest=1" -frames:v 1 -update 1 -q:v 3 "$bannerOutDir/banner-dark.jpg"
    }

    if ($Mode -eq 'all' -or $Mode -eq 'social') {
        Write-Host "==> Rendering Social Preview (1280x640, light + dark) ..." -ForegroundColor Green
        & ./gradlew :shots:run "--args=social-preview" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Social preview render failed" }

        if ($Install) {
            Copy-Item -Path "build/shots/social-preview.png" -Destination "docs/social-preview.png" -Force
            Write-Host "Installed docs/social-preview.png" -ForegroundColor Green
        }
    }

    if ($Mode -eq 'all' -or $Mode -eq 'shots') {
        $langs = if ($Lang -eq 'all') { @('zh-cn', 'zh-hant', 'en', 'ja', 'ko') } else { @($Lang) }
        $themes = if ($Theme -eq 'all') { @('light', 'dark') } else { @($Theme) }
        $langSuffixMap = @{
            'zh-cn'   = ''
            'zh-hant' = '-zh-Hant'
            'en'      = '-en'
            'ja'      = '-ja'
            'ko'      = '-ko'
        }

        foreach ($l in $langs) {
            $argLine = "readme-shots --lang $l --theme $Theme"
            if ($Only) { $argLine += " --only $Only" }
            Write-Host "==> Rendering README shots: $argLine" -ForegroundColor Green
            & ./gradlew :shots:run "--args=$argLine" --console=plain
            if ($LASTEXITCODE -ne 0) { throw "README shots failed for $l" }
        }

        $onlySet = if ($Only) { ($Only -split ',') | ForEach-Object { $_.Trim().ToLower() } } else { @() }
        $encodeFeed = ($onlySet.Count -eq 0) -or ($onlySet -contains 'feed')
        if ($encodeFeed) {
            foreach ($l in $langs) {
                $ls = $langSuffixMap[$l]
                foreach ($t in $themes) {
                    $ts = if ($t -eq 'dark') { '-dark' } else { '' }
                    $frames = "build/shots/readme/feed$ls$ts-frames/frame_%04d.png"
                    if (Test-Path "build/shots/readme/feed$ls$ts-frames/frame_0000.png") {
                        Invoke-AvifEncode -InputPattern $frames -OutputAvif "build/shots/readme/feed$ls$ts.avif"
                    }
                }
            }
        }

        if ($Install) {
            New-Item -ItemType Directory -Force -Path "docs/screenshots" | Out-Null
            Get-ChildItem -Path "build/shots/readme" -File | Where-Object {
                $_.Name -match '^(hero|feed|links|customize|tools).*\.(jpg|avif)$'
            } | ForEach-Object {
                Copy-Item -Path $_.FullName -Destination (Join-Path "docs/screenshots" $_.Name) -Force
            }
            Write-Host "Installed README screenshots into docs/screenshots/" -ForegroundColor Green
        }
    }

    $previewPath = Join-Path $repoRoot "build/shots/readme/preview.html"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $previewPath) | Out-Null
    Write-PreviewHtml -OutFile $previewPath
    if ($OpenPreview) {
        Start-Process $previewPath
    }
} finally {
    Pop-Location
}
