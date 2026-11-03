#!/bin/bash
# Builds the APK without Gradle (toolchain used inside the Claude workspace).
# With Android Studio, just use the normal Gradle build instead (see README.md).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="${TC:-/home/claude/tc}"
B="$TC/bt/build-tools/34.0.4"
AJ="$TC/Reginer_aosp-android-jar/android-36/android.jar"      # compile classpath (API 36)
AR="$TC/sdk/android-sdk/platforms/android-33/android.jar"      # framework resources for aapt2
LS="$B/core-lambda-stubs.jar"
M3JAR="$TC/build-m3/lib-stripped.jar"                          # Media3 + Guava classes
M3DEX="$TC/build-m3/dex/classes.dex"                           # Media3 + Guava dex
ASM="/opt/gradle/lib/asm-9.7.1.jar"
STRIP="$TC/tools/classes"
APP="$HERE/app/src/main"
OUT="$HERE/build"
# version: ../version.json (same rule as app/build.gradle.kts)
VERSION_NAME="${VERSION_NAME:-$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$HERE/../version.json")}"
VERSION_CODE="${VERSION_CODE:-$(python3 -c "import sys; a,b,c=(int(x) for x in sys.argv[1].split('-')[0].split('.')); print(a*10000+b*100+c)" "$VERSION_NAME")}"
# signing key: keystore.properties (never committed), same file the Gradle build reads
PROPS="$HERE/keystore.properties"
prop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | tr -d '\r' | head -n 1; }
SF="$(prop storeFile)"; case "$SF" in /*) ;; ?*) SF="$HERE/$SF" ;; esac
KS="${KS:-$SF}"; KS_PASS="${KS_PASS:-$(prop storePassword)}"; KS_ALIAS="${KS_ALIAS:-$(prop keyAlias)}"
KS_ALIAS="${KS_ALIAS:-moa}"
if [ ! -f "$KS" ] || [ -z "$KS_PASS" ]; then
  echo "signing key missing: create android/keystore.properties (see README.md)"; exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex,merged}
echo "[1/6] resources"
"$B/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip" 2>&1 | grep -v tzdata || true
# Gradle style manifest has no package attribute; aapt2 needs one.
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.moa.player">#' "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$B/aapt2" link -I "$AR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/base.apk" --java "$OUT/gen" "$OUT/res.zip" 2>&1 | grep -v tzdata || true
test -f "$OUT/base.apk"
echo "[2/6] javac"
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -J-Xmx2g -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:none \
  -bootclasspath "$AJ:$LS" -cp "$M3JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v "JAVA_TOOL_OPTIONS\|^warning: \[options\]\|^Note:\|^[0-9]* warning" || true
test -f "$OUT/classes/com/moa/player/PlayerActivity.class"
echo "[3/6] dex"
(cd "$OUT/classes" && jar cf ../app.jar .)
java -cp "$STRIP:$ASM" StripParams "$OUT/app.jar" "$OUT/app-s.jar" 2>&1 | grep -v JAVA_TOOL
java -Xmx2g -cp "$B/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" \
  --classpath "$M3JAR" --output "$OUT/dex" "$OUT/app-s.jar" 2>&1 | grep -v JAVA_TOOL || true
java -Xmx2g -cp "$B/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" \
  --output "$OUT/merged" "$OUT/dex/classes.dex" "$M3DEX" 2>&1 | grep -v JAVA_TOOL || true
echo "[4/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/merged" && zip -q -j "$OUT/unsigned.apk" classes*.dex)
"$B/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "[5/6] sign"
java -jar "$B/lib/apksigner.jar" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out "$OUT/MoaPlayer.apk" "$OUT/aligned.apk" 2>&1 | grep -v JAVA_TOOL || true
echo "[6/6] verify"
java -jar "$B/lib/apksigner.jar" verify "$OUT/MoaPlayer.apk" 2>&1 | grep -v JAVA_TOOL && echo "signature OK"
ls -la "$OUT/MoaPlayer.apk"
