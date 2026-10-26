#!/usr/bin/env bash
# =============================================================================
# OneHandTuner 构建脚本（Linux / macOS）
#
# 用法：./module/build.sh
#
# 可通过环境变量覆盖：
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK 路径
#   JAVA_HOME                         JDK 17 路径
#   BUILD_TOOLS_VERSION               build-tools 版本，默认 36.0.0
#   PLATFORM_VERSION                  platforms/android-XX，默认 35
#   KEYSTORE_PASS                     签名口令，默认 onehand123
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$c" ]; then SDK="$c"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "未找到 Android SDK，请设置 ANDROID_HOME" >&2; exit 1; }

BT_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-35}"
KEYSTORE_PASS="${KEYSTORE_PASS:-onehand123}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

BT="$SDK/build-tools/$BT_VERSION"
ANDROID_JAR="$SDK/platforms/android-$PLATFORM_VERSION/android.jar"
[ -d "$BT" ] || { echo "缺少 build-tools: $BT" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR" >&2; exit 1; }

# build-tools 里同一工具在不同平台的后缀不一样（Linux 无后缀 / Windows 是 .bat 或 .exe），
# 这里按"存在即用"的顺序探测，避免写死平台。
pick_tool() {
  for suff in "" ".bat" ".cmd" ".exe"; do
    if [ -f "$BT/$1$suff" ]; then printf '%s' "$BT/$1$suff"; return 0; fi
  done
  printf '%s' "$BT/$1"
}
D8="$(pick_tool d8)"
AAPT2="$(pick_tool aapt2)"
ZIPALIGN="$(pick_tool zipalign)"
APKSIGNER="$(pick_tool apksigner)"

JAVAC="javac"; KEYTOOL="keytool"; JAR="jar"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  KEYTOOL="$JAVA_HOME/bin/keytool"
  JAR="$JAVA_HOME/bin/jar"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

XPOSED_API="$ROOT/lib/api-82.jar"
[ -f "$XPOSED_API" ] || { echo "缺少 Xposed API: $XPOSED_API" >&2; exit 1; }

OUT_DIR="$ROOT/build"
CLASSES_DIR="$OUT_DIR/classes"
DEX_DIR="$OUT_DIR/dex"
OUT_APK="$ROOT/out/OneHandTuner.apk"

rm -rf "$OUT_DIR"
mkdir -p "$CLASSES_DIR" "$DEX_DIR" "$(dirname "$OUT_APK")"

# javac 的 -classpath 在 Windows 上用 ";"、类 Unix 上用 ":" 分隔，
# 这里统一把两个 jar 拷进同一个目录、用 wildcard 引用，跨平台都不会踩分隔符的坑。
LIB_DIR="$OUT_DIR/libs"
mkdir -p "$LIB_DIR"
cp "$ANDROID_JAR" "$LIB_DIR/android.jar"
cp "$XPOSED_API" "$LIB_DIR/api-82.jar"

echo "==> javac"
mapfile -t SRCS < <(find "$ROOT/src" -name '*.java')
# 注意：在 $OUT_DIR 里用相对路径 "libs/*" 引用 classpath。
# git-bash/Cygwin 下绝对 POSIX 路径 + 通配符会被自动路径转换破坏，相对路径没这个问题。
( cd "$OUT_DIR" && "$JAVAC" --release 11 -encoding UTF-8 -nowarn \
    -classpath "libs/*" -d classes "${SRCS[@]}" )

echo "==> d8"
mapfile -t CLASSES < <(find "$CLASSES_DIR" -name '*.class')
"$D8" --release --min-api 31 --lib "$ANDROID_JAR" --output "$DEX_DIR" "${CLASSES[@]}"
[ -f "$DEX_DIR/classes.dex" ] || { echo "classes.dex 未生成" >&2; exit 1; }

echo "==> aapt2 link"
UNSIGNED="$OUT_DIR/unsigned.apk"
"$AAPT2" link -o "$UNSIGNED" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -A "$ROOT/assets" \
  --min-sdk-version 31 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "==> add classes.dex"
# 用 JDK 自带的 jar 写入 dex：git-bash / 精简容器里通常没有 zip 命令
cp "$DEX_DIR/classes.dex" "$OUT_DIR/classes.dex"
( cd "$OUT_DIR" && "$JAR" uf "$UNSIGNED" classes.dex )

echo "==> zipalign"
ALIGNED="$OUT_DIR/aligned.apk"
"$ZIPALIGN" -f 4 "$UNSIGNED" "$ALIGNED"

echo "==> keystore"
SIGNING_DIR="$ROOT/signing"
mkdir -p "$SIGNING_DIR"
KS="$SIGNING_DIR/onehand.keystore"
if [ ! -f "$KS" ]; then
  echo "    未发现 keystore，自动生成一个新的"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias onehand -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -dname 'CN=OneHandTuner, OU=dev, O=dev, L=CN, S=CN, C=CN'
fi

echo "==> apksigner"
"$APKSIGNER" sign --ks "$KS" \
  --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" \
  --out "$OUT_APK" "$ALIGNED"

"$APKSIGNER" verify --print-certs "$OUT_APK"

echo
echo "OK -> $OUT_APK  ($(wc -c < "$OUT_APK") bytes)"
