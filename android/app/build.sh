#!/usr/bin/env bash
# build.sh - construye el APK sin Gradle: aapt2 + javac + d8 + apksigner.
# El mismo script corre en Termux (telefono) y en Ubuntu (CI).
# Solo ASCII: tambien se lee desde consolas de Windows.
#
# Variables opcionales:
#   ANDROID_JAR        por omision ~/android/platforms/android-34/android.jar
#   KEYSTORE           por omision ~/.android-cuw-debug.keystore (se crea sola)
#   KEYSTORE_PASSWORD  por omision "android"
#   KEY_ALIAS          por omision "cuwdebug"
# Para el APK de release, el workflow pasa las tres y una llave de verdad.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CORE="$ROOT/android/core"
cd "$HERE"

ANDROID_JAR="${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.android-cuw-debug.keystore}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
KEY_ALIAS="${KEY_ALIAS:-cuwdebug}"
MIN_SDK=31
TARGET_SDK=34
OUT=build
APK="$OUT/claude-usage.apk"

for t in aapt2 javac d8 apksigner keytool zip python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR: falta la herramienta '$t'"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "ERROR: no existe ANDROID_JAR=$ANDROID_JAR"; exit 1; }

# El manifiesto tiene su propio <uses-sdk> porque lint lo lee de ahi. Si los dos sitios
# divergen, aapt2 y lint usan el del manifiesto y d8 y apksigner el de aqui, sin avisar.
for pair in "minSdkVersion:$MIN_SDK" "targetSdkVersion:$TARGET_SDK"; do
  attr="${pair%%:*}"; want="${pair##*:}"
  got="$(grep -oE "android:$attr=\"[0-9]+\"" AndroidManifest.xml | grep -oE '[0-9]+' || true)"
  if [ "$got" != "$want" ]; then
    echo "ERROR: AndroidManifest.xml dice $attr=$got y build.sh usa $want"
    exit 1
  fi
done

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/6 aapt2 compile =="
aapt2 compile --dir res -o "$OUT/compiled/res.zip"

# Bloque TEST=1 numero 1: el manifiesto con la instrumentacion tiene que existir ANTES de
# enlazar. Si se genera despues, nunca entra al APK y `am instrument` falla con
# "Unable to find instrumentation info".
MANIFEST="AndroidManifest.xml"
if [ "${TEST:-0}" = "1" ]; then
  # TEST=1 es de F3 en adelante. Hasta entonces nada de esto existe, y con `set -e` un
  # `find test` sobre un directorio ausente tumbaria el script con un error que no explica nada.
  [ -d test ] || { echo "ERROR: TEST=1 necesita android/app/test (llega en F3)"; exit 1; }
  grep -q "AppInstrumentation" test/com/claulimitswidgets/android/*.java 2>/dev/null \
    || { echo "ERROR: TEST=1 necesita AppInstrumentation (llega en F3)"; exit 1; }
  echo "== extra: manifiesto con instrumentacion =="
  python3 - "$HERE/AndroidManifest.xml" "$OUT/manifest-test.xml" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()
tag = ('    <instrumentation android:name=".AppInstrumentation"\n'
       '        android:targetPackage="com.claulimitswidgets.android" />\n')
assert "</manifest>" in s, "el manifiesto no tiene </manifest>"
open(dst, "w", encoding="utf-8").write(s.replace("</manifest>", tag + "</manifest>"))
PYEOF
  MANIFEST="$OUT/manifest-test.xml"
fi

echo "== 2/6 aapt2 link =="
# versionCode y versionName no van en el manifiesto: los pone aapt2, para que el workflow de
# release los derive del tag. Sin versionCode creciente, Obtainium no puede actualizar.
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-0.0.0-dev}"

aapt2 link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/compiled/res.zip"

# El nucleo va con --release 8 a proposito: Android trae java.time desde API 26 con la
# superficie de Java 8, y un JDK moderno dejaria colar APIs de Java 9+ que revientan en el
# telefono. La app no necesita esa restriccion porque compila contra android.jar, que ya
# describe lo que hay.
echo "== 3/6 javac (nucleo, --release 8) =="
find "$CORE/src" -name '*.java' | sort | sed 's/.*/"&"/' > "$OUT/core.txt"
javac -encoding UTF-8 --release 8 -Xlint:all,-options -Werror -d "$OUT/classes" "@$OUT/core.txt"

# OJO: aqui NO se puede usar -bootclasspath (javac lo prohibe con target >= 9) ni --release 8
# para acotar las APIs. Se probaron las dos:
#   -bootclasspath "$ANDROID_JAR"  -> "option --boot-class-path not allowed with target 17"
#   --release 8                    -> compila `java.util.List.of` igual, porque android.jar esta
#                                     en el classpath y aporta sus propias clases java.* al nivel
#                                     de la API con la que se compila (34).
# O sea que la app NO tiene la red de seguridad que si tiene android/core. Lo que de verdad
# comprueba el nivel de API en una app de Android es lint (NewApi), que este build no corre.
# Esta anotado en "No ejercitado en W3".
echo "== 4/6 javac (app) =="
find src "$OUT/gen" -name '*.java' | sort | sed 's/.*/"&"/' > "$OUT/app.txt"
javac -encoding UTF-8 -source 17 -target 17 \
  -classpath "$ANDROID_JAR:$OUT/classes" -Xlint:all,-options -Werror \
  -d "$OUT/classes" "@$OUT/app.txt"

# Bloque TEST=1 numero 2: las pruebas de la cascara. Mismas banderas que la app, porque corren
# en el telefono. De `android/core/test` solo entra `Assert`: el resto (FixtureRunner, TestRunner,
# AndroidSmoke) es para la JVM, usa APIs que Android no tiene y no tiene por que compilar aqui.
if [ "${TEST:-0}" = "1" ]; then
  echo "== extra: guarda estatica de las pruebas (N2) =="
  bash "$HERE/guard-tests.sh" "$HERE/test"
  echo "== extra: javac (pruebas) =="
  { echo "$CORE/test/com/claudewidgets/core/Assert.java"; find test -name '*.java'; } \
    | sort | sed 's/.*/"&"/' > "$OUT/tests.txt"
  javac -encoding UTF-8 -source 17 -target 17 \
    -classpath "$ANDROID_JAR:$OUT/classes" -Xlint:all,-options -Werror \
    -d "$OUT/classes" "@$OUT/tests.txt"
fi

echo "== 5/6 d8 =="
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class' | sort)
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" "${CLASSES[@]}"

echo "== 6/6 empaquetar y firmar =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# El dex se anade al final: no mueve el desplazamiento de resources.arsc, que aapt2 deja
# `Stored`, asi que conserva la alineacion a 4 bytes que exige targetSdk >= 30. Verificado
# en W0; por eso no hace falta zipalign, que ademas no existe en Termux.
( cd "$OUT/dex" && zip -q -X "../unsigned.apk" classes.dex )

if [ ! -f "$KEYSTORE" ]; then
  echo "   (llave de depuracion nueva en $KEYSTORE)"
  # Las contrasenas van por el entorno, no en la linea de comandos: con `pass:` quedan a la
  # vista de cualquiera que mire `ps` o /proc. Con la llave de depuracion da igual, pero en F6
  # la que se usa es la de release y entonces no da igual.
  KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass:env KEYSTORE_PASSWORD -keypass:env KEYSTORE_PASSWORD \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=claude-usage-widgets debug, OU=debug, O=none, C=MX" >/dev/null
fi

KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" apksigner sign --ks "$KEYSTORE" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD \
  --ks-key-alias "$KEY_ALIAS" --min-sdk-version "$MIN_SDK" \
  --out "$APK" "$OUT/unsigned.apk"
apksigner verify "$APK"

echo
echo "APK: $HERE/$APK ($(stat -c %s "$APK") bytes)"
