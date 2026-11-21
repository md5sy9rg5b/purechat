# 一键发布：写版本 -> 构建 -> 校验 -> 提交 -> 推送 -> 发布 Release
# 用法: pwsh -NoProfile -File release.ps1 -Version 1.0.5 -Notes "本次改动说明"
param(
    [Parameter(Mandatory=$true)][string]$Version,
    [string]$Notes = "",
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$Version = $Version.TrimStart("v")
if ($Version -notmatch "^\d+\.\d+\.\d+$") { Write-Host "!! 版本号格式应为 x.y.z" -ForegroundColor Red; exit 1 }

Write-Host "==> 写入版本 $Version" -ForegroundColor Cyan
Set-Content -Path (Join-Path $root "version.txt") -Value $Version -Encoding UTF8

Write-Host "==> 构建" -ForegroundColor Cyan
& (Join-Path $root "build.ps1") -Version $Version
if ($LASTEXITCODE -ne 0) { Write-Host "!! 构建失败" -ForegroundColor Red; exit 1 }

# 校验：APK 内的版本必须与目标版本一致，否则不准发布。
$aapt = "C:\Android\Sdk\build-tools\34.0.0\aapt.exe"
$badging = & $aapt dump badging (Join-Path $root "PureChat.apk") 2>&1 | Out-String
if ($badging -notmatch ("versionName='" + [regex]::Escape($Version) + "'")) {
    Write-Host "!! APK 内版本与目标不一致，已中止发布" -ForegroundColor Red
    Write-Host $badging.Substring(0, [Math]::Min(200, $badging.Length))
    exit 1
}
Write-Host "    APK 版本校验通过" -ForegroundColor Green

Write-Host "==> 提交" -ForegroundColor Cyan
& git add -A
& git commit -q -m "release: v$Version" -m $Notes
if ($LASTEXITCODE -ne 0) { Write-Host "    （无改动可提交，继续）" -ForegroundColor DarkGray }

if (-not $SkipPush) {
    Write-Host "==> 推送" -ForegroundColor Cyan
    & git push -q origin master
}

Write-Host "==> 创建 Release v$Version" -ForegroundColor Cyan
$noteText = if ($Notes) { $Notes } else { "v$Version" }
& gh release create "v$Version" "PureChat.apk" --title "PureChat v$Version" --notes $noteText
if ($LASTEXITCODE -ne 0) { Write-Host "!! Release 创建失败" -ForegroundColor Red; exit 1 }

Write-Host ("发布完成: v" + $Version) -ForegroundColor Green
