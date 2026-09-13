#!/bin/bash
# 在设备上(proot Ubuntu)直接打包 APK：aapt2 + javac + d8 + zipalign + apksigner
# 依赖: aapt2 / javac / zipalign / apksigner / python3, 以及 android.jar 和 R8(d8)
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-/root/dl/p34/android-34-ext12/android.jar}"
R8_JAR="${R8_JAR:-/root/dl/r8.jar}"
PKG=com.deepseek.whalepet
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=14
VERSION_NAME=1.4.5

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

if [ ! -f "$ANDROID_JAR" ]; then echo "缺少 android.jar: $ANDROID_JAR" >&2; exit 1; fi
echo "[1/6] aapt2 compile"
aapt2 compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip" </dev/null

echo "[2/6] aapt2 link"
aapt2 link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE \
  --version-name $VERSION_NAME \
  --no-version-vectors </dev/null

echo "[3/6] javac"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log" || {
    echo "javac 失败："; grep -v 'bootstrap class path' "$OUT/javac.log" | head -60; exit 1; }

echo "[4/6] d8 (dex)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] 打包 dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PYEOF
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
KS="$ROOT/tools/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/whale-pet.apk" "$OUT/aligned.apk"
ls -lh "$OUT/whale-pet.apk"
echo "OK -> $OUT/whale-pet.apk"
