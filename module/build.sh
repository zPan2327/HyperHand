#!/usr/bin/env bash
# =============================================================================
# HyperHand 构建入口（Linux / macOS）
#
# 构建现在由 Gradle 驱动（见 module/build.gradle.kts 与 glass/build.gradle.kts）。
# 这个脚本保留原来的文件名、环境变量和产物路径，免得 README、CI、肌肉记忆全部失效。
#
# 用法：./module/build.sh [额外的 gradle 参数]
#
# 环境变量（与原脚本同名，语义一致）：
#   VERSION_NAME       版本名，缺省 3.0.2-beta.1
#   VERSION_CODE       版本号；未给时由 VERSION_NAME 推导
#                      正式版 1.0.1 -> 10001 ／ 预发布 3.0.2-beta.1 -> 29903
#   KEYSTORE_PASS      签名口令，默认 onehand123
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 路径（也可写在 local.properties）
#   JAVA_HOME          JDK 17 路径
#   GRADLE             指定 gradle 可执行文件；缺省用仓库里的 ./gradlew
#                      （内网/离线环境若 wrapper 下不到发行版，可以指向本地 gradle）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_BIN="${GRADLE:-$ROOT/gradlew}"
if [ ! -x "$GRADLE_BIN" ]; then
  chmod +x "$GRADLE_BIN" 2>/dev/null || {
    echo "找不到 gradle：$GRADLE_BIN（可用 GRADLE=/path/to/gradle 指定）" >&2
    exit 1
  }
fi

echo "==> gradle :module:assembleHyperHand"
# 版本号通过环境变量传给 Gradle：build.gradle.kts 里读 VERSION_NAME / VERSION_CODE，
# 空值不导出，让 Gradle 用自己脚本里的默认值。
"$GRADLE_BIN" --console=plain :module:assembleHyperHand "$@"

APK="$ROOT/module/out/HyperHand.apk"
[ -f "$APK" ] || { echo "构建结束但没找到产物：$APK" >&2; exit 1; }

echo
echo "OK -> $APK  ($(wc -c < "$APK") bytes)"
