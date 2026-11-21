# PureChat 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 仅依赖本机 Android SDK 与 JDK，不需要 Gradle。
param(
    [string]$Sdk = "C:\Android\Sdk",
    [string]$BuildTools = "34.0.0",
    [string]$Platform = "android-34",
    [string]$Sign = "sign",
    [switch]$SelfTestOnly
)

$ErrorActionPreference = "Stop"
# 本机 JDK 21 编译的匿名类会让 d8 34.0.0 内部崩溃，统一改用 JDK 17 工具链。
$Jdk = "C:\Program Files\Java\jdk-17"
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $env:PATH
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$bt = Join-Path $Sdk "build-tools\$BuildTools"
$jar = Join-Path $Sdk "platforms\$Platform\android.jar"
$out = Join-Path $root "build"
$webSrc = Join-Path $root "web\src"
$assetsWeb = Join-Path $out "assets\web"

function Step($name) { Write-Host "==> $name" -ForegroundColor Cyan }
function Fail($message) { Write-Host "!! $message" -ForegroundColor Red; exit 1 }

Step "准备目录"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path (Join-Path $out "gen"), (Join-Path $out "classes"), (Join-Path $out "dex"), $assetsWeb | Out-Null

Step "同步前端资源"
Copy-Item (Join-Path $webSrc "index.html") $assetsWeb -Force
Copy-Item (Join-Path $webSrc "app.js") $assetsWeb -Force
Copy-Item (Join-Path $webSrc "styles.css") $assetsWeb -Force
$cssLen = (Get-Item (Join-Path $assetsWeb "styles.css")).Length
if ($cssLen -lt 1000) { Fail ("styles.css 未就绪（{0} 字节）" -f $cssLen) }

# KaTeX：公式排版所需的脚本、样式与字体一并打包，离线可用。
$katex = Join-Path $root "web\node_modules\katex\dist"
if (Test-Path $katex) {
    Copy-Item (Join-Path $katex "katex.min.js") $assetsWeb -Force
    Copy-Item (Join-Path $katex "katex.min.css") $assetsWeb -Force
    $fontsDst = Join-Path $assetsWeb "fonts"
    New-Item -ItemType Directory -Force -Path $fontsDst | Out-Null
    Copy-Item (Join-Path $katex "fonts\*.woff2") $fontsDst -Force
} else {
    Fail "缺少 KaTeX 依赖，请先在 web 目录执行 npm install"
}

# 所有待打包资源（相对 build/assets 的路径，统一用正斜杠）
$assetFiles = Get-ChildItem -Path (Join-Path $out "assets") -Recurse -File |
    ForEach-Object { $_.FullName.Substring((Join-Path $out "assets").Length + 1).Replace("\", "/") }

$sources = Get-ChildItem -Recurse -Path (Join-Path $root "app\src") -Filter *.java | ForEach-Object { $_.FullName }
$tests = @()
$testDir = Join-Path $root "devtest"
if (Test-Path $testDir) { $tests = Get-ChildItem -Recurse -Path $testDir -Filter *.java | ForEach-Object { $_.FullName } }

if ($SelfTestOnly) {
    Step "纯 JVM 自测"
    $testOut = Join-Path $out "selftest"
    New-Item -ItemType Directory -Force -Path $testOut | Out-Null
    $pureSources = $sources | Where-Object { $_ -match "\\md\\|\\netcore\\|\\AsrClient\.java$" }
    & (Join-Path $Jdk "bin\javac.exe") --release 8 -encoding UTF-8 -d $testOut @($pureSources + $tests)
    if ($LASTEXITCODE -ne 0) { Fail "自测编译失败" }
    $mains = Select-String -Path $tests -Pattern "public static void main" -List | ForEach-Object { $_.Path }
    foreach ($file in $mains) {
        $rel = $file.Substring($testDir.Length).TrimStart("\")
        $class = ($rel -replace "\.java$", "") -replace "\\", "."
        Write-Host "--- $class" -ForegroundColor DarkGray
        & (Join-Path $Jdk "bin\java.exe") -cp $testOut $class
        if ($LASTEXITCODE -ne 0) { Fail "$class 自测失败" }
    }
    exit 0
}

Step "aapt2 compile"
& (Join-Path $bt "aapt2.exe") compile --dir (Join-Path $root "app\res") -o (Join-Path $out "res.zip")
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile 失败" }

Step "aapt2 link"
$linkArgs = @("link", "-o", (Join-Path $out "base.apk"), "-I", $jar, "--manifest", (Join-Path $root "app\AndroidManifest.xml"), "-R", (Join-Path $out "res.zip"), "--java", (Join-Path $out "gen"), "--auto-add-overlay")
& (Join-Path $bt "aapt2.exe") @linkArgs
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link 失败" }

Step "javac"
$allSources = $sources + (Get-ChildItem -Recurse -Path (Join-Path $out "gen") -Filter *.java | ForEach-Object { $_.FullName })
& (Join-Path $Jdk "bin\javac.exe") --release 8 -encoding UTF-8 -nowarn -classpath $jar -d (Join-Path $out "classes") $allSources
if ($LASTEXITCODE -ne 0) { Fail "javac 失败" }

Step "d8"
$classFiles = (Get-ChildItem -Recurse -Path (Join-Path $out "classes") -Filter *.class | ForEach-Object { $_.FullName })
& (Join-Path $bt "d8.bat") --release --min-api 26 --lib $jar --output (Join-Path $out "dex") $classFiles
if ($LASTEXITCODE -ne 0) { Fail "d8 失败" }

Step "打包"
& (Join-Path $bt "zipalign.exe") -f 4 (Join-Path $out "base.apk") (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { Fail "zipalign 失败" }
Push-Location (Join-Path $out "dex")
try {
    # aapt2 -A 在 Windows 上会把 asset 条目写成反斜杠路径，Android 无法解析；
    # 这里改用 aapt.exe 以显式正斜杠路径写入，保证运行时能按 assets/web/... 读取。
    & (Join-Path $bt "aapt.exe") add (Join-Path $out "aligned.apk") classes.dex
    Push-Location $out
    foreach ($name in $assetFiles) {
        & (Join-Path $bt "aapt.exe") add (Join-Path $out "aligned.apk") ("assets/" + $name)
    }
    Pop-Location
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { Fail "写入 dex/assets 失败" }

if ($Sign -eq "sign") {
    Step "签名"
    $ks = Join-Path $root "keystore\purechat.jks"
    if (-not (Test-Path $ks)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ks) | Out-Null
        & (Join-Path $Jdk "bin\keytool.exe") -genkeypair -v -keystore $ks -storepass purechat -keypass purechat -alias purechat -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PureChat, O=PureChat, C=CN" 2>&1 | Out-Null
    }
    & (Join-Path $bt "apksigner.bat") sign --ks $ks --ks-pass pass:purechat --key-pass pass:purechat --ks-key-alias purechat --out (Join-Path $root "PureChat.apk") (Join-Path $out "aligned.apk")
    if ($LASTEXITCODE -ne 0) { Fail "签名失败" }
    & (Join-Path $bt "apksigner.bat") verify (Join-Path $root "PureChat.apk")
    if ($LASTEXITCODE -ne 0) { Fail "验签失败" }
    $size = [math]::Round((Get-Item (Join-Path $root "PureChat.apk")).Length / 1KB, 1)
    Write-Host ("构建完成: PureChat.apk ({0} KB)" -f $size) -ForegroundColor Green
} else {
    Copy-Item (Join-Path $out "aligned.apk") (Join-Path $root "PureChat-unsigned.apk") -Force
    Write-Host "构建完成: PureChat-unsigned.apk" -ForegroundColor Green
}