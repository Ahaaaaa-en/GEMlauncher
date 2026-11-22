#!/usr/bin/env bash
# Gem桌面 —— 纯手工构建链：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 不依赖 Gradle / Android Studio / 任何第三方库，一台装了 Android SDK 的 Linux 或 macOS 就能出包。
#
# 可选环境变量：
#   ANDROID_SDK     Android SDK 根目录（默认 ~/android-sdk，也认 $ANDROID_HOME）
#   BUILD_TOOLS     指定 build-tools 版本目录（默认自动取版本号最大的一个）
#   ANDROID_JAR     指定 android.jar（默认自动取 platforms/android-* 里最大的一个）
#   JDK_HOME        指定 JDK 17（不填就用 PATH 里的 javac / keytool）
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/android-sdk}}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
AJAR="${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}"
if [ ! -x "$BT/aapt2" ]; then echo "找不到 aapt2：$BT" >&2; exit 1; fi
if [ ! -f "$AJAR" ]; then echo "找不到 android.jar：请装 platforms;android-33" >&2; exit 1; fi

JAVAC=javac
KEYTOOL=keytool
if [ -n "${JDK_HOME:-}" ]; then
    JAVAC="$JDK_HOME/bin/javac"
    KEYTOOL="$JDK_HOME/bin/keytool"
fi

VCODE=$(grep -o 'android:versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VNAME=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
OUT=build
APK="GemDesktop_${VNAME}.apk"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "[2/6] 链接资源 + 生成 R.java"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 23 \
    --target-sdk-version 28 \
    "$OUT/res.zip"

echo "[3/6] 编译 Java ($(find src -name '*.java' | wc -l | tr -d ' ') 个文件)"
"$JAVAC" -source 8 -target 8 -nowarn -bootclasspath "$AJAR" -classpath "$AJAR" \
    -encoding UTF-8 -d "$OUT/classes" \
    $(find src "$OUT/gen" -name "*.java")

echo "[4/6] dex"
"$BT/d8" --release --lib "$AJAR" --min-api 23 --output "$OUT/dex" \
    $(find "$OUT/classes" -name "*.class")

echo "[5/6] 打包 dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "[6/6] 对齐 + 签名"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f debug.keystore ]; then
    echo "    生成一次性 debug 签名密钥 debug.keystore"
    "$KEYTOOL" -genkeypair -keystore debug.keystore -alias gemdesktop \
        -keyalg RSA -keysize 2048 -validity 12000 \
        -storepass android -keypass android \
        -dname "CN=Gem Desktop, OU=Gem, O=Gem, L=Guangxi, S=Guangxi, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 23 --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --verbose "$APK" | grep -E "v1|v2|v3" || true
echo
echo "==> $(pwd)/$APK  (versionCode=$VCODE, versionName=$VNAME)"
ls -lh "$APK"
