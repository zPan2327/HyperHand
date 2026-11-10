# =============================================================================
# HyperHand 构建入口（Windows / PowerShell 7+）
#
# 构建现在由 Gradle 驱动（见 module/build.gradle.kts 与 glass/build.gradle.kts）。
# 这个脚本保留原来的文件名、环境变量和产物路径。
#
# 用法：
#   pwsh -File module/build.ps1
#
# 环境变量（与原脚本同名，语义一致）：
#   VERSION_NAME       版本名，缺省 3.0.2-beta.1
#   VERSION_CODE       版本号；未给时由 VERSION_NAME 推导
#                      正式版 1.0.1 -> 10001 ／ 预发布 3.0.2-beta.1 -> 29903
#   KEYSTORE_PASS      签名口令，默认 onehand123
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 路径（也可写在 local.properties）
#   JAVA_HOME          JDK 17 路径
#   GRADLE             指定 gradle 可执行文件；缺省用仓库里的 gradlew.bat
# =============================================================================
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$gradleBin = if ($env:GRADLE) { $env:GRADLE } else { Join-Path $root 'gradlew.bat' }
if (-not (Test-Path $gradleBin)) {
    throw "找不到 gradle：$gradleBin（可用 `$env:GRADLE 指定）"
}

Write-Host '==> gradle :module:assembleHyperHand'
& $gradleBin --console=plain :module:assembleHyperHand @args
if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败（exit $LASTEXITCODE）" }

$apk = Join-Path $root 'module\out\HyperHand.apk'
if (-not (Test-Path $apk)) { throw "构建结束但没找到产物：$apk" }

$size = (Get-Item $apk).Length
Write-Host ''
Write-Host "OK -> $apk  ($size bytes)"
