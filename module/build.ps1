# =============================================================================
# OneHandTuner 构建脚本（Windows / PowerShell 7+）
#
# 用法：
#   pwsh -File module/build.ps1
#
# 可通过环境变量覆盖：
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 路径
#   JAVA_HOME                         JDK 17 路径
#   BUILD_TOOLS_VERSION               build-tools 版本，默认 36.0.0
#   PLATFORM_VERSION                  platforms/android-XX，默认 35
#   KEYSTORE_PASS                     签名口令，默认 onehand123
# =============================================================================
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'Android\Sdk'))
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    throw '未找到 Android SDK，请设置 ANDROID_HOME'
}

$sdk = Find-Sdk
$btVersion = if ($env:BUILD_TOOLS_VERSION) { $env:BUILD_TOOLS_VERSION } else { '36.0.0' }
$platformVersion = if ($env:PLATFORM_VERSION) { $env:PLATFORM_VERSION } else { '35' }
$keystorePass = if ($env:KEYSTORE_PASS) { $env:KEYSTORE_PASS } else { 'onehand123' }
$versionName = if ($env:VERSION_NAME) { $env:VERSION_NAME } else { '1.0' }
# 未显式指定 versionCode 时由 versionName 推导：1.0.1 -> 10001
$versionCode = $env:VERSION_CODE
if (-not $versionCode) {
    if ($versionName -match '^(\d+)\.(\d+)\.(\d+)$') {
        $versionCode = [string]([int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3])
    } else {
        $versionCode = '1'
    }
}

$buildTools = Join-Path $sdk "build-tools\$btVersion"
$androidJar = Join-Path $sdk "platforms\android-$platformVersion\android.jar"
if (-not (Test-Path $buildTools)) { throw "缺少 build-tools: $buildTools" }
if (-not (Test-Path $androidJar)) { throw "缺少 android.jar: $androidJar" }

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $jdkHome = $env:JAVA_HOME
} else {
    $jdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
    if (-not (Test-Path (Join-Path $jdkHome 'bin\javac.exe'))) {
        $jdkHome = $null
    }
}
$javac = if ($jdkHome) { Join-Path $jdkHome 'bin\javac.exe' } else { 'javac' }
$keytool = if ($jdkHome) { Join-Path $jdkHome 'bin\keytool.exe' } else { 'keytool' }
if ($jdkHome) {
    $env:JAVA_HOME = $jdkHome
    $env:PATH = (Join-Path $jdkHome 'bin') + ';' + $env:PATH
}

$xposedApi = Join-Path $root 'lib\api-82.jar'
if (-not (Test-Path $xposedApi)) { throw "缺少 Xposed API: $xposedApi" }

$outDir = Join-Path $root 'build'
$classesDir = Join-Path $outDir 'classes'
$dexDir = Join-Path $outDir 'dex'
$outApk = Join-Path $root 'out\OneHandTuner.apk'

foreach ($d in @($outDir, $classesDir, $dexDir, (Split-Path -Parent $outApk))) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force $d | Out-Null
}

Write-Host '==> javac'
$srcs = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac '--release' '11' '-encoding' 'UTF-8' '-nowarn' '-classpath' "$androidJar;$xposedApi" '-d' $classesDir @srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '==> d8'
$classFiles = Get-ChildItem -Path $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $buildTools 'd8.bat') '--release' '--min-api' '31' '--lib' $androidJar '--output' $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
if (-not (Test-Path (Join-Path $dexDir 'classes.dex'))) { throw 'classes.dex not produced' }

Write-Host '==> aapt2 link'
$unsigned = Join-Path $outDir 'unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') 'link' '-o' $unsigned `
    '--manifest' (Join-Path $root 'AndroidManifest.xml') `
    '-I' $androidJar `
    '-A' (Join-Path $root 'assets') `
    '--min-sdk-version' '31' '--target-sdk-version' '36' `
    '--version-code' $versionCode '--version-name' $versionName
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '==> add classes.dex'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir 'classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

Write-Host '==> zipalign'
$aligned = Join-Path $outDir 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') '-f' '4' $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '==> keystore'
# 签名密钥放在 build 之外（且已 gitignore）：换机器时请自带同一个 keystore，
# 否则签名变化会导 adb install -r 失败 / 与已安装版本不兼容。
$signingDir = Join-Path $root 'signing'
if (-not (Test-Path $signingDir)) { New-Item -ItemType Directory -Force $signingDir | Out-Null }
$ks = Join-Path $signingDir 'onehand.keystore'
if (-not (Test-Path $ks)) {
    Write-Host '    未发现 keystore，自动生成一个新的'
    & $keytool '-genkeypair' '-keystore' $ks '-alias' 'onehand' '-keyalg' 'RSA' '-keysize' '2048' `
        '-validity' '10000' '-storepass' $keystorePass '-keypass' $keystorePass `
        '-dname' 'CN=OneHandTuner, OU=dev, O=dev, L=CN, S=CN, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Write-Host '==> apksigner'
& (Join-Path $buildTools 'apksigner.bat') 'sign' '--ks' $ks `
    '--ks-pass' "pass:$keystorePass" '--key-pass' "pass:$keystorePass" `
    '--out' $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $buildTools 'apksigner.bat') 'verify' '--print-certs' $outApk

$f = Get-Item $outApk
Write-Host "`nOK -> $($f.FullName)  ($($f.Length) bytes)"
