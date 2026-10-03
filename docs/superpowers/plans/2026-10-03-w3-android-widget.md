# W3 Widget de Android — Plan detallado (F2 a F6)

> **Para agentes:** SUB-SKILL REQUERIDA: usar superpowers:subagent-driven-development (recomendado)
> o superpowers:executing-plans para ejecutar este plan tarea por tarea. Los pasos usan casillas
> (`- [ ]`) para seguimiento. Cada tarea es un ciclo TDD donde aplica: test que falla →
> implementación → test que pasa → commit. Los bloques de código son **completos**: se copian tal
> cual, no se "adaptan".
>
> **F1 (núcleo `android/core`) ya está cerrada y mergeada** (PR #11, `cee8c06`). Este plan cubre
> F2 a F6.

**Objetivo:** un APK instalable que muestre en la pantalla de inicio, en un widget 4×1 y 4×2, el
uso real de la cuenta de Claude del usuario —sesión de 5 h, semana, cuota de hoy y proyección—
actualizado cada 15 minutos y al tocarlo, con la cookie de sesión cifrada en el Android Keystore
y publicado en Releases con su checksum.

**Arquitectura:** dos módulos. `android/core/` (ya hecho) es **la única implementación de las
reglas** R0–R7: Java puro, sin APIs de Android, contrastado contra las 60 fixtures del contrato.
`android/app/` es la cáscara: WebView de login, almacén cifrado, cliente HTTP, almacén de
muestras, `JobScheduler` y `AppWidgetProvider`. **La cáscara nunca recalcula nada**: no decide
colores, no proyecta, no reparte por día. Llama al núcleo y pinta lo que le devuelve. Si alguna
vez un color se calcula en `android/app/`, es un error, no una optimización.

**Spec:** `docs/superpowers/specs/2026-10-02-claude-usage-widgets-design.md` (§3.1, §3.4, §5, §6, §7).

**Reportes de los spikes que este plan asume como verdad:**
`docs/spikes/2026-10-w0-a1-claude-ai.md` y `docs/spikes/2026-10-w0-a2-b-android.md`, incluida su
sección **"Lo que W3 NO debe copiar del spike"**.

**Stack (verificado en este teléfono el 2026-10-03):**

| Pieza | Versión | Nota |
|---|---|---|
| `aapt2` | 2.20-android-16.0.0_r4 | Termux |
| `javac` / `java` | OpenJDK 21.0.12 | el núcleo se compila con `--release 8` |
| `d8` | 9.2.4-dev | `--min-api 29` |
| `apksigner` | 0.9 | v2/v3; **sin `zipalign`** (no existe en Termux y no hace falta: verificado en W0) |
| `android.jar` | `~/android/platforms/android-34/android.jar` | `ANDROID_JAR` lo puede sobrescribir |
| `minSdk` / `targetSdk` | 29 / 34 | |
| `actions/checkout` | `3d3c42e5aac5ba805825da76410c181273ba90b1` # v7.0.1 | mismo SHA que ya usa el repo |
| `actions/setup-java` | `b6effb05e454b25005698d916606bdc6ffcbf961` # v5 | temurin 17 |
| Dispositivo de prueba | Android 17 (SDK 37), One UI | el `minSdk` 29 **no se prueba en hardware**: ver Review Focus |

**Sin Gradle, sin androidx, sin ninguna dependencia externa.** Todo lo que no esté en `android.jar`
se escribe aquí. Es una restricción del proyecto, no una preferencia: hace que compilar en el
teléfono y en el CI sea literalmente el mismo comando.

---

## Restricciones globales

Valen para **todas** las tareas. Son de `SECURITY.md` §5, de la spec §5 y §6, y de los spikes.

1. **La cookie no sale del almacén.** Nunca a un log, a un `toString()`, a un mensaje de error, a
   una captura ni a un `Intent`. De ella solo se registran: cuántas hay, sus nombres, y si existe
   `sessionKey`.
2. **`lastActiveOrg` es una credencial**, no un dato: su valor es un UUID de organización y recibe
   el mismo trato que `sessionKey`.
3. **Nunca se registran cuerpos de respuesta.** Solo código HTTP, `content-type` y longitud.
4. **Sin `addJavascriptInterface`** y sin ningún puente JS. El WebView es solo para el login.
5. `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, sin permisos de red local.
6. `setAcceptThirdPartyCookies(web, false)`.
7. **A `claude.ai` se manda el mínimo de cookies**: `sessionKey` y, si aplica, `lastActiveOrg`.
   No el jarro entero (eso lo hacía el spike; ver "Lo que W3 NO debe copiar").
8. **Red: solo `claude.ai`.** Ningún otro host. Cero telemetría.
9. **El núcleo es la única implementación de R0–R7.** `android/app/` no calcula colores, ni
   proyecciones, ni reparto por día.
10. **Identificadores en inglés, comentarios y documentos en español.** Los scripts (`.sh`) solo en
    ASCII, porque también corren en consolas de Windows.
11. **El token de esta sesión no puede tocar `.github/workflows/`.** Todo workflow nuevo se escribe
    en otra ruta y una tarea explícita pide a la sesión PC que lo mueva.
12. **`MIN_TOTAL` del corredor de fixtures se sube a mano** cuando el contrato crece. Hoy 60.

## Review Focus

Entradas que la spec implica, que ninguna prueba de las tareas ejercita, y que muerden al usuario.
Cada una tiene su prueba asignada a la tarea que posee el código.

1. **Sin red o DNS caído.** `UnknownHostException` no es "0 % de uso": el widget debe seguir
   mostrando el último dato con su antigüedad. → `UsageRefresher.last()`, Tarea 4.2.
2. **Sesión caducada (401) con cookie presente.** La cookie existe pero ya no vale; el widget debe
   decir "inicia sesión" y no quedarse con el número viejo para siempre. → `UsageClient.check`,
   Tarea 3.4 (prueba `UsageClientTest`), y el aviso en Tarea 4.3.
3. **Respuesta HTML donde se espera JSON.** Es el reto de Cloudflare, y `Json.parse` lo rechaza:
   hay que distinguirlo de "formato no reconocido" para aplicar backoff y avisar. →
   `UsageClient.looksLikeHtml`, Tarea 3.4 (prueba `UsageClientTest`).
4. **Reinicio del teléfono.** `JobScheduler` **no** sobrevive al reinicio: sin
   `RECEIVE_BOOT_COMPLETED` el widget se congela para siempre y el usuario no sabe por qué.
   → Tarea 4.4.
5. **Widget añadido sin sesión.** Primera instalación: alguien pone el widget antes de iniciar
   sesión. Debe invitar a hacerlo, no quedarse en blanco ni mostrar ceros. → Tarea 4.3, paso 4.

## Decisiones abiertas (las confirma el dueño antes de F3)

Cada una lleva mi recomendación. La sesión PC las lleva al dueño; hasta entonces el plan asume la
recomendación y lo dice donde toca.

**D1 — Nombre del paquete Android.** Recomiendo **`com.claudewidgets.android`**. Es estable,
coincide con el repositorio y no reclama un dominio. La alternativa ortodoxa sería un dominio del
dueño en orden inverso; si lo prefiere, se cambia en una tarea (afecta al manifiesto, al
`build.sh`, a `res/` y a la firma, pero no al núcleo). **No se puede cambiar después del primer
Release**: Android lo trata como otra app y el usuario perdería el widget y la sesión.

**D2 — Regla de selección de organización.** Hay cuentas con más de una (la del dueño tiene 2).
Recomiendo, en orden: (a) si existe la cookie `lastActiveOrg` y `/usage` de esa organización
responde `200`, usar esa — es la que la web considera activa, así que coincide con lo que el
usuario ve en claude.ai; (b) si no, la primera de `/api/organizations` cuyo `/usage` responda
`200`; (c) **selector manual obligatorio en ajustes**, que gana siempre sobre (a) y (b).
Descarto `rate_limit_tier` y `capabilities` como criterio automático: describen el plan, no cuál
mira el usuario, y elegir "la del plan más alto" mostraría una cuota que no es la suya.

**D3 — Dónde vive la llave de release.** Recomiendo un secret del repositorio
(`ANDROID_KEYSTORE_BASE64` + `ANDROID_KEYSTORE_PASSWORD` + `ANDROID_KEY_ALIAS`), y que el APK de
release **solo** se firme en GitHub Actions. La llave no se guarda en el teléfono ni en el repo.
El dueño la genera y la sube él: yo no debo verla. Si se pierde, no hay actualización posible para
quien ya instaló: conviene que guarde una copia fuera de GitHub.

**D4 — Idioma de la UI.** Recomiendo español e inglés por el idioma del sistema, igual que W1:
`res/values/strings.xml` en inglés (por omisión) y `res/values-es/strings.xml` en español. Sin
selector manual en W3.

**D5 — Almacén de muestras.** El historial necesita muestras para R3, R4 y R6. Recomiendo un JSONL
en el almacenamiento privado de la app (`filesDir`), **sin cifrar**, con ventana de 15 días, igual
que el escritorio. Razón: son porcentajes, no credenciales, y `allowBackup=false` más el aislamiento
de la app ya los protegen de otras apps. Lo que sí revelan es el patrón de uso del dueño, así que
si prefiere cifrarlos con la misma llave del Keystore, es una tarea de media hora; dígalo y lo
cambio.

---

## Mapa de archivos

Lo que existe hoy y no se toca:

```
android/core/                      W3 F1, cerrada. La UNICA implementacion de R0-R7.
  src/com/claudewidgets/core/      Json, Parser, History, Projection, Colors, modelo
  test/com/claudewidgets/core/     Assert, JsonTest, CoreTest, FixtureRunner, TestRunner
  build.sh  mutantes.sh  README.md
android/spikes/w0/                 Desechable. REFERENCIA, no base. No se hereda codigo.
```

Lo que crea este plan:

```
android/app/
  AndroidManifest.xml              permisos, Activities, receptor, servicio
  build.sh                         aapt2 + javac + d8 + apksigner; usa android/core como fuente
  ci/android.yml                   job de CI (PC lo mueve a .github/workflows/)
  res/
    values/strings.xml             ingles (por omision)
    values-es/strings.xml          espanol
    layout/activity_login.xml      pantalla previa + WebView
    layout/activity_settings.xml   selector de organizacion, cerrar sesion
    layout/widget_4x1.xml          compacto: sesion + semana
    layout/widget_4x2.xml          con barras y proyeccion
    layout/widget_4x1_preview.xml  previewLayout (obligatorio, F4)
    layout/widget_4x2_preview.xml
    drawable/bar_green.xml         progressDrawable verde  (R7: green)
    drawable/bar_amber.xml         progressDrawable ambar  (R7: amber)
    drawable/bar_red.xml           progressDrawable rojo   (R7: red)
    drawable/bar_gray.xml          progressDrawable gris   (R7: gray)
    xml/widget_4x1_info.xml        appwidget-provider
    xml/widget_4x2_info.xml
  src/com/claudewidgets/android/
    SessionStore.java              cookie cifrada AES-GCM con llave del Android Keystore
    LoginActivity.java             pantalla previa + WebView de claude.ai
    SettingsActivity.java          selector manual de organizacion, cerrar sesion
    UsageClient.java               HttpURLConnection a claude.ai; distingue bloqueo de 401
    BlockedException.java          403 / cf-mitigated / HTML donde se espera JSON
    AuthExpiredException.java      401
    OrgSelector.java               D2: lastActiveOrg -> primera que responda 200 -> manual
    SampleStore.java               JSONL de muestras, ventana de 15 dias
    Snapshot.java                  lo que el widget pinta: modelo + historial + proyeccion + hora
    SnapshotStore.java             ultimo Snapshot bueno, para "sin red"
    UsageRefresher.java            orquesta: cookie -> consulta -> nucleo -> Snapshot
    WidgetRenderer.java            Snapshot -> RemoteViews (sin logica de reglas)
    Widget4x1Provider.java         AppWidgetProvider compacto
    Widget4x2Provider.java         AppWidgetProvider con barras
    WidgetUpdateJob.java           JobService; JobScheduler cada 15 min
    BootReceiver.java              reprograma el job tras el reinicio (Review Focus 4)
    Backoff.java                   espera exponencial hasta 30 min (spec 3.4)
    Texts.java                     formato de horas y antiguedad, es/en
  test/com/claudewidgets/android/
    AppTestRunner.java             corredor propio, sin JUnit, como en el nucleo
    SessionStoreTest.java          cifrado y borrado (corre bajo ART, no en JVM)
    OrgSelectorTest.java           D2
    SampleStoreTest.java           ventana de 15 dias, archivo corrupto
    BackoffTest.java               progresion y tope
    UsageClientTest.java           clasificacion de respuestas (sin red: entradas sinteticas)
```

## Firmas públicas

Lo que cada tarea produce y las siguientes consumen. Los nulos son `null` de verdad.

```java
// SessionStore.java  (F3)
public final class SessionStore {
    public SessionStore(Context ctx);
    /** Cifra y guarda. `cookies` es el par nombre=valor tal cual lo da CookieManager. */
    public void save(String cookies) throws GeneralSecurityException, IOException;
    /** Null si no hay sesion. El llamador NO debe registrar el resultado. */
    public String load() throws GeneralSecurityException, IOException;
    public boolean hasSession();
    /** Borra el archivo y la llave del Keystore. Idempotente. */
    public void clear();
}

// UsageClient.java  (F3)
public final class UsageClient {
    public UsageClient(String cookieHeader, String userAgent);
    /** De todo el jarro del WebView, deja solo sessionKey y lastActiveOrg. */
    public static String minimalCookies(String all);
    /** El valor de lastActiveOrg, o null. Es una credencial: no se registra. */
    public static String lastActiveOrg(String all);
    public List<String> organizations() throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException;
    public UsageModel usage(String orgUuid) throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException;
    /** 429 y 5xx: ni vencida ni bloqueada. Se reintenta con Backoff. */
    public static final class RetryLaterException extends Exception { }
}

// OrgSelector.java  (F3)
public final class OrgSelector {
    /** Dice si `/usage` de esa organizacion responde. Lo inyecta el llamador: la regla se
        prueba sin red. */
    public interface Probe { boolean responds(String orgUuid); }
    /** D2. `manual` gana si no es null. Null = ninguna sirve. */
    public static String choose(List<String> organizations, String manual,
                                String lastActiveOrg, Probe probe);
}

// SampleStore.java  (F4)
public final class SampleStore {
    public SampleStore(File dir);
    public void append(Sample s) throws IOException;
    /** Orden ascendente por `t`, sin las de mas de 15 dias. Un archivo corrupto no lanza: se ignora la linea. */
    public List<Sample> load() throws IOException;
    public void clear();
}

// Snapshot.java  (F4)
public final class Snapshot {
    public final UsageModel model;
    public final DayUsage day;
    public final Forecast sessionForecast;
    public final Forecast weeklyForecast;
    public final Double paceMark;
    public final Instant fetchedAt;
    /** Null = todo bien. Si no, que mostrar en vez de los numeros. */
    public final Problem problem;
    public enum Problem { NO_SESSION, AUTH_EXPIRED, BLOCKED, OFFLINE, BAD_FORMAT }
}

// SnapshotStore.java  (F4)
public final class SnapshotStore {
    public SnapshotStore(Context ctx);
    /** Guarda lo minimo para reconstruir el widget sin red. */
    public void remember(UsageModel model, Instant fetchedAt, List<String> orgs);
    /** El ultimo modelo guardado, o null si nunca hubo uno. */
    public UsageModel lastModel();
    /** Instante de la ultima consulta buena, o null. */
    public Instant lastFetchInstant();
    public List<String> knownOrgs();
    public void clear();
}

// UsageRefresher.java  (F4)
public final class UsageRefresher {
    public UsageRefresher(Context ctx);
    /** Consulta, guarda muestra, calcula con el nucleo y persiste. Nunca lanza. */
    public Snapshot refresh();
    /** Reconstruye desde lo guardado, sin red. Nunca null. */
    public Snapshot last();
}

// WidgetRenderer.java  (F4)
public final class WidgetRenderer {
    /** `compact` = 4x1. Sin logica de reglas: solo pinta lo que trae el Snapshot. */
    public static RemoteViews render(Context ctx, Snapshot s, boolean compact);
}

// Backoff.java  (F3)
public final class Backoff {
    /** Espera en segundos para el intento n (0-based). Tope 1800 s (spec 3.4). */
    public static long seconds(int attempt);
}
```

---

## F2 — Build del APK · rama `android/w3-f2-build`

**Entregable:** un APK que se construye con un solo comando, idéntico en Termux y en Ubuntu, que
instala y arranca, y que **enlaza el núcleo** (lo demuestra llamándolo en pantalla). Sin login y
sin widget todavía: eso es F3 y F4.

**Por qué primero:** si el build no es reproducible en los dos sitios, cada fase siguiente se
depura dos veces. El spike ya probó que la cadena funciona; aquí se convierte en producto.

### Tarea 2.1: Rama y esqueleto del módulo

**Archivos:** crear `android/app/AndroidManifest.xml`, `android/app/res/values/strings.xml`,
`android/app/res/values-es/strings.xml`, `android/app/res/layout/activity_login.xml`,
`android/app/src/com/claudewidgets/android/LoginActivity.java`

- [ ] **Paso 1: Rama**

```bash
cd claude-usage-widgets
git checkout main && git pull
git checkout -b android/w3-f2-build
mkdir -p android/app/src/com/claudewidgets/android \
         android/app/test/com/claudewidgets/android \
         android/app/res/values android/app/res/values-es \
         android/app/res/layout android/app/res/xml android/app/res/drawable \
         android/app/ci
```

- [ ] **Paso 2: Manifiesto** (D1: `com.claudewidgets.android`)

Crear `android/app/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.claudewidgets.android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="@string/app_name"
        android:allowBackup="false"
        android:usesCleartextTraffic="false"
        android:supportsRtl="true">

        <activity
            android:name=".LoginActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`configChanges` evita que girar el teléfono recree la Activity y tire el WebView a medio login
(Review Focus del spike: el login es largo y se pierde con facilidad).

- [ ] **Paso 3: Cadenas, inglés por omisión**

Crear `android/app/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Claude Usage</string>
    <string name="core_ok">Core linked: %1$s</string>
</resources>
```

Crear `android/app/res/values-es/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Uso de Claude</string>
    <string name="core_ok">Núcleo enlazado: %1$s</string>
</resources>
```

- [ ] **Paso 4: Layout mínimo**

Crear `android/app/res/layout/activity_login.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">
    <TextView
        android:id="@+id/status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="16sp" />
</LinearLayout>
```

- [ ] **Paso 5: Activity que demuestra que el núcleo está enlazado**

Crear `android/app/src/com/claudewidgets/android/LoginActivity.java`:

```java
package com.claudewidgets.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.claudewidgets.core.Colors;

/**
 * En F2 solo demuestra que el nucleo quedo enlazado y dexeado para API 29. En F3 se convierte
 * en el login de verdad.
 */
public class LoginActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        // Si el nucleo no estuviera en el dex, esto no compilaria ni arrancaria.
        String probe = Colors.bar(85).toString();   // R7: 85 es rojo
        ((TextView) findViewById(R.id.status)).setText(getString(R.string.core_ok, probe));
    }
}
```

- [ ] **Paso 6: Commit**

```bash
git add android/app
git commit -m "feat: esqueleto del modulo android/app"
```

### Tarea 2.2: `build.sh`

**Archivos:** crear `android/app/build.sh`

El script compila **el núcleo y la app juntos**: el núcleo con `--release 8` (restricción heredada
de F1), la app contra `android.jar`.

- [ ] **Paso 1: Escribir el script**

Crear `android/app/build.sh`:

```bash
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
MIN_SDK=29
TARGET_SDK=34
OUT=build
APK="$OUT/claude-usage.apk"

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

# El nucleo va con --release 8 a proposito: Android trae java.time desde API 26 con la
# superficie de Java 8, y un JDK moderno dejaria colar APIs de Java 9+ que revientan en el
# telefono. La app no necesita esa restriccion porque compila contra android.jar, que ya
# describe lo que hay.
echo "== 3/6 javac (nucleo, --release 8) =="
find "$CORE/src" -name '*.java' | sort > "$OUT/core.txt"
javac -encoding UTF-8 --release 8 -Xlint:all,-options -Werror -d "$OUT/classes" "@$OUT/core.txt"

echo "== 4/6 javac (app) =="
find src "$OUT/gen" -name '*.java' | sort > "$OUT/app.txt"
javac -encoding UTF-8 -Xlint:all,-options -Werror \
  -classpath "$ANDROID_JAR:$OUT/classes" -d "$OUT/classes" "@$OUT/app.txt"

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
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=claude-usage-widgets debug, OU=debug, O=none, C=MX" \
    >/dev/null 2>&1
fi

apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --key-pass "pass:$KEYSTORE_PASSWORD" --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version "$MIN_SDK" --out "$APK" "$OUT/unsigned.apk"
apksigner verify "$APK"

echo
echo "APK: $HERE/$APK ($(stat -c %s "$APK") bytes)"
```

- [ ] **Paso 2: Permisos y primera construcción**

```bash
chmod +x android/app/build.sh
bash android/app/build.sh
```

Esperado: los seis pasos sin errores y una última línea
`APK: .../android/app/build/claude-usage.apk (NNNNN bytes)`.
Si `aapt2 link` se queja de `resource style/... not found`, es que falta un `res/values`: el
manifiesto no referencia ningún tema, así que no debería pasar.

- [ ] **Paso 3: Comprobar que `resources.arsc` quedó sin comprimir**

Esto es lo que hace innecesario `zipalign`. Si algún día cambia, el APK dejará de instalarse en
`targetSdk >= 30` y el mensaje de error no lo explica.

```bash
unzip -lv android/app/build/claude-usage.apk | grep resources.arsc
```
Esperado: la columna de método dice `Stored`.

- [ ] **Paso 4: `.gitignore`**

```bash
grep -q 'android/\*\*/build/' .gitignore || echo 'android/**/build/' >> .gitignore
```

- [ ] **Paso 5: Commit**

```bash
git add android/app/build.sh .gitignore
git commit -m "feat: build.sh del APK, sin Gradle"
```

### Tarea 2.3: Instalar y comprobar en el teléfono

- [ ] **Paso 1: Comprobar que ADB responde**

```bash
adb devices -l
```
Esperado: una línea con `device` y el modelo. Si sale vacío, la depuración inalámbrica cambió de
puerto: escanear `127.0.0.1` en el rango 30000-65535 y `adb connect` a cada candidato (el
procedimiento está en el `CLAUDE.md` global del aparato).

- [ ] **Paso 2: Instalar**

```bash
adb install -r android/app/build/claude-usage.apk
```
Esperado: `Success`.

- [ ] **Paso 3: Arrancar y leer lo que muestra**

```bash
adb shell monkey -p com.claudewidgets.android -c android.intent.category.LAUNCHER 1
sleep 3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -5
```
Esperado: entre los textos aparece `Núcleo enlazado: red` (o `Core linked: red` si el teléfono
está en inglés). `red` es el color que R7 da a 85, así que esa palabra demuestra que el núcleo
se ejecutó de verdad dentro de la app, no que solo compiló.

- [ ] **Paso 4: Comprobar que no hay errores de carga de clases**

```bash
adb logcat -d -s AndroidRuntime:E | tail -5
```
Esperado: ninguna línea de `com.claudewidgets`. Si aparece `NoClassDefFoundError`, el `d8` no
metió el núcleo: revisar que el paso 5 de `build.sh` recoja `$OUT/classes` **entero**.

- [ ] **Paso 5: Commit (si hubo ajustes)**

```bash
git add -A android/app
git commit -m "test: APK instalado y comprobado en el telefono"
```

### Tarea 2.4: Job de CI

**Archivos:** crear `android/app/ci/android.yml`

Recordar la restricción global 11: este archivo **no** puede ir a `.github/workflows/` desde esta
sesión.

- [ ] **Paso 1: Escribir el workflow**

Crear `android/app/ci/android.yml`:

```yaml
# Job del APK de Android (W3 F2).
#
# Vive aqui y NO en .github/workflows/ porque el token de la sesion TEL no tiene el scope
# `workflow` y GitHub rechaza el push. La sesion PC lo mueve con un commit suyo.
name: android
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5
        with:
          distribution: temurin
          java-version: "17"
      # El runner trae el SDK de Android preinstalado; se fija la version para que el APK
      # del CI y el del telefono se construyan contra el mismo android.jar.
      - name: Instalar las herramientas de compilacion
        run: |
          set -euo pipefail
          yes | sdkmanager --install "platforms;android-34" "build-tools;34.0.0" >/dev/null
          echo "$ANDROID_HOME/build-tools/34.0.0" >> "$GITHUB_PATH"
      - name: Construir el APK
        env:
          ANDROID_JAR: ${{ env.ANDROID_HOME }}/platforms/android-34/android.jar
        run: bash android/app/build.sh
      - name: Comprobar que resources.arsc no quedo comprimido
        run: |
          set -euo pipefail
          unzip -lv android/app/build/claude-usage.apk | grep resources.arsc | grep -q Stored
```

- [ ] **Paso 2: Comprobar que el YAML es válido** (el CI no lo dirá hasta que PC lo mueva)

```bash
python3 -c "import sys,json; print('sin parser YAML en Termux; se revisa a ojo')"
grep -n "uses:\|run:\|name:" android/app/ci/android.yml | head
```

- [ ] **Paso 3: Commit**

```bash
git add android/app/ci/android.yml
git commit -m "ci: job que construye el APK (PC lo mueve a .github/workflows)"
```

- [ ] **Paso 4: PR y memo**

```bash
git push -u origin android/w3-f2-build
gh pr create --base main --head android/w3-f2-build --title "W3 F2: build del APK sin Gradle"
```

El memo a PC debe decir, con estas palabras: **"el workflow está en `android/app/ci/android.yml`
y hay que moverlo a `.github/workflows/android.yml`; sin eso este PR no tiene CI propio"**.

---

## F3 — Login y almacén · rama `android/w3-f3-login`

**Entregable:** el usuario inicia sesión por correo, la cookie queda cifrada con una llave no
exportable del Android Keystore, y la app puede consultar `/usage` distinguiendo tres fracasos
distintos: sesión vencida, bloqueo y formato irreconocible. Más "cerrar sesión", que borra todo.

**Nota sobre las pruebas de esta fase:** el Android Keystore **no existe en una JVM**, así que
`SessionStoreTest` no puede correr con `java`. Corre bajo ART en el teléfono, con el mismo truco
que ya usa `android/core/test/AndroidSmoke`: dexear y lanzar con `app_process`. Es la razón de
que esta fase traiga su propio corredor.

### Tarea 3.1: Corredor de pruebas de la app

**Archivos:** crear `android/app/test/com/claudewidgets/android/AppTestRunner.java`,
modificar `android/app/build.sh`

- [ ] **Paso 1: Escribir el corredor**

Crear `android/app/test/com/claudewidgets/android/AppTestRunner.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;

import java.util.List;

/**
 * Corredor de las pruebas de la cascara. Corre bajo ART, no en una JVM: el Android Keystore
 * y el almacenamiento de la app no existen fuera del dispositivo.
 *
 * Se lanza con:
 *   adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / \
 *              com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test"
 */
public final class AppTestRunner {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("uso: AppTestRunner <directorio temporal escribible>");
            System.exit(2);
        }
        java.io.File tmp = new java.io.File(args[0]);
        if (!tmp.isDirectory() && !tmp.mkdirs()) {
            System.err.println("no se pudo crear " + tmp);
            System.exit(2);
        }
        Assert a = new Assert();
        SessionStoreTest.run(a);
        OrgSelectorTest.run(a);
        SampleStoreTest.run(a, tmp);
        BackoffTest.run(a);
        UsageClientTest.run(a);

        List<String> failures = a.failures();
        if (failures.isEmpty()) {
            System.out.println("OK: " + a.checks() + " comprobaciones, 0 fallos");
            return;
        }
        System.out.println("FALLOS (" + failures.size() + " de " + a.checks() + "):");
        for (String f : failures) System.out.println("  - " + f);
        System.exit(1);
    }
}
```

`Assert` se reutiliza del núcleo: ya es `public` y no tiene dependencias.

- [ ] **Paso 2: Añadir a `build.sh` un modo que dexea también las pruebas**

En `android/app/build.sh`, justo antes de `echo "== 5/6 d8 =="`, insertar:

```bash
# Con TEST=1 se compilan y dexean tambien las pruebas de la cascara, para correrlas bajo ART.
if [ "${TEST:-0}" = "1" ]; then
  echo "== extra: javac (pruebas) =="
  find "$CORE/test" test -name '*.java' | sort > "$OUT/tests.txt"
  javac -encoding UTF-8 -Xlint:all,-options -Werror \
    -classpath "$ANDROID_JAR:$OUT/classes" -d "$OUT/classes" "@$OUT/tests.txt"
fi
```

- [ ] **Paso 3: Commit**

```bash
git add android/app/test android/app/build.sh
git commit -m "test: corredor de la cascara, corre bajo ART"
```

### Tarea 3.2: `SessionStore` — cookie cifrada con el Android Keystore

**Archivos:** crear `android/app/test/com/claudewidgets/android/SessionStoreTest.java`,
`android/app/src/com/claudewidgets/android/SessionStore.java`

- [ ] **Paso 1: Escribir la prueba que falla**

Crear `android/app/test/com/claudewidgets/android/SessionStoreTest.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** El Keystore solo existe bajo ART; estas pruebas no corren en una JVM. */
public final class SessionStoreTest {

    private static final String COOKIE = "sessionKey=valor-de-prueba; lastActiveOrg=otro-valor";

    public static void run(Assert a) {
        SessionStore s = new SessionStore(TestContext.get());
        s.clear();
        a.isTrue("sin sesion al empezar", !s.hasSession());
        a.eq("load sin sesion da null", null, call(a, () -> s.load()));

        call(a, () -> { s.save(COOKIE); return null; });
        a.isTrue("hasSession tras guardar", s.hasSession());
        a.eq("vuelve lo mismo que entro", COOKIE, call(a, () -> s.load()));

        // Lo que queda en disco no puede contener el texto claro.
        File f = new File(TestContext.get().getFilesDir(), SessionStore.FILE_NAME);
        String raw = new String(readAll(f), StandardCharsets.ISO_8859_1);
        a.isTrue("el archivo no contiene la cookie en claro", !raw.contains("valor-de-prueba"));
        a.isTrue("el archivo no contiene el nombre sessionKey", !raw.contains("sessionKey"));

        s.clear();
        a.isTrue("clear borra la sesion", !s.hasSession());
        a.isTrue("clear es idempotente", clearTwiceOk(s));
    }

    private static boolean clearTwiceOk(SessionStore s) {
        try { s.clear(); return true; } catch (RuntimeException e) { return false; }
    }

    private static byte[] readAll(File f) {
        try { return Files.readAllBytes(f.toPath()); } catch (Exception e) { return new byte[0]; }
    }

    interface Body<T> { T run() throws Exception; }

    private static <T> T call(Assert a, Body<T> b) {
        try {
            return b.run();
        } catch (Exception e) {
            a.fail("lanzo " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
```

`TestContext` es un ayudante mínimo que da un `Context` bajo `app_process` (donde no hay
Application): se escribe en el paso siguiente.

- [ ] **Paso 2: El ayudante de contexto**

Crear `android/app/test/com/claudewidgets/android/TestContext.java`:

```java
package com.claudewidgets.android;

import android.app.Application;
import android.content.Context;

/**
 * Bajo `app_process` no hay Application, asi que se crea una a mano. Es lo minimo que necesitan
 * `getFilesDir()` y el Keystore.
 */
final class TestContext {
    private static Context ctx;

    private TestContext() {}

    static synchronized Context get() {
        if (ctx == null) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("systemMain").invoke(null);
                ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
                Application app = (Application) ctx.getPackageManager()
                        .getApplicationInfo("com.claudewidgets.android", 0)
                        .getClass().getClassLoader()
                        .loadClass("android.app.Application").newInstance();
                app.attachBaseContext(ctx.createPackageContext(
                        "com.claudewidgets.android", Context.CONTEXT_INCLUDE_CODE));
                ctx = app;
            } catch (Exception e) {
                throw new IllegalStateException("no se pudo armar un Context de prueba", e);
            }
        }
        return ctx;
    }
}
```

> **Si este ayudante no funciona en el dispositivo** (`app_process` cambia entre versiones de
> Android), la alternativa es una `Activity` de pruebas en el propio APK que corra el
> `AppTestRunner` y escriba el resultado en `logcat`, lanzada con `am start`. Ejecutar la
> alternativa **solo** si el paso 4 de esta tarea falla con `IllegalStateException`, y anotarlo
> en el PR: cambia cómo se corren todas las pruebas de la cáscara.

- [ ] **Paso 3: Correr la prueba y verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: **falla a compilar** con `cannot find symbol: class SessionStore`. Ese es el rojo.

- [ ] **Paso 4: Implementar `SessionStore`**

Crear `android/app/src/com/claudewidgets/android/SessionStore.java`:

```java
package com.claudewidgets.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda la cookie de sesion cifrada con AES-GCM y una llave del Android Keystore que **no es
 * exportable**: ni con root se saca del dispositivo, solo se puede pedir al sistema que cifre
 * o descifre con ella.
 *
 * Formato del archivo: [1 byte longitud del IV][IV][texto cifrado + etiqueta GCM].
 *
 * Nada de lo que pasa por aqui se registra. `load()` devuelve material sensible: quien lo llame
 * no debe pasarlo a `Log`, a un `Intent` ni a un mensaje de error.
 */
public final class SessionStore {

    static final String FILE_NAME = "session.bin";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cuw-session-v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final Context ctx;

    public SessionStore(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    public boolean hasSession() {
        return file().isFile() && file().length() > 0;
    }

    public void save(String cookies) throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] body = c.doFinal(cookies.getBytes(StandardCharsets.UTF_8));
        File f = file();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(iv.length);
            out.write(iv);
            out.write(body);
        }
    }

    /** Null si no hay sesion. Si el archivo esta corrupto, se borra y se devuelve null. */
    public String load() throws GeneralSecurityException, IOException {
        if (!hasSession()) return null;
        byte[] all = readAll(file());
        if (all.length < 2) { clear(); return null; }
        int ivLen = all[0] & 0xFF;
        if (ivLen <= 0 || all.length < 1 + ivLen + 1) { clear(); return null; }
        byte[] iv = new byte[ivLen];
        System.arraycopy(all, 1, iv, 0, ivLen);
        byte[] body = new byte[all.length - 1 - ivLen];
        System.arraycopy(all, 1 + ivLen, body, 0, body.length);
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // La etiqueta GCM no cuadra: el archivo se manipulo o la llave cambio.
            // No se puede recuperar; se borra y el usuario vuelve a iniciar sesion.
            clear();
            return null;
        }
    }

    /** Borra el archivo y la llave. Idempotente: no lanza si no habia nada. */
    public void clear() {
        File f = file();
        if (f.isFile()) {
            overwrite(f);
            if (!f.delete()) f.deleteOnExit();
        }
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException | IOException ignored) {
            // Si el Keystore no responde, el archivo ya esta borrado: sin llave no se descifra.
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin autenticacion de usuario: el widget se actualiza con la pantalla apagada.
                .setUserAuthenticationRequired(false)
                .build());
        return g.generateKey();
    }

    private File file() { return new File(ctx.getFilesDir(), FILE_NAME); }

    private static byte[] readAll(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        }
    }

    /** Pisa el contenido antes de borrar, para que no quede en bloques libres del sistema. */
    private static void overwrite(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] zeros = new byte[(int) r.length()];
            r.seek(0);
            r.write(zeros);
            r.getFD().sync();
        } catch (IOException ignored) {
            // Si no se puede pisar, igual se borra: sin la llave el contenido no sirve.
        }
    }
}
```

- [ ] **Paso 5: Dexear las pruebas y correrlas bajo ART**

```bash
TEST=1 bash android/app/build.sh
cd android/app
mkdir -p build/dextest
find build/classes -name '*.class' > build/all.txt
d8 --min-api 29 --lib "${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}" \
   --output build/dextest $(cat build/all.txt)
adb push build/dextest/classes.dex /data/local/tmp/app-test.dex
adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / \
  com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test"
cd ../..
```
Esperado: `OK: N comprobaciones, 0 fallos`.

- [ ] **Paso 6: Comprobar a mano que el archivo no tiene la cookie en claro**

Esto no se delega a la prueba: es la regla 1 de `SECURITY.md` y conviene verla con los ojos.

```bash
adb shell run-as com.claudewidgets.android ls -l files/ 2>/dev/null \
  || echo "(el APK no es debuggable: se comprueba con la asercion de la prueba)"
```

- [ ] **Paso 7: Commit**

```bash
git add android/app/src/com/claudewidgets/android/SessionStore.java android/app/test
git commit -m "feat: cookie cifrada con AES-GCM y llave del Android Keystore"
```

### Tarea 3.3: Excepciones y `Backoff`

**Archivos:** crear `BlockedException.java`, `AuthExpiredException.java`, `Backoff.java`,
`test/.../BackoffTest.java`

Tres fracasos distintos necesitan tres respuestas distintas, y confundirlos es lo que haría que el
widget mintiera. Por eso son tipos y no un booleano.

- [ ] **Paso 1: La prueba de `Backoff` que falla**

Crear `android/app/test/com/claudewidgets/android/BackoffTest.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;

public final class BackoffTest {
    public static void run(Assert a) {
        a.eq("primer reintento", 60L, Backoff.seconds(0));
        a.eq("segundo", 120L, Backoff.seconds(1));
        a.eq("tercero", 240L, Backoff.seconds(2));
        a.eq("se topa en 30 min", 1800L, Backoff.seconds(10));
        a.eq("sigue topado muy arriba", 1800L, Backoff.seconds(1000));
        a.eq("un intento negativo cuenta como el primero", 60L, Backoff.seconds(-1));
    }
}
```

- [ ] **Paso 2: Verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: `cannot find symbol: class Backoff`.

- [ ] **Paso 3: Implementar**

Crear `android/app/src/com/claudewidgets/android/Backoff.java`:

```java
package com.claudewidgets.android;

/** Espera exponencial de la spec 3.4: desde 1 min, duplicando, con tope de 30 min. */
public final class Backoff {

    private static final long FIRST = 60L;
    private static final long MAX = 1800L;

    private Backoff() {}

    public static long seconds(int attempt) {
        if (attempt <= 0) return FIRST;
        if (attempt >= 5) return MAX;          // 60 * 2^5 = 1920 > 1800
        return Math.min(FIRST << attempt, MAX);
    }
}
```

Crear `android/app/src/com/claudewidgets/android/AuthExpiredException.java`:

```java
package com.claudewidgets.android;

/** 401: la cookie ya no vale. Lleva al usuario a iniciar sesion otra vez. */
public final class AuthExpiredException extends Exception {
    private static final long serialVersionUID = 1L;
    public AuthExpiredException(String message) { super(message); }
}
```

Crear `android/app/src/com/claudewidgets/android/BlockedException.java`:

```java
package com.claudewidgets.android;

/**
 * La peticion no llego al endpoint: 403, cabecera `cf-mitigated`, o HTML donde se esperaba JSON.
 * No es lo mismo que una sesion vencida (ahi hay que re-login) ni que un formato cambiado
 * (ahi hay que avisar al proyecto): aqui toca esperar y reintentar.
 */
public final class BlockedException extends Exception {
    private static final long serialVersionUID = 1L;
    public BlockedException(String message) { super(message); }
}
```

- [ ] **Paso 4: Verla pasar**

```bash
TEST=1 bash android/app/build.sh && \
  (cd android/app && d8 --min-api 29 --lib "${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}" \
     --output build/dextest $(find build/classes -name '*.class') && \
   adb push build/dextest/classes.dex /data/local/tmp/app-test.dex >/dev/null && \
   adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test")
```
Esperado: `OK: N comprobaciones, 0 fallos`, con N mayor que antes.

- [ ] **Paso 5: Commit**

```bash
git add android/app/src android/app/test
git commit -m "feat: backoff y los tres fracasos distinguibles"
```

### Tarea 3.4: `UsageClient` — la consulta nativa

**Archivos:** crear `UsageClient.java`, `test/.../UsageClientTest.java`

Esta clase es donde viven las restricciones globales 3, 7 y 8. Lee con atención: lo que **no**
hace es tan importante como lo que hace.

**Review Focus 2 y 3** se cubren aquí.

- [ ] **Paso 1: La prueba que falla**

La clasificación de respuestas se prueba sin red, con un método interno que recibe código,
`content-type` y cuerpo. La petición de verdad se prueba a mano en la Tarea 3.6.

Crear `android/app/test/com/claudewidgets/android/UsageClientTest.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;

public final class UsageClientTest {

    public static void run(Assert a) {
        // 200 con JSON: pasa.
        a.eq("200 json", null, classify(200, "application/json", "{}"));
        a.eq("200 json con charset", null,
                classify(200, "application/json; charset=utf-8", "{}"));

        // 401: sesion vencida (Review Focus 2).
        a.eq("401", "auth", classify(401, "application/json", "{}"));

        // Bloqueo (Review Focus 3): 403, cf-mitigated, o HTML donde se espera JSON.
        a.eq("403", "blocked", classify(403, "application/json", "{}"));
        a.eq("200 pero html", "blocked", classify(200, "text/html", "<!DOCTYPE html>"));
        a.eq("200 sin content-type y cuerpo html", "blocked",
                classify(200, null, "  <html><head>"));
        a.eq("cf-mitigated", "blocked", classifyWithHeader(200, "application/json", "{}", "challenge"));

        // 429 y 5xx: ni vencida ni bloqueada; se reintenta con backoff.
        a.eq("429", "retry", classify(429, "application/json", "{}"));
        a.eq("500", "retry", classify(500, "application/json", "{}"));
        a.eq("503", "retry", classify(503, "application/json", "{}"));

        // Cualquier otro codigo raro: formato no reconocido, no se inventa nada.
        a.eq("418", "format", classify(418, "application/json", "{}"));
    }

    private static String classify(int code, String ctype, String body) {
        return classifyWithHeader(code, ctype, body, null);
    }

    private static String classifyWithHeader(int code, String ctype, String body, String cfMitigated) {
        try {
            UsageClient.check(code, ctype, body, cfMitigated);
            return null;
        } catch (AuthExpiredException e) {
            return "auth";
        } catch (BlockedException e) {
            return "blocked";
        } catch (UsageClient.RetryLaterException e) {
            return "retry";
        } catch (com.claudewidgets.core.UnrecognizedFormatException e) {
            return "format";
        }
    }
}
```

- [ ] **Paso 2: Verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: `cannot find symbol: class UsageClient`.

- [ ] **Paso 3: Implementar**

Crear `android/app/src/com/claudewidgets/android/UsageClient.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Json;
import com.claudewidgets.core.Parser;
import com.claudewidgets.core.Source;
import com.claudewidgets.core.UnrecognizedFormatException;
import com.claudewidgets.core.UsageModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consulta de uso contra claude.ai. Via principal del widget (spike A2: la peticion nativa no
 * recibio reto en las condiciones probadas).
 *
 * Lo que esta clase NO hace, a proposito:
 * - No registra cuerpos de respuesta. Solo codigo, content-type y longitud.
 * - No manda el jarro de cookies entero: solo `sessionKey` y `lastActiveOrg`.
 * - No sigue redirecciones: la cookie jamas debe viajar a otro host.
 * - No habla con ningun host que no sea claude.ai.
 */
public final class UsageClient {

    private static final String ORIGIN = "https://claude.ai";
    private static final String ORGS = ORIGIN + "/api/organizations";
    /** 1 MiB: lo mismo que acota el lector JSON, pero en BYTES y antes de decodificar. */
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int TIMEOUT_MS = 20000;

    /** 429 y 5xx: ni vencida ni bloqueada. Se reintenta con {@link Backoff}. */
    public static final class RetryLaterException extends Exception {
        private static final long serialVersionUID = 1L;
        RetryLaterException(String message) { super(message); }
    }

    private final String cookieHeader;
    private final String userAgent;

    /** `cookieHeader` ya viene reducido al minimo: lo arma {@link #minimalCookies}. */
    public UsageClient(String cookieHeader, String userAgent) {
        this.cookieHeader = cookieHeader;
        this.userAgent = userAgent;
    }

    /**
     * De todo lo que guardo el WebView, deja solo lo que claude.ai necesita. Mandar el resto
     * seria entregar cookies de terceros sin ninguna razon.
     */
    public static String minimalCookies(String all) {
        if (all == null) return "";
        StringBuilder out = new StringBuilder();
        for (String piece : all.split(";")) {
            String p = piece.trim();
            if (p.startsWith("sessionKey=") || p.startsWith("lastActiveOrg=")) {
                if (out.length() > 0) out.append("; ");
                out.append(p);
            }
        }
        return out.toString();
    }

    /** El valor de `lastActiveOrg`, o null. Es una credencial: no se registra. */
    public static String lastActiveOrg(String all) {
        if (all == null) return null;
        for (String piece : all.split(";")) {
            String p = piece.trim();
            if (p.startsWith("lastActiveOrg=")) {
                String v = p.substring("lastActiveOrg=".length());
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    public List<String> organizations() throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        String body = get(ORGS);
        Object root = parseOrFail(body);
        if (!(root instanceof List)) {
            throw new UnrecognizedFormatException("/api/organizations no devolvio un arreglo");
        }
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) root) {
            if (!(item instanceof Map)) continue;
            Object uuid = ((Map<?, ?>) item).get("uuid");
            if (uuid instanceof String && !((String) uuid).isEmpty()) out.add((String) uuid);
        }
        if (out.isEmpty()) throw new UnrecognizedFormatException("ninguna organizacion con uuid");
        return out;
    }

    public UsageModel usage(String orgUuid) throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        // orgUuid es una credencial: si falla, el mensaje no lo lleva.
        return Parser.parse(get(ORGS + "/" + orgUuid + "/usage"), Source.CLAUDE_AI);
    }

    private static Object parseOrFail(String body) throws UnrecognizedFormatException {
        try {
            return Json.parse(body);
        } catch (Json.JsonException e) {
            throw new UnrecognizedFormatException("la respuesta no es JSON valido", e);
        }
    }

    private String get(String url) throws IOException, AuthExpiredException, BlockedException,
            RetryLaterException, UnrecognizedFormatException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Cookie", cookieHeader);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Referer", ORIGIN + "/");

            int code = c.getResponseCode();
            String ctype = c.getHeaderField("content-type");
            String cfMitigated = c.getHeaderField("cf-mitigated");
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : read(in);
            check(code, ctype, body, cfMitigated);
            return body;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Clasifica la respuesta. Package-private para poder probarla sin red.
     * No recibe la URL ni la cookie: nada de lo que entra aqui puede acabar en un log.
     */
    static void check(int code, String ctype, String body, String cfMitigated)
            throws AuthExpiredException, BlockedException, RetryLaterException,
            UnrecognizedFormatException {
        if (cfMitigated != null && !cfMitigated.isEmpty()) {
            throw new BlockedException("cf-mitigated presente");
        }
        if (code == 401) throw new AuthExpiredException("401");
        if (code == 403) throw new BlockedException("403");
        if (code == 429 || (code >= 500 && code < 600)) {
            throw new RetryLaterException("HTTP " + code);
        }
        if (code != 200) throw new UnrecognizedFormatException("HTTP " + code);
        if (looksLikeHtml(ctype, body)) {
            // 200 con HTML es la pagina de reto de Cloudflare, no una respuesta.
            throw new BlockedException("se esperaba JSON y llego HTML");
        }
    }

    private static boolean looksLikeHtml(String ctype, String body) {
        if (ctype != null && ctype.toLowerCase(Locale.ROOT).contains("text/html")) return true;
        String head = body.length() > 64 ? body.substring(0, 64) : body;
        String t = head.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("<!doctype") || t.startsWith("<html");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) {
                throw new IOException("respuesta de mas de " + MAX_BYTES + " bytes");
            }
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

El tope de bytes **antes** de construir el `String` es el pendiente que dejó el lector JSON del
núcleo: allí `MAX_INPUT` se mide sobre texto ya decodificado y no protege de una descarga enorme.

- [ ] **Paso 4: Verla pasar**

```bash
TEST=1 bash android/app/build.sh && (cd android/app && \
  d8 --min-api 29 --lib "${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}" \
     --output build/dextest $(find build/classes -name '*.class') && \
  adb push build/dextest/classes.dex /data/local/tmp/app-test.dex >/dev/null && \
  adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test")
```
Esperado: `OK`, con las 11 comprobaciones nuevas de `UsageClientTest`.

- [ ] **Paso 5: Commit**

```bash
git add android/app/src android/app/test
git commit -m "feat: cliente de uso que distingue vencida, bloqueada y formato raro"
```

### Tarea 3.5: `OrgSelector` (decisión D2)

**Archivos:** crear `OrgSelector.java`, `test/.../OrgSelectorTest.java`

- [ ] **Paso 1: La prueba que falla**

Crear `android/app/test/com/claudewidgets/android/OrgSelectorTest.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;

import java.util.Arrays;
import java.util.List;

/**
 * Se prueba la REGLA, no la red: `OrgSelector` recibe un probador que dice que organizaciones
 * responden 200.
 */
public final class OrgSelectorTest {

    public static void run(Assert a) {
        List<String> dos = Arrays.asList("org-a", "org-b");

        a.eq("el manual gana siempre", "org-z",
                OrgSelector.choose(dos, "org-z", "org-a", u -> true));
        a.eq("el manual gana aunque no responda", "org-z",
                OrgSelector.choose(dos, "org-z", "org-a", u -> false));

        a.eq("sin manual, lastActiveOrg si responde", "org-b",
                OrgSelector.choose(dos, null, "org-b", u -> true));
        a.eq("lastActiveOrg que no responde cae a la primera que si", "org-a",
                OrgSelector.choose(dos, null, "org-b", u -> u.equals("org-a")));
        a.eq("lastActiveOrg ajeno a la lista igual se intenta", "org-c",
                OrgSelector.choose(dos, null, "org-c", u -> true));

        a.eq("sin lastActiveOrg, la primera que responde", "org-b",
                OrgSelector.choose(dos, null, null, u -> u.equals("org-b")));
        a.eq("si ninguna responde, null", null,
                OrgSelector.choose(dos, null, null, u -> false));
        a.eq("lista vacia sin manual, null", null,
                OrgSelector.choose(Arrays.asList(), null, null, u -> true));
    }
}
```

- [ ] **Paso 2: Verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: `cannot find symbol: class OrgSelector`.

- [ ] **Paso 3: Implementar**

Crear `android/app/src/com/claudewidgets/android/OrgSelector.java`:

```java
package com.claudewidgets.android;

import java.util.List;

/**
 * Decision D2: que organizacion mira el widget cuando la cuenta tiene varias.
 *
 * Orden: lo que el usuario eligio a mano, luego la que la web considera activa
 * (`lastActiveOrg`), luego la primera que responda. Nunca se elige "la del plan mas alto":
 * eso mostraria una cuota que el usuario no esta usando.
 */
public final class OrgSelector {

    /** Dice si `/usage` de esa organizacion responde bien. */
    public interface Probe { boolean responds(String orgUuid); }

    private OrgSelector() {}

    public static String choose(List<String> organizations, String manual,
                                String lastActiveOrg, Probe probe) {
        if (manual != null && !manual.isEmpty()) return manual;
        if (lastActiveOrg != null && !lastActiveOrg.isEmpty() && probe.responds(lastActiveOrg)) {
            return lastActiveOrg;
        }
        for (String uuid : organizations) {
            if (probe.responds(uuid)) return uuid;
        }
        return null;
    }
}
```

- [ ] **Paso 4: Verla pasar y commit**

```bash
TEST=1 bash android/app/build.sh && (cd android/app && \
  d8 --min-api 29 --lib "${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}" \
     --output build/dextest $(find build/classes -name '*.class') && \
  adb push build/dextest/classes.dex /data/local/tmp/app-test.dex >/dev/null && \
  adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test")
git add android/app/src android/app/test
git commit -m "feat: regla de seleccion de organizacion (D2)"
```

### Tarea 3.6: `LoginActivity` — la pantalla previa y el WebView

**Archivos:** modificar `res/layout/activity_login.xml`, `res/values/strings.xml`,
`res/values-es/strings.xml`, `src/.../LoginActivity.java`

La pantalla previa existe por un hallazgo del spike: **el botón de Google de claude.ai bloquea la
cuenta 48 horas** dentro de un WebView, y la app no controla esa página. Lo único que puede hacer
es avisar antes.

- [ ] **Paso 1: Cadenas** — añadir a `res/values/strings.xml`:

```xml
    <string name="login_title">Sign in to Claude</string>
    <string name="login_email_only">On Android, <b>only email sign-in works</b>. The "Continue with
        Google" button fails inside an in-app browser: Google blocks the attempt for 48 hours.</string>
    <string name="login_steps">You will get a link by email. It expires in 10 minutes. If opening it
        shows a verification code instead of signing you in, type that code here.</string>
    <string name="login_start">Sign in with email</string>
    <string name="login_done">Done</string>
    <string name="login_checking">Checking…</string>
    <string name="login_no_session">No session yet. Finish signing in on the page above.</string>
    <string name="login_ok">Signed in. You can add the widget to your home screen.</string>
    <string name="logout">Sign out</string>
```

Y a `res/values-es/strings.xml`:

```xml
    <string name="login_title">Inicia sesión en Claude</string>
    <string name="login_email_only">En Android <b>solo funciona el acceso por correo</b>. El botón
        "Continuar con Google" falla dentro de un navegador incrustado: Google bloquea el intento
        durante 48 horas.</string>
    <string name="login_steps">Te llegará un enlace por correo. Vence en 10 minutos. Si al abrirlo
        te muestra un código de verificación en vez de iniciar sesión, escribe ese código aquí.</string>
    <string name="login_start">Entrar con correo</string>
    <string name="login_done">Listo</string>
    <string name="login_checking">Comprobando…</string>
    <string name="login_no_session">Todavía no hay sesión. Termina de entrar en la página de arriba.</string>
    <string name="login_ok">Sesión iniciada. Ya puedes añadir el widget a la pantalla de inicio.</string>
    <string name="logout">Cerrar sesión</string>
```

- [ ] **Paso 2: Layout** — reemplazar `res/layout/activity_login.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <LinearLayout
        android:id="@+id/intro"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:padding="20dp">
        <TextView android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="@string/login_title" android:textSize="22sp" android:textStyle="bold"
            android:paddingBottom="16dp" />
        <TextView android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="@string/login_email_only" android:textSize="15sp"
            android:paddingBottom="12dp" />
        <TextView android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="@string/login_steps" android:textSize="15sp"
            android:paddingBottom="20dp" />
        <Button android:id="@+id/btn_start" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/login_start" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/web_pane"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:visibility="gone">
        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal">
            <Button android:id="@+id/btn_done" android:layout_width="0dp"
                android:layout_height="wrap_content" android:layout_weight="1"
                android:text="@string/login_done" />
            <Button android:id="@+id/btn_logout" android:layout_width="0dp"
                android:layout_height="wrap_content" android:layout_weight="1"
                android:text="@string/logout" />
        </LinearLayout>
        <TextView android:id="@+id/status" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:padding="10dp" android:textSize="14sp" />
        <WebView android:id="@+id/web" android:layout_width="match_parent"
            android:layout_height="0dp" android:layout_weight="1" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Paso 3: Implementar** — reemplazar `src/com/claudewidgets/android/LoginActivity.java`:

```java
package com.claudewidgets.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

/**
 * Login por correo en un WebView de claude.ai.
 *
 * El WebView es SOLO para el login: sin puente JS, sin cookies de terceros, y cuando termina
 * se le borra todo. Las consultas periodicas van por HttpURLConnection (spike A2).
 */
public class LoginActivity extends Activity {

    private static final String LOGIN_URL = "https://claude.ai/login";
    private static final String ORIGIN = "https://claude.ai";

    private WebView web;
    private TextView status;
    private SessionStore store;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        store = new SessionStore(this);
        status = findViewById(R.id.status);
        web = findViewById(R.id.web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // claude.ai no carga sin JS
        s.setDomStorageEnabled(true);
        // Sin addJavascriptInterface: SECURITY.md regla 5.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);   // restriccion global 6

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startLogin());
        ((Button) findViewById(R.id.btn_done)).setOnClickListener(v -> finishLogin());
        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> logout());

        if (store.hasSession()) status.setText(R.string.login_ok);
    }

    private void startLogin() {
        findViewById(R.id.intro).setVisibility(View.GONE);
        findViewById(R.id.web_pane).setVisibility(View.VISIBLE);
        web.loadUrl(LOGIN_URL);
    }

    /** El usuario dice que ya entro. Se comprueba leyendo la cookie, no creyendole. */
    private void finishLogin() {
        status.setText(R.string.login_checking);
        String all = CookieManager.getInstance().getCookie(ORIGIN);
        String minimal = UsageClient.minimalCookies(all);
        if (!minimal.contains("sessionKey=")) {
            status.setText(R.string.login_no_session);
            return;
        }
        try {
            store.save(minimal);           // se guarda YA reducida al minimo
        } catch (Exception e) {
            // El mensaje de la excepcion podria arrastrar material sensible: no se muestra.
            status.setText(R.string.login_no_session);
            return;
        }
        wipeWebView();
        status.setText(R.string.login_ok);
        WidgetUpdateJob.schedule(this);    // F4
        WidgetUpdateJob.runNow(this);      // F4: que el widget no espere 15 min
    }

    private void logout() {
        store.clear();
        wipeWebView();
        WidgetUpdateJob.cancel(this);      // F4
        status.setText(R.string.login_no_session);
    }

    /** Tras el login el WebView no debe conservar nada: su copia de la cookie sobra. */
    private void wipeWebView() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        web.clearCache(true);
        web.clearHistory();
        web.clearFormData();
        web.loadUrl("about:blank");
    }

    /** El UA del WebView es el que usan las consultas nativas: una sola huella. */
    static String userAgent(WebView web) {
        return web.getSettings().getUserAgentString();
    }
}
```

> **Dependencia hacia adelante:** `WidgetUpdateJob` se crea en F4. Hasta entonces, comentar esas
> dos líneas y dejar un `// TODO F4` **no** es aceptable en el commit final: la Tarea 4.4 las
> descomenta y el PR de F3 debe compilar. Por eso F3 incluye la Tarea 3.7, que crea el esqueleto
> de `WidgetUpdateJob` con los tres métodos vacíos.

- [ ] **Paso 4: Commit**

```bash
git add android/app/src android/app/res
git commit -m "feat: login por correo con pantalla previa que explica lo de Google"
```

### Tarea 3.7: Esqueleto de `WidgetUpdateJob` y comprobación del login a mano

**Archivos:** crear `src/.../WidgetUpdateJob.java` (esqueleto)

- [ ] **Paso 1: Esqueleto que compila**

```java
package com.claudewidgets.android;

import android.content.Context;

/** Esqueleto de F3; F4 lo llena. Los metodos existen para que LoginActivity compile. */
public final class WidgetUpdateJob {
    private WidgetUpdateJob() {}
    public static void schedule(Context ctx) { }
    public static void cancel(Context ctx) { }
    public static void runNow(Context ctx) { }
}
```

- [ ] **Paso 2: Construir, instalar y hacer el login de verdad**

```bash
bash android/app/build.sh
adb install -r android/app/build/claude-usage.apk
adb shell monkey -p com.claudewidgets.android -c android.intent.category.LAUNCHER 1
```

Luego, **a mano en el teléfono** (es la única parte que no se automatiza): tocar "Entrar con
correo", escribir el correo en la página de claude.ai, abrir el enlace que llega **antes de que
pasen 10 minutos**, y si el navegador muestra un código en vez de entrar, escribirlo en la página.
Al terminar, tocar "Listo".

Esperado en pantalla: `Sesión iniciada. Ya puedes añadir el widget a la pantalla de inicio.`

- [ ] **Paso 3: Comprobar que no se filtró nada**

```bash
adb logcat -d | grep -i "sessionKey\|lastActiveOrg" | grep -v "nombres=" || echo "limpio"
```
Esperado: `limpio`. Si aparece algo, es un fallo de seguridad y bloquea el PR.

- [ ] **Paso 4: Comprobar que la consulta real funciona con la cookie guardada**

```bash
adb shell am start -n com.claudewidgets.android/.SettingsActivity 2>/dev/null \
  || echo "(SettingsActivity es la Tarea 3.8)"
```

- [ ] **Paso 5: Commit, PR y memo**

```bash
git add android/app/src
git commit -m "feat: esqueleto del job para que F3 compile"
git push -u origin android/w3-f3-login
gh pr create --base main --head android/w3-f3-login --title "W3 F3: login por correo y almacen cifrado"
```

El memo a PC debe incluir: la salida del corredor bajo ART, la confirmación de que `logcat` no
tiene la cookie, y **si el ayudante `TestContext` funcionó o hubo que usar la alternativa**.

### Tarea 3.8: `SettingsActivity` — selector de organización y cerrar sesión

**Archivos:** crear `res/layout/activity_settings.xml`, `src/.../SettingsActivity.java`,
modificar `AndroidManifest.xml`

- [ ] **Paso 1: Declarar la Activity** en `AndroidManifest.xml`, dentro de `<application>`:

```xml
        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="@string/settings_title" />
```

- [ ] **Paso 2: Cadenas** — `res/values/strings.xml`:

```xml
    <string name="settings_title">Settings</string>
    <string name="settings_org">Organization</string>
    <string name="settings_org_auto">Automatic</string>
    <string name="settings_org_help">Your account has more than one organization. Pick the one the
        widget should show.</string>
```

`res/values-es/strings.xml`:

```xml
    <string name="settings_title">Ajustes</string>
    <string name="settings_org">Organización</string>
    <string name="settings_org_auto">Automática</string>
    <string name="settings_org_help">Tu cuenta tiene más de una organización. Elige cuál debe
        mostrar el widget.</string>
```

- [ ] **Paso 3: Layout** — crear `res/layout/activity_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="20dp">
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="@string/settings_org" android:textSize="18sp" android:textStyle="bold" />
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="@string/settings_org_help" android:textSize="14sp"
        android:paddingTop="4dp" android:paddingBottom="12dp" />
    <RadioGroup android:id="@+id/orgs" android:layout_width="match_parent"
        android:layout_height="wrap_content" />
    <Button android:id="@+id/btn_logout" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:layout_marginTop="24dp"
        android:text="@string/logout" />
</LinearLayout>
```

- [ ] **Paso 4: Implementar** — crear `src/com/claudewidgets/android/SettingsActivity.java`:

```java
package com.claudewidgets.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.util.List;

/**
 * Selector manual de organizacion y cerrar sesion.
 *
 * Los uuid son credenciales (restriccion global 2): se guardan en las preferencias privadas de
 * la app, no se muestran enteros y no se registran. En pantalla se ven abreviados.
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "cuw";
    static final String KEY_ORG = "manual_org";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        RadioGroup group = findViewById(R.id.orgs);
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_ORG, null);

        RadioButton auto = new RadioButton(this);
        auto.setText(R.string.settings_org_auto);
        auto.setChecked(current == null);
        auto.setOnClickListener(v -> prefs.edit().remove(KEY_ORG).apply());
        group.addView(auto);

        for (String uuid : knownOrgs()) {
            RadioButton b = new RadioButton(this);
            b.setText(abbreviate(uuid));
            b.setChecked(uuid.equals(current));
            b.setOnClickListener(v -> prefs.edit().putString(KEY_ORG, uuid).apply());
            group.addView(b);
        }

        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> {
            new SessionStore(this).clear();
            prefs.edit().remove(KEY_ORG).apply();
            new SnapshotStore(this).clear();   // F4
            WidgetUpdateJob.cancel(this);
            finish();
        });
    }

    /** Los ultimos uuid vistos, cacheados por el refrescador. Vacio si todavia no consulto. */
    private List<String> knownOrgs() {
        return new SnapshotStore(this).knownOrgs();   // F4
    }

    /** Un uuid completo en pantalla acabaria en una captura. Ocho caracteres bastan para elegir. */
    static String abbreviate(String uuid) {
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8) + "…";
    }
}
```

- [ ] **Paso 5: Commit**

```bash
git add android/app
git commit -m "feat: ajustes con selector de organizacion y cerrar sesion"
```

---

## F4 — Widget · rama `android/w3-f4-widget`

**Entregable:** dos widgets en la pantalla de inicio (4×1 compacto y 4×2 con barras) que muestran
el uso real, se actualizan cada 15 minutos y al tocarlos, y que dicen la verdad cuando algo falla:
sin sesión, sesión vencida, bloqueo, sin red.

**La regla que gobierna esta fase:** `android/app` **no calcula nada**. `UsageRefresher` llama al
núcleo y guarda el resultado; `WidgetRenderer` solo pinta. Si aparece un `if (percent < 60)` en
esta fase, está mal.

### Tarea 4.1: `SampleStore` — las muestras para el historial

**Archivos:** crear `SampleStore.java`, `test/.../SampleStoreTest.java`

- [ ] **Paso 1: La prueba que falla**

Crear `android/app/test/com/claudewidgets/android/SampleStoreTest.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Sample;

import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.List;

public final class SampleStoreTest {

    public static void run(Assert a, File tmp) {
        File dir = new File(tmp, "samples-" + System.nanoTime());
        SampleStore s = new SampleStore(dir);
        Instant now = Instant.parse("2026-10-03T12:00:00Z");

        try {
            a.eq("vacio al empezar", 0, s.load().size());

            s.append(new Sample(now.minusSeconds(3600), 10, now.plusSeconds(86400)));
            s.append(new Sample(now, 12, now.plusSeconds(86400)));
            List<Sample> got = s.load();
            a.eq("dos muestras", 2, got.size());
            a.isTrue("orden ascendente", got.get(0).t.isBefore(got.get(1).t));
            a.near("el porcentaje sobrevive", 12.0, got.get(1).percent, 0.001);

            // Mas de 15 dias: fuera.
            s.append(new Sample(now.minusSeconds(16L * 86400), 1, now));
            a.eq("la vieja no vuelve", 2, s.load().size());

            // Una linea corrupta no puede tirar el widget entero.
            try (FileWriter w = new FileWriter(new File(dir, SampleStore.FILE_NAME), true)) {
                w.write("{esto no es json}\n");
            }
            a.eq("la linea corrupta se ignora", 2, s.load().size());

            s.clear();
            a.eq("clear deja vacio", 0, s.load().size());
        } catch (Exception e) {
            a.fail("SampleStore lanzo " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Paso 2: Verla fallar** — `TEST=1 bash android/app/build.sh`. Esperado:
`cannot find symbol: class SampleStore`.

- [ ] **Paso 3: Implementar**

Crear `android/app/src/com/claudewidgets/android/SampleStore.java`:

```java
package com.claudewidgets.android;

import com.claudewidgets.core.Json;
import com.claudewidgets.core.Sample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Muestras del uso semanal, una por linea en JSON. Son la entrada de R3, R4 y R6.
 *
 * Decision D5: en claro, en el almacenamiento privado de la app. Son porcentajes, no
 * credenciales; `allowBackup=false` y el aislamiento de la app ya las protegen de otras apps.
 * Lo que revelan es el patron de uso del dueno.
 */
public final class SampleStore {

    static final String FILE_NAME = "samples.jsonl";
    private static final long WINDOW_DAYS = 15;

    private final File dir;

    public SampleStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("no se pudo crear " + dir);
        }
    }

    public void append(Sample s) throws IOException {
        List<Sample> all = load();
        all.add(s);
        rewrite(all, s.t);
    }

    /** Ascendente por `t`, sin las de mas de 15 dias. Una linea ilegible se ignora. */
    public List<Sample> load() throws IOException {
        File f = new File(dir, FILE_NAME);
        List<Sample> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                Sample s = parseLine(line);
                if (s != null) out.add(s);
            }
        }
        out.sort(Comparator.comparing(s -> s.t));
        return out;
    }

    public void clear() {
        File f = new File(dir, FILE_NAME);
        if (f.isFile() && !f.delete()) f.deleteOnExit();
    }

    private static Sample parseLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        try {
            Object o = Json.parse(line);
            if (!(o instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) o;
            Object t = m.get("t");
            Object p = m.get("percent");
            if (!(t instanceof String) || !(p instanceof Double)) return null;
            Object r = m.get("resets_at");
            return new Sample(Instant.parse((String) t), (Double) p,
                    r instanceof String ? Instant.parse((String) r) : null);
        } catch (RuntimeException e) {
            // Linea corrupta: se ignora. Perder una muestra es mucho mejor que no pintar nada.
            return null;
        }
    }

    private void rewrite(List<Sample> all, Instant now) throws IOException {
        Instant cutoff = now.minusSeconds(WINDOW_DAYS * 86400);
        File f = new File(dir, FILE_NAME);
        try (FileWriter w = new FileWriter(f, false)) {
            for (Sample s : all) {
                if (s.t.isBefore(cutoff)) continue;
                w.write("{\"t\":\"" + s.t + "\",\"percent\":" + s.percent
                        + ",\"resets_at\":" + (s.resetsAt == null ? "null" : "\"" + s.resetsAt + "\"")
                        + "}\n");
            }
        }
    }
}
```

- [ ] **Paso 4: Verla pasar y commit**

```bash
TEST=1 bash android/app/build.sh && (cd android/app && \
  d8 --min-api 29 --lib "${ANDROID_JAR:-$HOME/android/platforms/android-34/android.jar}" \
     --output build/dextest $(find build/classes -name '*.class') && \
  adb push build/dextest/classes.dex /data/local/tmp/app-test.dex >/dev/null && \
  adb shell "CLASSPATH=/data/local/tmp/app-test.dex app_process / com.claudewidgets.android.AppTestRunner /data/local/tmp/cuw-test")
git add android/app/src android/app/test && git commit -m "feat: almacen de muestras con ventana de 15 dias"
```

### Tarea 4.2: `Snapshot`, `SnapshotStore` y `UsageRefresher`

**Archivos:** crear `Snapshot.java`, `SnapshotStore.java`, `UsageRefresher.java`

**Review Focus 1 y 5** se cubren aquí: sin red se conserva el último dato con su hora, y sin
sesión el widget lo dice.

- [ ] **Paso 1: `Snapshot`**

```java
package com.claudewidgets.android;

import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.Forecast;
import com.claudewidgets.core.UsageModel;

import java.time.Instant;

/** Todo lo que el widget necesita para pintarse, ya calculado por el nucleo. */
public final class Snapshot {

    /** Que mostrar cuando no hay numeros que mostrar. */
    public enum Problem { NO_SESSION, AUTH_EXPIRED, BLOCKED, OFFLINE, BAD_FORMAT }

    public final UsageModel model;
    public final DayUsage day;
    public final Forecast sessionForecast;
    public final Forecast weeklyForecast;
    public final Double paceMark;
    public final Instant fetchedAt;
    /** Null = todo bien. Si no, el widget muestra el aviso y, si los hay, los datos viejos. */
    public final Problem problem;

    public Snapshot(UsageModel model, DayUsage day, Forecast sessionForecast,
                    Forecast weeklyForecast, Double paceMark, Instant fetchedAt,
                    Problem problem) {
        this.model = model;
        this.day = day;
        this.sessionForecast = sessionForecast;
        this.weeklyForecast = weeklyForecast;
        this.paceMark = paceMark;
        this.fetchedAt = fetchedAt;
        this.problem = problem;
    }

    /** Un snapshot sin datos, solo con el problema. */
    public static Snapshot of(Problem p) {
        return new Snapshot(null, null, null, null, null, null, p);
    }

    /** El mismo dato, marcado con un problema nuevo: asi el widget sigue mostrando algo util. */
    public Snapshot withProblem(Problem p) {
        return new Snapshot(model, day, sessionForecast, weeklyForecast, paceMark, fetchedAt, p);
    }

    public boolean hasData() { return model != null; }
}
```

- [ ] **Paso 2: `SnapshotStore`**

```java
package com.claudewidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Source;
import com.claudewidgets.core.UsageModel;
import com.claudewidgets.core.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Guarda lo minimo para volver a pintar el widget sin red: los dos porcentajes con sus reinicios,
 * la hora de la ultima consulta buena y los uuid conocidos.
 *
 * Los uuid son credenciales: viven en las preferencias privadas y no se registran.
 */
public final class SnapshotStore {

    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final String KEY_ORGS = "known_orgs";
    private static final String KEY_SP = "last_session_percent";
    private static final String KEY_SR = "last_session_resets";
    private static final String KEY_WP = "last_weekly_percent";
    private static final String KEY_WR = "last_weekly_resets";

    private final SharedPreferences prefs;

    public SnapshotStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
    }

    public void rememberOrgs(List<String> orgs) {
        prefs.edit().putString(KEY_ORGS, String.join(",", orgs)).apply();
    }

    /**
     * Guarda lo minimo para volver a pintar el widget sin red. No se guarda `scoped` ni
     * `breakdown`: el widget no los muestra y serian datos de la cuenta en disco sin razon.
     */
    public void remember(UsageModel model, Instant fetchedAt, List<String> orgs) {
        prefs.edit()
                .putFloat(KEY_SP, (float) model.session.percent)
                .putString(KEY_SR, model.session.resetsAt == null ? "" : model.session.resetsAt.toString())
                .putFloat(KEY_WP, (float) model.weekly.percent)
                .putString(KEY_WR, model.weekly.resetsAt == null ? "" : model.weekly.resetsAt.toString())
                .putLong(KEY_FETCHED_AT, fetchedAt.getEpochSecond())
                .putString(KEY_ORGS, String.join(",", orgs))
                .apply();
    }

    /** Null si nunca hubo una consulta buena. */
    public UsageModel lastModel() {
        if (!prefs.contains(KEY_WP)) return null;
        return new UsageModel(Source.CLAUDE_AI,
                new Window(prefs.getFloat(KEY_SP, 0), instant(prefs.getString(KEY_SR, ""))),
                new Window(prefs.getFloat(KEY_WP, 0), instant(prefs.getString(KEY_WR, ""))),
                new ArrayList<>(), new ArrayList<>());
    }

    /** Null si nunca se consulto. */
    public Instant lastFetchInstant() {
        long s = prefs.getLong(KEY_FETCHED_AT, 0L);
        return s == 0L ? null : Instant.ofEpochSecond(s);
    }

    private static Instant instant(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return Instant.parse(v); } catch (RuntimeException e) { return null; }
    }

    public List<String> knownOrgs() {
        String raw = prefs.getString(KEY_ORGS, "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    public void clear() {
        prefs.edit().remove(KEY_FETCHED_AT).remove(KEY_ORGS)
                .remove(KEY_SP).remove(KEY_SR).remove(KEY_WP).remove(KEY_WR).apply();
    }
}
```

- [ ] **Paso 3: `UsageRefresher`**

```java
package com.claudewidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Colors;
import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.History;
import com.claudewidgets.core.Projection;
import com.claudewidgets.core.Sample;
import com.claudewidgets.core.UnrecognizedFormatException;
import com.claudewidgets.core.UsageModel;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Orquesta una actualizacion: cookie -> consulta -> muestra -> nucleo -> Snapshot.
 *
 * No calcula nada por su cuenta: reparto por dia, proyecciones y marca de ritmo salen de
 * `android/core`, que es la unica implementacion de R0-R7.
 *
 * `refresh()` nunca lanza: un widget que se cae no es un widget.
 */
public final class UsageRefresher {

    private final Context ctx;
    private final SessionStore session;
    private final SampleStore samples;
    private final SnapshotStore meta;

    public UsageRefresher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.session = new SessionStore(this.ctx);
        this.samples = new SampleStore(this.ctx.getFilesDir());
        this.meta = new SnapshotStore(this.ctx);
    }

    public Snapshot refresh() {
        String cookies;
        try {
            cookies = session.load();
        } catch (Exception e) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }
        if (cookies == null || !cookies.contains("sessionKey=")) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }

        UsageClient client = new UsageClient(cookies, userAgent());
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    SettingsActivity.PREFS, Context.MODE_PRIVATE);
            String manual = prefs.getString(SettingsActivity.KEY_ORG, null);
            List<String> orgs = client.organizations();
            meta.rememberOrgs(orgs);   // antes de elegir: Ajustes los necesita aunque falle luego
            String org = OrgSelector.choose(orgs, manual,
                    UsageClient.lastActiveOrg(cookies), uuid -> probe(client, uuid));
            if (org == null) return keepOld(Snapshot.Problem.BAD_FORMAT);

            UsageModel model = client.usage(org);
            Instant now = Instant.now();
            return compute(model, now);
        } catch (AuthExpiredException e) {
            return keepOld(Snapshot.Problem.AUTH_EXPIRED);
        } catch (BlockedException e) {
            return keepOld(Snapshot.Problem.BLOCKED);
        } catch (UsageClient.RetryLaterException | IOException e) {
            // Sin red, DNS caido, 429 o 5xx: el ultimo dato sigue valiendo, con su hora.
            return keepOld(Snapshot.Problem.OFFLINE);
        } catch (UnrecognizedFormatException e) {
            return keepOld(Snapshot.Problem.BAD_FORMAT);
        }
    }

    /** Lo ultimo que se pudo calcular, marcado con el problema de ahora. */
    private Snapshot keepOld(Snapshot.Problem p) {
        Snapshot old = last();
        return old.hasData() ? old.withProblem(p) : Snapshot.of(p);
    }

    /**
     * Reconstruye el ultimo estado conocido sin tocar la red. Nunca null.
     *
     * Esto es lo que hace que "sin conexion" muestre el dato viejo con su hora en vez de nada
     * (Review Focus 1). Si devolviera un Snapshot sin modelo, el widget se quedaria en blanco.
     */
    public Snapshot last() {
        if (!session.hasSession()) return Snapshot.of(Snapshot.Problem.NO_SESSION);
        UsageModel model = meta.lastModel();
        Instant fetchedAt = meta.lastFetchInstant();
        if (model == null || fetchedAt == null) return Snapshot.of(Snapshot.Problem.OFFLINE);
        List<Sample> all;
        try {
            all = samples.load();
        } catch (IOException e) {
            all = java.util.Collections.emptyList();
        }
        Instant now = Instant.now();
        DayUsage day = History.compute(model.weekly, all, now, ZoneId.systemDefault());
        return new Snapshot(model, day,
                Projection.session(model.session.percent, model.session.resetsAt, now),
                Projection.weekly(model.weekly.percent, model.weekly.resetsAt, all, now),
                Colors.paceMark(model.weekly.resetsAt, now),
                fetchedAt, Snapshot.Problem.OFFLINE);
    }

    private Snapshot compute(UsageModel model, Instant now) {
        ZoneId tz = ZoneId.systemDefault();
        try {
            samples.append(new Sample(now, model.weekly.percent, model.weekly.resetsAt));
        } catch (IOException ignored) {
            // Si no se pudo guardar la muestra, el dato de ahora igual se muestra.
        }
        List<Sample> all;
        try {
            all = samples.load();
        } catch (IOException e) {
            all = java.util.Collections.emptyList();
        }
        DayUsage day = History.compute(model.weekly, all, now, tz);
        meta.remember(model, now, meta.knownOrgs());
        return new Snapshot(
                model,
                day,
                Projection.session(model.session.percent, model.session.resetsAt, now),
                Projection.weekly(model.weekly.percent, model.weekly.resetsAt, all, now),
                Colors.paceMark(model.weekly.resetsAt, now),
                now,
                null);
    }

    private boolean probe(UsageClient client, String uuid) {
        try {
            client.usage(uuid);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * El mismo User-Agent que usa el WebView del login: una sola huella hacia claude.ai.
     * Se guarda al iniciar sesion porque crear un WebView desde un JobService no es viable.
     */
    private String userAgent() {
        return ctx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString("user_agent", System.getProperty("http.agent"));
    }
}
```

> **Nota para quien ejecute:** `LoginActivity.finishLogin()` debe guardar el User-Agent del
> WebView en esa preferencia antes de llamar a `WidgetUpdateJob.schedule`. Añadir en el paso
> correspondiente:
> `prefs.edit().putString("user_agent", userAgent(web)).apply();`

- [ ] **Paso 4: Commit**

```bash
git add android/app/src
git commit -m "feat: snapshot y refrescador que nunca lanza"
```

### Tarea 4.3: Widgets, renderizador y recursos

**Archivos:** crear `res/layout/widget_4x1.xml`, `widget_4x2.xml`, los dos `*_preview.xml`,
`res/drawable/bar_*.xml`, `res/xml/widget_4x1_info.xml`, `widget_4x2_info.xml`,
`src/.../WidgetRenderer.java`, `Widget4x1Provider.java`, `Widget4x2Provider.java`;
modificar `AndroidManifest.xml` y los dos `strings.xml`

- [ ] **Paso 1: Rellenos de barra** — cuatro `progressDrawable` en `res/drawable/`, idénticos salvo
  el color del relleno: `bar_green.xml` (`#3FBF6F`), `bar_amber.xml` (`#E8B03F`), `bar_red.xml`
  (`#E5534B`), `bar_gray.xml` (`#6B7280`). Plantilla, cambiando solo el `android:color` del item
  `@android:id/progress`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@android:id/background">
        <shape android:shape="rectangle">
            <solid android:color="#2A2E37" />
            <corners android:radius="3dp" />
        </shape>
    </item>
    <item android:id="@android:id/progress">
        <clip>
            <shape android:shape="rectangle">
                <solid android:color="#3FBF6F" />
                <corners android:radius="3dp" />
            </shape>
        </clip>
    </item>
</layer-list>
```

**Por qué cuatro drawables y cuatro `ProgressBar` superpuestas por barra, en vez de una sola
teñida:** `RemoteViews` solo acepta las llamadas de su lista blanca. En API 29 no están ni
`setColorFilter` ni `setProgressTintList`, y `setViewLayoutWidth` es de API 31. Lo que sí están
son `setProgressBar(...)` y `setViewVisibility(...)`, así que se enciende la del color que toca y
se apagan las otras tres. Es feo por dentro y correcto por fuera; cualquier atajo aquí se
descubre en el teléfono, no en el compilador.

- [ ] **Paso 2: Layout 4×1** — `res/layout/widget_4x1.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root" android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="10dp" android:gravity="center_vertical"
    android:background="#CC14161B">
    <TextView android:id="@+id/notice" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:textColor="#FFE8B03F"
        android:textSize="12sp" android:visibility="gone" />
    <LinearLayout android:id="@+id/numbers" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:orientation="horizontal">
        <TextView android:id="@+id/session" android:layout_width="0dp"
            android:layout_height="wrap_content" android:layout_weight="1"
            android:textColor="#FFFFFFFF" android:textSize="15sp" />
        <TextView android:id="@+id/weekly" android:layout_width="0dp"
            android:layout_height="wrap_content" android:layout_weight="1"
            android:textColor="#FFFFFFFF" android:textSize="15sp" />
        <TextView android:id="@+id/age" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:textColor="#FF9AA3B2"
            android:textSize="11sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Paso 3: Layout 4×2** — `res/layout/widget_4x2.xml`: lo mismo más tres barras y la
  proyección. Cada barra son cuatro `ProgressBar` superpuestas, una por color (ver el paso 1):

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root" android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="12dp" android:background="#CC14161B">
    <TextView android:id="@+id/notice" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:textColor="#FFE8B03F"
        android:textSize="12sp" android:visibility="gone" android:paddingBottom="4dp" />
    <LinearLayout android:id="@+id/numbers" android:layout_width="match_parent"
        android:layout_height="0dp" android:layout_weight="1" android:orientation="vertical">

        <TextView android:id="@+id/session" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:textColor="#FFFFFFFF" android:textSize="13sp" />
        <!-- Una ProgressBar por color. RemoteViews solo deja llamar setProgressBar y
             setViewVisibility; no puede tenir un drawable en API 29, asi que se enciende
             la del color que toca y se apagan las otras tres. -->
        <FrameLayout android:layout_width="match_parent" android:layout_height="8dp"
            android:layout_marginBottom="6dp">
            <ProgressBar android:id="@+id/session_green" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_green" />
            <ProgressBar android:id="@+id/session_amber" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_amber"
                android:visibility="gone" />
            <ProgressBar android:id="@+id/session_red" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_red"
                android:visibility="gone" />
            <ProgressBar android:id="@+id/session_gray" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_gray"
                android:visibility="gone" />
        </FrameLayout>

        <TextView android:id="@+id/weekly" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:textColor="#FFFFFFFF" android:textSize="13sp" />
        <FrameLayout android:layout_width="match_parent" android:layout_height="8dp"
            android:layout_marginBottom="6dp">
            <ProgressBar android:id="@+id/weekly_green" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_green" />
            <ProgressBar android:id="@+id/weekly_amber" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_amber" android:visibility="gone" />
            <ProgressBar android:id="@+id/weekly_red" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_red" android:visibility="gone" />
            <ProgressBar android:id="@+id/weekly_gray" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_gray" android:visibility="gone" />
        </FrameLayout>

        <TextView android:id="@+id/today" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:textColor="#FFFFFFFF" android:textSize="13sp" />
        <FrameLayout android:layout_width="match_parent" android:layout_height="8dp">
            <ProgressBar android:id="@+id/today_green" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_green" />
            <ProgressBar android:id="@+id/today_amber" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_amber" android:visibility="gone" />
            <ProgressBar android:id="@+id/today_red" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_red" android:visibility="gone" />
            <ProgressBar android:id="@+id/today_gray" style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent" android:layout_height="8dp"
                android:max="100" android:progressDrawable="@drawable/bar_gray" android:visibility="gone" />
        </FrameLayout>

        <TextView android:id="@+id/forecast" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:textColor="#FF9AA3B2"
            android:textSize="11sp" android:paddingTop="6dp" />
    </LinearLayout>
    <TextView android:id="@+id/age" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:textColor="#FF6B7280" android:textSize="10sp" />
</LinearLayout>
```

El `layout_weight="1"` en el bloque de números es lo que hace que el contenido **llene la celda**:
es el defecto que el spike dejó anotado.

- [ ] **Paso 4: `previewLayout`** — copiar cada layout a `*_preview.xml` cambiando los `TextView`
  por textos de ejemplo fijos (`android:text="Sesión 42%"`, etc.) y quitando los `id`. Sin esto, el
  diálogo de anclaje muestra "No se pudo agregar el widget" (verificado en W0).

- [ ] **Paso 5: `appwidget-provider`** — `res/xml/widget_4x1_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp" android:minHeight="40dp"
    android:targetCellWidth="4" android:targetCellHeight="1"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/widget_4x1"
    android:previewLayout="@layout/widget_4x1_preview"
    android:resizeMode="horizontal"
    android:widgetCategory="home_screen"
    android:description="@string/widget_desc" />
```

`widget_4x2_info.xml` igual con `minHeight="110dp"`, `targetCellHeight="2"` y los layouts 4×2.

`updatePeriodMillis="0"` **a propósito**: el refresco lo lleva `JobScheduler`, que respeta la
batería y permite reintentos. El de `AppWidgetProvider` no baja de 30 minutos y no se puede
reintentar.

- [ ] **Paso 6: Cadenas** — `values/strings.xml`:

```xml
    <string name="widget_desc">Claude usage: session, week and today\'s quota.</string>
    <string name="w_session">Session %1$d%%</string>
    <string name="w_weekly">Week %1$d%%</string>
    <string name="w_today">Today %1$s of %2$s</string>
    <string name="w_age_now">just now</string>
    <string name="w_age_min">%1$d min ago</string>
    <string name="w_age_hour">%1$d h ago</string>
    <string name="w_full_at">Full at %1$s</string>
    <string name="w_no_forecast">No forecast yet</string>
    <string name="p_no_session">Tap to sign in</string>
    <string name="p_auth_expired">Session expired — tap to sign in</string>
    <string name="p_blocked">Blocked by Claude — retrying</string>
    <string name="p_offline">No connection</string>
    <string name="p_bad_format">Unexpected response format</string>
```

`values-es/strings.xml`:

```xml
    <string name="widget_desc">Uso de Claude: sesión, semana y cuota de hoy.</string>
    <string name="w_session">Sesión %1$d%%</string>
    <string name="w_weekly">Semana %1$d%%</string>
    <string name="w_today">Hoy %1$s de %2$s</string>
    <string name="w_age_now">ahora mismo</string>
    <string name="w_age_min">hace %1$d min</string>
    <string name="w_age_hour">hace %1$d h</string>
    <string name="w_full_at">Al 100%% a las %1$s</string>
    <string name="w_no_forecast">Sin proyección todavía</string>
    <string name="p_no_session">Toca para iniciar sesión</string>
    <string name="p_auth_expired">Sesión vencida — toca para entrar</string>
    <string name="p_blocked">Claude está bloqueando — reintentando</string>
    <string name="p_offline">Sin conexión</string>
    <string name="p_bad_format">Formato de respuesta no reconocido</string>
```

- [ ] **Paso 7: `WidgetRenderer`**

```java
package com.claudewidgets.android;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.claudewidgets.core.Color;
import com.claudewidgets.core.Colors;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Snapshot -> RemoteViews. NO decide nada: los colores salen de `Colors` (R7) y los numeros del
 * Snapshot. Si aqui aparece un umbral, esta mal.
 */
public final class WidgetRenderer {

    private WidgetRenderer() {}

    public static RemoteViews render(Context ctx, Snapshot s, boolean compact) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(),
                compact ? R.layout.widget_4x1 : R.layout.widget_4x2);

        if (s.problem != null) {
            v.setTextViewText(R.id.notice, ctx.getString(problemText(s.problem)));
            v.setViewVisibility(R.id.notice, android.view.View.VISIBLE);
            v.setViewVisibility(R.id.numbers, s.hasData()
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        } else {
            v.setViewVisibility(R.id.notice, android.view.View.GONE);
            v.setViewVisibility(R.id.numbers, android.view.View.VISIBLE);
        }

        if (s.hasData()) {
            int sp = (int) Math.round(s.model.session.percent);
            int wp = (int) Math.round(s.model.weekly.percent);
            v.setTextViewText(R.id.session, ctx.getString(R.string.w_session, sp));
            v.setTextViewText(R.id.weekly, ctx.getString(R.string.w_weekly, wp));
            v.setTextViewText(R.id.age, age(ctx, s.fetchedAt));

            if (!compact) {
                bar(v, SESSION_BARS, s.model.session.percent,
                        Colors.bar(s.model.session.percent));
                bar(v, WEEKLY_BARS, s.model.weekly.percent,
                        Colors.bar(s.model.weekly.percent));
                double todayPct = s.day.quotaToday == null || s.day.quotaToday <= 0
                        ? 0 : 100 * s.day.todayUsed / s.day.quotaToday;
                bar(v, TODAY_BARS, todayPct,
                        Colors.today(s.day.todayUsed, s.day.quotaToday));
                v.setTextViewText(R.id.today, ctx.getString(R.string.w_today,
                        one(s.day.todayUsed), s.day.quotaToday == null ? "—" : one(s.day.quotaToday)));
                v.setTextViewText(R.id.forecast, forecast(ctx, s));
            }
        }

        // Tocar = actualizar. El PendingIntent corre con la identidad de la app, asi que llega
        // al receptor aunque sea exported="false" (verificado en W0).
        Intent tap = new Intent(ctx, compact ? Widget4x1Provider.class : Widget4x2Provider.class)
                .setAction(WidgetUpdateJob.ACTION_TAP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getBroadcast(ctx, compact ? 1 : 2, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    // Orden fijo: verde, ambar, rojo, gris. Debe coincidir con `index(Color)`.
    private static final int[] SESSION_BARS =
            {R.id.session_green, R.id.session_amber, R.id.session_red, R.id.session_gray};
    private static final int[] WEEKLY_BARS =
            {R.id.weekly_green, R.id.weekly_amber, R.id.weekly_red, R.id.weekly_gray};
    private static final int[] TODAY_BARS =
            {R.id.today_green, R.id.today_amber, R.id.today_red, R.id.today_gray};

    /**
     * Enciende la barra del color que toca y apaga las otras tres. RemoteViews no puede tenir
     * un drawable en API 29; `setProgressBar` y `setViewVisibility` si estan en su lista blanca.
     */
    private static void bar(RemoteViews v, int[] ids, double percent, Color color) {
        int wanted = index(color);
        int clamped = (int) Math.round(Math.max(0, Math.min(100, percent)));
        for (int i = 0; i < ids.length; i++) {
            boolean on = i == wanted;
            v.setViewVisibility(ids[i], on ? android.view.View.VISIBLE : android.view.View.GONE);
            if (on) v.setProgressBar(ids[i], 100, clamped, false);
        }
    }

    private static int index(Color c) {
        switch (c) {
            case GREEN: return 0;
            case AMBER: return 1;
            case RED:   return 2;
            default:    return 3;
        }
    }

    private static int problemText(Snapshot.Problem p) {
        switch (p) {
            case NO_SESSION:    return R.string.p_no_session;
            case AUTH_EXPIRED:  return R.string.p_auth_expired;
            case BLOCKED:       return R.string.p_blocked;
            case OFFLINE:       return R.string.p_offline;
            default:            return R.string.p_bad_format;
        }
    }

    private static String forecast(Context ctx, Snapshot s) {
        if (s.weeklyForecast == null || s.weeklyForecast.hitsAt == null) {
            return ctx.getString(R.string.w_no_forecast);
        }
        String when = DateTimeFormatter.ofPattern("EEE HH:mm")
                .withZone(ZoneId.systemDefault()).format(s.weeklyForecast.hitsAt);
        return ctx.getString(R.string.w_full_at, when);
    }

    private static String age(Context ctx, Instant fetchedAt) {
        if (fetchedAt == null) return "";
        long min = Duration.between(fetchedAt, Instant.now()).toMinutes();
        if (min < 1) return ctx.getString(R.string.w_age_now);
        if (min < 60) return ctx.getString(R.string.w_age_min, min);
        return ctx.getString(R.string.w_age_hour, min / 60);
    }

    private static String one(double v) { return String.format(java.util.Locale.getDefault(), "%.1f%%", v); }
}
```

- [ ] **Paso 8: Los dos proveedores**

```java
package com.claudewidgets.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** 4x1 compacto. `exported="false"`: verificado en One UI (spike B). */
public class Widget4x1Provider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        Snapshot s = new UsageRefresher(ctx).last();
        for (int id : ids) awm.updateAppWidget(id, WidgetRenderer.render(ctx, s, compact()));
        WidgetUpdateJob.schedule(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (WidgetUpdateJob.ACTION_TAP.equals(intent.getAction())) {
            WidgetUpdateJob.runNow(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onDisabled(Context ctx) {
        // Si no queda ningun widget de ningun tamanio, no hay a quien actualizar.
        if (WidgetUpdateJob.countAll(ctx) == 0) WidgetUpdateJob.cancel(ctx);
    }

    boolean compact() { return true; }
}
```

`Widget4x2Provider.java` es el mismo con `boolean compact() { return false; }`. Se repite entero
en el archivo (no se hereda uno del otro: `AppWidgetProvider` se instancia por el sistema y
heredar entre proveedores registrados confunde el despacho de `onUpdate`).

- [ ] **Paso 9: Manifiesto** — añadir dentro de `<application>`:

```xml
        <receiver android:name=".Widget4x1Provider" android:exported="false">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider"
                android:resource="@xml/widget_4x1_info" />
        </receiver>
        <receiver android:name=".Widget4x2Provider" android:exported="false">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider"
                android:resource="@xml/widget_4x2_info" />
        </receiver>
```

- [ ] **Paso 10: Commit**

```bash
git add android/app && git commit -m "feat: widgets 4x1 y 4x2 con previewLayout"
```

### Tarea 4.4: `WidgetUpdateJob` y `BootReceiver`

**Archivos:** reemplazar `WidgetUpdateJob.java`, crear `BootReceiver.java`;
modificar `AndroidManifest.xml`

**Review Focus 4** se cubre aquí: sin `BootReceiver`, el widget se congela tras reiniciar y el
usuario no tiene forma de saber por qué.

- [ ] **Paso 1: Implementar el job**

```java
package com.claudewidgets.android;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * Actualiza los widgets cada 15 min (minimo que respeta el sistema) y cuando se toca uno.
 *
 * El trabajo va en un hilo aparte: `onStartJob` corre en el principal y la consulta es de red.
 */
public class WidgetUpdateJob extends JobService {

    public static final String ACTION_TAP = "com.claudewidgets.android.TAP";
    private static final int JOB_ID = 4201;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WidgetUpdateJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)   // se reprograma desde BootReceiver
                .build());
    }

    public static void cancel(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    /** Actualizacion inmediata, fuera del periodo: al iniciar sesion y al tocar el widget. */
    public static void runNow(Context ctx) {
        new Thread(() -> {
            Snapshot s = new UsageRefresher(ctx).refresh();
            pushToWidgets(ctx, s);
        }, "cuw-refresh").start();
    }

    public static int countAll(Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        return awm.getAppWidgetIds(new ComponentName(ctx, Widget4x1Provider.class)).length
             + awm.getAppWidgetIds(new ComponentName(ctx, Widget4x2Provider.class)).length;
    }

    static void pushToWidgets(Context ctx, Snapshot s) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        for (int id : awm.getAppWidgetIds(new ComponentName(ctx, Widget4x1Provider.class))) {
            awm.updateAppWidget(id, WidgetRenderer.render(ctx, s, true));
        }
        for (int id : awm.getAppWidgetIds(new ComponentName(ctx, Widget4x2Provider.class))) {
            awm.updateAppWidget(id, WidgetRenderer.render(ctx, s, false));
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Snapshot s = new UsageRefresher(getApplicationContext()).refresh();
            pushToWidgets(getApplicationContext(), s);
            // Si estamos bloqueados, el periodo normal ya es mas largo que el backoff inicial,
            // asi que no se reprograma nada especial: el siguiente ciclo reintenta.
            jobFinished(params, false);
        }, "cuw-job").start();
        return true;   // sigue trabajando en segundo plano
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // reintentar si el sistema lo corto
    }
}
```

- [ ] **Paso 2: `BootReceiver`**

```java
package com.claudewidgets.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * `JobScheduler` no sobrevive al reinicio con `setPersisted(false)`, y persistirlo exige el
 * permiso igual. Se reprograma aqui: sin esto el widget se congela tras reiniciar el telefono
 * y el usuario no tiene como saber por que.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (WidgetUpdateJob.countAll(ctx) == 0) return;
        WidgetUpdateJob.schedule(ctx);
        WidgetUpdateJob.runNow(ctx);
    }
}
```

- [ ] **Paso 3: Manifiesto** — permiso y receptor:

```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

```xml
        <service android:name=".WidgetUpdateJob"
            android:permission="android.permission.BIND_JOB_SERVICE"
            android:exported="false" />
        <receiver android:name=".BootReceiver" android:exported="false">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
        </receiver>
```

- [ ] **Paso 4: Construir, instalar y anclar los dos widgets**

```bash
bash android/app/build.sh && adb install -r android/app/build/claude-usage.apk
adb shell dumpsys appwidget | grep -A2 claudewidgets | head -20
```

Anclar a mano (o con `requestPinAppWidget` desde la app) uno de cada tamaño y comprobar:

```bash
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell uiautomator dump /sdcard/w.xml >/dev/null
adb shell cat /sdcard/w.xml | grep -oE 'text="(Sesión|Session|Toca|Tap)[^"]*"'
```
Esperado: textos del widget. Si todavía no hay sesión, debe decir "Toca para iniciar sesión"
(Review Focus 5), **no** quedarse en blanco ni mostrar `0%`.

- [ ] **Paso 5: Comprobar el job**

```bash
adb shell dumpsys jobscheduler | grep -A3 "com.claudewidgets.android" | head -10
```
Esperado: un job con `PERIODIC` de 900000 ms.

- [ ] **Paso 6: Comprobar el reinicio sin reiniciar el teléfono**

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.claudewidgets.android/.BootReceiver
sleep 5
adb shell dumpsys jobscheduler | grep -c "com.claudewidgets.android"
```
Esperado: distinto de 0. (La prueba de reinicio de verdad va en F5.)

- [ ] **Paso 7: Commit, PR y memo**

```bash
git add android/app && git commit -m "feat: JobScheduler cada 15 min y reprogramacion tras reinicio"
git push -u origin android/w3-f4-widget
gh pr create --base main --head android/w3-f4-widget --title "W3 F4: widgets 4x1 y 4x2 con datos reales"
```

El memo a PC debe traer **una captura del widget con datos reales** (cuidando que no se vea nada
de la cuenta más que los porcentajes) y la salida de `dumpsys jobscheduler`.

---

## F5 — Pruebas en dispositivo · rama `android/w3-f5-pruebas`

**Entregable:** un recorrido automatizado que ejercita la app en el teléfono de verdad, más la
prueba de 24 h que mide si Cloudflare reta la vía nativa al ritmo real del widget. Esta fase no
añade funciones: busca las que faltan.

**Por qué el ritmo importa:** el spike A2 observó 8 peticiones en tres minutos desde una IP
doméstica con una sesión recién emitida, y de ahí no se puede concluir que no haya reto nunca.
Cloudflare puntúa huella TLS, ritmo y reputación de IP. Esta fase mide el ritmo real.

### Tarea 5.1: Guion de recorrido automatizado

**Archivos:** crear `android/app/pruebas/recorrido.sh`

- [ ] **Paso 1: Escribir el guion** — `android/app/pruebas/recorrido.sh`:

```bash
#!/usr/bin/env bash
# recorrido.sh - ejercita la app instalada en el telefono y comprueba lo que muestra.
# Requiere una sesion ya iniciada (el login es lo unico manual).
# Solo ASCII. Uso: bash android/app/pruebas/recorrido.sh
set -uo pipefail
PKG=com.claudewidgets.android
S="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
[ -n "$S" ] || { echo "ERROR: sin dispositivo; revisar la depuracion inalambrica"; exit 1; }
fallos=0

texto() { adb -s "$S" shell uiautomator dump /sdcard/r.xml >/dev/null 2>&1; \
          adb -s "$S" shell cat /sdcard/r.xml 2>/dev/null | grep -oE 'text="[^"]*"'; }

comprobar() { # nombre, patron que DEBE aparecer
  if texto | grep -qE "$2"; then echo "  OK        $1"; else
    echo "  FALLA     $1  (no aparece: $2)"; fallos=$((fallos+1)); fi
}

echo "== 1. el widget muestra datos =="
adb -s "$S" shell input keyevent KEYCODE_HOME; sleep 3
comprobar "porcentaje de sesion" '(Sesion|Sesión|Session) [0-9]+%'

echo "== 2. tocar actualiza =="
antes=$(texto | grep -oE '(ahora mismo|just now|hace [0-9]+ min|[0-9]+ min ago)' | head -1)
# El toque se localiza por el texto del widget, no por coordenadas fijas.
coord=$(adb -s "$S" shell uiautomator dump /sdcard/r.xml >/dev/null 2>&1; \
        adb -s "$S" shell cat /sdcard/r.xml | tr '<' '\n' | grep -m1 'Sesi' | \
        grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | \
        grep -oE '[0-9]+' | paste -sd' ')
set -- $coord
if [ $# -eq 4 ]; then adb -s "$S" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); fi
sleep 8
comprobar "sigue mostrando datos tras el toque" '(Sesion|Sesión|Session) [0-9]+%'

echo "== 3. sin red =="
echo "  (se simula cortando el acceso del paquete, no apagando las radios del dueno)"
adb -s "$S" shell cmd netpolicy add restrict-background "$(adb -s "$S" shell dumpsys package $PKG | grep -m1 userId= | grep -oE '[0-9]+')" 2>/dev/null
adb -s "$S" shell am broadcast -a $PKG.TAP -n $PKG/.Widget4x2Provider >/dev/null 2>&1
sleep 10
comprobar "avisa de que no hay conexion o conserva el dato" '(Sin conexion|Sin conexión|No connection|hace [0-9]+)'
adb -s "$S" shell cmd netpolicy remove restrict-background "$(adb -s "$S" shell dumpsys package $PKG | grep -m1 userId= | grep -oE '[0-9]+')" 2>/dev/null

echo "== 4. rotacion =="
adb -s "$S" shell settings put system accelerometer_rotation 0
adb -s "$S" shell settings put system user_rotation 1; sleep 3
comprobar "el widget sobrevive a la rotacion" '(Sesion|Sesión|Session) [0-9]+%'
adb -s "$S" shell settings put system user_rotation 0; sleep 2

echo "== 5. el job sigue programado =="
if adb -s "$S" shell dumpsys jobscheduler | grep -q "$PKG"; then
  echo "  OK        job presente"
else
  echo "  FALLA     el job desaparecio"; fallos=$((fallos+1))
fi

echo
if [ "$fallos" -gt 0 ]; then echo "ERROR: $fallos comprobacion(es) fallan"; exit 1; fi
echo "recorrido completo sin fallos"
```

> **Nota deliberada:** el guion **no apaga el WiFi ni los datos**. Es el teléfono del dueño, lo
> está usando, y la sesión de trabajo va por esa red. Se restringe el acceso del paquete, que es
> reversible y local.

- [ ] **Paso 2: Correrlo** — `bash android/app/pruebas/recorrido.sh`.
  Esperado: `recorrido completo sin fallos`. Cada `FALLA` es un hallazgo que se arregla antes de
  seguir, no se anota para después.

- [ ] **Paso 3: Commit**

```bash
chmod +x android/app/pruebas/recorrido.sh
git add android/app/pruebas && git commit -m "test: recorrido automatizado en el dispositivo"
```

### Tarea 5.2: Sesión vencida y bloqueo, provocados a propósito

- [ ] **Paso 1: Sesión vencida** — se provoca corrompiendo la cookie guardada, que es más honesto
  que esperar a que caduque:

```bash
adb shell am start -n com.claudewidgets.android/.SettingsActivity
# Cerrar sesion desde la app, y luego comprobar el widget:
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell uiautomator dump /sdcard/r.xml >/dev/null
adb shell cat /sdcard/r.xml | grep -oE 'text="[^"]*"' | grep -iE 'sesion|session|toca|tap'
```
Esperado: `Toca para iniciar sesión`. **No** debe quedar el número viejo sin aviso.

- [ ] **Paso 2: Bloqueo** — no se puede provocar un reto real de Cloudflare a voluntad, así que se
  comprueba la rama con una prueba de unidad (ya existe: `UsageClientTest`) y se **anota en el
  reporte que la rama de bloqueo no se ejercitó contra el servidor real**. Decirlo es parte del
  entregable: afirmar que se probó sería la clase de sobreafirmación que ya costó dos revisiones.

- [ ] **Paso 3: Reinicio de verdad**

```bash
adb reboot
# esperar a que vuelva
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 5; done
sleep 30
adb shell dumpsys jobscheduler | grep -c com.claudewidgets.android
```
Esperado: distinto de 0. Y el widget debe seguir mostrando datos (con su antigüedad).

- [ ] **Paso 4: Commit del reporte parcial**

```bash
git add docs/ && git commit -m "docs: resultados del recorrido en dispositivo"
```

### Tarea 5.3: La prueba de 24 horas

**Archivos:** crear `android/app/pruebas/ritmo24h.sh`, `docs/spikes/2026-10-w3-ritmo-24h.md`

Esta es la que responde la pregunta que el spike dejó abierta.

- [ ] **Paso 1: Guion que cuenta las respuestas**

```bash
#!/usr/bin/env bash
# ritmo24h.sh - deja correr el widget 24 h al ritmo real y cuenta como responde claude.ai.
# No manda peticiones propias: solo lee lo que la app ya registra (codigo, content-type,
# longitud; nunca cuerpos).
# Solo ASCII. Uso: bash android/app/pruebas/ritmo24h.sh <archivo de salida>
set -uo pipefail
OUT="${1:-ritmo24h.log}"
S="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
[ -n "$S" ] || { echo "ERROR: sin dispositivo"; exit 1; }
adb -s "$S" logcat -c
echo "inicio: $(date -u +%FT%TZ)" > "$OUT"
adb -s "$S" logcat -v time -s CuwHttp:I >> "$OUT" &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT INT TERM
sleep 86400
echo "fin: $(date -u +%FT%TZ)" >> "$OUT"
echo "== resumen =="
grep -oE 'code=[0-9]+' "$OUT" | sort | uniq -c
grep -c 'blocked' "$OUT" || true
```

- [ ] **Paso 2: Añadir el registro mínimo a `UsageClient`** (solo código, tipo y longitud; nunca
  cuerpo ni cabeceras con valores):

```java
        android.util.Log.i("CuwHttp", "code=" + code
                + " ctype=" + (ctype == null ? "-" : ctype.split(";")[0])
                + " len=" + body.length()
                + (cfMitigated != null && !cfMitigated.isEmpty() ? " cf-mitigated=yes" : ""));
```

- [ ] **Paso 3: Correrla**

```bash
nohup bash android/app/pruebas/ritmo24h.sh ritmo24h.log >/dev/null 2>&1 &
```

Durante esas 24 h **no se toca el teléfono para esta prueba**; se puede seguir usando normalmente.

- [ ] **Paso 4: Escribir el reporte** — `docs/spikes/2026-10-w3-ritmo-24h.md` con: cuántas
  peticiones, cuántos `200`, cuántos `403`, cuántos `cf-mitigated`, cuántos HTML; y **la conclusión
  acotada a lo observado**, no una garantía. Si hubo algún bloqueo, el reporte debe decir a qué
  hora, tras cuántas peticiones, y si el backoff lo absorbió.

- [ ] **Paso 5: PR y memo**

```bash
git add android/app/pruebas docs/spikes
git commit -m "test: prueba de 24 h al ritmo real del widget"
git push -u origin android/w3-f5-pruebas
gh pr create --base main --head android/w3-f5-pruebas --title "W3 F5: pruebas en dispositivo y ritmo de 24 h"
```

---

## F6 — Release · rama `android/w3-f6-release`

**Entregable:** un APK firmado con la llave de release, publicado en un Release de GitHub con su
checksum SHA-256 y una guía para instalarlo con Obtainium.

**Lo que esta fase NO hace:** generar la llave. Eso lo hace el dueño (decisión D3) y la sube como
secret. Esta sesión no debe ver la llave ni su contraseña.

### Tarea 6.1: Workflow de release

**Archivos:** crear `android/app/ci/release.yml`

- [ ] **Paso 1: Escribirlo**

```yaml
# Release del APK (W3 F6). PC lo mueve a .github/workflows/release-android.yml.
name: release-android
on:
  push:
    tags: ["android-v*"]
permissions:
  contents: write
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5
        with:
          distribution: temurin
          java-version: "17"
      - name: Instalar las herramientas de compilacion
        run: |
          set -euo pipefail
          yes | sdkmanager --install "platforms;android-34" "build-tools;34.0.0" >/dev/null
          echo "$ANDROID_HOME/build-tools/34.0.0" >> "$GITHUB_PATH"
      - name: Reconstruir la llave desde el secret
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.keystore"
      - name: Construir y firmar
        env:
          ANDROID_JAR: ${{ env.ANDROID_HOME }}/platforms/android-34/android.jar
          KEYSTORE: ${{ runner.temp }}/release.keystore
          KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
        run: bash android/app/build.sh
      - name: Borrar la llave del runner
        if: always()
        run: shred -u "$RUNNER_TEMP/release.keystore" 2>/dev/null || rm -f "$RUNNER_TEMP/release.keystore"
      - name: Checksum
        run: |
          set -euo pipefail
          cd android/app/build
          sha256sum claude-usage.apk > claude-usage.apk.sha256
          cat claude-usage.apk.sha256
      - name: Publicar
        uses: softprops/action-gh-release@72f2c25fcb47643c292f7107632f7a47c1df5cd8 # v2.3.2
        with:
          files: |
            android/app/build/claude-usage.apk
            android/app/build/claude-usage.apk.sha256
```

El `shred` con `if: always()` está a propósito: si el build falla, la llave no puede quedarse en
el runner.

- [ ] **Paso 2: Verificar que `build.sh` respeta las tres variables** (ya las lee desde F2: `KEYSTORE`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`). Probarlo en local con una llave de juguete:

```bash
keytool -genkeypair -keystore /tmp/juguete.jks -storepass prueba123 -keypass prueba123 \
  -alias juguete -keyalg RSA -keysize 2048 -validity 30 -dname "CN=prueba" >/dev/null 2>&1
KEYSTORE=/tmp/juguete.jks KEYSTORE_PASSWORD=prueba123 KEY_ALIAS=juguete bash android/app/build.sh
apksigner verify --print-certs android/app/build/claude-usage.apk | grep "CN=prueba"
rm -f /tmp/juguete.jks
```
Esperado: el certificado del APK es el de juguete, lo que demuestra que las variables mandan.

- [ ] **Paso 3: Commit**

```bash
git add android/app/ci/release.yml
git commit -m "ci: release del APK firmado con la llave del secret"
```

### Tarea 6.2: Guía de instalación y aviso

**Archivos:** crear `android/app/README.md`, modificar `README.md` de la raíz

- [ ] **Paso 1: `android/app/README.md`** con: cómo instalar con Obtainium (URL del repo, filtro
  `android-v*`), cómo verificar el SHA-256 antes de instalar, qué permisos pide la app y por qué
  (`INTERNET` y `RECEIVE_BOOT_COMPLETED`, nada más), y el aviso de que **el login es solo por
  correo** con la explicación de por qué.

- [ ] **Paso 2: Aviso en el README raíz** — que el proyecto no es oficial ni está afiliado a
  Anthropic y usa endpoints no documentados que pueden dejar de funcionar (spec §6 punto 8).

- [ ] **Paso 3: Commit, PR y memo**

```bash
git add android/app/README.md README.md
git commit -m "docs: guia de instalacion con Obtainium y avisos"
git push -u origin android/w3-f6-release
gh pr create --base main --head android/w3-f6-release --title "W3 F6: release del APK"
```

El memo a PC debe pedir dos cosas que esta sesión no puede hacer: **mover los dos workflows** a
`.github/workflows/`, y **pedirle al dueño que genere la llave de release y cargue los tres
secrets**. Hasta que eso pase, F6 no se puede cerrar.

---

## Dependencias entre fases

```
F2 build  ──> F3 login ──> F4 widget ──> F5 pruebas ──> F6 release
                 │                           │
                 └── D1 (paquete)            └── la prueba de 24 h no se puede acortar
                     D2 (organizacion)
                     D5 (muestras)
```

F2 no depende de ninguna decisión abierta salvo D1, que hay que confirmar **antes** de empezar:
cambiar el nombre del paquete después del primer Release obliga al usuario a reinstalar y perder
la sesión.
