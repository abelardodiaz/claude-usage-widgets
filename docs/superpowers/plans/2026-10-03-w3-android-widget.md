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
| `actions/setup-java` | `de7274f081f381c8f8158605e0321c36c376e2e6` # v6.0.1 | temurin 17 |
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
2. **El UUID de organización (`lastActiveOrg`, `manual_org`) es un dato de cuenta privado**, no una
   credencial: por sí solo no da acceso a nada, pero identifica al usuario. No se registra, no
   sale en mensajes de error ni en pantalla (se muestran nombres), y se borra al cerrar sesión.
   **No se cifra**: cifrarlo daría una falsa sensación de que es un secreto, y vive en las
   preferencias privadas de la app, que ya están aisladas.
3. **Nunca se registran cuerpos de respuesta.** Solo código HTTP, `content-type` y longitud.
4. **Sin `addJavascriptInterface`** y sin ningún puente JS. El WebView es solo para el login.
5. `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, sin permisos de red local.
6. `setAcceptThirdPartyCookies(web, false)`.
7. **A `claude.ai` se manda el mínimo de cookies**: `sessionKey` y, si aplica, `lastActiveOrg`.
   No el jarro entero (eso lo hacía el spike; ver "Lo que W3 NO debe copiar").
8. **Red: las peticiones nativas van solo a `claude.ai`.** Ningún otro host, cero telemetría. El
   WebView de login carga los subrecursos e iframes que el reto de Cloudflare exige (de terceros);
   la navegación principal sí se restringe a `claude.ai`.
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
6. **`minSdk` 29 sin hardware que lo pruebe.** El teléfono de pruebas es Android 17 (SDK 37), así
   que todo lo que este plan afirma sobre API 29 sale de la documentación, no de ejecutarlo. Las
   llamadas en riesgo, todas en `RemoteViews`: `setProgressBar` (API 1), `setViewVisibility`
   (API 1), `setTextViewText` (API 1), `setOnClickPendingIntent` (API 1) — elegidas a propósito
   por ser antiguas. Las que **no** se usan y habrían sido naturales: `setViewLayoutWidth`,
   `setColorStateList` y `setCompoundButtonChecked`, todas API 31. → sin prueba asignada; va en
   "No ejercitado en W3".

## Decisiones abiertas (las confirma el dueño antes de F3)

Cada una lleva mi recomendación. La sesión PC las lleva al dueño; hasta entonces el plan asume la
recomendación y lo dice donde toca.

**D1 — Nombre del paquete Android.** Recomiendo **`com.claulimitswidgets.android`**. Es estable,
coincide con el repositorio y no reclama un dominio. La alternativa ortodoxa sería un dominio del
dueño en orden inverso; si lo prefiere, se cambia en una tarea (afecta al manifiesto, al
`build.sh`, a `res/` y a la firma, pero no al núcleo). **No se puede cambiar después del primer
Release**: Android lo trata como otra app y el usuario perdería el widget y la sesión.

**D2 — Regla de selección de organización. CONFIRMADA** por el dueño, con dos ajustes de la
revisión. En orden:

1. **El selector manual de ajustes gana siempre.**
2. Si no hay manual: la cookie `lastActiveOrg`, si su `/usage` responde `200`. Es la que la web
   considera activa, así que coincide con lo que el usuario ve en claude.ai. **Ojo:** ese valor se
   congela en el login — si el usuario cambia de organización en la web después, el widget seguirá
   mirando la vieja. Para eso está el selector manual, y la pantalla de ajustes lo dice así.
3. Si no hay ni manual ni `lastActiveOrg`: si **solo una** responde `200`, esa. Si responden
   **varias**, el widget **no elige**: `Problem.CHOOSE_ORG` ("Elige organización en ajustes") y al
   tocarlo abre Ajustes. Adivinar sería mostrar una cuota que no es la suya, sin que el usuario
   tenga cómo saberlo.

Se descartan `rate_limit_tier` y `capabilities` como criterio automático: describen el plan
contratado, no cuál mira el usuario.

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
  src/com/claulimitswidgets/android/
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
  test/com/claulimitswidgets/android/
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
    /** uuid + nombre (name, si no plan_display_name, si no null). */
    public static final class Org { public final String uuid; public final String name; }
    public List<Org> organizations() throws IOException, AuthExpiredException,
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
    /** Una organizacion, "ninguna sirve" (orgUuid null, ambiguous false), o "que elija el
        usuario" (orgUuid null, ambiguous true). */
    public static final class Choice { public final String orgUuid; public final boolean ambiguous; }
    /** D2. `manual` gana si no es null. */
    public static Choice choose(List<String> organizations, String manual,
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
    public enum Problem { NO_SESSION, AUTH_EXPIRED, BLOCKED, OFFLINE, BAD_FORMAT, CHOOSE_ORG }
}

// SnapshotStore.java  (F4)
public final class SnapshotStore {
    public SnapshotStore(Context ctx);
    /** Guarda lo minimo para reconstruir el widget sin red. Las organizaciones van aparte. */
    public void remember(UsageModel model, Instant fetchedAt);
    /** Las organizaciones vistas. Ajustes las necesita aunque falle el resto. */
    public void rememberOrgs(List<UsageClient.Org> orgs);
    public List<UsageClient.Org> knownOrgs();
    /** El ultimo modelo guardado, o null si nunca hubo uno. */
    public UsageModel lastModel();
    /** Instante de la ultima consulta buena, o null. */
    public Instant lastFetchInstant();
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
`android/app/src/com/claulimitswidgets/android/LoginActivity.java`

- [ ] **Paso 1: Rama**

```bash
cd claude-usage-widgets
git checkout main && git pull
git checkout -b android/w3-f2-build
mkdir -p android/app/src/com/claulimitswidgets/android \
         android/app/test/com/claulimitswidgets/android \
         android/app/res/values android/app/res/values-es \
         android/app/res/layout android/app/res/xml android/app/res/drawable \
         android/app/ci
```

- [ ] **Paso 2: Manifiesto** (D1: `com.claulimitswidgets.android`)

Crear `android/app/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.claulimitswidgets.android">

    <!-- lint lee el minSdk de AQUI, no de las banderas de aapt2: sin esto asumiria minSdk 1
         y NewApi marcaria medio SDK. Los valores tienen que coincidir con los de build.sh. -->
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34" />

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

Crear `android/app/src/com/claulimitswidgets/android/LoginActivity.java`:

```java
package com.claulimitswidgets.android;

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

# Bloque TEST=1 numero 1: el manifiesto con la instrumentacion tiene que existir ANTES de
# enlazar. Si se genera despues, nunca entra al APK y `am instrument` falla con
# "Unable to find instrumentation info".
MANIFEST="AndroidManifest.xml"
if [ "${TEST:-0}" = "1" ]; then
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
find "$CORE/src" -name '*.java' | sort > "$OUT/core.txt"
javac -encoding UTF-8 --release 8 -Xlint:all,-options -Werror -d "$OUT/classes" "@$OUT/core.txt"

# OJO: aqui NO se puede usar -bootclasspath (javac lo prohibe con target >= 9) ni --release 8
# para acotar las APIs. Se probaron las dos:
#   -bootclasspath "$ANDROID_JAR"  -> "option --boot-class-path not allowed with target 17"
#   --release 8                    -> compila `java.util.List.of` igual, porque android.jar esta
#                                     en el classpath y aporta sus propias clases java.* al nivel
#                                     de la API con la que se compila (34).
# O sea que la app NO tiene la red de seguridad que si tiene android/core, y no es cuestion de
# banderas: en una app de Android las APIs java.* disponibles son las de android.jar. Lo que
# comprueba el minSdk de verdad es lint (NewApi), que corre en el CI (Tarea 2.4).
echo "== 4/6 javac (app) =="
find src "$OUT/gen" -name '*.java' | sort > "$OUT/app.txt"
javac -encoding UTF-8 -source 17 -target 17 \
  -classpath "$ANDROID_JAR:$OUT/classes" -Xlint:all,-options -Werror \
  -d "$OUT/classes" "@$OUT/app.txt"

# Bloque TEST=1 numero 2: las pruebas de la cascara. Mismas banderas que la app, porque corren
# en el telefono. De `android/core/test` solo entra `Assert`: el resto (FixtureRunner, TestRunner,
# AndroidSmoke) es para la JVM, usa APIs que Android no tiene y no tiene por que compilar aqui.
if [ "${TEST:-0}" = "1" ]; then
  echo "== extra: javac (pruebas) =="
  { echo "$CORE/test/com/claudewidgets/core/Assert.java"; find test -name '*.java'; } \
    | sort > "$OUT/tests.txt"
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

- [x] **Paso 1: Comprobar que ADB responde**

```bash
adb devices -l
```
Esperado: una línea con `device` y el modelo. Si sale vacío, la depuración inalámbrica cambió de
puerto: escanear `127.0.0.1` en el rango 30000-65535 y `adb connect` a cada candidato (el
procedimiento está en el `CLAUDE.md` global del aparato).

- [x] **Paso 2: Instalar**

```bash
adb install -r android/app/build/claude-usage.apk
```
Esperado: `Success`.

- [x] **Paso 3: Arrancar y leer lo que muestra**

```bash
adb shell monkey -p com.claulimitswidgets.android -c android.intent.category.LAUNCHER 1
sleep 3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -5
```
Esperado: entre los textos aparece `Núcleo enlazado: red` (o `Core linked: red` si el teléfono
está en inglés). `red` es el color que R7 da a 85, así que esa palabra demuestra que el núcleo
se ejecutó de verdad dentro de la app, no que solo compiló.

- [x] **Paso 4: Comprobar que no hay errores de carga de clases**

```bash
adb logcat -d -s AndroidRuntime:E | tail -5
```
Esperado: ninguna línea de `com.claudewidgets`. Si aparece `NoClassDefFoundError`, el `d8` no
metió el núcleo: revisar que el paso 5 de `build.sh` recoja `$OUT/classes` **entero**.

- [x] **Paso 5: Commit (si hubo ajustes)**

```bash
git add -A android/app
git commit -m "test: APK instalado y comprobado en el telefono"
```

**Resultado (2026-10-09, SM-S948B por depuracion inalambrica):** APK de 20958 bytes instalado con
`adb install -r` (`Success`). La app arranca y muestra `Uso de Claude` y **`Núcleo enlazado: red`**
—la palabra `red` es la que R7 asigna a 85, asi que el nucleo corrio dentro de la app—. Sin ajustes
en el codigo: no hizo falta tocar nada. `adb logcat -d -s AndroidRuntime:E` salio **vacio** y no hay
`NoClassDefFoundError`, de modo que `d8` si metio el nucleo entero. `dumpsys` confirma
`minSdk=29 targetSdk=34 versionName=0.0.0-dev`.

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
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: "17"
      # El runner trae el SDK de Android preinstalado; se fija la version para que el APK
      # del CI y el del telefono se construyan contra el mismo android.jar.
      - name: Instalar las herramientas de compilacion
        run: |
          set -euo pipefail
          # `yes |` muere con 141 (SIGPIPE) cuando sdkmanager deja de leer, y con pipefail eso
          # tumba el paso aunque la instalacion haya ido bien.
          yes 2>/dev/null | sdkmanager --install "platforms;android-34" "build-tools;34.0.0" >/dev/null || true
          echo "$ANDROID_HOME/build-tools/34.0.0" >> "$GITHUB_PATH"
      - name: Construir el APK
        # ANDROID_JAR se arma DENTRO del run: el contexto `env` de Actions no ve las variables
        # del runner, asi que `${{ env.ANDROID_HOME }}` saldria vacio.
        run: |
          set -euo pipefail
          ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar" bash android/app/build.sh
      - name: Comprobar que resources.arsc no quedo comprimido
        run: |
          set -euo pipefail
          unzip -lv android/app/build/claude-usage.apk | grep resources.arsc | grep -q Stored
      # La UNICA comprobacion de que el codigo respeta minSdk 29. No la hace javac: con
      # android.jar en el classpath, las APIs java.* disponibles son las de la API 34, y ni
      # `--release 8` lo evita. Y tampoco hay un dispositivo API 29 donde probarlo.
      - name: lint NewApi (minSdk 29)
        run: |
          set -euo pipefail
          lint --check NewApi --exitcode \
            --sdk-home "$ANDROID_HOME" \
            --classpath android/app/build/classes \
            --libraries "$ANDROID_HOME/platforms/android-34/android.jar" \
            android/app
```

El `sdkmanager` del paso anterior instala tambien `cmdline-tools;latest`, que es donde vive
`lint`, y anade su `bin` al `PATH`.

**Dos cosas que la revisión añadió y que no son opcionales:**

1. **El pin de herramientas tiene que verificarse.** La primera versión de este paso no hacía nada:
   `sdkmanager` no está en el `PATH` del runner, y `|| true` con `>/dev/null` se tragaban el
   `command not found`. El job pasaba porque la imagen ya trae la plataforma 34, así que el pin era
   decorativo. Hay que buscar `sdkmanager` dentro del SDK, mirar su código real con `PIPESTATUS`, y
   comprobar con `test` que `android.jar`, `aapt2` y `lint` existen de verdad.
2. **Un canario de lint en cada corrida.** `lint` también pasa cuando no analiza nada ("No issues
   found"), así que el job copia `android/app`, le inyecta una llamada a `VibratorManager` (API 31)
   y **exige que lint falle con `[NewApi]`**; si no falla, el job falla. Sin esto, que lint esté
   sano hoy no dice nada de mañana.

Si se cambia la línea de `LoginActivity` donde el canario inyecta la llamada, hay que ajustar su
`sed`.

**`lint` no existe en Termux** (no hay `cmdline-tools`), asi que esta comprobacion es **solo de
CI**. Es una asimetria incomoda —el resto del build es identico en los dos sitios— pero la
alternativa seria no comprobar el `minSdk` en ningun lado.

- [ ] **Paso 1b: Hacer fallar a lint a proposito**

Una comprobacion que nunca ha fallado no sirve. En una rama de usar y tirar, meter una llamada a
una API posterior a 29 en `LoginActivity`:

```java
        // API 31: deberia hacer fallar a lint con minSdk 29
        android.os.VibratorManager vm = getSystemService(android.os.VibratorManager.class);
```

Empujar, comprobar que el job **falla** con `NewApi`, guardar la salida para el PR, y borrar la
rama. Si lint **no** falla, la comprobacion no sirve y hay que arreglarla antes de seguir.

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
`SessionStoreTest` no puede correr con `java`. Corre en el teléfono, dentro del proceso de la app,
con una `Instrumentation` lanzada por `adb shell am instrument`. **No** con `app_process`: ése
corre con el uid de `shell`, así que el Keystore sería de otro usuario y el `filesDir` de la app
no sería escribible. Es la razón de que esta fase traiga su propio corredor.

### Tarea 3.1: Corredor de pruebas de la app

**Archivos:** crear `android/app/test/com/claulimitswidgets/android/AppTestRunner.java`,
modificar `android/app/build.sh`

- [ ] **Paso 1: Escribir el corredor**

Crear `android/app/test/com/claulimitswidgets/android/AppTestRunner.java`:

```java
package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.util.List;

/**
 * Corredor de las pruebas de la cascara. NO corre en una JVM: el Keystore y el almacenamiento
 * de la app no existen fuera del dispositivo.
 *
 * Lo lanza {@link AppInstrumentation} dentro del proceso de la app. Correrlo con `app_process`
 * NO sirve: ese proceso tiene el uid de `shell`, asi que el Keystore seria de otro usuario y
 * `filesDir` no seria escribible.
 */
public final class AppTestRunner {

    private AppTestRunner() {}

    /** Devuelve el texto del resultado; quien llama decide el codigo de salida. */
    public static String run(Assert a, android.content.Context ctx) {
        SessionStoreTest.run(a, ctx);
        OrgSelectorTest.run(a);
        SampleStoreTest.run(a, ctx.getCacheDir());
        BackoffTest.run(a);
        UsageClientTest.run(a);
        List<String> failures = a.failures();
        if (failures.isEmpty()) return "OK: " + a.checks() + " comprobaciones, 0 fallos";
        StringBuilder sb = new StringBuilder("FALLOS (" + failures.size()
                + " de " + a.checks() + "):");
        for (String f : failures) sb.append("\n  - ").append(f);
        return sb.toString();
    }
}
```

`Assert` se reutiliza del núcleo: ya es `public` y no tiene dependencias.

- [ ] **Paso 2: Comprobar los dos bloques `TEST=1` de `build.sh`**

Son **dos** y el orden importa. El manifiesto con la instrumentación tiene que existir **antes**
de `aapt2 link`; si se genera después, nunca entra al APK y `am instrument` falla con
`Unable to find instrumentation info`. El `javac` de pruebas va después del de la app.

Ambos están escritos en la Tarea 2.2. Aquí solo se verifica que estén y en ese orden:

```bash
grep -n 'TEST:-0' android/app/build.sh
grep -n 'aapt2 link\|== 5/6 d8' android/app/build.sh
```
Esperado: dos líneas de `TEST:-0`, la primera **antes** de la de `aapt2 link` y la segunda
**antes** de la de `d8`.

- [ ] **Paso 3: Commit**

```bash
git add android/app/test android/app/build.sh
git commit -m "test: corredor de la cascara, corre bajo ART"
```

### Tarea 3.2: `SessionStore` — cookie cifrada con el Android Keystore

**Archivos:** crear `android/app/test/com/claulimitswidgets/android/SessionStoreTest.java`,
`android/app/src/com/claulimitswidgets/android/SessionStore.java`

- [ ] **Paso 1: Escribir la prueba que falla**

Crear `android/app/test/com/claulimitswidgets/android/SessionStoreTest.java`:

```java
package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** El Keystore solo existe bajo ART; estas pruebas no corren en una JVM. */
public final class SessionStoreTest {

    private static final String COOKIE = "sessionKey=valor-de-prueba; lastActiveOrg=otro-valor";

    /** Archivo y llave propios: una prueba NO puede borrar la sesion real del usuario. */
    private static final String TEST_FILE = "session-test.bin";
    private static final String TEST_ALIAS = "cuw-session-test";

    public static void run(Assert a, android.content.Context ctx) {
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
        a.isTrue("sin sesion al empezar", !s.hasSession());
        a.eq("load sin sesion da null", null, call(a, () -> s.load()));

        call(a, () -> { s.save(COOKIE); return null; });
        a.isTrue("hasSession tras guardar", s.hasSession());
        a.eq("vuelve lo mismo que entro", COOKIE, call(a, () -> s.load()));

        // Lo que queda en disco no puede contener el texto claro.
        File f = new File(ctx.getFilesDir(), TEST_FILE);
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

El `Context` se lo pasa la `Instrumentation` del paso siguiente: no hay ningún ayudante que lo
invente.

- [ ] **Paso 2: Correr las pruebas como `Instrumentation`, no con `app_process`**

`app_process` corre con el uid de `shell`: el Keystore sería de otro usuario, el `filesDir` de la
app no sería escribible, y `ActivityThread.systemMain` es API oculta. Nada de eso se nota al
compilar; se nota como fallos raros en el dispositivo.

La forma pública y con el uid correcto es una `Instrumentation`.

Crear `android/app/test/com/claulimitswidgets/android/AppInstrumentation.java`:

```java
package com.claulimitswidgets.android;

import android.app.Instrumentation;
import android.os.Bundle;

import com.claudewidgets.core.Assert;

import java.util.List;

/**
 * Corre las pruebas DENTRO del proceso de la app: uid correcto, `filesDir` escribible y
 * Keystore propio. Se lanza con `adb shell am instrument -w`.
 */
public class AppInstrumentation extends Instrumentation {

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override
    public void onStart() {
        Bundle out = new Bundle();
        Assert a = new Assert();
        String text;
        try {
            // La lista de pruebas vive en UN sitio: AppTestRunner. Repetirla aqui garantiza
            // que algun dia se agregue una prueba y no corra.
            text = AppTestRunner.run(a, getTargetContext());
        } catch (RuntimeException e) {
            a.fail("excepcion no controlada: " + e);
            text = "FALLOS (1 de " + a.checks() + "):\n  - " + e;
        }
        out.putString("stream", text);
        finish(a.failures().isEmpty() ? 0 : 1, out);
    }
}
```

La declaración en el manifiesto y el `--manifest "$MANIFEST"` ya están en el `build.sh` de la
Tarea 2.2 (primer bloque `TEST=1`). En el APK de release la instrumentación **no existe**: el
manifiesto normal no la lleva.

`SessionStoreTest` y `SampleStoreTest` reciben el `Context`/`File` como parámetro en vez de
pedirlo a un ayudante: sus firmas son `run(Assert a, Context ctx)` y `run(Assert a, File dir)`.
No hay `TestContext`.

- [ ] **Paso 3: Correr la prueba y verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: **falla a compilar** con `cannot find symbol: class SessionStore`. Ese es el rojo.

- [ ] **Paso 4: Implementar `SessionStore`**

Crear `android/app/src/com/claulimitswidgets/android/SessionStore.java`:

```java
package com.claulimitswidgets.android;

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
    private final String fileName;
    private final String keyAlias;

    public SessionStore(Context ctx) {
        this(ctx, FILE_NAME, KEY_ALIAS);
    }

    /**
     * Para las pruebas. Ahora que corren con el uid y el `filesDir` de la app, un
     * `new SessionStore(ctx).clear()` borraria la sesion de verdad del usuario, y volver a entrar
     * cuesta un correo con ventana de 10 minutos y limite de reenvios. Las pruebas usan su propio
     * archivo y su propia llave.
     */
    SessionStore(Context ctx, String fileName, String keyAlias) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.fileName = fileName;
        this.keyAlias = keyAlias;
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
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias);
        } catch (GeneralSecurityException | IOException ignored) {
            // Si el Keystore no responde, el archivo ya esta borrado: sin llave no se descifra.
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(keyAlias, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin autenticacion de usuario: el widget se actualiza con la pantalla apagada.
                .setUserAuthenticationRequired(false)
                .build());
        return g.generateKey();
    }

    private File file() { return new File(ctx.getFilesDir(), fileName); }

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
adb install -r android/app/build/claude-usage.apk
# `am instrument -w` sale 0 aunque la instrumentacion termine con finish(1): el codigo de salida
# que devuelve es el de adb, no el de la prueba. Hay que mirar el texto.
adb shell am instrument -w com.claulimitswidgets.android/.AppInstrumentation \
  | tee /dev/stderr | grep -q "OK:"
```
Esperado: `OK: N comprobaciones, 0 fallos`.

- [ ] **Paso 6: Comprobar a mano que el archivo no tiene la cookie en claro**

Esto no se delega a la prueba: es la regla 1 de `SECURITY.md` y conviene verla con los ojos.

```bash
adb shell run-as com.claulimitswidgets.android ls -l files/ 2>/dev/null \
  || echo "(el APK no es debuggable: se comprueba con la asercion de la prueba)"
```

- [ ] **Paso 7: Commit**

```bash
git add android/app/src/com/claulimitswidgets/android/SessionStore.java android/app/test
git commit -m "feat: cookie cifrada con AES-GCM y llave del Android Keystore"
```

### Tarea 3.3: Excepciones y `Backoff`

**Archivos:** crear `BlockedException.java`, `AuthExpiredException.java`, `Backoff.java`,
`test/.../BackoffTest.java`

Tres fracasos distintos necesitan tres respuestas distintas, y confundirlos es lo que haría que el
widget mintiera. Por eso son tipos y no un booleano.

- [ ] **Paso 1: La prueba de `Backoff` que falla**

Crear `android/app/test/com/claulimitswidgets/android/BackoffTest.java`:

```java
package com.claulimitswidgets.android;

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

Crear `android/app/src/com/claulimitswidgets/android/Backoff.java`:

```java
package com.claulimitswidgets.android;

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

Crear `android/app/src/com/claulimitswidgets/android/AuthExpiredException.java`:

```java
package com.claulimitswidgets.android;

/** 401: la cookie ya no vale. Lleva al usuario a iniciar sesion otra vez. */
public final class AuthExpiredException extends Exception {
    private static final long serialVersionUID = 1L;
    public AuthExpiredException(String message) { super(message); }
}
```

Crear `android/app/src/com/claulimitswidgets/android/BlockedException.java`:

```java
package com.claulimitswidgets.android;

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
# `am instrument -w` sale 0 aunque la instrumentacion termine con finish(1): el codigo que
# devuelve es el de adb, no el de la prueba. Por eso se mira el texto.
TEST=1 bash android/app/build.sh \
  && adb install -r android/app/build/claude-usage.apk \
  && adb shell am instrument -w com.claulimitswidgets.android/.AppInstrumentation \
  | tee /dev/stderr | grep -q "OK:"
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

Crear `android/app/test/com/claulimitswidgets/android/UsageClientTest.java`:

```java
package com.claulimitswidgets.android;

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

Crear `android/app/src/com/claulimitswidgets/android/UsageClient.java`:

```java
package com.claulimitswidgets.android;

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

    /**
     * Una organizacion, con lo unico que la app necesita. El `uuid` es un dato de cuenta
     * privado; el `name` es lo que ve el usuario en Ajustes.
     */
    public static final class Org {
        public final String uuid;
        public final String name;    // puede ser null: entonces Ajustes muestra el uuid abreviado
        Org(String uuid, String name) { this.uuid = uuid; this.name = name; }
    }

    public List<Org> organizations() throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        String body = get(ORGS);
        Object root = parseOrFail(body);
        if (!(root instanceof List)) {
            throw new UnrecognizedFormatException("/api/organizations no devolvio un arreglo");
        }
        List<Org> out = new ArrayList<>();
        for (Object item : (List<?>) root) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Object uuid = m.get("uuid");
            if (!(uuid instanceof String) || ((String) uuid).isEmpty()) continue;
            // `name` es el nombre de la organizacion; si falta, `plan_display_name` sirve para
            // distinguir dos. Si tampoco esta, Ajustes cae al uuid abreviado.
            String name = str(m.get("name"));
            if (name == null) name = str(m.get("plan_display_name"));
            out.add(new Org((String) uuid, name));
        }
        if (out.isEmpty()) throw new UnrecognizedFormatException("ninguna organizacion con uuid");
        return out;
    }

    private static String str(Object v) {
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.isEmpty() ? null : s;
    }

    private static final java.util.regex.Pattern UUID_RE =
            java.util.regex.Pattern.compile("^[0-9a-fA-F-]{36}$");

    public UsageModel usage(String orgUuid) throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        // El uuid viene de una respuesta del servidor o de una preferencia: no se concatena a
        // una URL sin mirarlo. Un valor con '/' o '?' cambiaria a que endpoint se llama.
        if (orgUuid == null || !UUID_RE.matcher(orgUuid).matches()) {
            throw new UnrecognizedFormatException("uuid de organizacion con forma invalida");
        }
        // orgUuid es un dato de cuenta: si falla, el mensaje no lo lleva.
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
# `am instrument -w` sale 0 aunque la instrumentacion termine con finish(1): el codigo que
# devuelve es el de adb, no el de la prueba. Por eso se mira el texto.
TEST=1 bash android/app/build.sh \
  && adb install -r android/app/build/claude-usage.apk \
  && adb shell am instrument -w com.claulimitswidgets.android/.AppInstrumentation \
  | tee /dev/stderr | grep -q "OK:"
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

Crear `android/app/test/com/claulimitswidgets/android/OrgSelectorTest.java`:

```java
package com.claulimitswidgets.android;

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
                OrgSelector.choose(dos, "org-z", "org-a", u -> true).orgUuid);
        a.eq("el manual gana aunque no responda", "org-z",
                OrgSelector.choose(dos, "org-z", "org-a", u -> false).orgUuid);

        a.eq("sin manual, lastActiveOrg si responde", "org-b",
                OrgSelector.choose(dos, null, "org-b", u -> true).orgUuid);
        a.eq("lastActiveOrg que no responde cae a la unica que si", "org-a",
                OrgSelector.choose(dos, null, "org-b", u -> u.equals("org-a")).orgUuid);
        a.eq("lastActiveOrg ajeno a la lista igual se intenta", "org-c",
                OrgSelector.choose(dos, null, "org-c", u -> true).orgUuid);

        a.eq("sin pistas y solo una responde", "org-b",
                OrgSelector.choose(dos, null, null, u -> u.equals("org-b")).orgUuid);
        // Lo que NO debe hacer: elegir por el usuario.
        a.eq("sin pistas y varias responden, no elige", null,
                OrgSelector.choose(dos, null, null, u -> true).orgUuid);
        a.isTrue("sin pistas y varias responden, pide elegir",
                OrgSelector.choose(dos, null, null, u -> true).ambiguous);
        a.eq("si ninguna responde, null", null,
                OrgSelector.choose(dos, null, null, u -> false).orgUuid);
        a.isTrue("si ninguna responde no es ambiguo",
                !OrgSelector.choose(dos, null, null, u -> false).ambiguous);
        a.eq("lista vacia sin manual, null", null,
                OrgSelector.choose(Arrays.asList(), null, null, u -> true).orgUuid);
    }
}
```

- [ ] **Paso 2: Verla fallar**

```bash
TEST=1 bash android/app/build.sh
```
Esperado: `cannot find symbol: class OrgSelector`.

- [ ] **Paso 3: Implementar**

Crear `android/app/src/com/claulimitswidgets/android/OrgSelector.java`:

```java
package com.claulimitswidgets.android;

import java.util.ArrayList;
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

    /** Lo que decidio la regla: una organizacion, "ninguna sirve", o "que elija el usuario". */
    public static final class Choice {
        public final String orgUuid;      // null si hay que preguntar o si ninguna sirve
        public final boolean ambiguous;   // true: varias responden y no hay pista
        Choice(String orgUuid, boolean ambiguous) {
            this.orgUuid = orgUuid;
            this.ambiguous = ambiguous;
        }
    }

    public static Choice choose(List<String> organizations, String manual,
                                String lastActiveOrg, Probe probe) {
        if (manual != null && !manual.isEmpty()) return new Choice(manual, false);
        if (lastActiveOrg != null && !lastActiveOrg.isEmpty() && probe.responds(lastActiveOrg)) {
            return new Choice(lastActiveOrg, false);
        }
        List<String> responden = new ArrayList<>();
        for (String uuid : organizations) {
            if (probe.responds(uuid)) responden.add(uuid);
        }
        if (responden.size() == 1) return new Choice(responden.get(0), false);
        if (responden.isEmpty()) return new Choice(null, false);
        return new Choice(null, true);   // varias responden y ninguna pista: no se adivina
    }
}
```

- [ ] **Paso 4: Verla pasar y commit**

```bash
# `am instrument -w` sale 0 aunque la instrumentacion termine con finish(1): el codigo que
# devuelve es el de adb, no el de la prueba. Por eso se mira el texto.
TEST=1 bash android/app/build.sh \
  && adb install -r android/app/build/claude-usage.apk \
  && adb shell am instrument -w com.claulimitswidgets.android/.AppInstrumentation \
  | tee /dev/stderr | grep -q "OK:"
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
    <string name="login_again">Sign in again</string>
    <string name="probe_title">Test the connection</string>
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
    <string name="login_again">Volver a entrar</string>
    <string name="probe_title">Probar la conexión</string>
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
        <TextView android:id="@+id/intro_status" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/login_steps"
            android:textSize="15sp" android:paddingBottom="20dp" />
        <Button android:id="@+id/btn_start" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/login_start" />
        <Button android:id="@+id/btn_probe" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/probe_title"
            android:visibility="gone" />
        <Button android:id="@+id/btn_settings" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/settings_title"
            android:visibility="gone" />
        <Button android:id="@+id/btn_logout_intro" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:text="@string/logout"
            android:visibility="gone" />
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

- [ ] **Paso 3: Implementar** — reemplazar `src/com/claulimitswidgets/android/LoginActivity.java`:

```java
package com.claulimitswidgets.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
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
        s.setAllowFileAccess(false);       // nada de file:// con JS activado
        s.setAllowContentAccess(false);    // ni content://
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Sin addJavascriptInterface: SECURITY.md regla 5.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);   // restriccion global 6

        // Sin esto, cualquier enlace de la pagina navega DENTRO del WebView, con JS y con la
        // cookie de sesion. Solo claude.ai se carga aqui; lo demas sale al navegador del sistema.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (isClaude(u)) return false;        // lo carga el WebView
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (RuntimeException ignored) {
                    // Sin navegador que lo abra: mejor no cargarlo aqui que cargarlo igual.
                }
                return true;                           // no se carga dentro
            }
        });

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startLogin());
        ((Button) findViewById(R.id.btn_done)).setOnClickListener(v -> finishLogin());
        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> logout());
        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Con sesion, la intro deja de ser un tutorial y pasa a ser el panel de la cuenta:
        // si no, Ajustes queda inalcanzable y "cerrar sesion" escondido tras el WebView.
        boolean signedIn = store.hasSession();
        ((TextView) findViewById(R.id.intro_status)).setText(
                signedIn ? R.string.login_ok : R.string.login_steps);
        findViewById(R.id.btn_settings).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_probe).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_logout_intro).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btn_logout_intro)).setOnClickListener(v -> {
            Session.logout(this);
            wipeWebView();        // el WebView pudo quedar con cookies de un login anterior
            recreate();
        });
        ((Button) findViewById(R.id.btn_start)).setText(
                signedIn ? R.string.login_again : R.string.login_start);
    }

    private void startLogin() {
        findViewById(R.id.intro).setVisibility(View.GONE);
        findViewById(R.id.web_pane).setVisibility(View.VISIBLE);
        web.loadUrl(LOGIN_URL);
    }

    /** El usuario dice que ya entro. Se comprueba leyendo la cookie, no creyendole. */
    private void finishLogin() {
        status.setText(R.string.login_checking);
        // El UA se lee en el hilo de UI: `web.getSettings()` no se toca desde otro hilo.
        final String ua = userAgent(web);
        String all = CookieManager.getInstance().getCookie(ORIGIN);
        String minimal = UsageClient.minimalCookies(all);
        if (!minimal.contains("sessionKey=")) {
            status.setText(R.string.login_no_session);
            return;
        }
        try {
            store.save(minimal);           // se guarda YA reducida al minimo
            // El UA del WebView es el que usaran las consultas nativas: una sola huella hacia
            // claude.ai. Se guarda aqui porque un JobService no puede crear un WebView.
            getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).edit()
                    .putString("user_agent", ua).apply();
            // Volver a entrar arregla el problema: la espera acumulada ya no aplica.
            UsageRefresher.clearBackoff(this);
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
        Session.logout(this);
        wipeWebView();
        status.setText(R.string.login_no_session);
    }

    /** Solo https hacia claude.ai o un subdominio suyo. */
    private static boolean isClaude(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String h = u.getHost();
        return h != null && (h.equals("claude.ai") || h.endsWith(".claude.ai"));
    }

    /**
     * Tras el login el WebView no debe conservar nada. `removeAllCookies` es asincrono: el
     * `flush` va DENTRO del callback o se escribe en disco lo que se acaba de borrar.
     * Y con DOM storage activado hay que borrar tambien localStorage e IndexedDB.
     */
    private void wipeWebView() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> cm.flush());
        WebStorage.getInstance().deleteAllData();          // localStorage e IndexedDB
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
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

- [x] **Paso 1: Esqueleto que compila**

```java
package com.claulimitswidgets.android;

import android.content.Context;

/** Esqueleto de F3; F4 lo llena. Los metodos existen para que LoginActivity compile. */
public final class WidgetUpdateJob {
    private WidgetUpdateJob() {}
    public static void schedule(Context ctx) { }
    public static void cancel(Context ctx) { }
    public static void runNow(Context ctx) { }
}
```

- [x] **Paso 2: Construir, instalar y hacer el login de verdad**

```bash
bash android/app/build.sh
adb install -r android/app/build/claude-usage.apk
adb shell monkey -p com.claulimitswidgets.android -c android.intent.category.LAUNCHER 1
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

- [x] **Paso 4: Probar la cookie MÍNIMA contra claude.ai real**

Esta es **la hipótesis central de la fase y no está demostrada**. El spike A2 mandó el jarro
entero, incluidas `__cf_bm` y `_cfuvid`, que son de gestión de bots de Cloudflare. Que
`sessionKey` + `lastActiveOrg` solas pasen es una suposición razonable, no un hecho.

Añadir a `LoginActivity` un botón "Probar" que llame a `organizations()` y muestre **solo el
código HTTP**:

```java
        ((Button) findViewById(R.id.btn_probe)).setOnClickListener(v -> new Thread(() -> {
            String msg;
            try {
                String cookies = store.load();
                int n = new UsageClient(cookies, userAgent(web)).organizations().size();
                msg = "HTTP 200, " + n + " organizacion(es)";
            } catch (AuthExpiredException e) {
                msg = "HTTP 401: la sesion no vale";
            } catch (BlockedException e) {
                msg = "bloqueado: la cookie minima no basta";   // <- el caso que importa
            } catch (Exception e) {
                msg = e.getClass().getSimpleName();
            }
            final String m = msg;
            runOnUiThread(() -> status.setText(m));
        }).start());
```

Con su botón en el layout y su cadena (`probe_title` = "Probar" / "Test"), y el `View` visible
solo cuando hay sesión.

Esperado: `HTTP 200, N organizacion(es)`.

**Si sale "bloqueado"**: la cookie mínima no basta y hay que decidir qué cookies añadir —
probablemente `__cf_bm` y `_cfuvid`— lo cual cambia la restricción global 7. **Eso es un memo a
PC, no una decisión de quien ejecute.** Y si el resultado no se puede obtener (sin sesión, sin
red), se declara como riesgo abierto en el reporte de la fase, no se da por bueno.

**Resultado del Paso 4 (2026-10-09, sesion real del duenio, SM-S948B):** la hipotesis central del
diseno queda **CONFIRMADA**. Lo guardado en el almacen cifrado era **solo** `sessionKey` y
`lastActiveOrg` —la cookie minima, ningun otro nombre— y claude.ai respondio **200 con JSON** a
`organizations()` y a `usage()`: ni 401, ni 403, ni `cf-mitigated`, ni HTML donde se esperaba JSON.
El **nucleo parseo la respuesta real** sin `UnrecognizedFormatException`, no solo los fixtures. La
cuenta tiene 2 organizaciones y D2 eligio sin ambiguedad con `lastActiveOrg` en el primer sondeo.
Valores que saldrian en el widget: sesion **64.0% (amber)**, semana **96.0% (red)** — y la app
oficial de Claude mostraba en ese mismo momento "Cerca del limite", lo que confirma el calculo de
forma independiente.

Tambien queda confirmado, de paso, que `CookieManager.getCookie` **si devuelve la `sessionKey`**
pese a ser HttpOnly: hasta hoy eso solo lo respaldaba el spike de W0.

La sonda que hizo la comprobacion es de diagnostico y **no se commitea**: necesita red y una sesion
real, asi que romperia CI. Queda guardada en el espacio de trabajo de la fase
(`RealSessionProbe.java` y `build-probe.sh`), junto con su reporte.

**Pendiente, deliberadamente aplazado al Paso 3 / F5:** comprobar que cerrar sesion no deja la
cookie en `app_webview/Default/Cookies`. Exige **borrar la sesion del duenio**, que acaba de
iniciarla y que hace falta para F4. Se hara cuando toque probar en dispositivo.

- [ ] **Paso 5: Commit, PR y memo**

```bash
git add android/app/src
git commit -m "feat: esqueleto del job para que F3 compile"
git push -u origin android/w3-f3-login
gh pr create --base main --head android/w3-f3-login --title "W3 F3: login por correo y almacen cifrado"
```

El memo a PC debe incluir: la salida del corredor bajo ART, la confirmación de que `logcat` no
tiene la cookie, y **si `am instrument` corrió sin problemas** (es la primera vez que el proyecto
usa `Instrumentation`; si falla, el bloqueo va al memo, no se improvisa otra vía).

### Tarea 3.7b: `Session` — un único "cerrar sesión"

**Archivos:** crear `src/.../Session.java`

Había dos sitios borrando cosas distintas: la pantalla de login borraba la cookie y los ajustes
borraban la cookie y las preferencias, y **ninguno borraba `samples.jsonl`**. Cerrar sesión tiene
que dejar el teléfono como antes de instalar, y eso solo se garantiza si hay un único sitio que lo
haga.

- [ ] **Paso 1: Implementar**

```java
package com.claulimitswidgets.android;

import android.content.Context;

/**
 * Cerrar sesion. Un solo sitio, llamado desde el login y desde los ajustes.
 *
 * Borra TODO lo que la app sabe del usuario: la cookie y su llave, las muestras, el ultimo
 * modelo, la organizacion elegida y el User-Agent guardado. Si manana se guarda algo nuevo del
 * usuario, se borra aqui o el "cerrar sesion" se vuelve mentira.
 */
public final class Session {

    private Session() {}

    public static void logout(Context ctx) {
        synchronized (UsageRefresher.LOCK) {   // no borrar mientras un refresco escribe
            logoutLocked(ctx);
        }
    }

    private static void logoutLocked(Context ctx) {
        Context app = ctx.getApplicationContext();
        new SessionStore(app).clear();                       // cookie + llave del Keystore
        new SampleStore(app.getFilesDir()).clear();          // samples.jsonl (D5)
        new SnapshotStore(app).clear();                      // ultimo modelo, orgs, hora
        app.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();                     // manual_org, user_agent, backoff
        WidgetUpdateJob.cancel(app);
        WidgetUpdateJob.pushToWidgets(app, Snapshot.of(Snapshot.Problem.NO_SESSION));
    }
}
```

- [ ] **Paso 2: Comprobar a mano que no queda nada**

```bash
adb shell run-as com.claulimitswidgets.android ls -R files shared_prefs 2>/dev/null \
  || echo "(APK no debuggable: lo cubre la prueba de SessionStore)"
```

- [ ] **Paso 3: Commit**

```bash
git add android/app/src && git commit -m "feat: un unico cerrar sesion que borra todo"
```

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

- [ ] **Paso 4: Implementar** — crear `src/com/claulimitswidgets/android/SettingsActivity.java`:

```java
package com.claulimitswidgets.android;

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
        paintOrgs();
        // La lista cacheada no se refresca sola: si el usuario crea o deja una organizacion,
        // Ajustes mostraria la de siempre. Abrir esta pantalla es el momento natural de mirar.
        // Al terminar se repintan los radios, porque la lista pudo cambiar.
        WidgetUpdateJob.refreshOrgs(this, this::paintOrgs);

        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> {
            Session.logout(this);
            finish();
        });
    }

    /**
     * Dibuja los radios desde la lista cacheada. Se llama al crear la pantalla y otra vez
     * cuando `refreshOrgs` termina, porque la lista pudo cambiar.
     */
    private void paintOrgs() {
        // `refreshOrgs` vuelve de un hilo de red: la pantalla pudo cerrarse mientras tanto.
        if (isFinishing() || isDestroyed()) return;
        RadioGroup group = findViewById(R.id.orgs);
        group.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_ORG, null);

        RadioButton auto = new RadioButton(this);
        auto.setText(R.string.settings_org_auto);
        auto.setChecked(current == null);
        auto.setOnClickListener(v -> {
            prefs.edit().remove(KEY_ORG).apply();
            // Volver a automatica es tan accion del usuario como elegir una: mismo trato.
            UsageRefresher.clearBackoff(this);
            WidgetUpdateJob.runNow(this);
        });
        group.addView(auto);

        // Se muestran NOMBRES, no uuid: un uuid no le dice nada al usuario y acabaria en una
        // captura. `SnapshotStore` los guarda como "uuid|nombre" al consultar /organizations.
        for (UsageClient.Org o : knownOrgs()) {
            RadioButton b = new RadioButton(this);
            b.setText(o.name != null ? o.name : abbreviate(o.uuid));
            b.setChecked(o.uuid.equals(current));
            b.setOnClickListener(v -> {
                prefs.edit().putString(KEY_ORG, o.uuid).apply();
                // Elegir organizacion es una accion del usuario: no debe esperar al backoff.
                UsageRefresher.clearBackoff(this);
                WidgetUpdateJob.runNow(this);
            });
            group.addView(b);
        }
    }

    /** Los ultimos uuid vistos, cacheados por el refrescador. Vacio si todavia no consulto. */
    private List<UsageClient.Org> knownOrgs() {
        return new SnapshotStore(this).knownOrgs();   // F4
    }

    /** Respaldo si no se guardo el nombre: ocho caracteres bastan para distinguir dos. */
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

Crear `android/app/test/com/claulimitswidgets/android/SampleStoreTest.java`:

```java
package com.claulimitswidgets.android;

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

Crear `android/app/src/com/claulimitswidgets/android/SampleStore.java`:

```java
package com.claulimitswidgets.android;

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
# `am instrument -w` sale 0 aunque la instrumentacion termine con finish(1): el codigo que
# devuelve es el de adb, no el de la prueba. Por eso se mira el texto.
TEST=1 bash android/app/build.sh \
  && adb install -r android/app/build/claude-usage.apk \
  && adb shell am instrument -w com.claulimitswidgets.android/.AppInstrumentation \
  | tee /dev/stderr | grep -q "OK:"
git add android/app/src android/app/test && git commit -m "feat: almacen de muestras con ventana de 15 dias"
```

### Tarea 4.2: `Snapshot`, `SnapshotStore` y `UsageRefresher`

**Archivos:** crear `Snapshot.java`, `SnapshotStore.java`, `UsageRefresher.java`

**Review Focus 1 y 5** se cubren aquí: sin red se conserva el último dato con su hora, y sin
sesión el widget lo dice.

- [ ] **Paso 1: `Snapshot`**

```java
package com.claulimitswidgets.android;

import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.Forecast;
import com.claudewidgets.core.UsageModel;

import java.time.Instant;

/** Todo lo que el widget necesita para pintarse, ya calculado por el nucleo. */
public final class Snapshot {

    /**
     * Que mostrar cuando no hay numeros que mostrar. `CHOOSE_ORG` no es un fallo: es que hay
     * varias organizaciones y ninguna pista de cual mira el usuario, asi que elegir por el
     * seria mostrarle una cuota que no es la suya.
     */
    public enum Problem { NO_SESSION, AUTH_EXPIRED, BLOCKED, OFFLINE, BAD_FORMAT, CHOOSE_ORG,
                          LOADING }

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
package com.claulimitswidgets.android;

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

    /** Se serializa `uuid|nombre`; el `|` no aparece en un uuid ni se espera en un nombre. */
    public void rememberOrgs(List<UsageClient.Org> orgs) {
        StringBuilder sb = new StringBuilder();
        for (UsageClient.Org o : orgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(o.uuid).append('|').append(o.name == null ? "" : o.name.replace(',', ' '));
        }
        prefs.edit().putString(KEY_ORGS, sb.toString()).apply();
    }

    /**
     * Guarda lo minimo para volver a pintar el widget sin red. No se guarda `scoped` ni
     * `breakdown`: el widget no los muestra y serian datos de la cuenta en disco sin razon.
     */
    public void remember(UsageModel model, Instant fetchedAt) {
        // No toca KEY_ORGS: de eso se encarga `rememberOrgs`, que corre antes y con otro formato.
        prefs.edit()
                .putFloat(KEY_SP, (float) model.session.percent)
                .putString(KEY_SR, model.session.resetsAt == null ? "" : model.session.resetsAt.toString())
                .putFloat(KEY_WP, (float) model.weekly.percent)
                .putString(KEY_WR, model.weekly.resetsAt == null ? "" : model.weekly.resetsAt.toString())
                .putLong(KEY_FETCHED_AT, fetchedAt.getEpochSecond())
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

    public List<UsageClient.Org> knownOrgs() {
        String raw = prefs.getString(KEY_ORGS, "");
        List<UsageClient.Org> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String entry : raw.split(",")) {
            int bar = entry.indexOf('|');
            String uuid = bar < 0 ? entry : entry.substring(0, bar);
            String name = bar < 0 || bar == entry.length() - 1 ? null : entry.substring(bar + 1);
            if (!uuid.isEmpty()) out.add(new UsageClient.Org(uuid, name));
        }
        return out;
    }

    public void clear() {
        prefs.edit().remove(KEY_FETCHED_AT).remove(KEY_ORGS)
                .remove(KEY_SP).remove(KEY_SR).remove(KEY_WP).remove(KEY_WR).apply();
    }
}
```

- [ ] **Paso 3: `UsageRefresher`**

```java
package com.claulimitswidgets.android;

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

    /**
     * Un solo refresco a la vez. `runNow` (toque, login) y el JobService pueden coincidir, y
     * `SampleStore.append` es leer-y-reescribir: sin esto, dos a la vez corrompen samples.jsonl.
     */
    static final Object LOCK = new Object();

    public Snapshot refresh() {
        synchronized (LOCK) {
            return refreshLocked();
        }
    }

    private Snapshot refreshLocked() {
        String cookies;
        try {
            cookies = session.load();
        } catch (Exception e) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }
        if (cookies == null || !cookies.contains("sessionKey=")) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }

        SharedPreferences prefs = ctx.getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);

        // Backoff: si el ultimo intento fallo, no se vuelve a la red hasta que toque.
        // Importante: aqui NO se llama a keepOld. Si lo hiciera, cada toque volveria a
        // incrementar el contador sin haber hecho una sola peticion, y cinco toques en un
        // minuto dejarian al widget media hora sin consultar.
        long notBefore = prefs.getLong(KEY_NEXT_ALLOWED, 0L);
        if (Instant.now().getEpochSecond() < notBefore) {
            Snapshot old = last();
            Snapshot.Problem p = problemFromName(prefs.getString(KEY_LAST_PROBLEM, null));
            return old.hasData() ? old.withProblem(p) : Snapshot.of(p);
        }

        UsageClient client = new UsageClient(cookies, userAgent());
        try {
            String manual = prefs.getString(SettingsActivity.KEY_ORG, null);
            String lastActive = UsageClient.lastActiveOrg(cookies);

            // Con una pista basta: no se llama a /organizations en cada refresco. Solo cuando
            // no hay pista o todavia no se vio la lista. Son 3 peticiones menos por ciclo.
            List<UsageClient.Org> orgs =
                    (manual != null || lastActive != null) && !meta.knownOrgs().isEmpty()
                            ? meta.knownOrgs()
                            : client.organizations();
            meta.rememberOrgs(orgs);   // Ajustes los necesita aunque el resto falle
            // `OrgSelector` recibe solo uuids: si le llegara "uuid|nombre", el probe llamaria a
            // usage("uuid|nombre") y `UUID_RE` lo rechazaria.
            List<String> uuids = new ArrayList<>();
            for (UsageClient.Org o : orgs) uuids.add(o.uuid);

            // El probe devuelve el modelo, no un booleano: asi la consulta que decide la
            // organizacion es la misma que se muestra, en vez de tirarla y repetirla.
            final UsageModel[] fetched = new UsageModel[1];
            final Exception[] fatal = new Exception[1];
            OrgSelector.Choice choice = OrgSelector.choose(uuids, manual, lastActive, uuid -> {
                if (fatal[0] != null) return false;
                try {
                    fetched[0] = client.usage(uuid);
                    return true;
                } catch (AuthExpiredException | BlockedException
                        | UsageClient.RetryLaterException | IOException e) {
                    // Estas NO son "esta organizacion no sirve": son fallos del intento entero.
                    // Tragarlas haria que sin red el widget dijera "formato no reconocido" en
                    // vez de "sin conexion".
                    fatal[0] = e;
                    return false;
                } catch (UnrecognizedFormatException e) {
                    return false;   // esta organizacion responde algo raro; se prueba la siguiente
                }
            });
            if (fatal[0] instanceof AuthExpiredException) throw (AuthExpiredException) fatal[0];
            if (fatal[0] instanceof BlockedException) throw (BlockedException) fatal[0];
            if (fatal[0] instanceof UsageClient.RetryLaterException) {
                throw (UsageClient.RetryLaterException) fatal[0];
            }
            if (fatal[0] instanceof IOException) throw (IOException) fatal[0];
            if (choice.ambiguous) return keepOld(Snapshot.Problem.CHOOSE_ORG);
            if (choice.orgUuid == null) return keepOld(Snapshot.Problem.BAD_FORMAT);

            // Si la eleccion vino del manual, el probe no corrio: hay que consultar.
            UsageModel model = fetched[0] != null ? fetched[0] : client.usage(choice.orgUuid);
            Instant now = Instant.now();
            clearBackoff(prefs);
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

    private static final String KEY_ATTEMPT = "backoff_attempt";
    private static final String KEY_NEXT_ALLOWED = "backoff_next_allowed_at";
    private static final String KEY_LAST_PROBLEM = "backoff_last_problem";

    /** Un 200 reinicia la cuenta: el siguiente fallo vuelve a esperar un minuto, no media hora. */
    private void clearBackoff(SharedPreferences prefs) {
        prefs.edit().remove(KEY_ATTEMPT).remove(KEY_NEXT_ALLOWED).remove(KEY_LAST_PROBLEM).apply();
    }

    /**
     * Lo llaman el login y el selector de organizacion: cuando el usuario arregla el problema,
     * la espera acumulada ya no tiene sentido.
     */
    public static void clearBackoff(Context ctx) {
        ctx.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ATTEMPT).remove(KEY_NEXT_ALLOWED).remove(KEY_LAST_PROBLEM)
                .apply();
    }

    private static Snapshot.Problem problemFromName(String name) {
        if (name == null) return Snapshot.Problem.OFFLINE;
        try {
            return Snapshot.Problem.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Snapshot.Problem.OFFLINE;
        }
    }

    /**
     * Lo ultimo que se pudo calcular, marcado con el problema de ahora, y se anota el backoff:
     * sin esto `Backoff` quedaria definido y probado pero nunca aplicado.
     */
    private Snapshot keepOld(Snapshot.Problem p) {
        // Solo cuentan para el backoff los fallos que se arreglan esperando. Un 401 o una
        // organizacion por elegir no mejoran con el tiempo: los arregla el usuario, y hacerle
        // esperar media hora despues de volver a entrar seria absurdo.
        if (p == Snapshot.Problem.OFFLINE || p == Snapshot.Problem.BLOCKED
                || p == Snapshot.Problem.BAD_FORMAT) {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    SettingsActivity.PREFS, Context.MODE_PRIVATE);
            int attempt = prefs.getInt(KEY_ATTEMPT, 0);
            prefs.edit()
                    .putInt(KEY_ATTEMPT, attempt + 1)
                    .putLong(KEY_NEXT_ALLOWED,
                            Instant.now().getEpochSecond() + Backoff.seconds(attempt))
                    .putString(KEY_LAST_PROBLEM, p.name())
                    .apply();
        }
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
        // Hay sesion pero todavia no se ha consultado nunca. No es "sin conexion": es que
        // acaba de empezar. Decir OFFLINE aqui seria mentir en el caso mas comun del primer uso.
        if (model == null || fetchedAt == null) return Snapshot.of(Snapshot.Problem.LOADING);
        List<Sample> all;
        try {
            all = samples.load();
        } catch (IOException e) {
            all = java.util.Collections.emptyList();
        }
        Instant now = Instant.now();
        DayUsage day = History.compute(model.weekly, all, now, ZoneId.systemDefault());
        // `problem` va en null: `last()` describe lo que se sabe, no un fallo. Marcarlo siempre
        // como OFFLINE haria que el widget dijera "Sin conexion" despues de cualquier onUpdate
        // del lanzador, con datos recien traidos. La edad ya la muestra el campo `age`.
        return new Snapshot(model, day,
                Projection.session(model.session.percent, model.session.resetsAt, now),
                Projection.weekly(model.weekly.percent, model.weekly.resetsAt, all, now),
                Colors.paceMark(model.weekly.resetsAt, now),
                fetchedAt, null);
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
        meta.remember(model, now);
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
    /** El UA guardado, para quien no tiene una instancia a mano (Ajustes). */
    static String userAgentOf(Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString("user_agent", "");
    }

    private String userAgent() {
        // Sin respaldo a `http.agent`: si no esta guardado es que no hubo login, y una huella
        // distinta a la del WebView es justo lo que podria disparar un reto.
        return ctx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString("user_agent", "");
    }
}
```

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

        <!-- Marca de ritmo parejo (R7): una barra fina gris debajo de la semanal, que avanza
             con la ventana. Sin esto, `Colors.paceMark` se calcularia y no se veria. -->
        <ProgressBar android:id="@+id/pace" style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent" android:layout_height="2dp"
            android:max="100" android:progressDrawable="@drawable/bar_gray" />
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
    <string name="p_choose_org">Pick an organization in Settings</string>
    <string name="p_loading">Updating…</string>
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
    <string name="p_choose_org">Elige organización en Ajustes</string>
    <string name="p_loading">Actualizando…</string>
```

- [ ] **Paso 7: `WidgetRenderer`**

```java
package com.claulimitswidgets.android;

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
                // Esto es DIBUJO, no regla: cuanto se llena la barra. El COLOR lo decide
                // `Colors.today`, que es R7. No se toca uno pensando en el otro.
                double todayPct = s.day.quotaToday == null || s.day.quotaToday <= 0
                        ? 0 : 100 * s.day.todayUsed / s.day.quotaToday;
                bar(v, TODAY_BARS, todayPct,
                        Colors.today(s.day.todayUsed, s.day.quotaToday));
                v.setTextViewText(R.id.today, ctx.getString(R.string.w_today,
                        one(s.day.todayUsed), s.day.quotaToday == null ? "—" : one(s.day.quotaToday)));
                // Marca de ritmo parejo: cuanto de la ventana semanal transcurrio (R7).
                v.setViewVisibility(R.id.pace, s.paceMark == null
                        ? android.view.View.GONE : android.view.View.VISIBLE);
                if (s.paceMark != null) {
                    v.setProgressBar(R.id.pace, 100, (int) Math.round(s.paceMark * 100), false);
                }
                v.setTextViewText(R.id.forecast, forecast(ctx, s));
            }
        }

        // El toque lleva a donde se arregla el problema, no siempre a "refrescar": decirle
        // "toca para iniciar sesion" y que al tocar solo reintente seria mentirle.
        v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, s, compact));
        return v;
    }

    /**
     * Con NO_SESSION o AUTH_EXPIRED abre el login; con CHOOSE_ORG abre Ajustes; en los demas
     * casos refresca. El PendingIntent corre con la identidad de la app, asi que el broadcast
     * llega al receptor aunque sea `exported="false"` (verificado en W0).
     *
     * "Mantener pulsado = abrir la app" NO es posible: la pulsacion larga sobre un widget la
     * consume el lanzador para moverlo, y un AppWidgetProvider no la ve. Por eso el acceso a
     * Ajustes vive en la pantalla de la app (Tarea 3.8) y en el toque cuando hay que elegir.
     */
    private static PendingIntent tapIntent(Context ctx, Snapshot s, boolean compact) {
        int req = compact ? 1 : 2;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (s.problem == Snapshot.Problem.NO_SESSION || s.problem == Snapshot.Problem.AUTH_EXPIRED) {
            return PendingIntent.getActivity(ctx, req,
                    new Intent(ctx, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        }
        if (s.problem == Snapshot.Problem.CHOOSE_ORG) {
            return PendingIntent.getActivity(ctx, req,
                    new Intent(ctx, SettingsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        }
        Intent tap = new Intent(ctx, compact ? Widget4x1Provider.class : Widget4x2Provider.class)
                .setAction(WidgetUpdateJob.ACTION_TAP);
        return PendingIntent.getBroadcast(ctx, req, tap, flags);
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
            case CHOOSE_ORG:    return R.string.p_choose_org;
            case LOADING:       return R.string.p_loading;
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
package com.claulimitswidgets.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** 4x1 compacto. `exported="false"`: verificado en One UI (spike B). */
public class Widget4x1Provider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        UsageRefresher r = new UsageRefresher(ctx);
        Snapshot s = r.last();
        for (int id : ids) awm.updateAppWidget(id, WidgetRenderer.render(ctx, s, compact()));
        WidgetUpdateJob.schedule(ctx);
        // Primera vez: hay sesion pero ningun dato todavia. Sin esto el widget se queda en
        // "Actualizando..." hasta el primer ciclo del job, que puede tardar 15 minutos.
        if (s.problem == Snapshot.Problem.LOADING) WidgetUpdateJob.runNow(ctx);
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
package com.claulimitswidgets.android;

import android.app.Activity;
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

    public static final String ACTION_TAP = "com.claulimitswidgets.android.TAP";
    private static final int JOB_ID = 4201;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WidgetUpdateJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // Con RECEIVE_BOOT_COMPLETED declarado, el job puede persistir y lo reprograma
                // el sistema. BootReceiver se queda solo para refrescar al arrancar.
                .setPersisted(true)
                .build());
    }

    public static void cancel(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    /** Actualizacion inmediata, fuera del periodo: al iniciar sesion y al tocar el widget. */
    public static void runNow(Context ctx) {
        new Thread(() -> {
            Snapshot s;
            try {
                s = new UsageRefresher(ctx).refresh();
            } catch (RuntimeException e) {
                // Un hilo crudo que lanza se lleva el proceso por delante. `refresh()` promete
                // no lanzar, pero esto es lo que hace que la promesa no dependa de recordarlo.
                s = Snapshot.of(Snapshot.Problem.BAD_FORMAT);
            }
            pushToWidgets(ctx, s);
        }, "cuw-refresh").start();
    }

    /**
     * Vuelve a leer /api/organizations y repinta los radios. Lo llama Ajustes al abrirse.
     *
     * NO llama a `refresh()`: ese, con pista y cache, se salta `/organizations` justo para
     * ahorrar peticiones, asi que la lista no se refrescaria nunca y encima gastaria una
     * consulta de uso. Aqui se pide la lista y nada mas; sin backoff, porque es una accion
     * del usuario.
     */
    public static void refreshOrgs(Activity activity, Runnable onDone) {
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            // El LOCK es a proposito y no sobra: `rememberOrgs` escribe las mismas preferencias
            // que un refresco en curso, y los dos pueden coincidir.
            synchronized (UsageRefresher.LOCK) {
                try {
                    SessionStore store = new SessionStore(app);
                    if (!store.hasSession()) return;
                    String cookies = store.load();
                    if (cookies == null) return;
                    UsageClient c = new UsageClient(cookies, UsageRefresher.userAgentOf(app));
                    new SnapshotStore(app).rememberOrgs(c.organizations());
                } catch (Exception ignored) {
                    // Ajustes sigue usable con la lista vieja.
                    return;
                }
            }
            activity.runOnUiThread(onDone);
        }, "cuw-orgs").start();
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
package com.claulimitswidgets.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * El job persiste al reinicio (`setPersisted(true)`), pero el primer ciclo tardaria hasta 15 min
 * en llegar. Aqui se refresca en cuanto arranca, y se reprograma por si el job se perdio.
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
adb shell dumpsys jobscheduler | grep -A3 "com.claulimitswidgets.android" | head -10
```
Esperado: un job con `PERIODIC` de 900000 ms.

- [ ] **Paso 6: Comprobar el reinicio sin reiniciar el teléfono**

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.claulimitswidgets.android/.BootReceiver
sleep 5
adb shell dumpsys jobscheduler | grep -c "com.claulimitswidgets.android"
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
# `set -e` NO: este guion tiene que REPORTAR fallos, no morirse en ellos. Con `-e` y `pipefail`,
# un `grep` que no encuentra nada (que es justo lo que hay que reportar) termina el guion y no se
# ve el resumen. Se protege lo que puede fallar, uno por uno.
set -uo pipefail
PKG=com.claulimitswidgets.android
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
antes=$(texto | grep -oE '(ahora mismo|just now|hace [0-9]+ min|[0-9]+ min ago)' | head -1 || true)
# El toque se localiza por el texto del widget, no por coordenadas fijas.
coord=$(adb -s "$S" shell uiautomator dump /sdcard/r.xml >/dev/null 2>&1; \
        adb -s "$S" shell cat /sdcard/r.xml | tr '<' '\n' | grep -m1 'Sesi' | \
        grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | \
        grep -oE '[0-9]+' | paste -sd' ' || true)
set -- $coord
if [ $# -eq 4 ]; then adb -s "$S" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); fi
sleep 8
comprobar "sigue mostrando datos tras el toque" '(Sesion|Sesión|Session) [0-9]+%'

echo "== 3. sin red =="
echo "  (se corta el acceso del paquete, no se apagan las radios del dueno)"
# OJO: `UID` es de SOLO LECTURA en bash. Asignarla falla en silencio y el guion seguiria con el
# uid de Termux, restringiendo la app equivocada y pasando igual. Por eso se llama APP_UID.
APP_UID=$(adb -s "$S" shell dumpsys package $PKG | grep -m1 userId= | grep -oE '[0-9]+')
if [ -z "$APP_UID" ]; then
  echo "  FALLA     no se pudo leer el uid de $PKG"; fallos=$((fallos+1))
else
  if ! adb -s "$S" shell cmd netpolicy add restrict-background-blacklist "$APP_UID"; then
    echo "  FALLA     cmd netpolicy no acepto la lista negra"; fallos=$((fallos+1))
  fi
  if ! adb -s "$S" shell cmd netpolicy set restrict-background true; then
    echo "  FALLA     cmd netpolicy no acepto activar la restriccion"; fallos=$((fallos+1))
  fi
  # Se VERIFICA que la restriccion entro. Si el comando fallara en silencio, la prueba pasaria
  # siempre sin probar nada: es justo lo que le paso a las dos primeras versiones de este guion.
  if adb -s "$S" shell cmd netpolicy list restrict-background-blacklist | grep -qw "$APP_UID"; then
    echo "  OK        la app esta en la lista de restriccion"
  else
    echo "  FALLA     no se pudo restringir la red de $PKG"; fallos=$((fallos+1))
  fi
  # La red tiene que ser MEDIDA: `restrict-background` solo corta datos de fondo ahi.
  # OJO: buscar "metered.*true" en todo el dumpsys casa SIEMPRE en un telefono con SIM, porque
  # la politica de la red movil dice metered=true aunque estemos en Wi-Fi. Lo que importa es que
  # la INTERFAZ ACTIVA este en "Metered ifaces".
  if adb -s "$S" shell dumpsys netpolicy | grep -i "metered ifaces" | grep -q wlan; then
    echo "  OK        la interfaz Wi-Fi activa esta entre las medidas"
  else
    echo "  FALLA     la Wi-Fi no esta marcada como medida: este paso no prueba nada"
    fallos=$((fallos+1))
  fi
  adb -s "$S" shell am broadcast -a $PKG.TAP -n $PKG/.Widget4x2Provider >/dev/null 2>&1
  sleep 15
  comprobar "avisa de que no hay conexion" '(Sin conexion|Sin conexión|No connection)'
  comprobar "conserva el dato viejo con su edad" '(hace [0-9]+|[0-9]+ (min|h) ago|ahora mismo|just now)'

  # Ida y vuelta: sin esto, un widget que SIEMPRE dijera "Sin conexion" pasaria la prueba.
  if ! adb -s "$S" shell cmd netpolicy set restrict-background false; then
    echo "  FALLA     no se pudo quitar la restriccion"; fallos=$((fallos+1))
  fi
  if ! adb -s "$S" shell cmd netpolicy remove restrict-background-blacklist "$APP_UID"; then
    echo "  FALLA     no se pudo sacar la app de la lista"; fallos=$((fallos+1))
  fi
  # El TAP de vuelta tiene que esperar a que pase el backoff: la ida dejo el widget en OFFLINE,
  # y eso persiste `next_allowed = now + 60 s`. Sin esta espera el gate bloquea la consulta y
  # "ahora mismo" no aparece NUNCA, asi que la comprobacion fallaria siempre por una razon que
  # no tiene nada que ver con la red. De paso, esto mide tambien que el backoff expire bien.
  sleep 70
  adb -s "$S" shell am broadcast -a $PKG.TAP -n $PKG/.Widget4x2Provider >/dev/null 2>&1
  sleep 15
  comprobar "al volver la red, vuelve a estar al dia" '(ahora mismo|just now)'
fi

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

- [ ] **Paso 1b: Marcar la Wi-Fi como medida (manual, una sola vez)**

`restrict-background` **solo corta datos de fondo en redes medidas**. En una Wi-Fi normal no corta
nada, así que sin este paso el "sin red" del guion no prueba nada aunque salga verde.

En el teléfono: Ajustes → Conexiones → Wi-Fi → la red → Uso de datos → **Medida**. Comprobar:

```bash
adb shell dumpsys netpolicy | grep -i "metered ifaces"
```
Esperado: la línea incluye la interfaz Wi-Fi (`wlan0` o similar). **No vale** buscar
`metered.*true` en todo el `dumpsys`: en un teléfono con SIM eso casa siempre, porque la política
de la red móvil dice `metered=true` aunque estés en Wi-Fi. El guion verifica la interfaz activa
y **falla** si no está.
Al terminar las pruebas, volver a dejarla sin medir.

- [ ] **Paso 2: Correrlo** — `bash android/app/pruebas/recorrido.sh`.
  Esperado: `recorrido completo sin fallos`. Cada `FALLA` es un hallazgo que se arregla antes de
  seguir, no se anota para después.

- [ ] **Paso 3: Commit**

```bash
chmod +x android/app/pruebas/recorrido.sh
git add android/app/pruebas && git commit -m "test: recorrido automatizado en el dispositivo"
```

### Tarea 5.2: Sesión vencida y bloqueo, provocados a propósito

- [ ] **Paso 1: Cerrar sesión** — esto prueba el camino de "no hay sesión", **no un 401 real**.
  Un 401 del servidor no se puede provocar a voluntad; esa rama la cubre `UsageClientTest` y queda
  declarada como no ejercitada contra el servidor:

```bash
adb shell am start -n com.claulimitswidgets.android/.SettingsActivity
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
adb shell dumpsys jobscheduler | grep -c com.claulimitswidgets.android
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
# Termux se duerme con Doze y el `sleep` no llega a las 24 h. El wake lock lo evita.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
trap 'command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock' EXIT INT TERM

adb -s "$S" logcat -c
echo "inicio: $(date -u +%FT%TZ)" > "$OUT"
adb -s "$S" logcat -v time -s CuwHttp:I >> "$OUT" &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null; command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock' EXIT INT TERM

sleep 86400
echo "fin: $(date -u +%FT%TZ)" >> "$OUT"

# Se SUMAN las lineas individuales, no se lee la ultima de totales: los contadores son por
# proceso y Samsung mata la app cada tanto, asi que esa linea se reinicia sin avisar.
echo "== respuestas, sumadas sobre todo el periodo =="
grep -oE 'code=[0-9]+' "$OUT" | sort | uniq -c || echo "  (ninguna: revisar que la app corriera)"
echo "== bloqueos =="
printf "  cf-mitigated: %s\n" "$(grep -c 'cf-mitigated=yes' "$OUT" || true)"
printf "  html:         %s\n" "$(grep -c 'ctype=text/html' "$OUT" || true)"
```

- [ ] **Paso 1b: Contadores en la app**

`UsageClient.check` es **estática y no tiene `Context`**, así que no puede escribir preferencias.
Lleva seis contadores estáticos y `UsageRefresher` los vuelca tras cada ciclo:

```java
    // En UsageClient: seis enteros, ningun dato de la cuenta.
    static final java.util.concurrent.atomic.AtomicIntegerArray COUNTS =
            new java.util.concurrent.atomic.AtomicIntegerArray(6);
    static final int N200 = 0, N401 = 1, N403 = 2, N_HTML = 3, N_CF = 4, N_RETRY = 5;

    /** Una sola linea con los seis totales: la ultima de logcat basta para el reporte. */
    public static String countsLine() {
        return "n200=" + COUNTS.get(N200) + " n401=" + COUNTS.get(N401)
                + " n403=" + COUNTS.get(N403) + " nHtml=" + COUNTS.get(N_HTML)
                + " nCfMitigated=" + COUNTS.get(N_CF) + " nRetry=" + COUNTS.get(N_RETRY);
    }
```

`check(...)` incrementa el que corresponda antes de lanzar. El volcado va en `refresh()`, en un
`finally`, para que salga también cuando el ciclo termina por excepción:

```java
    public Snapshot refresh() {
        synchronized (LOCK) {
            try {
                return refreshLocked();
            } finally {
                android.util.Log.i("CuwHttp", UsageClient.countsLine());
            }
        }
    }
```

**Los contadores son por proceso y Samsung mata la app**, así que `ritmo24h.sh` **suma todas las
líneas `code=`** del log en vez de leer la última de totales: la línea de totales se reinicia cada
vez que el proceso vuelve a arrancar. Y la prueba parte de un APK recién arrancado, porque las
pruebas de la cáscara también mueven los contadores.

**Por qué así y no con `run-as`:** el APK de release no es depurable, así que
`adb shell run-as ... cat shared_prefs/...` **siempre** falla. Un guion que lea por ahí daría
cero sin que nadie lo note, que es el mismo error que ya apareció dos veces en este plan.

- [ ] **Paso 2: Añadir el registro mínimo a `UsageClient`**

**Este `Log.i` se queda en el APK de release**, a propósito: es lo único que permite diagnosticar
un bloqueo en el teléfono de otra persona. Por eso registra **solo** código HTTP, tipo de
contenido y longitud — nunca el cuerpo, nunca una cabecera con su valor, nunca la URL (que lleva
el UUID de organización).

```java
        android.util.Log.i("CuwHttp", "code=" + code
                + " ctype=" + (ctype == null ? "-" : ctype.split(";")[0])
                + " len=" + body.length()
                + (cfMitigated != null && !cfMitigated.isEmpty() ? " cf-mitigated=yes" : ""));
```

- [ ] **Paso 3: Correrla**, partiendo de un proceso recién arrancado (las pruebas de la cáscara
  también mueven los contadores):

```bash
adb shell am force-stop com.claulimitswidgets.android
nohup bash android/app/pruebas/ritmo24h.sh ritmo24h.log >/dev/null 2>&1 &
```

Durante esas 24 h **no se toca el teléfono para esta prueba**; se puede seguir usando normalmente.

- [ ] **Paso 3b: Si hubo retos, se abre el plan B** — el reporte no se queda en describirlo: si
  aparece algún `403`, `cf-mitigated` o HTML, se abre una tarea de **plan B** (rehacer la consulta
  dentro de un WebView en primer plano) y se mide si el bloqueo es puntual o sistemático. Sin
  retos, el plan B se declara innecesario por ahora y se deja documentado por si cambia.

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
    # Environment con revisor obligatorio (D3): la llave de release no se usa sin que una
    # persona apruebe esa ejecucion.
    environment: android-release
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          fetch-depth: 0          # versionCode = numero de commits
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: "17"
      - name: Instalar las herramientas de compilacion
        run: |
          set -euo pipefail
          yes 2>/dev/null | sdkmanager --install "platforms;android-34" "build-tools;34.0.0" >/dev/null || true
          echo "$ANDROID_HOME/build-tools/34.0.0" >> "$GITHUB_PATH"
      - name: Reconstruir la llave desde el secret
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.keystore"
      - name: Construir y firmar
        env:
          KEYSTORE: ${{ runner.temp }}/release.keystore
          KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          VERSION_NAME: ${{ github.ref_name }}
        run: |
          set -euo pipefail
          # El tag es `android-vN.N.N`; versionCode es el numero de commits, monotono y sin
          # tener que acordarse de subirlo a mano.
          export VERSION_CODE="$(git rev-list --count HEAD)"
          export VERSION_NAME="${VERSION_NAME#android-v}"
          ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar" bash android/app/build.sh
      - name: Borrar la llave del runner
        if: always()
        run: shred -u "$RUNNER_TEMP/release.keystore" 2>/dev/null || rm -f "$RUNNER_TEMP/release.keystore"
      - name: Checksum y huella del certificado
        run: |
          set -euo pipefail
          cd android/app/build
          sha256sum claude-usage.apk > claude-usage.apk.sha256
          cat claude-usage.apk.sha256
          # La huella del certificado es lo que deja comprobar que una actualizacion viene de
          # la misma llave. Se publica en el README y se imprime aqui para cotejarla.
          apksigner verify --print-certs claude-usage.apk | grep -i "SHA-256 digest"
      - name: Publicar (borrador: lo revisa una persona antes de que sea publico)
        uses: softprops/action-gh-release@72f2c25fcb47643c292f7107632f7a47c1df5cd8 # v2.3.2
        with:
          draft: true
          files: |
            android/app/build/claude-usage.apk
            android/app/build/claude-usage.apk.sha256
```

El `shred` con `if: always()` está a propósito: si el build falla, la llave no puede quedarse en
el runner.

> **Condición del `versionCode`:** `git rev-list --count HEAD` solo es monótono si los tags
> `android-v*` se crean **siempre sobre `main`**. Si alguna vez se etiqueta una rama, el número
> puede bajar y Android rechazaría la actualización con `INSTALL_FAILED_VERSION_DOWNGRADE`, que
> no explica nada. Etiquetar solo `main`, o pasar a un contador manual.

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

## No ejercitado en W3

Lo que este plan **no** demuestra, escrito en un solo sitio para que nadie lo lea como probado:

1. **El reto real de Cloudflare.** No se puede provocar a voluntad. La clasificación
   (403, `cf-mitigated`, HTML) se prueba por unidad en `UsageClientTest`; que el servidor la
   dispare de verdad solo se sabrá si ocurre en la prueba de 24 h.
2. **Un `401` real del servidor.** Igual: la rama está probada por unidad; el recorrido prueba
   "cerrar sesión", que es otro camino.
3. **API 29 en hardware.** El teléfono de pruebas es Android 17 (SDK 37). El nivel de API lo
   comprueba **lint (`NewApi`) en el CI**, no el compilador ni un dispositivo: con `android.jar`
   en el classpath, `javac` ve las APIs de la 34 y ni `--release 8` lo impide. Las llamadas de
   `RemoteViews` se eligieron entre las de API 1 de todas formas. Lo que sigue sin probarse es el
   **comportamiento** en un dispositivo API 29, no la disponibilidad de las APIs.
4. **Otros lanzadores.** `exported="false"` en el `AppWidgetProvider` está verificado en One UI.
   Nova, Pixel Launcher y los demás no se han probado.
5. **El plan B del WebView bajo `JobScheduler`.** El spike midió `postDelayed` con el proceso
   congelado, no un job real. Si hiciera falta el plan B, hay que medirlo otra vez.
6. **Que la cookie mínima baste.** Se prueba en la Tarea 3.7 contra el servidor real; hasta que
   esa prueba dé `200`, es una hipótesis.
7. **`Instrumentation` en este dispositivo.** Es la primera vez que el proyecto la usa. Si
   `am instrument` falla, el bloqueo va a un memo: no se improvisa otra vía, porque la que había
   (`app_process`) es justamente la que se descartó por correr con otro uid.
8. **La prueba "sin red" solo vale con la Wi-Fi marcada como medida.** `restrict-background` no
   corta nada en una red sin medir. El guion verifica que la interfaz Wi-Fi activa esté en
   `Metered ifaces` y falla si no, pero depende de un ajuste manual del teléfono que hay que
   acordarse de deshacer.

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
