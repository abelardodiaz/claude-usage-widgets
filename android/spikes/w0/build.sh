#!/usr/bin/env bash
# build.sh - construye el APK del spike W0 sin Gradle: aapt2 + javac + d8 + apksigner.
# Solo ASCII: tambien se lee desde consolas de Windows.
#
# Variables de entorno opcionales:
#   ANDROID_JAR      ruta al android.jar (por omision ~/android/platforms/android-34/android.jar)
#   SPIKE_KEYSTORE   llave de depuracion (por omision ~/.android-spike-debug.keystore)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

ANDROID_JAR="${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}"
KEYSTORE="${SPIKE_KEYSTORE:-$HOME/.android-spike-debug.keystore}"
MIN_SDK=29
TARGET_SDK=34
OUT=build
APK="$OUT/spike-w0.apk"
KS_PASS=android
KS_ALIAS=spikedebug

for t in aapt2 javac d8 apksigner keytool zip; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR: falta la herramienta '$t'"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "ERROR: no existe ANDROID_JAR=$ANDROID_JAR"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/6 aapt2 compile =="
aapt2 compile --dir res -o "$OUT/compiled/res.zip"

echo "== 2/6 aapt2 link =="
aapt2 link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --auto-add-overlay \
  "$OUT/compiled/res.zip"

echo "== 3/6 javac =="
find src "$OUT/gen" -name '*.java' | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -nowarn \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" "@$OUT/sources.txt"

echo "== 4/6 d8 =="
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class' | sort)
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" "${CLASSES[@]}"

echo "== 5/6 empaquetar =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# El dex se anade al final: los desplazamientos de resources.arsc no se mueven,
# asi que conserva su alineacion a 4 bytes (requisito de targetSdk >= 30).
( cd "$OUT/dex" && zip -q -X "../unsigned.apk" classes.dex )

if [ ! -f "$KEYSTORE" ]; then
  echo "== llave de depuracion nueva en $KEYSTORE =="
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=claude-usage-widgets spike, OU=spike, O=none, L=none, S=none, C=MX" >/dev/null 2>&1
fi

echo "== 6/6 apksigner =="
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version "$MIN_SDK" \
  --out "$APK" "$OUT/unsigned.apk"
apksigner verify "$APK"

echo
echo "APK: $APK ($(stat -c %s "$APK") bytes)"
