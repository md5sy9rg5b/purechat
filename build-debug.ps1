param([string]$Key = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifest = Join-Path $root "app\AndroidManifest.xml"
$backup = Join-Path $env:TEMP "purechat-manifest.bak"
Copy-Item $manifest $backup -Force
$text = Get-Content $manifest -Raw
$text = $text -replace ' android:debuggable="true"', ''
$text = $text -replace 'android:allowBackup="false"', 'android:allowBackup="false" android:debuggable="true"'
Set-Content -Path $manifest -Value $text -NoNewline
try {
    & (Join-Path $root "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
} finally {
    Copy-Item $backup $manifest -Force
}
Copy-Item (Join-Path $root "PureChat.apk") (Join-Path $root "PureChat-debug.apk") -Force
Write-Host "调试包: PureChat-debug.apk" -ForegroundColor Green